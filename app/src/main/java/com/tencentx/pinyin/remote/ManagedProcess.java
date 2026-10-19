package com.tencentx.pinyin.remote;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.tencentx.pinyin.BuildConfig;

/**
 * A single long-running child process (frpc, tailscaled, nezha-agent, ...).
 * <p>
 * Output (stdout+stderr) is appended to a log file which can be shown in the UI.
 * The log file is capped; see {@link #MAX_LOG_BYTES}.
 */
public final class ManagedProcess {

    private static final String TAG = "ManagedProcess";

    /** Log files are truncated to this size before a (re)start. */
    private static final long MAX_LOG_BYTES = 256 * 1024;

    @NonNull
    private final String name;
    @NonNull
    private final File logFile;

    @Nullable
    private Process process;
    @Nullable
    private Thread outputThread;

    private volatile boolean running;

    /**
     * Constructor.
     *
     * @param name    of the tool; used in log messages
     * @param logFile to write the output to
     */
    public ManagedProcess(@NonNull final String name,
                          @NonNull final File logFile) {
        this.name = name;
        this.logFile = logFile;
    }

    /**
     * Is the process currently running?
     *
     * @return flag
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Start the process.
     *
     * @param command    full command line
     * @param workingDir (optional) working directory
     * @param env        (optional) extra environment variables
     *
     * @throws IOException on failure to start
     */
    public synchronized void start(@NonNull final List<String> command,
                                   @Nullable final File workingDir,
                                   @Nullable final Map<String, String> env)
            throws IOException {
        if (running) {
            return;
        }

        truncateLog();

        final ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (workingDir != null) {
            pb.directory(workingDir);
        }
        if (env != null) {
            pb.environment().putAll(env);
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "start " + name + ": " + command);
        }

        appendLog("=== starting " + name + " ===\n" + String.join(" ", command) + "\n");

        process = pb.start();
        running = true;

        final InputStream is = process.getInputStream();
        outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line + "\n");
                }
            } catch (@NonNull final IOException ignore) {
                // process was killed / stream closed
            }
            // process ended
            running = false;
            appendLog("=== " + name + " stopped ===\n");
        }, name + "-output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    /**
     * Stop the process.
     */
    public synchronized void stop() {
        final Process p = process;
        process = null;
        running = false;
        if (p != null) {
            p.destroy();
            try {
                if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (@NonNull final InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void truncateLog() {
        //noinspection OverlyBroadCatchBlock
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                final List<String> lines = readTail(logFile, 200);
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
                try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile, true))) {
                    w.write("=== log truncated ===\n");
                }
                for (final String line : lines) {
                    appendLog(line + "\n");
                }
            }
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }

    private void appendLog(@NonNull final String text) {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter, OverlyBroadCatchBlock
        synchronized (logFile) {
            //noinspection OverlyBroadCatchBlock
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new java.io.FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                w.write(text);
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
    }

    /**
     * Read up to {@code maxLines} lines from the end of a log file.
     *
     * @param file     to read
     * @param maxLines maximum number of lines
     *
     * @return the lines; never {@code null}
     */
    @NonNull
    public static List<String> readTail(@NonNull final File file,
                                        final int maxLines) {
        final List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            lines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
        final int size = lines.size();
        if (size > maxLines) {
            return new ArrayList<>(lines.subList(size - maxLines, size));
        }
        return lines;
    }

    /**
     * Delete the log file.
     */
    public void clearLog() {
        //noinspection ResultOfMethodCallIgnored,OverlyBroadCatchBlock
        try {
            logFile.delete();
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }
}
