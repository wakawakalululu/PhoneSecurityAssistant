package com.tencentx.pinyin.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.privilege.RootUtils;
import com.tencentx.pinyin.settings.Prefs;

/**
 * Tailscale integration.
 * <p>
 * tailscaled can be run in two ways:
 * <ul>
 *     <li>userspace-networking (default): no root required; the daemon is
 *         started by the app itself and provides a SOCKS5 proxy on
 *         {@value #SOCKS5_ADDRESS}</li>
 *     <li>TUN mode: a real TUN interface is created, which requires root; the
 *         daemon is started through "{@code su}"</li>
 * </ul>
 * Both the daemon state and the unix socket are kept in the tools directory,
 * so a login survives app restarts.
 * <p>
 * The "{@code tailscale}" CLI commands ({@link #up up}, {@link #down down},
 * {@link #status status}) run asynchronously on a background thread and report
 * their combined stdout/stderr back on the main thread.
 */
public final class TailscaleManager {

    /** Name of the daemon binary; also the suffix of "{@code libtailscaled.so}". */
    private static final String DAEMON_BINARY_NAME = "tailscaled";
    /** Name of the CLI binary; also the suffix of "{@code libtailscale.so}". */
    private static final String CLI_BINARY_NAME = "tailscale";

    /** Name of the daemon state file in the tools directory. */
    private static final String STATE_FILE_NAME = "tailscaled.state";
    /** Name of the unix socket used by the daemon and the CLI. */
    private static final String SOCKET_FILE_NAME = "tailscaled.sock";

    /** Address of the SOCKS5 proxy served in userspace-networking mode. */
    private static final String SOCKS5_ADDRESS = "127.0.0.1:1055";
    /** UDP port used for direct (WireGuard) connections. */
    private static final String PORT = "41641";

    /*
     * SharedPreferences keys, kept in sync with
     * "com.tencentx.pinyin.settings.Prefs". Its key constants are package
     * private and therefore not accessible from this package.
     */
    /** String. */
    private static final String KEY_AUTH_KEY = "ts.authkey";
    /** String. */
    private static final String KEY_HOSTNAME = "ts.hostname";
    /** String. Explicit path to a "{@code tailscaled}" executable; empty = bundled/imported. */
    private static final String KEY_BINARY = "ts.binary";

    /**
     * Runs the (blocking) CLI commands off the main thread.
     * Single-threaded: the commands are cheap and rarely used.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "tailscale-cli");
        thread.setDaemon(true);
        return thread;
    });

    private TailscaleManager() {
    }

    /**
     * Is Tailscale usable, i.e. is a daemon binary available (imported,
     * downloaded or bundled)?
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isConfigured(@NonNull final Context context) {
        return isBinaryAvailable(context);
    }

    /**
     * A short, translated description of the current configuration,
     * for use in the settings screen.
     *
     * @param context Current context
     *
     * @return mode, hostname, auth key state and socket path as a multi-line
     *         String; or "not configured"
     */
    @NonNull
    public static String getSummary(@NonNull final Context context) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        if (!isConfigured(context)) {
            return context.getString(R.string.ts_not_configured);
        }

