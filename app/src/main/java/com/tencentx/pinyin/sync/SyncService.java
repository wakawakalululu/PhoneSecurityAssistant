package com.tencentx.pinyin.sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.tencentx.pinyin.BuildConfig;
import com.tencentx.pinyin.MainActivity;
import com.tencentx.pinyin.R;

/**
 * Foreground service running {@code rclone copy} for {@link SyncTask}s.
 * <p>
 * A task is started with {@link #startTask(Context, String)} and runs as a
 * child process whose output is parsed for progress and appended to the task
 * its log file. The service keeps running as long as at least one task is
 * active; it stops itself when the last task finishes.
 */
public final class SyncService
        extends Service {

    /** Broadcast (via LocalBroadcastManager) sent when a task state changes. */
    public static final String STATE_CHANGED = "com.tencentx.pinyin.sync.STATE_CHANGED";

    private static final String ACTION_START = "com.tencentx.pinyin.sync.START";
    private static final String ACTION_STOP = "com.tencentx.pinyin.sync.STOP";
    private static final String ACTION_STOP_ALL = "com.tencentx.pinyin.sync.STOP_ALL";
    private static final String EXTRA_TASK_ID = "taskId";

    private static final String CHANNEL_ID = "com.tencentx.pinyin.SYNC_CHANNEL";
    private static final String RESULT_CHANNEL_ID = "com.tencentx.pinyin.SYNC_RESULT_CHANNEL";
    private static final int NOTIFICATION_ID = 3;
    private static final int RESULT_NOTIFICATION_BASE = 0x10000;
    private static final long MAX_LOG_BYTES = 256 * 1024;

    private static final String TAG = "SyncService";

    private static final Object LOCK = new Object();
    @Nullable
    private static SyncService sInstance;

    private final ConcurrentHashMap<String, Process> processes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Thread> threads = new ConcurrentHashMap<>();

    private boolean channelCreated;
    private boolean resultChannelCreated;

    @Nullable
    private static String taskIdFromIntent(@NonNull final Intent intent) {
        return intent.getStringExtra(EXTRA_TASK_ID);
    }

    /**
     * Is a specific task currently running?
     *
     * @param taskId to check
     *
     * @return flag
     */
    public static boolean isRunning(@NonNull final String taskId) {
        synchronized (LOCK) {
            return sInstance != null && sInstance.threads.containsKey(taskId);
        }
    }

    /**
     * Is any sync task running?
     *
     * @return flag
     */
    public static boolean isAnyRunning() {
        synchronized (LOCK) {
            return sInstance != null && !sInstance.threads.isEmpty();
        }
    }

    /**
     * Start a single task.
     *
     * @param context Current context
     * @param taskId  to start
     */
    public static void startTask(@NonNull final Context context,
                                 @NonNull final String taskId) {
        final Intent intent = new Intent(context, SyncService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startForegroundService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "startTask failed", e);
        }
    }

    /**
     * Stop a single task.
     *
     * @param context Current context
     * @param taskId  to stop
     */
    public static void stopTask(@NonNull final Context context,
                                @NonNull final String taskId) {
        final Intent intent = new Intent(context, SyncService.class);
        intent.setAction(ACTION_STOP);
        intent.putExtra(EXTRA_TASK_ID, taskId);
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "stopTask failed", e);
        }
    }

    /**
     * Stop all tasks and the service.
     *
     * @param context Current context
     */
    public static void stopService(@NonNull final Context context) {
        final Intent intent = new Intent(context, SyncService.class);
        intent.setAction(ACTION_STOP_ALL);
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startService(intent);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "stopService failed", e);
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
                              ? intent.getAction() : ACTION_STOP_ALL;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onStartCommand action=" + action);
        }

        switch (action) {
            case ACTION_START: {
                final String taskId = taskIdFromIntent(intent);
                if (taskId != null) {
                    startForegroundNotification();
                    startTaskInternal(taskId);
                }
                break;
            }
            case ACTION_STOP: {
                final String taskId = taskIdFromIntent(intent);
                if (taskId != null) {
                    stopTaskInternal(taskId, SyncTask.Status.CANCELLED);
                }
                checkStopSelf();
                break;
            }
            case ACTION_STOP_ALL: {
                for (final String taskId : new ArrayList<>(threads.keySet())) {
                    stopTaskInternal(taskId, SyncTask.Status.CANCELLED);
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

    private void startTaskInternal(@NonNull final String taskId) {
        if (threads.containsKey(taskId)) {
            return;
        }
        final List<SyncTask> tasks = SyncTaskStore.load(this);
        SyncTask task = null;
        for (final SyncTask t : tasks) {
            if (t.getId().equals(taskId)) {
                task = t;
                break;
            }
        }
        if (task == null) {
            Log.w(TAG, "Task not found: " + taskId);
            return;
        }
        if (!task.isConfigured()) {
            markFailed(task, getString(R.string.sync_not_configured));
            return;
        }

        task.setStatus(SyncTask.Status.RUNNING);
        task.setLastRunEpochMs(System.currentTimeMillis());
        task.resetProgress();
        SyncTaskStore.upsert(this, task);
        appendLog(task, "=== starting " + task.getName() + " ===\n");
        broadcastState();

        final SyncTask taskRef = task;
        final Thread thread = new Thread(() -> startAndRun(taskRef),
                                         "sync-" + taskId);
        thread.setDaemon(true);
        threads.put(taskId, thread);
        thread.start();
    }

    private void startAndRun(@NonNull final SyncTask task) {
        final List<String> command;
        //noinspection OverlyBroadCatchBlock
        try {
            command = SyncManager.buildCommand(this, task);
        } catch (@NonNull final IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                threads.remove(task.getId());
                return;
            }
            Log.e(TAG, "Cannot build command for " + task.getName(), e);
            markFailed(task, e.getMessage());
            threads.remove(task.getId());
            return;
        }

        appendLog(task, String.join(" ", command) + "\n");

        final ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        final Process process;
        //noinspection OverlyBroadCatchBlock
        try {
            process = pb.start();
        } catch (@NonNull final IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                threads.remove(task.getId());
                return;
            }
            Log.e(TAG, "Failed to start rclone for " + task.getName(), e);
            markFailed(task, e.getMessage());
            threads.remove(task.getId());
            return;
        }

        processes.put(task.getId(), process);
        broadcastState();
        runLoop(task, process);
    }

    private void runLoop(@NonNull final SyncTask task,
                         @NonNull final Process process) {
        //noinspection OverlyBroadCatchBlock
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(task, line + "\n");
                final SyncManager.Progress progress = SyncManager.parseProgress(line);
                if (progress != null) {
                    task.setTransferredBytes(progress.getTransferred());
                    task.setTotalBytes(progress.getTotal());
                    SyncTaskStore.upsert(this, task);
                    broadcastState();
                }
            }
        } catch (@NonNull final IOException ignore) {
            // process killed / stream closed
        }

        int exitCode;
        //noinspection OverlyBroadCatchBlock
        try {
            exitCode = process.waitFor();
        } catch (@NonNull final InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode = -1;
        }

        processes.remove(task.getId());
        threads.remove(task.getId());

        final SyncTask.Status status;
        final String error;
        if (exitCode == 0) {
            status = SyncTask.Status.SUCCEEDED;
            error = "";
        } else if (exitCode == -1) {
            status = SyncTask.Status.CANCELLED;
            error = "";
        } else {
            status = SyncTask.Status.FAILED;
            error = "exit code " + exitCode;
        }
        task.setStatus(status);
        task.setLastError(error);
        appendLog(task, "=== " + task.getName() + " " + status + " ===\n");
        SyncTaskStore.upsert(this, task);
        broadcastState();

        if (status == SyncTask.Status.SUCCEEDED
            || status == SyncTask.Status.FAILED) {
            notifyResult(task, status, error);
        }

        if (!isAnyRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopTaskInternal(@NonNull final String taskId,
                                  @NonNull final SyncTask.Status finalStatus) {
        final Process process = processes.remove(taskId);
        if (process != null) {
            process.destroy();
            //noinspection OverlyBroadCatchBlock
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (@NonNull final InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        final Thread thread = threads.remove(taskId);
        if (thread != null) {
            thread.interrupt();
        }

        final List<SyncTask> tasks = SyncTaskStore.load(this);
        for (final SyncTask t : tasks) {
            if (t.getId().equals(taskId) && t.getStatus() == SyncTask.Status.RUNNING) {
                t.setStatus(finalStatus);
                SyncTaskStore.upsert(this, t);
                break;
            }
        }
        broadcastState();
    }

    private void markFailed(@NonNull final SyncTask task,
                            @Nullable final String error) {
        task.setStatus(SyncTask.Status.FAILED);
        task.setLastError(error != null ? error : "");
        SyncTaskStore.upsert(this, task);
        appendLog(task, "=== " + task.getName() + " FAILED: " + error + " ===\n");
        broadcastState();
        if (!isAnyRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void checkStopSelf() {
        if (!isAnyRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void appendLog(@NonNull final SyncTask task,
                           @NonNull final String text) {
        final File logFile = SyncTaskStore.getLogFile(this, task);
        //noinspection OverlyBroadCatchBlock
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                w.write(text);
            }
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }

    private void startForegroundNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (!channelCreated) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.sync_service_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
            channelCreated = true;
        }
        final Notification notification = createNotification(
                getString(R.string.sync_notification_running));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void notifyResult(@NonNull final SyncTask task,
                              @NonNull final SyncTask.Status status,
                              @NonNull final String error) {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (!resultChannelCreated) {
            final NotificationChannel channel = new NotificationChannel(
                    RESULT_CHANNEL_ID,
                    getString(R.string.sync_notification_channel_result),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setShowBadge(true);
            nm.createNotificationChannel(channel);
            resultChannelCreated = true;
        }
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0,
                                          new Intent(this, MainActivity.class),
                                          PendingIntent.FLAG_IMMUTABLE);
        final String title;
        final String text;
        if (status == SyncTask.Status.SUCCEEDED) {
            title = getString(R.string.sync_notification_succeeded_fmt,
                              task.getName());
            text = getString(R.string.sync_state_succeeded);
        } else {
            title = getString(R.string.sync_notification_failed_fmt,
                              task.getName());
            text = error.isEmpty() ? getString(R.string.sync_state_failed) : error;
        }
        final Notification notification =
                new NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.app)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setLocalOnly(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build();
        final int notifId = RESULT_NOTIFICATION_BASE
                            + (task.getId().hashCode() & 0xFFFF);
        nm.notify(notifId, notification);
    }

    private void updateNotification() {
        if (!isAnyRunning()) {
            return;
        }
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        final List<SyncTask> tasks = SyncTaskStore.load(this);
        final StringBuilder text = new StringBuilder();
        for (final SyncTask t : tasks) {
            if (isRunning(t.getId())) {
                if (text.length() > 0) {
                    text.append(", ");
                }
                text.append(t.getName());
            }
        }
        nm.notify(NOTIFICATION_ID,
                  createNotification(getString(R.string.sync_notification_running_fmt, text)));
    }

    @NonNull
    private Notification createNotification(@NonNull final CharSequence text) {
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0,
                                          new Intent(this, MainActivity.class),
                                          PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app)
                .setTicker(getString(R.string.sync_service_name))
                .setContentTitle(getString(R.string.sync_service_name))
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setLocalOnly(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void broadcastState() {
        final Intent intent = new Intent(STATE_CHANGED);
        //noinspection deprecation
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        for (final String taskId : new ArrayList<>(threads.keySet())) {
            stopTaskInternal(taskId, SyncTask.Status.CANCELLED);
        }
        synchronized (LOCK) {
            sInstance = null;
        }
        broadcastState();
        super.onDestroy();
    }
}
