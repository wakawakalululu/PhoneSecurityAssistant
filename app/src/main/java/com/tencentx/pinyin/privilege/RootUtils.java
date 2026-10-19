package com.tencentx.pinyin.privilege;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.tencentx.pinyin.BuildConfig;

/**
 * Detection of, and running commands with, root ("{@code su}") privileges.
 * <p>
 * Only standard shell commands are used, so this works with
 * Magisk, KernelSU, APatch and the older SuperSU style implementations.
 */
public final class RootUtils {

    private static final String TAG = "RootUtils";

    /**
     * Well known locations for the "{@code su}" binary.
     * <p>
     * Note that "{@code su}" could also simply be available via the PATH,
     * this is checked as a fallback.
     */
    private static final String[] SU_CANDIDATES = {
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
            };

    private static final Object LOCK = new Object();
    /** Cached result of {@link #findSuPath()}; {@code null} if there is no su. */
    @Nullable
    private static String suPath;
    private static boolean suPathResolved;
    /** Cached result of {@link #isRootAvailable()}. */
    @Nullable
    private static Boolean rootAvailable;

    private RootUtils() {
    }

    /**
     * Check if the device is rooted and "{@code su}" can actually be used.
     * <p>
     * A warning: on most implementations this will show the "grant root" prompt
     * to the user the first time.
     *
     * @return {@code true} if "{@code su -c id}" returned the root uid
     */
    public static boolean isRootAvailable() {
        synchronized (LOCK) {
            if (rootAvailable != null) {
                return rootAvailable;
            }
        }

        // Do the (potentially slow) check outside the lock.
        boolean available = false;
        final String su = findSuPath();
        if (su != null) {
            final ExecResult result = run(su, "id");
            available = !result.timedOut && result.exitCode == 0
                        && result.output.contains("uid=0");
        }

        synchronized (LOCK) {
            rootAvailable = available;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "isRootAvailable=" + available + " su=" + su);
        }
        return available;
    }

    /**
     * Forget the cached values; i.e. a new check will be done on the next call.
     */
    public static void reset() {
        synchronized (LOCK) {
            suPath = null;
            suPathResolved = false;
            rootAvailable = null;
        }
    }

    /**
     * Find the path of the "{@code su}" binary.
     *
     * @return full path, or {@code null} if not found. Result is cached.
     */
    @Nullable
    public static String findSuPath() {
        synchronized (LOCK) {
            if (suPathResolved) {
                return suPath;
            }
        }

        String found = null;
        // Check the well known locations first (avoids running a shell)
        for (final String candidate : SU_CANDIDATES) {
            final File file = new File(candidate);
            if (file.exists() && file.canExecute()) {
                found = candidate;
                break;
            }
        }

        if (found == null) {
            // Fall back to whatever is in the PATH
            final ExecResult result =
                    run("/system/bin/sh", "command -v su || which su");
            if (!result.timedOut && result.exitCode == 0) {
                for (final String line : result.output.split("\n")) {
                    final String path = line.strip();
                    if (!path.isEmpty() && new File(path).exists()) {
                        found = path;
                        break;
                    }
                }
            }
        }

        synchronized (LOCK) {
            suPath = found;
            suPathResolved = true;
        }
        return found;
    }

    /**
     * Resolve the "{@code su}" binary to use.
     *
     * @param userOverride (optional) user configured path
     *
     * @return the path to use, or {@code null} when there is no usable "{@code su}"
     */
    @Nullable
    public static String getSuPath(@Nullable final String userOverride) {
        if (userOverride != null && !userOverride.isBlank()) {
            final File file = new File(userOverride.strip());
            if (file.exists()) {
                return file.getPath();
            }
        }
        return findSuPath();
    }

    /**
     * Run a command with root privileges.
     *
     * @param command to run; executed as "{@code su -c command}"
     *
     * @return the result; never {@code null}
     */
    @NonNull
    public static ExecResult runAsRoot(@NonNull final String command) {
        final String su = findSuPath();
        if (su == null) {
            return new ExecResult(-1, "", false);
        }
        return run(su, command);
    }

    /**
     * Run a command, capturing the output.
     * <p>
     * The command has a limited time to complete; see {@link #TIMEOUT_MS}.
     *
     * @param executable to run
     * @param command    single shell command, run with "{@code -c}"
     *
     * @return the result; never {@code null}
     */
    @NonNull
    private static ExecResult run(@NonNull final String executable,
                                  @NonNull final String command) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "run: " + executable + " -c " + command);
        }

        Process process = null;
        try {
            process = new ProcessBuilder(executable, "-c", command)
                    .redirectErrorStream(true)
                    .start();

            final StringBuilder sb = new StringBuilder();
            // read the output on this thread, with a hard timeout on the process.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ExecResult(-1, sb.toString(), true);
            }
            return new ExecResult(process.exitValue(), sb.toString(), false);

        } catch (@NonNull final IOException | InterruptedException e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "run failed", e);
            }
            return new ExecResult(-1, "", false);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** Maximum time a command may take; "su" prompts can take a while. */
    private static final long TIMEOUT_MS = 30_000;

    /**
     * Result of a command execution.
     */
    public static final class ExecResult {

        /** Exit code, or -1 on failure/timeout. */
        public final int exitCode;
        /** Combined stdout/stderr. */
        @NonNull
        public final String output;
        /** {@code true} if the command did not complete in time. */
        public final boolean timedOut;

        ExecResult(final int exitCode,
                   @NonNull final String output,
                   final boolean timedOut) {
            this.exitCode = exitCode;
            this.output = output;
            this.timedOut = timedOut;
        }

        /** @return {@code true} if executed successful */
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }
}
