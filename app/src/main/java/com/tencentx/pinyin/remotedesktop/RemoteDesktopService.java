package com.tencentx.pinyin.remotedesktop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicReference;

import com.tencentx.pinyin.BuildConfig;
import com.tencentx.pinyin.MainActivity;
import com.tencentx.pinyin.R;

/**
 * Foreground service that captures the device screen via {@link MediaProjection}
 * and streams it as MJPEG over HTTP.  Input events received by the HTTP server
 * are dispatched through {@link RemoteDesktopAccessibilityService}.
 */
public final class RemoteDesktopService
        extends Service {

    private static final String TAG = "RemoteDesktopService";

    private static final String ACTION_START = "com.tencentx.pinyin.rd.START";
    private static final String ACTION_STOP = "com.tencentx.pinyin.rd.STOP";
    private static final String EXTRA_RESULT_CODE = "resultCode";
    private static final String EXTRA_RESULT_DATA = "resultData";
    private static final String EXTRA_PORT = "port";

    private static final String CHANNEL_ID = "com.tencentx.pinyin.RD_CHANNEL";
    private static final int NOTIFICATION_ID = 4;
    private static final int DEFAULT_PORT = 8888;

    private static final Object LOCK = new Object();
    @Nullable
    private static RemoteDesktopService sInstance;

    private final AtomicReference<byte[]> latestFrame = new AtomicReference<>();

    @Nullable
    private ScreenCaptureManager captureManager;
    @Nullable
    private MjpegServer server;
    private boolean channelCreated;

    /**
     * Start the remote desktop service.
     *
     * @param context    Current context
     * @param resultCode Result code from MediaProjection permission dialog
     * @param data       Result data from MediaProjection permission dialog
     * @param port       HTTP server port (0 = default 8888)
     */
    public static void start(@NonNull final Context context,
                             final int resultCode,
                             @NonNull final Intent data,
                             final int port) {
        final Intent intent = new Intent(context, RemoteDesktopService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, data);
        intent.putExtra(EXTRA_PORT, port);
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startForegroundService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "start failed", e);
        }
    }

    /**
     * Stop the remote desktop service.
     *
     * @param context Current context
     */
    public static void stop(@NonNull final Context context) {
        final Intent intent = new Intent(context, RemoteDesktopService.class);
        intent.setAction(ACTION_STOP);
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "stop failed", e);
        }
    }

    /**
     * Is the remote desktop service currently running?
     *
     * @return flag
     */
    public static boolean isRunning() {
        synchronized (LOCK) {
            return sInstance != null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        final String action = intent != null && intent.getAction() != null
                              ? intent.getAction() : ACTION_STOP;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStartCommand action=" + action);
        }

        if (ACTION_START.equals(action) && intent != null) {
            final int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            final Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            final int port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT);
            if (resultData != null) {
                startCapture(resultCode, resultData, port);
            } else {
                Log.w(TAG, "Missing MediaProjection result data");
                stopSelf();
            }
        } else if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startCapture(final int resultCode,
                              @NonNull final Intent resultData,
                              final int port) {
        startForegroundNotification();

        final MediaProjectionManager mpm =
                getSystemService(MediaProjectionManager.class);
        if (mpm == null) {
            Log.w(TAG, "MediaProjectionManager unavailable");
            stopSelf();
            return;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            final MediaProjection projection = mpm.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                Log.w(TAG, "Failed to get MediaProjection");
                stopSelf();
                return;
            }
            captureManager = new ScreenCaptureManager(projection, latestFrame);
            captureManager.start();
            server = new MjpegServer(port > 0 ? port : DEFAULT_PORT, latestFrame);
            server.start();
            Log.i(TAG, "Remote desktop started on port " + (port > 0 ? port : DEFAULT_PORT));
            updateNotification(port > 0 ? port : DEFAULT_PORT);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "Failed to start capture", e);
            stopSelf();
        }
    }

    private void stopCapture() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (captureManager != null) {
            captureManager.stop();
            captureManager = null;
        }
        latestFrame.set(null);
    }

    private void startForegroundNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (!channelCreated) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.rd_service_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
            channelCreated = true;
        }
        final Notification notification = createNotification(
                getString(R.string.rd_notification_starting));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(final int port) {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        nm.notify(NOTIFICATION_ID,
                  createNotification(getString(R.string.rd_notification_running_fmt,
                                               String.valueOf(port))));
    }

    @NonNull
    private Notification createNotification(@NonNull final CharSequence text) {
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0,
                                          new Intent(this, MainActivity.class),
                                          PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app)
                .setTicker(getString(R.string.rd_service_name))
                .setContentTitle(getString(R.string.rd_service_name))
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    @Override
    public void onDestroy() {
        stopCapture();
        synchronized (LOCK) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
