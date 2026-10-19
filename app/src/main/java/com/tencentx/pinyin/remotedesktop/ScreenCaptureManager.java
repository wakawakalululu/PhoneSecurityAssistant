package com.tencentx.pinyin.remotedesktop;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tencentx.pinyin.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures the device screen using {@link MediaProjection} and
 * {@link ImageReader}, converts each frame to JPEG and stores it in an
 * {@link AtomicReference} for the {@link MjpegServer} to stream.
 */
final class ScreenCaptureManager {

    private static final String TAG = "ScreenCaptureManager";

    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;
    private static final int DPI = 160;
    private static final int JPEG_QUALITY = 60;
    private static final long FRAME_INTERVAL_MS = 80;

    private final MediaProjection projection;
    private final AtomicReference<byte[]> frameRef;

    @Nullable
    private ImageReader imageReader;
    @Nullable
    private VirtualDisplay virtualDisplay;
    @Nullable
    private Thread captureThread;
    private volatile boolean running;

    ScreenCaptureManager(@NonNull final MediaProjection projection,
                         @NonNull final AtomicReference<byte[]> frameRef) {
        this.projection = projection;
        this.frameRef = frameRef;
    }

    void start() {
        imageReader = ImageReader.newInstance(WIDTH, HEIGHT,
                                               android.graphics.PixelFormat.RGBA_8888, 2);
        final Surface surface = imageReader.getSurface();
        virtualDisplay = projection.createVirtualDisplay(
                "RemoteDesktop", WIDTH, HEIGHT, DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null);

        running = true;
        captureThread = new Thread(this::captureLoop, "rd-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    void stop() {
        running = false;
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (virtualDisplay != null) {
            //noinspection OverlyBroadCatchBlock
            try {
                virtualDisplay.release();
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
            virtualDisplay = null;
        }
        if (imageReader != null) {
            //noinspection OverlyBroadCatchBlock
            try {
                imageReader.close();
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
            imageReader = null;
        }
        //noinspection OverlyBroadCatchBlock
        try {
            projection.stop();
        } catch (@NonNull final Exception ignore) {
            // ignore
        }
    }

    private void captureLoop() {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        Bitmap bitmap = null;
        while (running && !Thread.currentThread().isInterrupted()) {
            //noinspection OverlyBroadCatchBlock
            try {
                Thread.sleep(FRAME_INTERVAL_MS);
            } catch (@NonNull final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (imageReader == null) {
                continue;
            }
            final Image image;
            //noinspection OverlyBroadCatchBlock
            try {
                image = imageReader.acquireLatestImage();
            } catch (@NonNull final Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "acquireLatestImage failed", e);
                }
                continue;
            }
            if (image == null) {
                continue;
            }
            //noinspection OverlyBroadCatchBlock
            try {
                final byte[] jpeg = imageToJpeg(image, bitmap, baos);
                if (jpeg != null) {
                    frameRef.set(jpeg);
                }
            } catch (@NonNull final Exception e) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Frame conversion failed", e);
                }
            } finally {
                //noinspection OverlyBroadCatchBlock
                try {
                    image.close();
                } catch (@NonNull final Exception ignore) {
                    // ignore
                }
            }
        }
    }

    @Nullable
    private static byte[] imageToJpeg(@NonNull final Image image,
                                      @Nullable Bitmap reuseBitmap,
                                      @NonNull final ByteArrayOutputStream baos) {
        final Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        final ByteBuffer buffer = planes[0].getBuffer();
        final int pixelStride = planes[0].getPixelStride();
        final int rowStride = planes[0].getRowStride();
        final int rowPadding = rowStride - pixelStride * image.getWidth();

        final int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
        if (reuseBitmap == null
            || reuseBitmap.getWidth() != bitmapWidth
            || reuseBitmap.getHeight() != image.getHeight()) {
            reuseBitmap = Bitmap.createBitmap(bitmapWidth, image.getHeight(),
                                              Bitmap.Config.ARGB_8888);
        }
        reuseBitmap.copyPixelsFromBuffer(buffer);

        final int cropWidth = Math.min(image.getWidth(), WIDTH);
        final int cropHeight = Math.min(image.getHeight(), HEIGHT);
        final Bitmap cropped;
        if (reuseBitmap.getWidth() != cropWidth
            || reuseBitmap.getHeight() != cropHeight) {
            cropped = Bitmap.createBitmap(reuseBitmap, 0, 0, cropWidth, cropHeight);
        } else {
            cropped = reuseBitmap;
        }

        baos.reset();
        if (!cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) {
            return null;
        }
        return baos.toByteArray();
    }
}
