package com.tencentx.pinyin.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tencentx.pinyin.privilege.PrivilegeMode;

/**
 * User settings.
 */
public final class Prefs {

    /** boolean. */
    static final String RUN_IN_FOREGROUND = "service.start.foreground";
    /** boolean. */
    static final String RUN_ON_BOOT = "service.start.onboot";
    /** boolean. */
    static final String RUN_ON_APP_START = "service.start.onopen";
    /** boolean. */
    static final String RUN_ON_INTENT_ALLOWED = "service.start.on.intent.allowed";
    /** boolean. */
    static final String ON_APP_EXIT_KEEP_RUNNING = "service.onappexit.keep_running";

    /** String. */
    static final String SSHD_PORT = "sshd.port";
    static final int DEFAULT_PORT = 2222;
    /** String. */
    static final String DROPBEAR_CMDLINE_OPTIONS = "dropbear.options";
    /** String. */
    static final String ENV_VARS = "sshd.env";
    /** String. */
    static final String HOME = "sshd.home";
    /** String. */
    static final String SHELL = "sshd.shell";
    static final String DEFAULT_SHELL = "/system/bin/sh";


    /** boolean. */
    static final String ENABLE_SERVICE_SHELL_ACCESS = "sshd.enable.service.shell";
    /** boolean. */
    static final String ENABLE_SINGLE_USE_PASSWORDS = "sshd.enable.single.use.password";
    /** boolean. */
    static final String ENABLE_PUBLIC_KEY_LOGIN = "sshd.enable.publickey.login";

    /**
     * String. Privilege mode used for an sshd login session:
     * {@link PrivilegeMode#VALUE_APP} or {@link PrivilegeMode#VALUE_ROOT}.
     * With "root" the session will be started via "{@code su}" (privilege escalation).
     */
    static final String SHELL_MODE = "sshd.shell.mode";

    /**
     * String. Explicit path to the "{@code su}" binary.
     * Empty/not-set means: auto-detect.
     */
    static final String SU_PATH = "sshd.su.path";

    /**
     * String. The mode the built-in terminal starts in:
     * {@link PrivilegeMode#VALUE_APP} or {@link PrivilegeMode#VALUE_ROOT} or
     * {@link PrivilegeMode#VALUE_ADB}.
     */
    static final String TERMINAL_MODE = "terminal.mode";

    /** String. Host for the ADB (wireless debugging) connection. */
    static final String ADB_HOST = "adb.host";
    static final String ADB_DEFAULT_HOST = "127.0.0.1";
    /** String. Port for the ADB (wireless debugging) connection. */
    static final String ADB_PORT = "adb.port";
    static final int ADB_DEFAULT_PORT = 5555;

    /** boolean. Remote ADB command bridge master switch (default off). */
    static final String ADB_BRIDGE_ENABLED = "adb.bridge.enabled";
    /** String. Port for the bridge sshd. */
    static final String ADB_BRIDGE_PORT = "adb.bridge.port";
    static final int ADB_BRIDGE_DEFAULT_PORT = 2223;
    /** String. Policy mode: ALLOWLIST / DENYLIST / OPEN. */
    static final String ADB_BRIDGE_POLICY_MODE = "adb.bridge.policy.mode";
    static final String ADB_BRIDGE_DEFAULT_POLICY_MODE = "ALLOWLIST";
    /** String. Newline-separated allowlist command prefixes. */
    static final String ADB_BRIDGE_ALLOWLIST = "adb.bridge.allowlist";
    /** String. Newline-separated denylist command prefixes. */
    static final String ADB_BRIDGE_DENYLIST = "adb.bridge.denylist";

    /** boolean. Start the remote access tools together with the sshd service. */
    static final String REMOTE_START_WITH_SSHD = "remote.start.with.sshd";

