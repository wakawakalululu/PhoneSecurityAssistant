package com.tencentx.pinyin.sync;

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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.remote.BinaryManager;

/**
 * Import or download the {@code rclone} binary used by the sync feature.
 * <p>
 * The official, statically linked release is downloaded from GitHub and stored
 * in the app its "tools" directory; because Android does not allow the app to
 * execute files from that directory, root ("{@code su}") is required to run an
 * imported/downloaded binary. A binary bundled in the APK is executable
 * directly.
 */
public final class SyncInstallHelper {

    private static final String TAG = "SyncInstallHelper";

    /** rclone release version downloaded by {@link #download}. */
    public static final String RCLONE_VERSION = "1.69.3";

    private static final String BINARY_NAME = "rclone";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SyncInstallHelper() {
    }

    /**
     * Download the official rclone binary, showing a progress dialog.
     *
     * @param activity to attach the dialog to
     */
    public static void download(@NonNull final FragmentActivity activity) {
        final String url = getDownloadUrl();
        if (url == null) {
            Toast.makeText(activity,
                           activity.getString(R.string.remote_download_unsupported_abi,
                                              Build.SUPPORTED_ABIS[0]),
                           Toast.LENGTH_LONG).show();
            return;
        }

        final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.sync_screen_title)
                .setMessage(activity.getString(R.string.remote_download_progress_fmt,
                                               BINARY_NAME))
                .setCancelable(false)
                .create();
        dialog.show();

        EXECUTOR.execute(() -> {
            String error = null;
            //noinspection OverlyBroadCatchBlock
            try {
                BinaryManager.downloadArchive(activity.getApplicationContext(), url,
                                               List.of(new BinaryManager.Target(BINARY_NAME,
                                                                                BINARY_NAME)),
                                               null);
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
                    message = activity.getString(R.string.remote_download_ok_fmt, BINARY_NAME);
                } else {
                    message = activity.getString(R.string.remote_download_failed_fmt, fError);
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    /**
     * Import an rclone binary from a user selected file.
     *
     * @param activity to use
     * @param uri      selected file
     */
    public static void importFromUri(@NonNull final FragmentActivity activity,
                                     @NonNull final Uri uri) {
        EXECUTOR.execute(() -> {
            String error = null;
            //noinspection OverlyBroadCatchBlock
            try {
                BinaryManager.importBinary(activity.getApplicationContext(),
                                            BINARY_NAME, uri);
            } catch (@NonNull final Exception e) {
                Log.e(TAG, "Import failed: " + uri, e);
                error = e.getMessage();
            }

            final String fError = error;
            MAIN.post(() -> {
                final String message;
                if (fError == null) {
                    message = activity.getString(R.string.remote_import_ok_fmt, BINARY_NAME);
                } else {
                    message = activity.getString(R.string.remote_import_failed_fmt, fError);
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                if (fError == null) {
                    Toast.makeText(activity, R.string.remote_import_root_hint,
                                   Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * The download url of the rclone release zip for the primary ABI.
     *
     * @return url, or {@code null} when the ABI is not supported
     */
    @Nullable
    private static String getDownloadUrl() {
        final String arch = getArch();
        if (arch == null) {
            return null;
        }
        return "https://github.com/rclone/rclone/releases/download/v" + RCLONE_VERSION
               + "/rclone-v" + RCLONE_VERSION + "-linux-" + arch + ".zip";
    }

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
