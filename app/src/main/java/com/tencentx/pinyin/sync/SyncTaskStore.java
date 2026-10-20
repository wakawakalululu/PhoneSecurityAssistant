package com.tencentx.pinyin.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Persists the list of {@link SyncTask}s as a single JSON file in the app's
 * private files directory ({@code files/sync/tasks.json}).
 * <p>
 * The file lives under {@code files/sync/} which is excluded from backup by
 * {@code backup_rules.xml}; credentials are never written to cloud backup.
 */
public final class SyncTaskStore {

    private static final String TAG = "SyncTaskStore";

    private static final String DIR_NAME = "sync";
    private static final String FILE_NAME = "tasks.json";

    private static final String K_ID = "id";
    private static final String K_NAME = "name";
    private static final String K_REMOTE_TYPE = "remoteType";
    private static final String K_REMOTE_HOST = "remoteHost";
    private static final String K_REMOTE_PORT = "remotePort";
    private static final String K_REMOTE_USER = "remoteUser";
    private static final String K_REMOTE_PASS = "remotePass";
    private static final String K_REMOTE_PATH = "remotePath";
    private static final String K_LOCAL_PATH = "localPath";
    private static final String K_DIRECTION = "direction";
    private static final String K_USE_TLS = "useTls";
    private static final String K_STATUS = "status";
    private static final String K_LAST_RUN = "lastRun";
    private static final String K_LAST_ERROR = "lastError";
    private static final String K_SCHEDULE_ENABLED = "scheduleEnabled";
    private static final String K_SCHEDULE_INTERVAL = "scheduleInterval";
    private static final String K_SCHEDULE_WIFI = "scheduleWifi";
    private static final String K_SCHEDULE_CHARGING = "scheduleCharging";

    private SyncTaskStore() {
    }

    /**
     * The directory holding the task store and per-task logs/configs.
     *
     * @param context Current context
     *
     * @return directory (created if needed)
     */
    @NonNull
    public static File getDir(@NonNull final Context context) {
        final File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * The log file of a task.
     *
     * @param context Current context
     * @param task    whose log
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    public static File getLogFile(@NonNull final Context context,
                                  @NonNull final SyncTask task) {
        return new File(getDir(context), task.getId() + ".log");
    }

    /**
     * The generated rclone configuration file for a task.
     *
     * @param context Current context
     * @param task    whose config
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    public static File getConfigFile(@NonNull final Context context,
                                     @NonNull final SyncTask task) {
        return new File(getDir(context), task.getId() + ".conf");
    }

    @NonNull
    private static File getStoreFile(@NonNull final Context context) {
        return new File(getDir(context), FILE_NAME);
    }

    /**
     * Load all tasks.
     *
     * @param context Current context
     *
     * @return the tasks; never {@code null}
     */
    @NonNull
    public static List<SyncTask> load(@NonNull final Context context) {
        final List<SyncTask> tasks = new ArrayList<>();
        final File file = getStoreFile(context);
        if (!file.exists()) {
            return tasks;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            final String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                final SyncTask task = fromJson(array.getJSONObject(i));
                if (task != null) {
                    tasks.add(task);
                }
            }
        } catch (@NonNull final IOException | JSONException e) {
            Log.w(TAG, "Failed to read task store", e);
        }
        return tasks;
    }

