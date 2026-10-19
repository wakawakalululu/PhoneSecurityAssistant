package com.tencentx.pinyin.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;

import androidx.annotation.NonNull;

/**
 * Checks the network and charging constraints attached to a
 * {@link SyncTask}'s schedule before a triggered run is allowed to start.
 */
final class SyncConditions {

    private SyncConditions() {
    }

    static boolean areMet(@NonNull final Context context,
                          @NonNull final SyncTask task) {
        if (task.isScheduleRequireWifi() && !isWifiConnected(context)) {
            return false;
        }
        if (task.isScheduleRequireCharging() && !isCharging(context)) {
            return false;
        }
        return true;
    }

    private static boolean isWifiConnected(@NonNull final Context context) {
        final ConnectivityManager cm =
                context.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            return false;
        }
        final Network network = cm.getActiveNetwork();
        if (network == null) {
            return false;
        }
        final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
               && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private static boolean isCharging(@NonNull final Context context) {
        final BatteryManager bm = context.getSystemService(BatteryManager.class);
        return bm != null && bm.isCharging();
    }
}
