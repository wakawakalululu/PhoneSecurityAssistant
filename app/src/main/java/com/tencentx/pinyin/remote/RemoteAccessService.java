package com.tencentx.pinyin.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.tencentx.pinyin.BuildConfig;
import com.tencentx.pinyin.MainActivity;
import com.tencentx.pinyin.R;
import com.tencentx.pinyin.settings.Prefs;

/**
 * Foreground service guarding the long-running helper processes:
 * <ul>
 *     <li>frpc (reverse proxy / tunneling)</li>
 *     <li>tailscaled (Tailscale)</li>
 *     <li>nezha-agent (Nezha monitoring)</li>
 * </ul>
 * Each tool can be started/stopped individually. The service keeps running as long
 * as at least one tool is active, so the tools survive the app being backgrounded.
 */
public class RemoteAccessService
        extends Service {

    /**
     * Broadcast (via LocalBroadcastManager) sent when a tool starts/stops.
     */
    public static final String STATE_CHANGED = "com.tencentx.pinyin.remote.STATE_CHANGED";

    private static final String ACTION_START_ALL = "com.tencentx.pinyin.remote.START_ALL";
    private static final String ACTION_START_TOOL = "com.tencentx.pinyin.remote.START_TOOL";
    private static final String ACTION_STOP_TOOL = "com.tencentx.pinyin.remote.STOP_TOOL";
    private static final String ACTION_STOP_ALL = "com.tencentx.pinyin.remote.STOP_ALL";
    private static final String EXTRA_TOOL = "tool";

    private static final String NOTIFICATION_CHANNEL_ID =
            "com.tencentx.pinyin.REMOTE_NOTIFICATION_CHANNEL";
    private static final int ONGOING_NOTIFICATION_ID = 2;

    private static final String TAG = "RemoteAccessService";

    private static final Object LOCK = new Object();
    @Nullable
    private static RemoteAccessService sInstance;

    @Nullable
    private static RemoteTool toolFromIntent(@NonNull final Intent intent) {
        final String name = intent.getStringExtra(EXTRA_TOOL);
        if (name == null) {
            return null;
        }
        for (final RemoteTool tool : RemoteTool.values()) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return null;
    }

    /**
     * Is a specific tool currently running?
     *
     * @param tool to check
     *
     * @return flag
     */
    public static boolean isRunning(@NonNull final RemoteTool tool) {
        synchronized (LOCK) {
            return sInstance != null && sInstance.isToolRunning(tool);
        }
    }

    /**
     * Check if at least one tool is running.
     *
     * @return flag
     */
    public static boolean isAnyRunning() {
        synchronized (LOCK) {
            if (sInstance == null) {
                return false;
            }
            for (final RemoteTool tool : RemoteTool.values()) {
                if (sInstance.isToolRunning(tool)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Start the service, and with it any tool that has "auto start" enabled.
     *
     * @param context Current context
     */
    public static void startService(@NonNull final Context context) {
        final Intent intent = new Intent(context, RemoteAccessService.class);
        intent.setAction(ACTION_START_ALL);
        try {
            context.getApplicationContext().startForegroundService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "startService failed", e);
        }
    }

    /**
     * Start a single tool.
     *
     * @param context Current context
     * @param tool    to start
     */
    public static void startTool(@NonNull final Context context,
                                 @NonNull final RemoteTool tool) {
        final Intent intent = new Intent(context, RemoteAccessService.class);
        intent.setAction(ACTION_START_TOOL);
        intent.putExtra(EXTRA_TOOL, tool.name());
        try {
            context.getApplicationContext().startForegroundService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "startTool failed", e);
        }
    }

    /**
     * Stop a single tool.
     *
     * @param context Current context
     * @param tool    to stop
     */
    public static void stopTool(@NonNull final Context context,
                                @NonNull final RemoteTool tool) {
        final Intent intent = new Intent(context, RemoteAccessService.class);
        intent.setAction(ACTION_STOP_TOOL);
        intent.putExtra(EXTRA_TOOL, tool.name());
        try {
            context.getApplicationContext().startService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "stopTool failed", e);
        }
    }

    /**
     * Stop all tools and the service itself.
     *
     * @param context Current context
     */
    public static void stopService(@NonNull final Context context) {
        final Intent intent = new Intent(context, RemoteAccessService.class);
        intent.setAction(ACTION_STOP_ALL);
        try {
            context.getApplicationContext().startService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "stopService failed", e);
        }
    }

    private final Map<RemoteTool, ManagedProcess> processes =
            new EnumMap<>(RemoteTool.class);

    private SharedPreferences prefs;
    private boolean notificationChannelCreated;

    private boolean isToolRunning(@NonNull final RemoteTool tool) {
        final ManagedProcess process = processes.get(tool);
        return process != null && process.isRunning();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.getSharedPreferences(this);
        for (final RemoteTool tool : RemoteTool.values()) {
            processes.put(tool, new ManagedProcess(tool.getName(), tool.getLogFile(this)));
        }
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
                              ? intent.getAction() : ACTION_START_ALL;

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStartCommand action=" + action);
        }

        switch (action) {
            case ACTION_START_ALL: {
                startForegroundNotification();
                // start all tools which are configured to auto-start,
                // but never restart one that is already running.
                if (Prefs.isFrpAutoStart(prefs) && !isToolRunning(RemoteTool.FRP)) {
                    startToolInternal(RemoteTool.FRP);
                }
                if (Prefs.isTailscaleAutoStart(prefs)
                    && !isToolRunning(RemoteTool.TAILSCALE)) {
                    startToolInternal(RemoteTool.TAILSCALE);
                }
                if (Prefs.isNezhaAutoStart(prefs) && !isToolRunning(RemoteTool.NEZHA)) {
                    startToolInternal(RemoteTool.NEZHA);
                }
                break;
            }
            case ACTION_START_TOOL: {
                final RemoteTool tool = toolFromIntent(intent);
                if (tool != null) {
                    startForegroundNotification();
                    startToolInternal(tool);
                }
                break;
            }
            case ACTION_STOP_TOOL: {
                final RemoteTool tool = toolFromIntent(intent);
                if (tool != null) {
                    stopToolInternal(tool);
                }
                checkStopSelf();
                break;
            }
            case ACTION_STOP_ALL: {
                for (final RemoteTool tool : RemoteTool.values()) {
                    stopToolInternal(tool);
                }
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                break;
            }
            default:
                break;
        }

        updateNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        for (final RemoteTool tool : RemoteTool.values()) {
            stopToolInternal(tool);
        }
        synchronized (LOCK) {
            sInstance = null;
        }
        updateUI();
        super.onDestroy();
    }

    private void startToolInternal(@NonNull final RemoteTool tool) {
        final ManagedProcess process = processes.get(tool);
        if (process == null || process.isRunning()) {
            return;
        }

        final List<String> command;
        try {
            switch (tool) {
                case FRP: {
                    command = FrpManager.buildCommand(this);
                    break;
                }
                case TAILSCALE: {
                    command = TailscaleManager.buildDaemonCommand(this);
                    break;
                }
                case NEZHA: {
                    command = NezhaManager.buildCommand(this);
                    break;
                }
                default: {
                    return;
                }
            }
        } catch (@NonNull final IOException e) {
            Log.e(TAG, "Cannot start " + tool.getName(), e);
            // surface the problem in the tool its log
            final FileWriterHelper helper = new FileWriterHelper(tool.getLogFile(this));
            helper.write(getString(R.string.err_remote_start_failed,
                                   tool.getName(), e.getMessage()));
            updateUI();
            // nothing running: no point in keeping the foreground service alive
            if (!isAnyRunning()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return;
        }

        try {
            process.start(command, BinaryManager.getToolsDir(this), null);
        } catch (@NonNull final IOException e) {
            Log.e(TAG, "Failed to start " + tool.getName(), e);
            final FileWriterHelper helper = new FileWriterHelper(tool.getLogFile(this));
            helper.write(getString(R.string.err_remote_start_failed,
                                   tool.getName(), e.getMessage()));
            if (!isAnyRunning()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
        updateUI();
    }

    private void stopToolInternal(@NonNull final RemoteTool tool) {
        final ManagedProcess process = processes.get(tool);
        if (process != null) {
            process.stop();
        }
        updateUI();
    }

    /**
     * If no tool is left running, stop the service.
     */
    private void checkStopSelf() {
        if (!isAnyRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void startForegroundNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (!notificationChannelCreated) {
            final NotificationChannel nc = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.remote_service_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(nc);
            notificationChannelCreated = true;
        }

        final Notification notification = createNotification(getString(
                R.string.remote_notification_starting));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ONGOING_NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        if (!isAnyRunning()) {
            return;
        }
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        final String running = runningToolsText();
        nm.notify(ONGOING_NOTIFICATION_ID,
                  createNotification(getString(R.string.remote_notification_running, running)));
    }

    @NonNull
    private String runningToolsText() {
        return java.util.Arrays.stream(RemoteTool.values())
                               .filter(this::isToolRunning)
                               .map(tool -> getString(tool.getLabelRes()))
                               .collect(Collectors.joining(", "));
    }

    @NonNull
    private Notification createNotification(@NonNull final CharSequence text) {
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0,
                                          new Intent(this, MainActivity.class),
                                          PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.app)
                .setTicker(getString(R.string.remote_service_name))
                .setContentTitle(getString(R.string.remote_service_name))
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateUI() {
        final Intent intent = new Intent(STATE_CHANGED);
        //noinspection deprecation
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    /**
     * Tiny helper to write a message to a log file without a running process.
     */
    private static final class FileWriterHelper {

        @NonNull
        private final java.io.File file;

        FileWriterHelper(@NonNull final java.io.File file) {
            this.file = file;
        }

        void write(@NonNull final String message) {
            //noinspection OverlyBroadCatchBlock
            try (java.io.FileWriter fw = new java.io.FileWriter(file, true)) {
                fw.write(message + "\n");
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
    }
}