    /** boolean. Automatically start frpc when the remote access service starts. */
    static final String FRP_AUTO_START = "frp.auto.start";
    /** String. */
    static final String FRP_SERVER_ADDR = "frp.server.addr";
    /** String. */
    static final String FRP_SERVER_PORT = "frp.server.port";
    static final String FRP_DEFAULT_SERVER_PORT = "7000";
    /** String. */
    static final String FRP_TOKEN = "frp.token";
    /** String. */
    static final String FRP_PROXY_NAME = "frp.proxy.name";
    static final String FRP_DEFAULT_PROXY_NAME = "sshd4a";
    /** String. Remote port on the frp server which will forward to the local sshd port. */
    static final String FRP_REMOTE_PORT = "frp.remote.port";
    /** String. Explicit path to an "{@code frpc}" executable; empty = bundled/imported. */
    static final String FRP_BINARY = "frp.binary";

    /** boolean. Automatically start tailscaled when the remote access service starts. */
    static final String TS_AUTO_START = "ts.auto.start";
    /** String. Tailscale auth key (can be left empty when already logged in). */
    static final String TS_AUTH_KEY = "ts.authkey";
    /** String. Tailscale hostname to advertise. */
    static final String TS_HOSTNAME = "ts.hostname";
    /** boolean. {@code true}: TUN mode (requires root); {@code false}: userspace-networking. */
    static final String TS_TUN_MODE = "ts.tun.mode";
    /** String. Explicit path to a "{@code tailscaled}" executable; empty = bundled/imported. */
    static final String TS_BINARY = "ts.binary";

    /** boolean. Automatically start the Nezha monitoring agent. */
    static final String NEZHA_AUTO_START = "nezha.auto.start";
    /** String. Nezha dashboard address, e.g. "demo.nz.leishi.io:8008". */
    static final String NEZHA_SERVER = "nezha.server";
    /** String. Nezha client secret. */
    static final String NEZHA_SECRET = "nezha.secret";
    /** boolean. Use TLS when connecting to the dashboard. */
    static final String NEZHA_TLS = "nezha.tls";
    /** boolean. Disallow remote command execution from the dashboard. */
    static final String NEZHA_DISABLE_COMMAND_EXECUTE = "nezha.disable.command.execute";
    /** String. Explicit path to a "{@code nezha-agent}" executable; empty = bundled/imported. */
    static final String NEZHA_BINARY = "nezha.binary";

    static final String WEBDAV_URL = "webdav.url";
    static final String WEBDAV_USERNAME = "webdav.username";
    static final String WEBDAV_PASSWORD = "webdav.password";

    /** String. Explicit path to an "{@code rclone}" executable; empty = bundled/imported. */
    static final String SYNC_BINARY = "sync.rclone.binary";

    /** The url from which the user last imported keys. */
    static final String IMPORT_URL = "import.url";

    /**
     * Extra command line options to pass to the dropbear executable.
     * Splits on spaces, but respects " and \
     */
    private static final Pattern CMD_OPTIONS_PATTERN = Pattern.compile(
            "\\S*\"([^\"]*)\"\\S*|(\\S+)");

    private Prefs() {
    }