        final String mode = context.getString(Prefs.isTailscaleTunMode(prefs)
                                              ? R.string.ts_mode_tun
                                              : R.string.ts_mode_userspace);
        final String hostname = Prefs.getString(prefs, KEY_HOSTNAME, "");
        final String hostnameText = hostname.isEmpty()
                                    ? context.getString(R.string.ts_hostname_none)
                                    : hostname;
        final String authKey = Prefs.getString(prefs, KEY_AUTH_KEY, "");
        final String authKeyText = authKey.isEmpty()
                                   ? context.getString(R.string.ts_authkey_unset)
                                   : context.getString(R.string.ts_authkey_set);
        final String socket = getSocketFile(context).getAbsolutePath();
        return context.getString(R.string.ts_summary_fmt,
                                mode, hostnameText, authKeyText, socket);
    }

    /**
     * Is the tailscaled binary available (imported, downloaded or bundled)?
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isBinaryAvailable(@NonNull final Context context) {
        return BinaryManager.isAvailable(context, DAEMON_BINARY_NAME,
                                         getBinaryPath(context));
    }

    /**
     * Is the "{@code tailscale}" CLI binary available (imported or bundled)?
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isCliAvailable(@NonNull final Context context) {
        return BinaryManager.isAvailable(context, CLI_BINARY_NAME, "");
    }

    /**
     * Build the command to run tailscaled.
     * <p>
     * In TUN mode the command is wrapped in "{@code su -c '...'}" because
     * creating a TUN interface requires root; in the default
     * userspace-networking mode the daemon is run by the app itself.
     *
     * @param context Current context
     *
     * @return the command to pass to {@link ProcessBuilder}
     *
     * @throws IOException when a binary or "{@code su}" is missing, or the
     *                     command cannot be built
     */
    @NonNull
    public static List<String> buildDaemonCommand(@NonNull final Context context)
            throws IOException {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final boolean tunMode = Prefs.isTailscaleTunMode(prefs);

        final List<String> args = new ArrayList<>();
        args.add("--state=" + getStateFile(context).getAbsolutePath());
        args.add("--socket=" + getSocketFile(context).getAbsolutePath());
        if (!tunMode) {
            args.add("--tun=userspace-networking");
        }
        args.add("--socks5-server=" + SOCKS5_ADDRESS);
        args.add("--port=" + PORT);

        if (tunMode) {
            final String suPath = RootUtils.getSuPath(Prefs.getSuPath(prefs));
            if (suPath == null) {
                throw new IOException(context.getString(R.string.ts_no_su));
            }
            final File exe = resolveDaemon(context, prefs);
            if (exe == null) {
                throw new IOException(context.getString(R.string.ts_no_binary));
            }
            final StringBuilder command =
                    new StringBuilder(BinaryManager.quote(exe.getAbsolutePath()));
            for (final String arg : args) {
                command.append(' ').append(BinaryManager.quote(arg));
            }
            return List.of(suPath, "-c", command.toString());
        }

        final File exe = resolveDaemon(context, prefs);
        if (exe == null) {
            throw new IOException(context.getString(R.string.ts_no_binary));
        }
        return BinaryManager.buildCommand(context, exe, args);
    }

    /**
     * Log in / connect to the tailnet ("{@code tailscale up}").
     * <p>
     * The configured auth key and hostname are passed as arguments when set.
     *
     * @param context  Current context
     * @param callback invoked on the main thread with the combined
     *                 stdout/stderr of the command
     */
    public static void up(@NonNull final Context context,
                          @NonNull final ResultCallback callback) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final List<String> args = new ArrayList<>();
        args.add("--socket=" + getSocketFile(context).getAbsolutePath());
        args.add("up");

        final String authKey = Prefs.getString(prefs, KEY_AUTH_KEY, "");
        if (!authKey.isEmpty()) {
            args.add("--auth-key=" + authKey);
        }
        final String hostname = Prefs.getString(prefs, KEY_HOSTNAME, "");
        if (!hostname.isEmpty()) {
            args.add("--hostname=" + hostname);
        }

        runCli(context, args, callback);
    }

    /**
     * Disconnect from the tailnet ("{@code tailscale down}").
     *
     * @param context  Current context
     * @param callback invoked on the main thread with the combined
     *                 stdout/stderr of the command
     */
    public static void down(@NonNull final Context context,
                            @NonNull final ResultCallback callback) {
        final List<String> args = new ArrayList<>();
        args.add("--socket=" + getSocketFile(context).getAbsolutePath());
        args.add("down");
        runCli(context, args, callback);
    }

    /**
     * Show the connection status ("{@code tailscale status}").
     *
     * @param context  Current context
     * @param callback invoked on the main thread with the combined
     *                 stdout/stderr of the command
     */
    public static void status(@NonNull final Context context,
                              @NonNull final ResultCallback callback) {
        final List<String> args = new ArrayList<>();
        args.add("--socket=" + getSocketFile(context).getAbsolutePath());
        args.add("status");
        runCli(context, args, callback);
    }

    /**
     * Callback for the (asynchronous) CLI commands.
     * <p>
     * Always invoked on the main thread.
     */
    public interface ResultCallback {

        /**
         * @param output combined stdout/stderr of the command; never {@code null}
         */
        void onResult(@NonNull String output);
    }

    /**
     * Run a CLI command on the background executor and post its combined
     * stdout/stderr to the main thread. Never blocks the caller.
     *
     * @param context  Current context
     * @param args     to pass to the CLI
     * @param callback to report the output to
     */
    private static void runCli(@NonNull final Context context,
                               @NonNull final List<String> args,
                               @NonNull final ResultCallback callback) {
        final File cli = resolveCli(context);
        if (cli == null) {
            postResult(callback, context.getString(R.string.ts_no_binary));
            return;
        }

        final Context appContext = context.getApplicationContext();
        final boolean daemonRunning = isDaemonRunning(context);

        EXECUTOR.execute(() -> {
            final StringBuilder output = new StringBuilder();
            Process process = null;
            try {
                final List<String> command = BinaryManager.buildCommand(appContext, cli, args);
                process = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                }
                process.waitFor();
            } catch (@NonNull final IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                output.append(e.getMessage() != null ? e.getMessage() : e.toString())
                      .append('\n');
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }

            final String text = output.toString().strip();
            final String result;
            if (daemonRunning) {
                result = text;
            } else {
                final String hint = appContext.getString(R.string.ts_daemon_not_running);
                result = text.isEmpty() ? hint : hint + "\n" + text;
            }
            postResult(callback, result);
        });
    }

    /**
     * Post a result to the main thread.
     *
     * @param callback to invoke
     * @param output   to report
     */
    private static void postResult(@NonNull final ResultCallback callback,
                                   @NonNull final String output) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(output));
    }

    /**
     * Is the daemon running? Either it is managed by the
     * {@link RemoteAccessService}, or its unix socket exists.
     *
     * @param context Current context
     *
     * @return flag
     */
    private static boolean isDaemonRunning(@NonNull final Context context) {
        if (RemoteAccessService.isRunning(RemoteTool.TAILSCALE)) {
            return true;
        }
        return getSocketFile(context).exists();
    }

    /**
     * The daemon state file; "{@code <tools directory>/tailscaled.state}".
     *
     * @param context Current context
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    private static File getStateFile(@NonNull final Context context) {
        return new File(BinaryManager.getToolsDir(context), STATE_FILE_NAME);
    }

    /**
     * The unix socket used by the daemon and the CLI;
     * "{@code <tools directory>/tailscaled.sock}".
     *
     * @param context Current context
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    private static File getSocketFile(@NonNull final Context context) {
        return new File(BinaryManager.getToolsDir(context), SOCKET_FILE_NAME);
    }

    /**
     * Resolve the daemon binary.
     *
     * @param context Current context
     * @param prefs   to read the explicit path from
     *
     * @return the file, or {@code null} when nothing was found
     */
    @Nullable
    private static File resolveDaemon(@NonNull final Context context,
                                      @NonNull final SharedPreferences prefs) {
        return BinaryManager.resolve(context, DAEMON_BINARY_NAME,
                                     Prefs.getString(prefs, KEY_BINARY, ""));
    }

    /**
     * Resolve the CLI binary; there is no separate preference for it.
     *
     * @param context Current context
     *
     * @return the file, or {@code null} when nothing was found
     */
    @Nullable
    private static File resolveCli(@NonNull final Context context) {
        return BinaryManager.resolve(context, CLI_BINARY_NAME, "");
    }

    /**
     * The user configured path to the tailscaled binary; may be empty.
     *
     * @param context Current context
     *
     * @return path, or an empty String
     */
    @NonNull
    private static String getBinaryPath(@NonNull final Context context) {
        return Prefs.getString(Prefs.getSharedPreferences(context), KEY_BINARY, "");
    }
}
