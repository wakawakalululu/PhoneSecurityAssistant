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
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.tencentx.pinyin.BuildConfig;
import com.tencentx.pinyin.MainActivity;
import com.tencentx.pinyin.R;
import com.tencentx.pinyin.settings.Prefs;

/**
 * Foreground service running a <strong>second</strong> dropbear instance
 * dedicated to the remote ADB bridge.
 * <p>
 * It reuses the {@code jni-dropbear} native implementation ({@code start_sshd},
 * {@code kill}, {@code waitpid}) by reflecting on {@code SshdService}'s private
 * native methods. The native functions do not depend on the receiver instance,
 * so they can be invoked on a bare {@code SshdService} object. This keeps
 * {@code SshdService} and the main SSH port untouched.
 * <p>
 * The second dropbear listens on {@link Prefs#getAdbBridgePort}, uses an
 * independent conf directory ({@code filesDir/.adb-bridge}) with its own
 * {@code authorized_keys}, and runs {@code bridge.sh} as the login shell.
 * {@code bridge.sh} in turn launches {@link AdbBridgeShellMain} via
 * {@code app_process}.
 */
public final class AdbBridgeSshdService
        extends Service {

    private static final String TAG = "AdbBridgeSshdService";
    private static final String CHANNEL_ID = "com.tencentx.pinyin.ADB_BRIDGE_SSHD_CHANNEL";
    private static final int NOTIFICATION_ID = 5;
    private static final int MIN_DURATION_MS = 10_000;
    private static final String[] Z_STRING = new String[0];

    private static final String BRIDGE_SCRIPT_DIR = "adb-bridge";
    private static final String BRIDGE_SCRIPT_NAME = "bridge.sh";
    private static final String BRIDGE_SCRIPT_CONTENT =
            "#!/system/bin/sh\n"
            + "exec app_process /system/bin "
            + AdbBridgeShellMain.class.getName() + " \"$@\"\n";

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    @Nullable
    private static AdbBridgeSshdService sInstance;

    @Nullable
    private static Method START_SSHD;
    @Nullable
    private static Method KILL;
    @Nullable
    private static Method WAITPID;
    @Nullable
    private static Object NATIVE_RECEIVER;

    static {
        initNativeBridge();
    }

    @GuardedBy("LOCK")
    private int sshdPid;
    @GuardedBy("LOCK")
    private long sshdStartTime;
    @GuardedBy("LOCK")
    private long sshdDuration;
    private SharedPreferences prefs;
    private boolean channelCreated;

    static boolean isRunning() {
        synchronized (LOCK) {
            if (sInstance == null) {
                return false;
            }
            return sInstance.sshdPid > 0;
        }
    }

    /**
     * Start the bridge sshd service.
     *
     * @param context Current context
     */
    public static void startService(@NonNull final Context context) {
        //noinspection OverlyBroadCatchBlock
        try {
            context.getApplicationContext().startForegroundService(
                    new Intent(context, AdbBridgeSshdService.class));
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "startService failed", e);
        }
    }

    /**
     * Stop the bridge sshd service.
     *
     * @param context Current context
     */
    public static void stopService(@NonNull final Context context) {
        context.getApplicationContext().stopService(
                new Intent(context, AdbBridgeSshdService.class));
    }

    private static void initNativeBridge() {
        //noinspection OverlyBroadCatchBlock
        try {
            System.loadLibrary("jni-dropbear");
            final Class<?> cls = Class.forName("com.tencentx.pinyin.SshdService");
            START_SSHD = cls.getDeclaredMethod("start_sshd",
                    String.class, String[].class, String.class, String.class,
                    String.class, String.class,
                    boolean.class, boolean.class, boolean.class);
            START_SSHD.setAccessible(true);
            KILL = cls.getDeclaredMethod("kill", int.class);
            KILL.setAccessible(true);
            WAITPID = cls.getDeclaredMethod("waitpid", int.class);
            WAITPID.setAccessible(true);
            final Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            NATIVE_RECEIVER = ctor.newInstance();
        } catch (@NonNull final Throwable t) {
            Log.e(TAG, "Failed to initialise native bridge to SshdService", t);
        }
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
        startBridgeSshd();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && !channelCreated) {
            final NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.adb_bridge_sshd_service_name),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
            channelCreated = true;
        }
        final PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app)
                .setContentTitle(getString(R.string.adb_bridge_sshd_service_name))
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

    private void startBridgeSshd() {
        if (START_SSHD == null || NATIVE_RECEIVER == null) {
            Log.e(TAG, "Native bridge not available");
            return;
        }
        stopBridgeSshd();

        final File scriptFile = releaseBridgeScript();
        if (scriptFile == null) {
            Log.e(TAG, "Failed to release bridge.sh");
            return;
        }

        final List<String> argList = new ArrayList<>();
        argList.add("sshd");
        argList.add("-e");
        argList.add("-R");
        argList.add("-F");
        argList.add("-p");
        argList.add(String.valueOf(Prefs.getAdbBridgePort(prefs)));

        final String[] args = argList.toArray(Z_STRING);
        final String confPath = AdbBridgeAuth.getBridgeDirectory(this).getPath();
        final String homePath = getFilesDir().getPath();
        final String shell = scriptFile.getPath();
        final String env = "";

        final int pid;
        try {
            pid = (int) START_SSHD.invoke(NATIVE_RECEIVER,
                    getApplicationInfo().nativeLibraryDir,
                    args,
                    confPath, homePath, shell, env,
                    true, true, false);
        } catch (@NonNull final Exception e) {
            Log.e(TAG, "start_sshd invoke failed", e);
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "start_sshd=" + pid);
        }

        if (pid == -1) {
            synchronized (LOCK) {
                sshdPid = 0;
            }
            return;
        }
        synchronized (LOCK) {
            sshdPid = pid;
        }

        EXECUTOR.execute(() -> {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "waitpid=" + pid);
            }
            synchronized (LOCK) {
                final long now = System.nanoTime();
                if (sshdStartTime == 0) {
                    sshdDuration = 0;
                } else {
                    sshdDuration = now - sshdStartTime;
                }
                sshdStartTime = now;
            }

            try {
                WAITPID.invoke(NATIVE_RECEIVER, pid);
            } catch (@NonNull final Exception e) {
                Log.w(TAG, "waitpid invoke failed", e);
            }

            final boolean failed;
            final boolean restart;
            synchronized (LOCK) {
                failed = (sshdPid == pid);
                if (failed) {
                    sshdPid = 0;
                    restart = sshdDuration == 0
                            || sshdDuration >= MIN_DURATION_MS
                            || (System.currentTimeMillis() - sshdStartTime) >= MIN_DURATION_MS;
                } else {
                    restart = false;
                }
            }
            if (restart) {
                startBridgeSshd();
            }
        });
    }

    @Nullable
    private File releaseBridgeScript() {
        final File dir = new File(getFilesDir(), BRIDGE_SCRIPT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        final File script = new File(dir, BRIDGE_SCRIPT_NAME);
        try (FileWriter w = new FileWriter(script)) {
            w.write(BRIDGE_SCRIPT_CONTENT);
        } catch (@NonNull final IOException e) {
            Log.e(TAG, "Failed to write bridge.sh", e);
            return null;
        }
        if (!script.setExecutable(true, false)) {
            Log.w(TAG, "Failed to set bridge.sh executable");
        }
        return script;
    }

    private void stopBridgeSshd() {
        synchronized (LOCK) {
            final int pid = sshdPid;
            sshdPid = 0;
            if (pid > 0 && KILL != null && NATIVE_RECEIVER != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "killing pid=" + pid);
                }
                try {
                    KILL.invoke(NATIVE_RECEIVER, pid);
                } catch (@NonNull final Exception e) {
                    Log.w(TAG, "kill invoke failed", e);
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        stopBridgeSshd();
        stopForeground(STOP_FOREGROUND_REMOVE);
        synchronized (LOCK) {
            sInstance = null;
        }
        super.onDestroy();
    }
}