    @NonNull
    public static SharedPreferences getSharedPreferences(@NonNull final Context context) {
        // Use same file name as used by the legacy androidx.preference
        return context.getSharedPreferences(
                context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
    }

    /**
     * Start the service when the device is booted.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartOnBoot(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(RUN_ON_BOOT, false);
    }

    /**
     * Start the service when the app is started.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isRunOnAppStart(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(RUN_ON_APP_START, false);
    }

    /**
     * Whether an external Intent can start app+service and stop the service+app.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartByIntentAllowed(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(RUN_ON_INTENT_ALLOWED, false);
    }

    /**
     * Whether a user/manually started service should be stopped
     * when the app enters {@code #onDestroy()}.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isKeepRunningOnAppExit(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(ON_APP_EXIT_KEEP_RUNNING, false);
    }

    /**
     * How the service should be handled by the system when the App goes to the background.
     *
     * @param context Current context
     *
     * @return {@code true}: the service should keep running
     *         {@code false}: the system can kill the service
     */
    public static boolean isRunInForeground(@NonNull final Context context) {
        return isRunInForeground(getSharedPreferences(context));
    }

    /**
     * How the service should be handled by the system when the App goes to the background.
     *
     * @param prefs to read from
     *
     * @return {@code true}: the service should keep running
     *         {@code false}: the system can kill the service
     */
    public static boolean isRunInForeground(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(RUN_IN_FOREGROUND, true);
    }

    /**
     * Get the bindings as configured.
     *
     * @param prefs to read from
     *
     * @return list of "[address:]port" strings
     */
    @NonNull
    public static List<String> getBindings(@NonNull final SharedPreferences prefs) {
        final List<String> userOptions = getCmdLineOptions(prefs);
        if (userOptions.contains("-p")) {
            return collectBindings(userOptions);
        } else {
            return List.of("*:" + getPort(prefs));
        }
    }

    /**
     * Collect all user configured bindings, we're NOT checking validity.
     * 1. we're assuming the user knows what they are doing... flw
     * 2. broken options will be detected in dropbear
     *
     * @param userOptions to parse
     *
     * @return list of "-p" arguments, i.e. the "addr:port" settings
     */
    @VisibleForTesting
    @NonNull
    public static List<String> collectBindings(@NonNull final List<String> userOptions) {
        final List<String> bindings = new ArrayList<>();
        final Iterator<String> it = userOptions.iterator();
        while (it.hasNext()) {
            final String s = it.next();
            if ("-p".equals(s) && it.hasNext()) {
                bindings.add(it.next());
            }
        }
        return bindings;
    }

    /**
     * The user configured port to listen on.
     *
     * @param prefs to read from
     *
     * @return port as a {@code String}
     */
    public static int getPort(@NonNull final SharedPreferences prefs) {
        final String ps = prefs.getString(Prefs.SSHD_PORT, null);
        if (ps != null && !ps.isBlank()) {
            //noinspection OverlyBroadCatchBlock
            try {
                return Integer.parseInt(ps);
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }

        return DEFAULT_PORT;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return options
     */
    @NonNull
    public static List<String> getCmdLineOptions(@NonNull final SharedPreferences prefs) {
        return splitOptions(prefs.getString(DROPBEAR_CMDLINE_OPTIONS, ""));
    }

    /**
     * Split the single String with options in a list.
     *
     * @param options to split
     *
     * @return list
     */
    @VisibleForTesting
    @NonNull
    public static List<String> splitOptions(@NonNull final CharSequence options) {
        final List<String> argList = new ArrayList<>();
        final Matcher matcher = CMD_OPTIONS_PATTERN.matcher(options);
        while (matcher.find()) {
            argList.add(matcher.group());
        }
        return argList;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param context Current context
     * @param prefs   to read from
     *
     * @return path
     */
    @NonNull
    public static String getHomePath(@NonNull final Context context,
                                     @NonNull final SharedPreferences prefs) {
        String homePath = prefs.getString(HOME, null);
        // If never set, init to the 'files' directory. */
        if (homePath == null || !new File(homePath).exists()) {
            homePath = context.getFilesDir().getPath();
            prefs.edit().putString(HOME, homePath).apply();
        }
        return homePath;
    }

    /**
     * Allow remote shell access to the device.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnableServiceShellAccess(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_SERVICE_SHELL_ACCESS, true);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return path
     */
    @NonNull
    public static String getShellCmd(@NonNull final SharedPreferences prefs) {
        String shellCmd = prefs.getString(SHELL, null);
        if (shellCmd == null || !new File(shellCmd).exists()) {
            shellCmd = DEFAULT_SHELL;
        }
        return shellCmd;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return env vars
     */
    @NonNull
    public static String getEnv(@NonNull final SharedPreferences prefs) {
        return prefs.getString(ENV_VARS, "");
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnablePublicKeyAuth(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_PUBLIC_KEY_LOGIN, true);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnableSingleUsePasswordAuth(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_SINGLE_USE_PASSWORDS, true);
    }

    /**
     * The privilege mode to use for a new sshd login session.
     *
     * @param prefs to read from
     *
     * @return mode; defaults to {@link PrivilegeMode#APP}
     */
    @NonNull
    public static PrivilegeMode getShellMode(@NonNull final SharedPreferences prefs) {
        return PrivilegeMode.fromValue(prefs.getString(SHELL_MODE, null));
    }

    /**
     * The mode the built-in terminal should start in.
     *
     * @param context Current context
     *
     * @return mode; defaults to {@link PrivilegeMode#APP}
     */
    @NonNull
    public static PrivilegeMode getTerminalMode(@NonNull final Context context) {
        return PrivilegeMode.fromValue(
                getSharedPreferences(context).getString(TERMINAL_MODE, null));
    }

    /**
     * Set the mode the built-in terminal should start in.
     *
     * @param context Current context
     * @param mode    to store
     */
    public static void setTerminalMode(@NonNull final Context context,
                                       @NonNull final PrivilegeMode mode) {
        getSharedPreferences(context)
                .edit()
                .putString(TERMINAL_MODE, mode.getValue())
                .apply();
    }

    /**
     * Set the privilege mode used for new sshd login sessions.
     *
     * @param prefs to write to
     * @param mode  to store
     */
    public static void setShellMode(@NonNull final SharedPreferences prefs,
                                    @NonNull final PrivilegeMode mode) {
        prefs.edit()
             .putString(SHELL_MODE, mode.getValue())
             .apply();
    }

    /**
     * The user configured (or auto-detected) path to the "{@code su}" binary.
     *
     * @param prefs to read from
     *
     * @return full path, or an empty String when not available
     */
    @NonNull
    public static String getSuPath(@NonNull final SharedPreferences prefs) {
        final String path = prefs.getString(SU_PATH, "");
        return path != null ? path.strip() : "";
    }

    /**
     * Host used for the ADB (wireless debugging) connection.
     *
     * @param prefs to read from
     *
     * @return host; defaults to {@link #ADB_DEFAULT_HOST}
     */
    @NonNull
    public static String getAdbHost(@NonNull final SharedPreferences prefs) {
        final String host = prefs.getString(ADB_HOST, null);
        if (host == null || host.isBlank()) {
            return ADB_DEFAULT_HOST;
        }
        return host.strip();
    }

    /**
     * Port used for the ADB (wireless debugging) connection.
     *
     * @param prefs to read from
     *
     * @return port; defaults to {@link #ADB_DEFAULT_PORT}
     */
    public static int getAdbPort(@NonNull final SharedPreferences prefs) {
        final String ps = prefs.getString(ADB_PORT, null);
        if (ps != null && !ps.isBlank()) {
            //noinspection OverlyBroadCatchBlock
            try {
                return Integer.parseInt(ps.strip());
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
        return ADB_DEFAULT_PORT;
    }

    /**
     * Store the host and port used for ADB (wireless debugging).
     *
     * @param context Current context
     * @param host    to store
     * @param port    to store
     */
    public static void setAdbHostPort(@NonNull final Context context,
                                      @NonNull final String host,
                                      final int port) {
        getSharedPreferences(context)
                .edit()
                .putString(ADB_HOST, host.strip())
                .putString(ADB_PORT, String.valueOf(port))
                .apply();
    }

    /**
     * Whether the remote ADB command bridge is enabled.
     *
     * @param prefs to read from
     *
     * @return flag; defaults to {@code false}
     */
    public static boolean isAdbBridgeEnabled(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ADB_BRIDGE_ENABLED, false);
    }

    /**
     * The port the bridge sshd listens on.
     *
     * @param prefs to read from
     *
     * @return port; defaults to {@link #ADB_BRIDGE_DEFAULT_PORT}
     */
    public static int getAdbBridgePort(@NonNull final SharedPreferences prefs) {
        final String ps = prefs.getString(ADB_BRIDGE_PORT, null);
        if (ps != null && !ps.isBlank()) {
            //noinspection OverlyBroadCatchBlock
            try {
                return Integer.parseInt(ps.strip());
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
        return ADB_BRIDGE_DEFAULT_PORT;
    }

    /**
     * The command policy mode for the bridge.
     *
     * @param prefs to read from
     *
     * @return mode value; defaults to {@link #ADB_BRIDGE_DEFAULT_POLICY_MODE}
     */
    @NonNull
    public static String getAdbBridgePolicyMode(@NonNull final SharedPreferences prefs) {
        final String m = prefs.getString(ADB_BRIDGE_POLICY_MODE, null);
        if (m == null || m.isBlank()) {
            return ADB_BRIDGE_DEFAULT_POLICY_MODE;
        }
        return m.strip();
    }

    /**
     * The newline-separated allowlist command prefixes.
     *
     * @param prefs to read from
     *
     * @return the raw list; defaults to an empty String
     */
    @NonNull
    public static String getAdbBridgeAllowlist(@NonNull final SharedPreferences prefs) {
        return prefs.getString(ADB_BRIDGE_ALLOWLIST, "");
    }

    /**
     * The newline-separated denylist command prefixes.
     *
     * @param prefs to read from
     *
     * @return the raw list; defaults to an empty String
     */
    @NonNull
    public static String getAdbBridgeDenylist(@NonNull final SharedPreferences prefs) {
        return prefs.getString(ADB_BRIDGE_DENYLIST, "");
    }

    /**
     * Whether the remote access tools should be started together with the sshd service.
     *
     * @param prefs to read from
     *
     * @return flag
     */
    public static boolean isRemoteStartWithSshd(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(REMOTE_START_WITH_SSHD, false);
    }

    /**
     * Whether frpc should be started automatically by the remote access service.
     *
     * @param prefs to read from
     *
     * @return flag
     */
    public static boolean isFrpAutoStart(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(FRP_AUTO_START, false);
    }

    /**
     * Whether tailscaled should be started automatically by the remote access service.
     *
     * @param prefs to read from
     *
     * @return flag
     */
    public static boolean isTailscaleAutoStart(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(TS_AUTO_START, false);
    }

    /**
     * Whether tailscaled should be run in TUN mode (requires root),
     * or in userspace-networking mode (no root required).
     *
     * @param prefs to read from
     *
     * @return flag
     */
    public static boolean isTailscaleTunMode(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(TS_TUN_MODE, false);
    }

    /**
     * Whether the Nezha monitoring agent should be started automatically
     * by the remote access service.
     *
     * @param prefs to read from
     *
     * @return flag
     */
    public static boolean isNezhaAutoStart(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(NEZHA_AUTO_START, false);
    }

    /**
     * Whether the Nezha agent must refuse remote command execution
     * initiated from the dashboard.
     *
     * @param prefs to read from
     *
     * @return flag; defaults to {@code true} (safer)
     */
    public static boolean isNezhaDisableCommandExecute(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(NEZHA_DISABLE_COMMAND_EXECUTE, true);
    }

    /**
     * Whether TLS should be used towards the Nezha dashboard.
     *
     * @param prefs to read from
     *
     * @return flag; defaults to {@code true}
     */
    public static boolean isNezhaTls(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(NEZHA_TLS, true);
    }

    /**
     * Read a String preference; never returns {@code null}.
     *
     * @param prefs        to read from
     * @param key          to read
     * @param defaultValue returned when not set
     *
     * @return the value, stripped, or the default value
     */
    @NonNull
    public static String getString(@NonNull final SharedPreferences prefs,
                                   @NonNull final String key,
                                   @NonNull final String defaultValue) {
        final String value = prefs.getString(key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.strip();
    }
}
