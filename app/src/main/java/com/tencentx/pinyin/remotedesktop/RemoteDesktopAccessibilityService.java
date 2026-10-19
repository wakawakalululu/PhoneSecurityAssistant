package com.tencentx.pinyin.remotedesktop;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * AccessibilityService that injects touch and swipe gestures on the device.
 * The {@link MjpegServer} calls {@link #dispatchTap} and {@link #dispatchSwipe}
 * to execute remote input events.
 * <p>
 * The user must manually enable this service in
 * {@code Settings > Accessibility > 春夏秋手机安全助手} before remote control works.
 */
public final class RemoteDesktopAccessibilityService
        extends AccessibilityService {

    private static final String TAG = "RDAccessibility";

    @Nullable
    private static RemoteDesktopAccessibilityService sInstance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(@Nullable final AccessibilityEvent event) {
        // We don't need to inspect events; only inject gestures.
    }

    @Override
    public void onInterrupt() {
        // ignore
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    /**
     * Is the accessibility service currently connected?
     *
     * @return flag
     */
    public static boolean isRunning() {
        return sInstance != null;
    }

    /**
     * Dispatch a tap (click) gesture at the given coordinates.
     *
     * @param x screen x coordinate
     * @param y screen y coordinate
     *
     * @return {@code true} if the gesture was dispatched
     */
    public static boolean dispatchTap(final float x, final float y) {
        final RemoteDesktopAccessibilityService instance = sInstance;
        if (instance == null) {
            Log.w(TAG, "Service not connected, cannot dispatch tap");
            return false;
        }
        final Path path = new Path();
        path.moveTo(x, y);
        final GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 50);
        final GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return instance.dispatchGesture(gesture, null, null);
    }

    /**
     * Dispatch a swipe gesture from one point to another.
     *
     * @param x1       start x
     * @param y1       start y
     * @param x2       end x
     * @param y2       end y
     * @param duration duration in milliseconds
     *
     * @return {@code true} if the gesture was dispatched
     */
    public static boolean dispatchSwipe(final float x1, final float y1,
                                        final float x2, final float y2,
                                        final long duration) {
        final RemoteDesktopAccessibilityService instance = sInstance;
        if (instance == null) {
            Log.w(TAG, "Service not connected, cannot dispatch swipe");
            return false;
        }
        final Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        final GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0,
                        Math.max(duration, 50));
        final GestureDescription gesture =
                new GestureDescription.Builder().addStroke(stroke).build();
        return instance.dispatchGesture(gesture, null, null);
    }
}
