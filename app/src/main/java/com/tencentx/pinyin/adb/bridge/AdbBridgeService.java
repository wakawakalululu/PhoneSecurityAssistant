package com.tencentx.pinyin.adb.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.BuildConfig;
import com.tencentx.pinyin.MainActivity;
import com.tencentx.pinyin.R;
import com.tencentx.pinyin.adb.AdbManager;
import com.tencentx.pinyin.settings.Prefs;

/**
 * In-app foreground service that owns the {@link LocalServerSocket} the bridge
 * shell connects to.
 * <p>
 * For every connection it validates, in order:
 * <ol>
 *     <li>{@link Prefs#isAdbBridgeEnabled} &mdash; the master switch</li>
 *     <li>{@link AdbBridgeAuth#isAuthorized} &mdash; the SSH fingerprint whitelist</li>
 *     <li>{@link AdbCommandPolicy#isAllowed} &mdash; the command policy</li>
 *     <li>{@link AdbManager#isConnected()} &mdash; the ADB connection; when this
 *         is {@code false} the request <strong>fails fast</strong> and is never
 *         degraded to an app/root shell</li>
 * </ol>
 * Only then is the command run via {@link AdbManager#exec} and the output
 * written back over the {@link LocalSocket}.
 */
public final class AdbBridgeService
        extends Service {

    /** Broadcast (via LocalBroadcastManager) sent when the bridge state changes. */
    public static final String STATE_CHANGED = "com.tencentx.pinyin.adb.bridge.STATE_CHANGED";

    static final String SOCKET_NAME = "com.tencentx.pinyin.adb.bridge.IPC";

    private static final String CHANNEL_ID = "com.tencentx.pinyin.ADB_BRIDGE_CHANNEL";
    private static final int NOTIFICATION_ID = 4;
    private static final String TAG = "AdbBridgeService";

    private static final Object LOCK = new Object();
    @Nullable
    private static AdbBridgeService sInstance;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private SharedPreferences prefs;
    private volatile boolean running;
    @Nullable
    private LocalServerSocket serverSocket;
    @Nullable
    private Thread acceptThread;
    private boolean channelCreated;

    static boolean isRunning() {
        synchronized (LOCK) {
            return sInstance != null && sInstance.running;
        }
    }

    /**
     * Start the bridge execution service.
     *
     * @param context Current context
     */
    public static void startService(@NonNull final Context context) {
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startForegroundService(
                    new Intent(context, AdbBridgeService.class));
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "startService failed", e);
        }
    }

    /**
     * Stop the bridge execution service.
     *
     * @param context Current context
     */
    public static void stopService(@NonNull final Context context) {
        context.getApplicationContext().stopService(
                new Intent(context, AdbBridgeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.getSharedPreferences(this);
        synchronized (LOCK) {
            sInstance = this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(@Nullable final Intent intent,
                              final int flags,
                              final int startId) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStartCommand");
        }
        startForegroundNotification();
        startServerSocket();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && !channelCreated) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.adb_bridge_service_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
            channelCreated = true;
        }
        final PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app)
                .setContentTitle(getString(R.string.adb_bridge_service_name))
                .setContentText(getString(R.string.adb_bridge_notification_running))
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startServerSocket() {
        if (running) {
            return;
        }
        running = true;
        try {
            serverSocket = new LocalServerSocket(SOCKET_NAME);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "Failed to create LocalServerSocket", e);
            running = false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "adb-bridge-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            final LocalSocket client;
            try {
                final LocalServerSocket s = serverSocket;
                if (s == null) {
                    break;
                }
                client = s.accept();
            } catch (@NonNull final Exception e) {
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
                break;
            }
            executor.execute(() -> handleClient(client));
        }
    }

    private void handleClient(@NonNull final LocalSocket client) {
        try (LocalSocket c = client) {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            final String fingerprint = reader.readLine();
            final String command = reader.readLine();
            final BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8));

            if (!Prefs.isAdbBridgeEnabled(prefs)) {
                writeError(writer, getString(R.string.adb_bridge_err_disabled));
                return;
            }
            if (!AdbBridgeAuth.isAuthorized(this, fingerprint)) {
                writeError(writer, getString(R.string.adb_bridge_err_not_authorized));
                return;
            }
            final AdbCommandPolicy.Mode mode = AdbCommandPolicy.Mode.fromValue(
                    Prefs.getAdbBridgePolicyMode(prefs));
            if (!AdbCommandPolicy.isAllowed(command, mode,
                    Prefs.getAdbBridgeAllowlist(prefs),
                    Prefs.getAdbBridgeDenylist(prefs))) {
                writeError(writer, getString(R.string.adb_bridge_err_denied));
                return;
            }
            final AdbManager adb = AdbManager.getInstance(this);
            if (!adb.isConnected()) {
                writeError(writer, getString(R.string.adb_bridge_err_not_connected));
                return;
            }

            final CountDownLatch latch = new CountDownLatch(1);
            final List<String> lines = Collections.synchronizedList(new ArrayList<>());
            adb.exec(command, new AdbManager.OutputListener() {
                @Override
                public void onLine(@NonNull final String line) {
                    lines.add(line);
                }

                @Override
                public void onComplete() {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (@NonNull final InterruptedException e) {
                Thread.currentThread().interrupt();
                writeError(writer, getString(R.string.adb_bridge_err_interrupted));
                return;
            }
            for (final String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (@NonNull final Exception e) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "handleClient error", e);
            }
        }
    }

    private void writeError(@NonNull final BufferedWriter writer,
                            @NonNull final String message) {
        try {
            writer.write(message);
            writer.newLine();
            writer.flush();
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        final LocalServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            try {
                s.close();
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
        final Thread t = acceptThread;
        if (t != null) {
            t.interrupt();
        }
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        synchronized (LOCK) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
