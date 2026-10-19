package com.tencentx.pinyin.remote;

import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.BuildConfig;
import com.tencentx.pinyin.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Import or download the helper binaries (frpc, tailscale, tailscaled, nezha-agent).
 * <p>
 * The official, statically linked release binaries are downloaded from GitHub/Pkg
 * servers. They are stored in the app its "tools" directory; because Android does
 * not allow the app to execute files from that directory, root ("su") is required
 * to run them. Binaries which are bundled in the APK (see
 * "{@code tools/fetch-go-binaries.sh}") are executable directly.
 */
public final class RemoteInstallHelper {

    private static final String TAG = "RemoteInstallHelper";

    public static final String FRP_VERSION = "0.65.0";
    public static final String TAILSCALE_VERSION = "1.90.6";
    public static final String NEZHA_VERSION = "2.3.5";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RemoteInstallHelper() {
    }

    /**
     * The binaries required for a tool.
     *
     * @param tool to check
     *
     * @return the targets; name to store as, and member name inside the archive
     */
    @NonNull
    public static List<BinaryManager.Target> getTargets(@NonNull final RemoteTool tool) {
        final List<BinaryManager.Target> targets = new ArrayList<>();
        switch (tool) {
            case FRP: {
                targets.add(new BinaryManager.Target("frpc", "frpc"));
                break;
            }
            case TAILSCALE: {
                targets.add(new BinaryManager.Target("tailscaled", "tailscaled"));
                targets.add(new BinaryManager.Target("tailscale", "tailscale"));
                break;
            }
            case NEZHA: {
                targets.add(new BinaryManager.Target("nezha-agent", "nezha-agent"));
                break;
            }
            default:
                break;
        }
        return targets;
    }

    /**
     * The name of the "main" binary of a tool; used when importing a single file.
     *
     * @param tool to check
     *
     * @return binary name
     */
    @NonNull
    public static String getPrimaryBinaryName(@NonNull final RemoteTool tool) {
        switch (tool) {
            case FRP:
                return "frpc";
            case TAILSCALE:
                return "tailscaled";
            case NEZHA:
            default:
                return "nezha-agent";
        }
    }

    /**
     * A human readable description of the binaries of a tool.
     *
     * @param tool to describe
     *
     * @return e.g. "frpc", "tailscaled, tailscale"
     */
    @NonNull
    public static String getTargetsDescription(@NonNull final RemoteTool tool) {
        final StringBuilder sb = new StringBuilder();
        for (final BinaryManager.Target target : getTargets(tool)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(target.name);
        }
        return sb.toString();
    }

    /**
     * Download the official binary/binaries for a tool, showing a progress dialog.
     *
     * @param activity to attach the dialog to
     * @param tool     to download
     */
    public static void download(@NonNull final FragmentActivity activity,
                                @NonNull final RemoteTool tool) {
        final String url = getDownloadUrl(tool);
        if (url == null) {
            Toast.makeText(activity,
                           activity.getString(R.string.remote_download_unsupported_abi,
                                             Build.SUPPORTED_ABIS[0]),
                           Toast.LENGTH_LONG).show();
            return;
        }

        final String description = getTargetsDescription(tool);
        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(tool.getLabelRes()))
                .setMessage(activity.getString(R.string.remote_download_progress_fmt, description))
                .setCancelable(false)
                .create();
        dialog.show();

        EXECUTOR.execute(() -> {
            String error = null;
            try {
                BinaryManager.downloadArchive(activity.getApplicationContext(), url,
                                              getTargets(tool), null);
            } catch (@NonNull final Exception e) {
                Log.e(TAG, "Download failed: " + url, e);
                error = e.getMessage();
            }

            final String fError = error;
            MAIN.post(() -> {
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                final String message;
                if (fError == null) {
                    message = activity.getString(R.string.remote_download_ok_fmt, description);
                } else {
                    message = activity.getString(R.string.remote_download_failed_fmt, fError);
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                appendToLog(activity, tool, message);
                // surface the result in the tool its log too
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, message);
                }
            });
        });
    }

    /**
     * Import a binary from a user selected file.
     *
     * @param activity to use
     * @param tool     to import for
     * @param uri      selected file
     *
     * @see #getPrimaryBinaryName(RemoteTool)
     */
    public static void importFromUri(@NonNull final FragmentActivity activity,
                                     @NonNull final RemoteTool tool,
                                     @NonNull final Uri uri) {
        final String name = getPrimaryBinaryName(tool);
        EXECUTOR.execute(() -> {
            String error = null;
            try {
                BinaryManager.importBinary(activity.getApplicationContext(), name, uri);
            } catch (@NonNull final Exception e) {
                Log.e(TAG, "Import failed: " + uri, e);
                error = e.getMessage();
            }

            final String fError = error;
            MAIN.post(() -> {
                final String message;
                if (fError == null) {
                    message = activity.getString(R.string.remote_import_ok_fmt, name);
                } else {
                    message = activity.getString(R.string.remote_import_failed_fmt, fError);
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                if (fError == null) {
                    // imported binaries are not in the native library dir:
                    // they can only be executed with root.
                    Toast.makeText(activity,
                                   R.string.remote_import_root_hint,
                                   Toast.LENGTH_LONG).show();
                }
                appendToLog(activity, tool, message);
            });
        });
    }

    /**
     * Notify the tool its log file; the log is shown in the UI.
     *
     * @param tool    its log
     * @param message to write
     */
    private static void appendToLog(@NonNull final FragmentActivity activity,
                                    @NonNull final RemoteTool tool,
                                    @NonNull final String message) {
        final File logFile = tool.getLogFile(activity);
        //noinspection OverlyBroadCatchBlock
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(logFile, true),
                java.nio.charset.StandardCharsets.UTF_8)) {
            w.write(message + "\n");
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }

    /**
     * The download url of the archive for a tool, based on the primary ABI.
     *
     * @param tool to check
     *
     * @return url, or {@code null} when the ABI is not supported
     */
    @Nullable
    private static String getDownloadUrl(@NonNull final RemoteTool tool) {
        final String arch = getArch();
        if (arch == null) {
            return null;
        }
        switch (tool) {
            case FRP: {
                return "https://github.com/fatedier/frp/releases/download/v" + FRP_VERSION
                       + "/frp_" + FRP_VERSION + "_linux_" + arch + ".tar.gz";
            }
            case TAILSCALE: {
                return "https://pkgs.tailscale.com/stable/tailscale_"
                       + TAILSCALE_VERSION + "_" + arch + ".tgz";
            }
            case NEZHA: {
                return "https://github.com/nezhahq/agent/releases/download/v" + NEZHA_VERSION
                       + "/nezha-agent_linux_" + arch + ".zip";
            }
            default:
                return null;
        }
    }

    /**
     * The ABI mapped to the release naming scheme used by the tools.
     *
     * @return architecture name, or {@code null} when not supported
     */
    @Nullable
    private static String getArch() {
        final String abi = Build.SUPPORTED_ABIS[0];
        switch (abi) {
            case "arm64-v8a":
                return "arm64";
            case "armeabi-v7a":
            case "armeabi":
                return "arm";
            case "x86_64":
                return "amd64";
            case "x86":
                return "386";
            default:
                return null;
        }
    }
}
