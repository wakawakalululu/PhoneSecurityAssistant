package com.tencentx.pinyin.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.remote.BinaryManager;
import com.tencentx.pinyin.settings.Prefs;

/**
 * rclone integration for the sync MVP.
 * <p>
 * Builds the per-task rclone INI configuration (SFTP or WebDAV backend),
 * obscures the remote password via {@code rclone obscure} (rclone requires
 * obscured passwords in its config file), and parses the {@code --progress}
 * output line into byte counts.
 * <p>
 * The rclone binary is resolved through {@link BinaryManager} (an explicit
 * path, an imported/downloaded binary, or one bundled in the APK).
 */
public final class SyncManager {

    private static final String BINARY_NAME = "rclone";

    /** SharedPreferences key for the explicit rclone binary path. */
    static final String KEY_BINARY = "sync.rclone.binary";

    private static final String REMOTE_NAME = "remote";

    /**
     * Matches a rclone {@code --progress} "Transferred" summary line, e.g.
     * {@code Transferred:   \t 1.206 GiB / 10.000 GiB, 12%, 5.243 MiB/s, ...}.
     * Group 1 = transferred size, group 2 = total size.
     */
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "Transferred:\\s*([0-9.]+\\s*[KMGTPEi]?i?B|0\\s*B)\\s*/\\s*"
            + "([0-9.]+\\s*[KMGTPEi]?i?B|0\\s*B|-)");

    /** Matches a size like "1.206 GiB" or "0 B". */
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "([0-9.]+)\\s*([KMGTPEi]?i?B|B)");

    private SyncManager() {
    }

    /**
     * Is the rclone binary available?
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isBinaryAvailable(@NonNull final Context context) {
        return BinaryManager.isAvailable(context, BINARY_NAME, getBinaryPath(context));
    }

    /**
     * Resolve the rclone binary.
     *
     * @param context Current context
     *
     * @return the file, or {@code null} when not found
     */
    @Nullable
    public static File resolveBinary(@NonNull final Context context) {
        return BinaryManager.resolve(context, BINARY_NAME, getBinaryPath(context));
    }

    /**
     * A short, translated summary of the rclone availability.
     *
     * @param context Current context
     *
     * @return summary text
     */
    @NonNull
    public static String getSummary(@NonNull final Context context) {
        return context.getString(isBinaryAvailable(context)
                                 ? R.string.sync_binary_ok
                                 : R.string.sync_binary_missing);
    }

    /**
     * Obscure a password using {@code rclone obscure}.
     * <p>
     * rclone stores passwords in an obscured form in its config file; a plain
     * password is rejected. This runs a one-shot {@code rclone obscure <pass>}
     * and returns its stdout.
     *
     * @param context  Current context
     * @param password to obscure
     *
     * @return the obscured password, or the plain password on failure
     *
     * @throws IOException when rclone is missing or the command fails
     */
    @NonNull
    public static String obscure(@NonNull final Context context,
                                 @NonNull final String password)
            throws IOException {
        if (password.isEmpty()) {
            return "";
        }
        final File exe = resolveBinary(context);
        if (exe == null) {
            throw new IOException(context.getString(R.string.sync_no_binary));
        }
        final List<String> command = BinaryManager.buildCommand(
                context, exe, List.of("obscure", password));
        final ProcessBuilder pb = new ProcessBuilder(command)
                .redirectErrorStream(true);
        final Process process = pb.start();
        final String output = new String(process.getInputStream().readAllBytes(),
                                          StandardCharsets.UTF_8).trim();
        //noinspection OverlyBroadCatchBlock
        try {
            final int code = process.waitFor();
            if (code != 0 || output.isEmpty()) {
                throw new IOException("rclone obscure failed: " + output);
            }
        } catch (@NonNull final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("rclone obscure interrupted", e);
        }
        return output;
    }

    /**
     * Write the rclone INI configuration for a task.
     * <p>
     * The password must already be obscured (see {@link #obscure}).
     *
     * @param context      Current context
     * @param task         whose config
     * @param obscuredPass obscured password (or empty)
     *
     * @return the written config file
     *
     * @throws IOException on failure
     */
    @NonNull
    public static File writeConfig(@NonNull final Context context,
                                   @NonNull final SyncTask task,
                                   @NonNull final String obscuredPass)
            throws IOException {
        final StringBuilder ini = new StringBuilder();
        ini.append('[').append(REMOTE_NAME).append("]\n");
        switch (task.getRemoteType()) {
            case SFTP: {
                ini.append("type = sftp\n");
                ini.append("host = ").append(task.getRemoteHost()).append('\n');
                if (task.getRemotePort() > 0) {
                    ini.append("port = ").append(task.getRemotePort()).append('\n');
                }
                if (!task.getRemoteUser().isEmpty()) {
                    ini.append("user = ").append(task.getRemoteUser()).append('\n');
                }
                if (!obscuredPass.isEmpty()) {
                    ini.append("pass = ").append(obscuredPass).append('\n');
                }
                // Trust the host key on first connect to avoid an interactive
                // prompt; the MVP runs unattended in a foreground service.
                ini.append("disable_hashcheck = true\n");
                break;
            }
            case WEBDAV: {
                ini.append("type = webdav\n");
                final String scheme = task.isUseTls() ? "https" : "http";
                ini.append("url = ").append(scheme).append("://")
                   .append(task.getRemoteHost());
                if (task.getRemotePort() > 0) {
                    ini.append(':').append(task.getRemotePort());
                }
                ini.append('\n');
                if (!task.getRemoteUser().isEmpty()) {
                    ini.append("user = ").append(task.getRemoteUser()).append('\n');
                }
                if (!obscuredPass.isEmpty()) {
                    ini.append("pass = ").append(obscuredPass).append('\n');
                }
                break;
            }
            default:
                break;
        }

        final File configFile = SyncTaskStore.getConfigFile(context, task);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile),
                                               StandardCharsets.UTF_8)) {
            w.write(ini.toString());
        }
        return configFile;
    }

    /**
     * Build the {@code rclone copy} command for a task.
     * <p>
     * The configuration file is (re)written on every call; the password is
     * obscured via {@link #obscure} when set.
     *
     * @param context Current context
     * @param task    to run
     *
     * @return the command to pass to {@code ProcessBuilder}
     *
     * @throws IOException when rclone is missing, the config cannot be written,
     *                     or the command cannot be built
     */
    @NonNull
    public static List<String> buildCommand(@NonNull final Context context,
                                            @NonNull final SyncTask task)
            throws IOException {
        final File exe = resolveBinary(context);
        if (exe == null) {
            throw new IOException(context.getString(R.string.sync_no_binary));
        }
        final String obscured = obscure(context, task.getRemotePassword());
        final File configFile = writeConfig(context, task, obscured);

        final String remoteRef = REMOTE_NAME + ":" + task.getRemotePath();
        final String src;
        final String dst;
        if (task.getDirection() == SyncTask.Direction.DOWNLOAD) {
            src = remoteRef;
            dst = task.getLocalPath();
        } else {
            src = task.getLocalPath();
            dst = remoteRef;
        }

        final List<String> args = new ArrayList<>();
        args.add("copy");
        args.add("--config");
        args.add(configFile.getAbsolutePath());
        args.add("--progress");
        args.add("--stats");
        args.add("1s");
        // rclone copy skips per-file errors by default and surfaces them in
        // the log; no extra flag needed.
        args.add(src);
        args.add(dst);
        return BinaryManager.buildCommand(context, exe, args);
    }

    /**
     * Parse a rclone {@code --progress} output line.
     *
     * @param line to parse
     *
     * @return the progress, or {@code null} when the line is not a summary
     */
    @Nullable
    public static Progress parseProgress(@NonNull final String line) {
        final Matcher m = PROGRESS_PATTERN.matcher(line);
        if (!m.find()) {
            return null;
        }
        final long transferred = parseSize(m.group(1));
        final long total = parseSize(m.group(2));
        return new Progress(transferred, total);
    }

    private static long parseSize(@Nullable final String text) {
        if (text == null || text.isBlank() || "-".equals(text.trim())) {
            return 0L;
        }
        final Matcher m = SIZE_PATTERN.matcher(text.trim());
        if (!m.matches()) {
            return 0L;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            final double value = Double.parseDouble(m.group(1));
            final String unit = m.group(2);
            return (long) (value * unitMultiplier(unit));
        } catch (@NonNull final NumberFormatException e) {
            return 0L;
        }
    }

    private static double unitMultiplier(@NonNull final String unit) {
        switch (unit) {
            case "B":
                return 1;
            case "KiB":
                return 1024;
            case "MiB":
                return 1024 * 1024;
            case "GiB":
                return 1024 * 1024 * 1024;
            case "TiB":
                return 1024L * 1024 * 1024 * 1024;
            case "PiB":
                return 1024L * 1024 * 1024 * 1024 * 1024;
            case "EiB":
                return 1024L * 1024 * 1024 * 1024 * 1024 * 1024;
            case "KB":
                return 1000;
            case "MB":
                return 1_000_000;
            case "GB":
                return 1_000_000_000;
            case "TB":
                return 1_000_000_000_000L;
            case "PB":
                return 1_000_000_000_000_000L;
            case "EB":
                return 1_000_000_000_000_000_000L;
            default:
                return 1;
        }
    }

    @NonNull
    private static String getBinaryPath(@NonNull final Context context) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        return Prefs.getString(prefs, KEY_BINARY, "");
    }

    /**
     * Parsed progress of a single rclone output line.
     */
    public static final class Progress {

        private final long transferred;
        private final long total;

        private Progress(final long transferred, final long total) {
            this.transferred = transferred;
            this.total = total;
        }

        public long getTransferred() {
            return transferred;
        }

        public long getTotal() {
            return total;
        }

        /**
         * Percent 0..100, or {@code -1} when the total is unknown.
         *
         * @return percent
         */
        public int percent() {
            if (total <= 0) {
                return -1;
            }
            return (int) Math.min(100, transferred * 100 / total);
        }
    }
}
