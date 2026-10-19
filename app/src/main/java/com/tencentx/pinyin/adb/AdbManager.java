package com.tencentx.pinyin.adb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.settings.Prefs;

import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Facade for the ADB (wireless debugging) "ADB mode".
 * <p>
 * All operations run on a single background thread; results are delivered back
 * on the main thread. This class never blocks the main thread.
 * <p>
 * Commands are one-shot: {@link #exec(String, OutputListener)} opens an ADB
 * "{@code shell:}" stream, reads the output until the stream closes and then
 * reports completion. Interactive stdin is not supported.
 */
public final class AdbManager {

    /**
     * Result callback of an asynchronous operation.
     */
    public interface Callback {
        /**
         * Called on the main thread when the operation has finished.
         *
         * @param success {@code true} if the operation succeeded
         * @param message error message when {@code success} is {@code false}; may be {@code null}
         */
        void onResult(boolean success, @Nullable String message);
    }

    /**
     * Line based output listener of a command.
     */
    public interface OutputListener {
        /**
         * Called on the main thread for each line of output.
         *
         * @param line a single output line
         */
        void onLine(@NonNull String line);

        /**
         * Called on the main thread exactly once when the command has finished,
         * successfully or not.
         */
        void onComplete();
    }

    private static final int READ_BUFFER_SIZE = 4096;

    private static volatile AdbManager instance;

    private final Context appContext;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Get the singleton instance.
     *
     * @param context Current context; the application context is used
     *
     * @return the instance
     */
    @NonNull
    public static AdbManager getInstance(@NonNull final Context context) {
        AdbManager local = instance;
        if (local == null) {
            synchronized (AdbManager.class) {
                local = instance;
                if (local == null) {
                    local = new AdbManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    private AdbManager(@NonNull final Context context) {
        appContext = context.getApplicationContext();
    }

    /**
     * Whether there is an established ADB connection.
     *
     * @return {@code true} if connected
     */
    public boolean isConnected() {
        return AdbConnectionManager.getInstance(appContext).isConnected();
    }

    /**
     * Pair with the device's own wireless-debugging pairing port + code.
     * <p>
     * Runs on a background thread; the callback is posted to the main thread.
     *
     * @param host     host address of the device (usually {@code 127.0.0.1})
     * @param port     pairing port shown in developer options
     * @param code     six-digit pairing code shown in developer options
     * @param callback result callback
     */
    public void pair(@NonNull final String host, final int port,
                     @NonNull final String code, @NonNull final Callback callback) {
        executorService.execute(() -> {
            boolean success = false;
            String message = null;
            try {
                success = AdbConnectionManager.getInstance(appContext).pair(host, port, code);
                if (!success) {
                    message = getString(R.string.adb_unknown_error);
                }
            } catch (@NonNull final AdbPairingRequiredException e) {
                message = getString(R.string.adb_need_pairing);
            } catch (@NonNull final AdbAuthenticationFailedException e) {
                message = getString(R.string.adb_auth_failed);
            } catch (@NonNull final Exception e) {
                message = exceptionMessage(e);
            }

            final boolean result = success;
            final String resultMessage = message;
            mainHandler.post(() -> callback.onResult(result, resultMessage));
        });
    }

    /**
     * Connect to the wireless-debugging port.
     * <p>
     * On success the host/port are stored in {@link Prefs}.
     * Runs on a background thread; the callback is posted to the main thread.
     *
     * @param host     host address of the device (usually {@code 127.0.0.1})
     * @param port     connect port (default 5555)
     * @param callback result callback
     */
    public void connect(@NonNull final String host, final int port,
                        @NonNull final Callback callback) {
        executorService.execute(() -> {
            final AdbConnectionManager manager = AdbConnectionManager.getInstance(appContext);
            boolean success = false;
            String message = null;
            try {
                // A 'false' return value may also mean "already connected".
                success = manager.connect(host, port) || manager.isConnected();
                if (!success) {
                    message = getString(R.string.adb_pair_first);
                }
            } catch (@NonNull final AdbPairingRequiredException e) {
                message = getString(R.string.adb_need_pairing);
            } catch (@NonNull final AdbAuthenticationFailedException e) {
                message = getString(R.string.adb_auth_failed);
            } catch (@NonNull final Exception e) {
                message = exceptionMessage(e);
            }

            if (success) {
                Prefs.setAdbHostPort(appContext, host, port);
            }

            final boolean result = success;
            final String resultMessage = message;
            mainHandler.post(() -> callback.onResult(result, resultMessage));
        });
    }

    /**
     * Connect using the host/port stored in {@link Prefs}.
     * <p>
     * Runs on a background thread; the callback is posted to the main thread.
     *
     * @param callback result callback
     */
    public void connect(@NonNull final Callback callback) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(appContext);
        connect(Prefs.getAdbHost(prefs), Prefs.getAdbPort(prefs), callback);
    }

    /**
     * Disconnect the current ADB connection, if any.
     * <p>
     * Runs on a background thread.
     */
    public void disconnect() {
        executorService.execute(() -> {
            try {
                AdbConnectionManager.getInstance(appContext).disconnect();
            } catch (@NonNull final IOException ignore) {
                // Already gone... flw
            }
        });
    }

    /**
     * Run a command over an ADB "{@code shell:}" stream and deliver the output
     * line by line until the stream closes. The error message of a failed
     * command is passed through {@link OutputListener#onLine(String)} as well.
     * <p>
     * Runs on a background thread; both callbacks are posted to the main thread.
     *
     * @param command  the shell command to run
     * @param listener output listener
     */
    public void exec(@NonNull final String command, @NonNull final OutputListener listener) {
        executorService.execute(() -> {
            final AdbConnectionManager manager = AdbConnectionManager.getInstance(appContext);
            if (!manager.isConnected()) {
                postLine(listener, getString(R.string.adb_not_connected));
                mainHandler.post(listener::onComplete);
                return;
            }

            AdbStream stream = null;
            try {
                stream = manager.openStream("shell:" + command);
                final InputStream input = stream.openInputStream();
                final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
                final byte[] buffer = new byte[READ_BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    for (int i = 0; i < count; i++) {
                        final byte b = buffer[i];
                        if (b == '\n') {
                            postLine(listener, decodeLine(lineBuffer));
                            lineBuffer.reset();
                        } else if (b != '\r') {
                            lineBuffer.write(b);
                        }
                    }
                }
                // Flush the last line when the output did not end with a newline.
                if (lineBuffer.size() > 0) {
                    postLine(listener, decodeLine(lineBuffer));
                }
            } catch (@NonNull final Exception e) {
                postLine(listener, getString(R.string.adb_exec_failed_fmt, exceptionMessage(e)));
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (@NonNull final IOException ignore) {
                        // Already closed... flw
                    }
                }
                mainHandler.post(listener::onComplete);
            }
        });
    }

    private void postLine(@NonNull final OutputListener listener, @NonNull final String line) {
        mainHandler.post(() -> listener.onLine(line));
    }

    @NonNull
    private static String decodeLine(@NonNull final ByteArrayOutputStream lineBuffer) {
        return new String(lineBuffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @NonNull
    private String exceptionMessage(@NonNull final Throwable t) {
        final String message = t.getMessage();
        if (message == null || message.isBlank()) {
            return getString(R.string.adb_unknown_error);
        }
        return message;
    }

    @NonNull
    private String getString(@StringRes final int resId) {
        return appContext.getString(resId);
    }

    @NonNull
    private String getString(@StringRes final int resId, @NonNull final Object... formatArgs) {
        return appContext.getString(resId, formatArgs);
    }
}
