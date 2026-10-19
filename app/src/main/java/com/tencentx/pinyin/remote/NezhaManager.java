package com.tencentx.pinyin.remote;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.settings.Prefs;

/**
 * Runs the Nezha monitoring agent ("{@code nezha-agent}") as a managed child process.
 * <p>
 * The agent reports this device (cpu, memory, disk, network, ...) to a Nezha dashboard.
 * A v1-style YAML configuration file ("{@code nezha-agent.yml}") is generated from the
 * user settings every time the agent is (re)started; see {@link #writeConfig(Context)}.
 * The process itself is managed by the {@link RemoteAccessService}, which captures the
 * agent its output in the "{@code nezha-agent.log}" file shown in the reporting panel
 * ({@code R.string.nezha_panel_title}).
 */
public final class NezhaManager {

    /** Name of the executable; the same name is used by {@link RemoteTool#NEZHA}. */
    private static final String BINARY_NAME = "nezha-agent";

    /** Name of the generated configuration file, inside the tools directory. */
    private static final String CONFIG_NAME = BINARY_NAME + ".yml";

    /**
     * Key of the dashboard address; see {@code Prefs.NEZHA_SERVER}.
     * {@code Prefs} keeps its keys package private, so the literals are repeated here.
     */
    private static final String KEY_SERVER = "nezha.server";

    /** Key of the client secret; see {@code Prefs.NEZHA_SECRET}. */
    private static final String KEY_SECRET = "nezha.secret";

    /** Key of the explicit executable path; see {@code Prefs.NEZHA_BINARY}. */
    private static final String KEY_BINARY = "nezha.binary";

    /** Prefix of the {@code uuid} line in an existing configuration file. */
    private static final String YAML_UUID = "uuid:";

