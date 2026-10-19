package com.tencentx.pinyin.adb;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Concrete {@link AbsAdbConnectionManager} for this app.
 * <p>
 * The key pair and certificate come from {@link AdbKeyStore}; the device name used
 * towards the ADB daemon is "Sshd4a".
 * <p>
 * All connection methods inherited from {@link AbsAdbConnectionManager} are
 * {@link WorkerThread} operations and must not be called from the main thread.
 * Use {@link AdbManager} instead.
 */
public final class AdbConnectionManager extends AbsAdbConnectionManager {

    /** Device name used towards the ADB daemon. */
    private static final String DEVICE_NAME = "Sshd4a";

    /** Timeout for making a connection. */
    private static final long TIMEOUT_SECONDS = 30L;

    private static volatile AdbConnectionManager instance;

    /**
     * Get the singleton instance.
     *
     * @param context Current context; the application context is used
     *
     * @return the instance
     */
    @NonNull
    public static AdbConnectionManager getInstance(@NonNull final Context context) {
        AdbConnectionManager local = instance;
        if (local == null) {
            synchronized (AdbConnectionManager.class) {
                local = instance;
                if (local == null) {
                    local = new AdbConnectionManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context appContext;

    private AdbConnectionManager(@NonNull final Context context) {
        appContext = context.getApplicationContext();

        setApi(Build.VERSION.SDK_INT);
        setTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Throw AdbAuthenticationFailedException when the daemon rejects the first
        // authentication attempt, so the caller can ask the user to (re-)pair.
        setThrowOnUnauthorised(true);
    }

    @NonNull
    @WorkerThread
    @Override
    protected PrivateKey getPrivateKey() {
        return AdbKeyStore.getInstance(appContext).getPrivateKey();
    }

    @NonNull
    @WorkerThread
    @Override
    protected Certificate getCertificate() {
        return AdbKeyStore.getInstance(appContext).getCertificate();
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return DEVICE_NAME;
    }
}
