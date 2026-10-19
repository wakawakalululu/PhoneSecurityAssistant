package com.tencentx.pinyin.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.SshdSettings;
import com.tencentx.pinyin.privilege.RootUtils;

@SuppressWarnings("WeakerAccess")
public class SettingsViewModel
        extends ViewModel {

    private final MutableLiveData<Void> importDone = new MutableLiveData<>();
    private final MutableLiveData<Exception> importFailed = new MutableLiveData<>();
    private final MutableLiveData<Boolean> importRunning = new MutableLiveData<>(false);
    private final MutableLiveData<String> rootDetected = new MutableLiveData<>();

    /** DataStore for user/password fields. */
    private UserPassStorage userPassDataStore;

    /** Prevent overlapping root checks; a "{@code su}" prompt can take a while. */
    private volatile boolean rootCheckRunning;

    void init(@NonNull final Context context) {
        if (userPassDataStore == null) {
            final SharedPreferences prefs = Prefs.getSharedPreferences(context);
            // Force init if never set before
            Prefs.getHomePath(context, prefs);

            userPassDataStore = new UserPassStorage(context);
        }
    }

    @NonNull
    LiveData<Boolean> onImportRunning() {
        return importRunning;
    }

    /**
     * Observable.
     *
     * @return aVoid
     */
    @NonNull
    LiveData<Void> onImportDone() {
        return importDone;
    }

    /**
     * Observable.
     *
     * @return Exception
     */
    @NonNull
    LiveData<Exception> onImportFailed() {
        return importFailed;
    }

    /**
     * Observable.
     * The result of a {@link #detectRoot(Context)} check:
     * the path of the detected "{@code su}" binary,
     * or {@code null} when root is not available.
     *
     * @return path
     */
    @NonNull
    LiveData<String> onRootDetected() {
        return rootDetected;
    }

    @NonNull
    UserPassStorage getUserPassDataStore() {
        return userPassDataStore;
    }

    boolean hasAtLeastOneAuthMethod(@NonNull final Context context) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);

        if (Prefs.isEnableSingleUsePasswordAuth(prefs)
            || Prefs.isEnablePublicKeyAuth(prefs)) {
            return true;
        }

        final String[] userAndPassword = SshdSettings.readPasswordFile(context);
        // both need to be not-empty
        return userAndPassword != null && userAndPassword.length == 2
               && userAndPassword[0] != null && !userAndPassword[0].isBlank()
               && userAndPassword[1] != null && !userAndPassword[1].isBlank();
    }

    /**
     * Check for root ("{@code su}") availability on a background thread.
     * <p>
     * The detected path is stored in {@link Prefs#SU_PATH}; the outcome is
     * reported through {@link #onRootDetected()}.
     * <p>
     * Note: this can block for up to ~30s while the device shows the
     * "grant root" prompt, so it must never run on the main thread.
     *
     * @param context Current context
     */
    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    void detectRoot(@NonNull final Context context) {
        // prevent starting twice
        if (rootCheckRunning) {
            return;
        }
        rootCheckRunning = true;

        final Context appContext = context.getApplicationContext();

        Executors.newSingleThreadExecutor()
                 .execute(() -> {
                     try {
                         // forget any cached values; the user may have changed
                         // the root manager or granted access since a previous check
                         RootUtils.reset();

                         final String suPath = RootUtils.findSuPath();
                         final boolean available = RootUtils.isRootAvailable();

                         if (suPath != null) {
                             // remember the detected path for login/terminal sessions
                             Prefs.getSharedPreferences(appContext)
                                  .edit()
                                  .putString(Prefs.SU_PATH, suPath)
                                  .apply();
                         }

                         // only report success when "su" can actually be used
                         rootDetected.postValue(available ? suPath : null);

                     } finally {
                         rootCheckRunning = false;
                     }
                 });
    }

    /**
     * Import the given <strong>file</strong> uri as the new "authorized_keys" file.
     * Overwrites the previous!
     * <p>
     * The task return is a bit of a hack...
     * - {@code null} for success.
     * - {@code "ERROR"} for a generic import problem
     * - if we got an IOException, the localised message from the exception
     *
     * @param context Current context
     * @param uri     to import
     *
     * @see #onImportDone()
     */
    void importAuthKeys(@NonNull final Context context,
                        @NonNull final Uri uri) {
        executeImport(context, () -> context.getApplicationContext()
                                            .getContentResolver()
                                            .openInputStream(uri));
    }

    /**
     * Import the given <strong>https</strong> url as the new "authorized_keys" file.
     * Overwrites the previous!
     * <p>
     * The task return is a bit of a hack...
     * - {@code null} for success.
     * - {@code "ERROR"} for a generic import problem
     * - if we got an IOException, the localised message from the exception
     *
     * @param context Current context
     * @param urlStr  to import
     *
     * @see #onImportDone()
     */
    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    void importAuthKeys(@NonNull final Context context,
                        @NonNull final String urlStr) {
        executeImport(context, () -> {
            final URL url = new URL(urlStr);
            final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();

            // Wrap the stream so we can disconnect when the stream closes
            return new FilterInputStream(connection.getInputStream()) {
                @Override
                public void close()
                        throws IOException {
                    try {
                        super.close();
                    } finally {
                        connection.disconnect();
                    }
                }
            };
        });
    }

    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    private void executeImport(@NonNull final Context context,
                               @NonNull final InputStreamProvider provider) {
        // prevent starting twice
        if (Boolean.TRUE.equals(importRunning.getValue())) {
            return;
        }
        importRunning.setValue(true);

        final Context appContext = context.getApplicationContext();

        //noinspection resource
        Executors.newSingleThreadExecutor()
                 .execute(() -> {
                     final File path = SshdSettings.getDropbearDirectory(appContext);
                     final File tmpFile = new File(path, SshdSettings.AUTHORIZED_KEYS + ".tmp");

                     // assume nothing works...
                     boolean error = true;
                     try (InputStream rawIs = provider.openStream();
                          InputStream is = checkForUtf8Bom(rawIs)) {
                         Files.copy(is, tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                         if (tmpFile.renameTo(new File(path, SshdSettings.AUTHORIZED_KEYS))) {
                             error = false;
                             importDone.postValue(null);
                             return;
                         }

                         importFailed.postValue(new FileRenameException());

                     } catch (@NonNull final Exception e) {
                         importFailed.postValue(e);
                     } finally {
                         if (error && tmpFile.exists()) {
                             //noinspection ResultOfMethodCallIgnored
                             tmpFile.delete();
                         }
                         importRunning.postValue(false);
                     }
                 });
    }

    /**
     * Wraps an InputStream and silently strips the UTF-8 BOM if present.
     *
     * @param in to check
     *
     * @return wrapped/stripped stream
     *
     * @throws IOException on any error
     */
    @SuppressWarnings({"BlockingMethodInNonBlockingContext", "MagicNumber"})
    @NonNull
    public InputStream checkForUtf8Bom(@NonNull final InputStream in)
            throws IOException {
        final PushbackInputStream pushbackStream = new PushbackInputStream(in, 3);
        final byte[] bom = new byte[3];
        final int bytesRead = pushbackStream.read(bom, 0, 3);

        if (bytesRead == 3
            && (bom[0] & 0xFF) == 0xEF
            && (bom[1] & 0xFF) == 0xBB
            && (bom[2] & 0xFF) == 0xBF) {
            // BOM present; return stream at byte 4
            return pushbackStream;
        }

        // Not a BOM; push back what was read
        if (bytesRead > 0) {
            pushbackStream.unread(bom, 0, bytesRead);
        }

        return pushbackStream;
    }

    void deleteAuthKeys(@NonNull final Context context) {
        //noinspection ResultOfMethodCallIgnored
        new File(SshdSettings.getDropbearDirectory(context), SshdSettings.AUTHORIZED_KEYS)
                .delete();
    }

    @FunctionalInterface
    private interface InputStreamProvider {
        InputStream openStream()
                throws Exception;
    }
}
