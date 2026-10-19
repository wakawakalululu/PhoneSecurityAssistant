package com.tencentx.pinyin.remote;

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
import java.util.List;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.settings.Prefs;

/**
 * frpc (fatedier/frp client) integration.
 * <p>
 * Generates a minimal frp v0.65 configuration which forwards a TCP port on the
 * frp server to the local sshd port, and builds the command line to run the
 * client.
 * <p>
 * The configuration is written to "{@code <tools directory>/frpc.toml}";
 * the "{@code frpc}" binary itself is resolved through {@link BinaryManager}
 * (an imported/downloaded binary or the copy bundled in the APK).
 */
public final class FrpManager {

    /** Name of the binary; also the suffix of the bundled "{@code libfrpc.so}". */
    private static final String BINARY_NAME = "frpc";

    /** Name of the generated configuration file in the tools directory. */
    private static final String CONFIG_FILE_NAME = "frpc.toml";

    /*
     * SharedPreferences keys, kept in sync with
     * "com.tencentx.pinyin.settings.Prefs". Its key constants are package
     * private and therefore not accessible from this package.
     */
    /** String. */
    private static final String KEY_SERVER_ADDR = "frp.server.addr";
    /** String. */
    private static final String KEY_SERVER_PORT = "frp.server.port";
    /** String. */
    private static final String KEY_TOKEN = "frp.token";
    /** String. */
    private static final String KEY_PROXY_NAME = "frp.proxy.name";
    /** String. */
    private static final String KEY_REMOTE_PORT = "frp.remote.port";
    /** String. Explicit path to an "{@code frpc}" executable; empty = bundled/imported. */
    private static final String KEY_BINARY = "frp.binary";

    /** Default frp server port; matches {@code Prefs.FRP_DEFAULT_SERVER_PORT}. */
    private static final int DEFAULT_SERVER_PORT = 7000;

    /** Default proxy name; matches {@code Prefs.FRP_DEFAULT_PROXY_NAME}. */
    private static final String DEFAULT_PROXY_NAME = "sshd4a";

    private FrpManager() {
    }

    /**
     * Is a frp server configured?
     *
     * @param context Current context
     *
     * @return {@code true} when a server address is set
     */
    public static boolean isConfigured(@NonNull final Context context) {
        return !getServerAddress(Prefs.getSharedPreferences(context)).isEmpty();
    }

    /**
     * A short, translated description of the current configuration,
     * for use in the settings screen.
     *
     * @param context Current context
     *
     * @return server, proxy and remote port as a multi-line String;
     *         or "not configured"
     */
    @NonNull
    public static String getSummary(@NonNull final Context context) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        if (!isConfigured(context)) {
            return context.getString(R.string.frp_not_configured);
        }

        final String server = getServerAddress(prefs) + ":" + getServerPort(prefs);
        final String proxy = getProxyName(prefs);
        final int remotePort = getRemotePort(prefs);
        return context.getString(R.string.frp_summary_fmt, server, proxy, remotePort);
    }

    /**
     * Is the frpc binary available (imported, downloaded or bundled)?
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isBinaryAvailable(@NonNull final Context context) {
        return BinaryManager.isAvailable(context, BINARY_NAME, getBinaryPath(context));
    }

    /**
     * Write the frpc configuration file.
     * <p>
     * The file is a minimal frp v0.65 TOML configuration which forwards a
     * single TCP port to the local sshd port.
     *
     * @param context Current context
     *
     * @return the written file
     *
     * @throws IOException on failure to write the file
     */
    @NonNull
    public static File writeConfig(@NonNull final Context context) throws IOException {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final StringBuilder toml = new StringBuilder();

        toml.append("serverAddr = \"")
            .append(escape(getServerAddress(prefs)))
            .append("\"\n");
        toml.append("serverPort = ")
            .append(getServerPort(prefs))
            .append('\n');

        final String token = Prefs.getString(prefs, KEY_TOKEN, "");
        if (!token.isEmpty()) {
            toml.append("auth.token = \"")
                .append(escape(token))
                .append("\"\n");
        }

        toml.append('\n');
        toml.append("[[proxies]]\n");
        toml.append("name = \"")
            .append(escape(getProxyName(prefs)))
            .append("\"\n");
        toml.append("type = \"tcp\"\n");
        toml.append("localIP = \"127.0.0.1\"\n");
        toml.append("localPort = ")
            .append(Prefs.getPort(prefs))
            .append('\n');
        toml.append("remotePort = ")
            .append(getRemotePort(prefs))
            .append('\n');

        final File configFile = new File(BinaryManager.getToolsDir(context),
                                         CONFIG_FILE_NAME);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(configFile),
                                                    StandardCharsets.UTF_8)) {
            writer.write(toml.toString());
        }
        return configFile;
    }

    /**
     * Build the command to run frpc with the generated configuration.
     * <p>
     * The configuration file is (re)written on every call.
     *
     * @param context Current context
     *
     * @return the command to pass to {@link ProcessBuilder}
     *
     * @throws IOException when the frpc binary is missing or the command
     *                     cannot be built (e.g. root is required but missing)
     */
    @NonNull
    public static List<String> buildCommand(@NonNull final Context context)
            throws IOException {
        @Nullable final File exe =
                BinaryManager.resolve(context, BINARY_NAME, getBinaryPath(context));
        if (exe == null) {
            throw new IOException(context.getString(R.string.frp_no_binary));
        }

        final File configFile = writeConfig(context);
        return BinaryManager.buildCommand(context, exe,
                                          List.of("-c", configFile.getAbsolutePath()));
    }

    /**
     * The user configured path to the frpc binary; may be empty.
     *
     * @param context Current context
     *
     * @return path, or an empty String
     */
    @NonNull
    private static String getBinaryPath(@NonNull final Context context) {
        return Prefs.getString(Prefs.getSharedPreferences(context), KEY_BINARY, "");
    }

    @NonNull
    private static String getServerAddress(@NonNull final SharedPreferences prefs) {
        return Prefs.getString(prefs, KEY_SERVER_ADDR, "");
    }

    private static int getServerPort(@NonNull final SharedPreferences prefs) {
        return parsePort(Prefs.getString(prefs, KEY_SERVER_PORT, ""),
                         DEFAULT_SERVER_PORT);
    }

    @NonNull
    private static String getProxyName(@NonNull final SharedPreferences prefs) {
        return Prefs.getString(prefs, KEY_PROXY_NAME, DEFAULT_PROXY_NAME);
    }

    /**
     * The remote port; falls back to the local sshd port when not (validly) set.
     */
    private static int getRemotePort(@NonNull final SharedPreferences prefs) {
        final int port = parsePort(Prefs.getString(prefs, KEY_REMOTE_PORT, ""), 0);
        if (port > 0) {
            return port;
        }
        return Prefs.getPort(prefs);
    }

    /**
     * Parse a TCP port.
     *
     * @param value    to parse
     * @param fallback returned when the value is empty, not a number or out of range
     *
     * @return the port, or the fallback
     */
    private static int parsePort(@NonNull final String value,
                                 final int fallback) {
        if (value.isEmpty()) {
            return fallback;
        }
        final int port;
        try {
            port = Integer.parseInt(value);
        } catch (@NonNull final NumberFormatException ignore) {
            return fallback;
        }
        if (port <= 0 || port > 65535) {
            return fallback;
        }
        return port;
    }

    /**
     * Escape a value for use inside a TOML basic string.
     *
     * @param value to escape
     *
     * @return the escaped value (backslashes and double quotes)
     */
    @NonNull
    private static String escape(@NonNull final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