    /**
     * Persist all tasks atomically (write to temp, then rename).
     *
     * @param context Current context
     * @param tasks   to save
     */
    public static void save(@NonNull final Context context,
                            @NonNull final List<SyncTask> tasks) {
        final JSONArray array = new JSONArray();
        for (final SyncTask task : tasks) {
            array.put(toJson(task));
        }
        final File file = getStoreFile(context);
        final File tmp = new File(getDir(context), FILE_NAME + ".tmp");
        //noinspection OverlyBroadCatchBlock
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (@NonNull final IOException e) {
            Log.w(TAG, "Failed to write task store", e);
            return;
        }
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.w(TAG, "Failed to rename task store");
        }
    }

    /**
     * Replace (or add) a single task and persist.
     *
     * @param context Current context
     * @param task    to upsert
     */
    public static void upsert(@NonNull final Context context,
                              @NonNull final SyncTask task) {
        final List<SyncTask> tasks = load(context);
        boolean found = false;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).getId().equals(task.getId())) {
                tasks.set(i, task);
                found = true;
                break;
            }
        }
        if (!found) {
            tasks.add(task);
        }
        save(context, tasks);
        SyncScheduler.schedule(context, task);
    }

    /**
     * Delete a task by id, including its log and config files.
     *
     * @param context Current context
     * @param id      of the task
     */
    public static void delete(@NonNull final Context context,
                              @NonNull final String id) {
        SyncScheduler.cancel(context, id);
        final List<SyncTask> tasks = load(context);
        final Iterator<SyncTask> it = tasks.iterator();
        while (it.hasNext()) {
            if (it.next().getId().equals(id)) {
                it.remove();
                break;
            }
        }
        save(context, tasks);
        //noinspection ResultOfMethodCallIgnored
        new File(getDir(context), id + ".log").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(getDir(context), id + ".conf").delete();
    }

    @NonNull
    private static JSONObject toJson(@NonNull final SyncTask task) {
        final JSONObject o = new JSONObject();
        //noinspection OverlyBroadCatchBlock
        try {
            o.put(K_ID, task.getId());
            o.put(K_NAME, task.getName());
            o.put(K_REMOTE_TYPE, task.getRemoteType().name());
            o.put(K_REMOTE_HOST, task.getRemoteHost());
            o.put(K_REMOTE_PORT, task.getRemotePort());
            o.put(K_REMOTE_USER, task.getRemoteUser());
            o.put(K_REMOTE_PASS, task.getRemotePassword());
            o.put(K_REMOTE_PATH, task.getRemotePath());
            o.put(K_LOCAL_PATH, task.getLocalPath());
            o.put(K_DIRECTION, task.getDirection().name());
            o.put(K_USE_TLS, task.isUseTls());
            o.put(K_STATUS, task.getStatus().name());
            o.put(K_LAST_RUN, task.getLastRunEpochMs());
            o.put(K_LAST_ERROR, task.getLastError());
            o.put(K_SCHEDULE_ENABLED, task.isScheduleEnabled());
            o.put(K_SCHEDULE_INTERVAL, task.getScheduleIntervalMinutes());
            o.put(K_SCHEDULE_WIFI, task.isScheduleRequireWifi());
            o.put(K_SCHEDULE_CHARGING, task.isScheduleRequireCharging());
        } catch (@NonNull final JSONException ignore) {
            // should never happen with simple puts
        }
        return o;
    }

    @Nullable
    private static SyncTask fromJson(@NonNull final JSONObject o) {
        final SyncTask task = new SyncTask();
        //noinspection OverlyBroadCatchBlock
        try {
            task.setId(o.getString(K_ID));
            task.setName(o.optString(K_NAME, ""));
            task.setRemoteType(SyncTask.RemoteType.valueOf(
                    o.optString(K_REMOTE_TYPE, SyncTask.RemoteType.SFTP.name())));
            task.setRemoteHost(o.optString(K_REMOTE_HOST, ""));
            task.setRemotePort(o.optInt(K_REMOTE_PORT, 0));
            task.setRemoteUser(o.optString(K_REMOTE_USER, ""));
            task.setRemotePassword(o.optString(K_REMOTE_PASS, ""));
            task.setRemotePath(o.optString(K_REMOTE_PATH, ""));
            task.setLocalPath(o.optString(K_LOCAL_PATH, ""));
            task.setDirection(SyncTask.Direction.valueOf(
                    o.optString(K_DIRECTION, SyncTask.Direction.DOWNLOAD.name())));
            task.setUseTls(o.optBoolean(K_USE_TLS, true));
            task.setStatus(SyncTask.Status.valueOf(
                    o.optString(K_STATUS, SyncTask.Status.IDLE.name())));
            task.setLastRunEpochMs(o.optLong(K_LAST_RUN, 0L));
            task.setLastError(o.optString(K_LAST_ERROR, ""));
            task.setScheduleEnabled(o.optBoolean(K_SCHEDULE_ENABLED, false));
            task.setScheduleIntervalMinutes(o.optInt(K_SCHEDULE_INTERVAL, 0));
            task.setScheduleRequireWifi(o.optBoolean(K_SCHEDULE_WIFI, false));
            task.setScheduleRequireCharging(o.optBoolean(K_SCHEDULE_CHARGING, false));
            return task;
        } catch (@NonNull final IllegalArgumentException | JSONException e) {
            Log.w(TAG, "Skipping malformed task", e);
            return null;
        }
    }
}
