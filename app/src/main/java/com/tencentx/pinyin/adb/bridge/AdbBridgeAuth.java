package com.tencentx.pinyin.adb.bridge;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SSH identity fingerprint whitelist and {@code authorized_keys} management
 * for the remote ADB bridge.
 * <p>
 * The bridge uses an <strong>independent</strong> {@code authorized_keys} file
 * at {@code filesDir/.adb-bridge/authorized_keys}, fully isolated from the main
 * sshd's keys. Each line is an OpenSSH public key
 * ({@code ssh-rsa AAAA... comment}); the SHA-256 fingerprint of every key is
 * computed and compared against the fingerprint presented by the bridge shell.
 */
public final class AdbBridgeAuth {

    private static final String DIR_NAME = ".adb-bridge";
    private static final String AUTHORIZED_KEYS = "authorized_keys";

    private AdbBridgeAuth() {
    }

    /**
     * The bridge configuration directory (also used as the dropbear conf dir).
     *
     * @param context Current context
     *
     * @return the directory, created if necessary
     */
    @NonNull
    public static File getBridgeDirectory(@NonNull final Context context) {
        final File path = new File(context.getFilesDir(), DIR_NAME);
        if (!path.exists()) {
            path.mkdirs();
        }
        return path;
    }

    /**
     * The independent {@code authorized_keys} file for the bridge.
     *
     * @param context Current context
     *
     * @return the file (may not yet exist)
     */
    @NonNull
    public static File getAuthorizedKeysFile(@NonNull final Context context) {
        return new File(getBridgeDirectory(context), AUTHORIZED_KEYS);
    }

    /**
     * Read the raw {@code authorized_keys} lines.
     *
     * @param context Current context
     *
     * @return list of non-empty, non-comment lines
     */
    @NonNull
    public static List<String> readAuthorizedKeys(@NonNull final Context context) {
        final List<String> lines = new ArrayList<>();
        final File file = getAuthorizedKeysFile(context);
        if (!file.exists()) {
            return lines;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                final String s = line.strip();
                if (!s.isEmpty() && !s.startsWith("#")) {
                    lines.add(s);
                }
            }
        } catch (@NonNull final IOException ignore) {
            // ignore
        }
        return lines;
    }

    /**
     * Overwrite the {@code authorized_keys} file with the given lines.
     *
     * @param context Current context
     * @param lines   public key lines to write
     */
    public static void writeAuthorizedKeys(@NonNull final Context context,
                                           @NonNull final List<String> lines) {
        final File file = getAuthorizedKeysFile(context);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            for (final String line : lines) {
                w.write(line);
                w.newLine();
            }
        } catch (@NonNull final IOException ignore) {
            // ignore
        }
    }

    /**
     * Compute the OpenSSH SHA-256 fingerprint of a public key line.
     *
     * @param publicKeyLine an OpenSSH public key line ({@code type base64-blob comment})
     *
     * @return the fingerprint ({@code SHA256:base64}), or {@code null} on failure
     */
    @Nullable
    public static String computeFingerprint(@NonNull final String publicKeyLine) {
        final String[] parts = publicKeyLine.strip().split("\\s+");
        if (parts.length < 2) {
            return null;
        }
        try {
            final byte[] blob = Base64.getDecoder().decode(parts[1]);
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(blob);
            return "SHA256:" + Base64.getEncoder().encodeToString(digest).replace("=", "");
        } catch (@NonNull final IllegalArgumentException | NoSuchAlgorithmException ignore) {
            return null;
        }
    }

    /**
     * The set of fingerprints derived from the current {@code authorized_keys}.
     *
     * @param context Current context
     *
     * @return set of fingerprints
     */
    @NonNull
    public static Set<String> getAuthorizedFingerprints(@NonNull final Context context) {
        final Set<String> fingerprints = new HashSet<>();
        for (final String line : readAuthorizedKeys(context)) {
            final String fp = computeFingerprint(line);
            if (fp != null) {
                fingerprints.add(fp);
            }
        }
        return fingerprints;
    }

    /**
     * Whether the presented SSH fingerprint is authorised to use the bridge.
     *
     * @param context     Current context
     * @param fingerprint the fingerprint presented by the bridge shell
     *
     * @return {@code true} if authorised
     */
    public static boolean isAuthorized(@NonNull final Context context,
                                       @Nullable final String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        return getAuthorizedFingerprints(context).contains(fingerprint.strip());
    }
}