    /** Characters which do not need quoting in the generated YAML file. */
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9_.:/\\-]+");

    /** A scalar which a YAML parser would read as a number, not as a String. */
    private static final Pattern YAML_NUMBER = Pattern.compile(
            "[-+]?(?:[0-9][0-9_]*|[0-9]*\\.[0-9]+|[0-9][0-9_]*\\."
            + "|0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+)"
            + "(?:[eE][-+]?[0-9]+)?");

    /** Scalars which a YAML parser would read as a boolean or null, not as a String. */
    private static final List<String> YAML_KEYWORDS =
            List.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    private NezhaManager() {
    }

    /**
     * Is the agent configured, i.e. has the user set a dashboard address?
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isConfigured(@NonNull final Context context) {
        return !Prefs.getString(Prefs.getSharedPreferences(context), KEY_SERVER, "").isBlank();
    }

    /**
     * A short, translated, human readable summary of the current configuration,
     * meant to be shown in the remote access UI.
     *
     * @param context Current context
     *
     * @return one or more lines, separated by {@code '\n'};
     *         {@code R.string.nezha_not_configured} when no dashboard is set
     */
    @NonNull
    public static String getSummary(@NonNull final Context context) {
        if (!isConfigured(context)) {
            return context.getString(R.string.nezha_not_configured);
        }

        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final String on = context.getString(R.string.nezha_on);
        final String off = context.getString(R.string.nezha_off);
        final String set = context.getString(R.string.nezha_set);
        final String unset = context.getString(R.string.nezha_unset);

        final StringBuilder summary = new StringBuilder();
        summary.append(context.getString(R.string.nezha_summary_server_fmt,
                                        Prefs.getString(prefs, KEY_SERVER, "")));
        summary.append('\n');
        summary.append(context.getString(R.string.nezha_summary_tls_fmt,
                                        Prefs.isNezhaTls(prefs) ? on : off));
        summary.append('\n');
        summary.append(context.getString(R.string.nezha_summary_secret_fmt,
                                        Prefs.getString(prefs, KEY_SECRET, "").isEmpty()
                                        ? unset : set));
        summary.append('\n');
        summary.append(context.getString(R.string.nezha_summary_cmd_fmt,
                                        Prefs.isNezhaDisableCommandExecute(prefs) ? on : off));
        summary.append('\n');
        summary.append(context.getString(isBinaryAvailable(context)
                                        ? R.string.nezha_binary_ok
                                        : R.string.nezha_binary_missing));
        return summary.toString();
    }

    /**
     * Is the {@code nezha-agent} executable available? It can be an explicitly
     * configured path, an imported/downloaded binary, or one bundled in the APK.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isBinaryAvailable(@NonNull final Context context) {
        return BinaryManager.isAvailable(context, BINARY_NAME, getExplicitPath(context));
    }

    /**
     * Write the {@code nezha-agent} configuration file ("{@code nezha-agent.yml}")
     * into the tools directory.
     * <p>
     * The generated file contains the v1-style settings:
     * {@code client_secret} (only when set), {@code debug},
     * {@code disable_auto_update}, {@code disable_force_update},
     * {@code disable_command_execute}, {@code disable_send_query},
     * {@code server} (only when set), {@code tls} and {@code uuid}.
     * <p>
     * The {@code uuid} of an existing file is reused, so the dashboard does not
     * list this device twice. User settings are written as single YAML scalars;
     * a value containing a line break is rejected so a hostile setting value
     * cannot inject extra YAML lines.
     *
     * @param context Current context
     *
     * @return the written configuration file
     *
     * @throws IOException when the file cannot be written, or when a setting
     *                     value contains a line break
     */
    @NonNull
    public static File writeConfig(@NonNull final Context context)
            throws IOException {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final File file = new File(BinaryManager.getToolsDir(context), CONFIG_NAME);

        final String server = checkSingleLine(
                Prefs.getString(prefs, KEY_SERVER, ""));
        final String secret = checkSingleLine(
                Prefs.getString(prefs, KEY_SECRET, ""));

        String uuid = readUuid(file);
        if (uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString();
        }

        final StringBuilder yaml = new StringBuilder();
        if (!secret.isEmpty()) {
            yaml.append("client_secret: ").append(yamlScalar(secret)).append('\n');
        }
        yaml.append("debug: false").append('\n');
        yaml.append("disable_auto_update: true").append('\n');
        yaml.append("disable_force_update: true").append('\n');
        yaml.append("disable_command_execute: ")
            .append(Prefs.isNezhaDisableCommandExecute(prefs))
            .append('\n');
        yaml.append("disable_send_query: false").append('\n');
        if (!server.isEmpty()) {
            yaml.append("server: ").append(yamlScalar(server)).append('\n');
        }
        yaml.append("tls: ").append(Prefs.isNezhaTls(prefs)).append('\n');
        yaml.append("uuid: ").append(yamlScalar(uuid)).append('\n');

        Files.write(file.toPath(), yaml.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Build the command line to run the {@code nezha-agent} child process.
     * <p>
     * The configuration file is regenerated before the command is returned.
     * The command is wrapped in "{@code su -c '...'}" by the
     * {@link BinaryManager} when root access is required.
     *
     * @param context Current context
     *
     * @return the command to pass to {@code ProcessBuilder}
     *
     * @throws IOException when the executable is not available, when it cannot
     *                     be executed (root required but not available), or when
     *                     the configuration cannot be written
     */
    @NonNull
    public static List<String> buildCommand(@NonNull final Context context)
            throws IOException {
        final File exe = BinaryManager.resolve(context, BINARY_NAME, getExplicitPath(context));
        if (exe == null) {
            throw new IOException(context.getString(R.string.nezha_no_binary));
        }
        final File config = writeConfig(context);
        return BinaryManager.buildCommand(context, exe,
                                          List.of("-c", config.getAbsolutePath()));
    }

    /**
     * The user configured explicit path to the executable.
     *
     * @param context Current context
     *
     * @return path, or an empty String when not set
     */
    @NonNull
    private static String getExplicitPath(@NonNull final Context context) {
        return Prefs.getString(Prefs.getSharedPreferences(context), KEY_BINARY, "");
    }

    /**
     * Reject a setting value containing a line break.
     *
     * @param value to check
     *
     * @return the value, unchanged
     *
     * @throws IOException when the value contains {@code '\n'} or {@code '\r'}
     */
    @NonNull
    private static String checkSingleLine(@NonNull final String value)
            throws IOException {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException(
                    "Nezha setting value must not contain line breaks: " + value);
        }
        return value;
    }

    /**
     * Render a String as a single YAML scalar.
     * <p>
     * Plain values are written as-is; values with special characters (or values
     * a YAML parser would read as a number, boolean or null) are single-quoted,
     * with inner quotes doubled.
     *
     * @param value to render
     *
     * @return the YAML representation
     */
    @NonNull
    private static String yamlScalar(@NonNull final String value) {
        if (isPlainScalar(value)) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Can a String be written as an unquoted YAML scalar?
     *
     * @param value to check
     *
     * @return flag
     */
    private static boolean isPlainScalar(@NonNull final String value) {
        if (value.isEmpty() || !PLAIN_SCALAR.matcher(value).matches()) {
            return false;
        }
        if (YAML_NUMBER.matcher(value).matches()) {
            return false;
        }
        return !YAML_KEYWORDS.contains(value.toLowerCase(Locale.ROOT));
    }

    /**
     * Read the {@code uuid} from an existing configuration file.
     *
     * @param file to read
     *
     * @return the uuid, or an empty String when there is none
     */
    @NonNull
    private static String readUuid(@NonNull final File file) {
        if (!file.exists()) {
            return "";
        }
        //noinspection OverlyBroadCatchBlock
        try {
            for (final String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                final String trimmed = line.strip();
                if (trimmed.startsWith(YAML_UUID)) {
                    final String value = unquote(trimmed.substring(YAML_UUID.length()).strip());
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (@NonNull final Exception ignore) {
            // unreadable; a fresh uuid will be generated
        }
        return "";
    }

    /**
     * Remove the surrounding quotes (if any) from a YAML scalar.
     *
     * @param value to unquote
     *
     * @return the unquoted value
     */
    @NonNull
    private static String unquote(@NonNull final String value) {
        if (value.length() >= 2) {
            final char first = value.charAt(0);
            final char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
