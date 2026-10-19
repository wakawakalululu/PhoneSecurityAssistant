package com.tencentx.pinyin.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Fired by {@link SyncScheduler}'s alarms. Re-arms the next interval,
 * then — if the task's network/charging constraints are satisfied and
 * the task is not already running — starts it via {@link SyncService}.
 */
public final class SyncTriggerReceiver
        extends BroadcastReceiver {

    private static final String TAG = "SyncTriggerReceiver";

    @Override
    public void onReceive(@NonNull final Context context,
                          @Nullable final Intent intent) {
        if (intent == null) {
            return;
        }
        final String taskId = intent.getStringExtra(SyncScheduler.EXTRA_TASK_ID);
        if (taskId == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final SyncTask task = findTask(app, taskId);
        if (task == null) {
            return;
        }
        SyncScheduler.schedule(app, task);
        if (!task.isScheduleEnabled() || task.getScheduleIntervalMinutes() <= 0) {
            return;
        }
        if (SyncService.isRunning(taskId)) {
            Log.d(TAG, "Task already running: " + taskId);
            return;
        }
        if (!SyncConditions.areMet(app, task)) {
            Log.d(TAG, "Conditions not met, skipping: " + taskId);
            return;
        }
        SyncService.startTask(app, taskId);
    }

    @Nullable
    private static SyncTask findTask(@NonNull final Context context,
                                     @NonNull final String taskId) {
        final List<SyncTask> tasks = SyncTaskStore.load(context);
        for (final SyncTask t : tasks) {
            if (t.getId().equals(taskId)) {
                return t;
            }
        }
        return null;
    }
}
