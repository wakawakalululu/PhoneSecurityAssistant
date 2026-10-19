package com.tencentx.pinyin.terminal;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.adb.AdbManager;
import com.tencentx.pinyin.privilege.PrivilegeMode;
import com.tencentx.pinyin.privilege.RootUtils;
import com.tencentx.pinyin.settings.Prefs;

/**
 * A single command executed by the built-in terminal.
 * <p>
 * Depending on the {@link PrivilegeMode} the command is run
 * <ul>
 *     <li>{@link PrivilegeMode#APP}: directly, with "{@code /system/bin/sh -c}".</li>
 *     <li>{@link PrivilegeMode#ROOT}: with escalated privileges, through "{@code su -c}".</li>
 *     <li>{@link PrivilegeMode#ADB}: through the existing ADB connection.</li>
 * </ul>
 * Combined stdout+stderr output is reported line by line to a {@link Listener}.
 * Process callbacks are made on a background thread, ADB callbacks are made
 * by the {@link AdbManager}.
 */
public final class ShellSession {

    /** The shell used to run commands in {@link PrivilegeMode#APP} mode. */
    private static final String SYSTEM_SH = "/system/bin/sh";

    /**
     * Callback for the output and the end of a {@link ShellSession}.
     */
    public interface Listener {

        /**
         * A single line of combined stdout+stderr output.
         *
         * @param line to handle; never {@code null}, but can be empty
         */
        void onLine(@NonNull String line);

        /**
         * The session has finished. Called exactly once.
         *
         * @param exitCode the exit code of the process,
         *                 or {@code -1} when the session was stopped by the user
         *                 or the exit code is unknown
         */
        void onFinished(int exitCode);
    }

    @NonNull
    private final Context appContext;
    @NonNull
    private final PrivilegeMode mode;

    /** Guards the state of this session; see {@link #start} and {@link #stop}. */
    private final Object lock = new Object();

    /** {@code true} from {@link #start} until the session finished or was stopped. */
    private volatile boolean running;
    /** {@code true} when the user has called {@link #stop}. */
    private boolean stopped;
    /** {@code true} once {@link Listener#onFinished} has been called. */
    private boolean finished;

    @Nullable
    private Process process;

    /**
     * Constructor.
     *
     * @param context Current context
     * @param mode    the privilege mode to run commands with
     */
    public ShellSession(@NonNull final Context context,
                        @NonNull final PrivilegeMode mode) {
        this.appContext = context.getApplicationContext();
        this.mode = mode;
    }

    /**
     * The privilege mode this session runs commands with.
     *
     * @return mode
     */
    @NonNull
    public PrivilegeMode getMode() {
        return mode;
    }

    /**
     * Is the session currently running?
     *
     * @return flag
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Start running a command. Does nothing when already running.
     *
     * @param command  to run; executed with "{@code sh -c}"
     * @param listener to report the output and the exit code to
     */
    public void start(@NonNull final String command,
                      @NonNull final Listener listener) {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            stopped = false;
            finished = false;
        }

        if (mode == PrivilegeMode.ADB) {
            startAdb(command, listener);
        } else {
            final Thread thread = new Thread(
                    () -> runProcess(command, listener), "ShellSession");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Stop the running command.
     * <p>
     * For {@link PrivilegeMode#APP} and {@link PrivilegeMode#ROOT} the process
     * is destroyed, after which {@link Listener#onFinished} is called with
     * {@code -1}. An ADB stream cannot be cancelled, so for
     * {@link PrivilegeMode#ADB} this only marks the session as stopped.
     */
    public void stop() {
        final Process p;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            stopped = true;
            p = process;
        }

        if (mode != PrivilegeMode.ADB && p != null) {
            p.destroy();
        }
    }

    /**
     * Start the command as an ADB "shell:" stream.
     *
     * @param command  to run
     * @param listener to report the output and the exit code to
     */
    private void startAdb(@NonNull final String command,
                          @NonNull final Listener listener) {
        final AdbManager adb = AdbManager.getInstance(appContext);
        if (!adb.isConnected()) {
            listener.onLine(appContext.getString(R.string.term_need_adb));
            notifyFinished(listener, -1);
            return;
        }

        try {
            adb.exec(command, new AdbManager.OutputListener() {
                @Override
                public void onLine(@NonNull final String line) {
                    listener.onLine(line);
                }

                @Override
                public void onComplete() {
                    // The "shell:" stream has no exit code; assume success.
                    notifyFinished(listener, 0);
                }
            });
        } catch (@NonNull final RuntimeException e) {
            listener.onLine(appContext.getString(R.string.term_io_error_fmt,
                                                String.valueOf(e.getMessage())));
            notifyFinished(listener, -1);
        }
    }

    /**
     * Resolve the executable, start the process and pump its output
     * to the listener. Runs on a private background thread.
     *
     * @param command  to run
     * @param listener to report the output and the exit code to
     */
    private void runProcess(@NonNull final String command,
                            @NonNull final Listener listener) {
        int exitCode = -1;
        Process process = null;
        try {
            final List<String> cmd = new ArrayList<>(3);

            if (mode == PrivilegeMode.ROOT) {
                final String suPath = RootUtils.getSuPath(
                        Prefs.getSuPath(Prefs.getSharedPreferences(appContext)));
                if (suPath == null) {
                    listener.onLine(appContext.getString(R.string.term_no_su));
                    listener.onLine(appContext.getString(R.string.term_no_su_hint));
                    return;
                }
                cmd.add(suPath);
            } else {
                cmd.add(SYSTEM_SH);
            }
            cmd.add("-c");
            cmd.add(command);

            if (isStopped()) {
                // stop() was called before we even got here
                return;
            }

            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            process = pb.start();

            synchronized (lock) {
                this.process = process;
                // stop() may have been called while starting up
                if (stopped) {
                    process.destroy();
                }
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    listener.onLine(line);
                }
            }

            exitCode = process.waitFor();

        } catch (@NonNull final IOException e) {
            if (!isStopped()) {
                listener.onLine(appContext.getString(R.string.term_io_error_fmt,
                                                    String.valueOf(e.getMessage())));
            }
        } catch (@NonNull final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (isStopped()) {
                exitCode = -1;
            }
            notifyFinished(listener, exitCode);
        }
    }

    /**
     * Call {@link Listener#onFinished} exactly once.
     *
     * @param listener to notify
     * @param exitCode to report
     */
    private void notifyFinished(@NonNull final Listener listener,
                                final int exitCode) {
        synchronized (lock) {
            if (finished) {
                return;
            }
            finished = true;
            running = false;
        }
        listener.onFinished(exitCode);
    }

    private boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }
}
