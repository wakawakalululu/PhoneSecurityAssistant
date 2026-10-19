package com.tencentx.pinyin.sync;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * Schedules periodic background runs of {@link SyncTask}s using
 * {@link AlarmManager}. Each enabled task gets a single inexact alarm
 * ({@code setAndAllowWhileIdle}) that fires {@link SyncTriggerReceiver},
 * which checks network/charging constraints, starts the task via
 * {@link SyncService#startTask} and re-arms the next interval.
 * <p>
 * No new permissions are required: {@code setAndAllowWhileIdle} is
 * doze-friendly and does not need {@code SCHEDULE_EXACT_ALARM}.
 */
public final class SyncScheduler {

    private static final String TAG = "SyncScheduler";

    /** Prefix of the trigger action; the full action is {@code PREFIX:taskId}. */
    public static final String ACTION_TRIGGER_PREFIX =
            "com.tencentx.pinyin.sync.TRIGGER";

    static final String EXTRA_TASK_ID = "taskId";

    private static final long MIN_INTERVAL_MS = 60_000L;

    private SyncScheduler() {
    }

    /**
     * Schedule (or cancel) the alarm for a single task based on its current
     * scheduling configuration. Idempotent: always cancels any previous alarm
     * for the task before (re)arming.
     *
     * @param context Current context
     * @param task    to schedule
     */
    public static void schedule(@NonNull final Context context,
                                @NonNull final SyncTask task) {
        final AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) {
            Log.w(TAG, "AlarmManager unavailable");
            return;
        }
        final PendingIntent pi = makePendingIntent(context, task.getId());
        //noinspection OverlyBroadCatchBlock
        try {
            am.cancel(pi);
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
        if (task.isScheduleEnabled() && task.getScheduleIntervalMinutes() > 0) {
            final long intervalMs = Math.max(MIN_INTERVAL_MS,
                    task.getScheduleIntervalMinutes() * 60_000L);
            final long triggerAt = System.currentTimeMillis() + intervalMs;
            //noinspection OverlyBroadCatchBlock
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } catch (@NonNull final Exception e) {
                Log.w(TAG, "Failed to schedule " + task.getName(), e);
            }
        } else {
            //noinspection OverlyBroadCatchBlock
            try {
                pi.cancel();
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
    }

    /**
     * Cancel any pending alarm for a task.
     *
     * @param context Current context
     * @param taskId  of the task
     */
    public static void cancel(@NonNull final Context context,
                              @NonNull final String taskId) {
        final AlarmManager am = context.getSystemService(AlarmManager.class);
        final PendingIntent pi = makePendingIntent(context, taskId);
        if (am != null) {
            //noinspection OverlyBroadCatchBlock
            try {
                am.cancel(pi);
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }
        //noinspection OverlyBroadCatchBlock
        try {
            pi.cancel();
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }

    /**
     * Re-arm alarms for all persisted tasks. Called from {@code App.onCreate}
     * to recover after process death or device reboot.
     *
     * @param context Current context
     */
    public static void rescheduleAll(@NonNull final Context context) {
        final List<SyncTask> tasks = SyncTaskStore.load(context);
        for (final SyncTask task : tasks) {
            schedule(context, task);
        }
    }

    @NonNull
    private static String actionFor(@NonNull final String taskId) {
        return ACTION_TRIGGER_PREFIX + ":" + taskId;
    }

    @NonNull
    private static PendingIntent makePendingIntent(@NonNull final Context context,
                                                   @NonNull final String taskId) {
        final Intent intent = new Intent(context, SyncTriggerReceiver.class);
        intent.setAction(actionFor(taskId));
        intent.putExtra(EXTRA_TASK_ID, taskId);
        return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
