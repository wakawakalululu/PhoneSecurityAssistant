package com.tencentx.pinyin.remotedesktop;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;

/**
 * Minimal HTTP server that streams the latest JPEG frame as MJPEG
 * ({@code multipart/x-mixed-replace}) and accepts touch/swipe input
 * via {@code POST /input}.
 * <p>
 * The viewer page at {@code GET /} is a simple HTML page with an
 * {@code <img>} tag pointing to {@code /stream} and JavaScript that
 * sends click/touch coordinates as JSON to {@code POST /input}.
 */
final class MjpegServer {

    private static final String TAG = "MjpegServer";

    private static final String BOUNDARY = "frame";
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final int port;
    private final AtomicReference<byte[]> frameRef;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    MjpegServer(final int port,
                @NonNull final AtomicReference<byte[]> frameRef) {
        this.port = port;
        this.frameRef = frameRef;
    }

    void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "rd-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            //noinspection OverlyBroadCatchBlock
            try {
                serverSocket.close();
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void acceptLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            final Socket client;
            //noinspection OverlyBroadCatchBlock
            try {
                client = serverSocket.accept();
            } catch (@NonNull final Exception e) {
                if (running) {
                    Log.w(TAG, "accept failed", e);
                }
                break;
            }
            final Thread handler = new Thread(() -> handleClient(client),
                                              "rd-client");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handleClient(@NonNull final Socket socket) {
        //noinspection OverlyBroadCatchBlock
        try (Socket s = socket) {
            s.setSoTimeout(10_000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();

            final String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            final String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            final String method = parts[0];
            final String path = parts[1];

            final int contentLength = readHeaders(in);

            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(out);
            } else if ("GET".equals(method) && "/stream".equals(path)) {
                streamMjpeg(out);
            } else if ("POST".equals(method) && "/input".equals(path)) {
                handleInput(in, out, contentLength);
            } else {
                sendNotFound(out);
            }
        } catch (@NonNull final Exception e) {
            if (running) {
                Log.d(TAG, "Client handler error", e);
            }
        }
    }

    @Nullable
    private static String readLine(@NonNull final InputStream in)
            throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        int prev = -1;
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n' && prev == '\r') {
                break;
            }
            baos.write(ch);
            prev = ch;
        }
        final byte[] bytes = baos.toByteArray();
        if (bytes.length == 0) {
            return "";
        }
        return new String(bytes, 0,
                          bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                          ? bytes.length - 1 : bytes.length,
                          StandardCharsets.US_ASCII).trim();
    }

    private static int readHeaders(@NonNull final InputStream in)
            throws IOException {
        int contentLength = 0;
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            if (line.startsWith("Content-Length:")) {
                //noinspection OverlyBroadCatchBlock
                try {
                    contentLength = Integer.parseInt(
                            line.substring("Content-Length:".length()).trim());
                } catch (@NonNull final NumberFormatException ignore) {
                    // ignore
                }
            }
        }
        return contentLength;
    }

    private void sendHtml(@NonNull final OutputStream out)
            throws IOException {
        final byte[] body = VIEWER_HTML.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream header = new ByteArrayOutputStream(256);
        header.write("HTTP/1.0 200 OK".getBytes(StandardCharsets.US_ASCII));
        header.write(CRLF);
        header.write("Content-Type: text/html; charset=utf-8".getBytes(
                StandardCharsets.US_ASCII));
        header.write(CRLF);
        header.write(("Content-Length: " + body.length).getBytes(
                StandardCharsets.US_ASCII));
        header.write(CRLF);
        header.write(CRLF);
        out.write(header.toByteArray());
        out.write(body);
        out.flush();
    }

    private void streamMjpeg(@NonNull final OutputStream out)
            throws IOException {
        final ByteArrayOutputStream header = new ByteArrayOutputStream(256);
        header.write("HTTP/1.0 200 OK".getBytes(StandardCharsets.US_ASCII));
        header.write(CRLF);
        header.write(("Content-Type: multipart/x-mixed-replace; boundary="
                      + BOUNDARY).getBytes(StandardCharsets.US_ASCII));
        header.write(CRLF);
        header.write(CRLF);
        out.write(header.toByteArray());
        out.flush();

        while (running && !Thread.currentThread().isInterrupted()) {
            final byte[] frame = frameRef.get();
            if (frame == null) {
                //noinspection OverlyBroadCatchBlock
                try {
                    Thread.sleep(50);
                } catch (@NonNull final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            final ByteArrayOutputStream frameHeader = new ByteArrayOutputStream(128);
            frameHeader.write(("--" + BOUNDARY).getBytes(StandardCharsets.US_ASCII));
            frameHeader.write(CRLF);
            frameHeader.write("Content-Type: image/jpeg".getBytes(
                    StandardCharsets.US_ASCII));
            frameHeader.write(CRLF);
            frameHeader.write(("Content-Length: " + frame.length).getBytes(
                    StandardCharsets.US_ASCII));
            frameHeader.write(CRLF);
            frameHeader.write(CRLF);
            //noinspection OverlyBroadCatchBlock
            try {
                out.write(frameHeader.toByteArray());
                out.write(frame);
                out.write(CRLF);
                out.flush();
            } catch (@NonNull final Exception e) {
                break;
            }
            //noinspection OverlyBroadCatchBlock
            try {
                Thread.sleep(80);
            } catch (@NonNull final InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handleInput(@NonNull final InputStream in,
                             @NonNull final OutputStream out,
                             final int contentLength)
            throws IOException {
        if (contentLength > 0 && contentLength < 8192) {
            final byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                final int n = in.read(body, read, contentLength - read);
                if (n == -1) {
                    break;
                }
                read += n;
            }
            //noinspection OverlyBroadCatchBlock
            try {
                final JSONObject json = new JSONObject(
                        new String(body, 0, read, StandardCharsets.UTF_8));
                final String type = json.optString("type", "");
                if ("tap".equals(type)) {
                    final float x = (float) json.optDouble("x", 0);
                    final float y = (float) json.optDouble("y", 0);
                    RemoteDesktopAccessibilityService.dispatchTap(x, y);
                } else if ("swipe".equals(type)) {
                    final float x1 = (float) json.optDouble("x1", 0);
                    final float y1 = (float) json.optDouble("y1", 0);
                    final float x2 = (float) json.optDouble("x2", 0);
                    final float y2 = (float) json.optDouble("y2", 0);
                    final long duration = json.optLong("duration", 300);
                    RemoteDesktopAccessibilityService.dispatchSwipe(
                            x1, y1, x2, y2, duration);
                }
            } catch (@NonNull final Exception e) {
                Log.d(TAG, "Input parse error", e);
            }
        }
        final byte[] resp = "HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        out.write(resp);
        out.flush();
    }

    private static void sendNotFound(@NonNull final OutputStream out)
            throws IOException {
        final byte[] resp = "HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        out.write(resp);
        out.flush();
    }

    private static final String VIEWER_HTML =
            "<!DOCTYPE html><html><head>"
            + "<meta name=\"viewport\" content=\"width=device-width,"
            + "initial-scale=1.0,user-scalable=no\">"
            + "<title>Remote Desktop</title>"
            + "<style>body{margin:0;background:#000;overflow:hidden}"
            + "#s{width:100%;height:100%;object-fit:contain;touch-action:none}"
            + "#c{position:fixed;top:0;left:0;width:100%;height:100%;"
            + "pointer-events:none;z-index:10}</style></head><body>"
            + "<img id=\"s\" src=\"/stream\">"
            + "<canvas id=\"c\"></canvas>"
            + "<script>"
            + "var img=document.getElementById('s'),"
            + "cv=document.getElementById('c'),ctx=cv.getContext('2d');"
            + "function rel(e){var r=img.getBoundingClientRect();"
            + "return{x:(e.clientX-r.left)/r.width*img.naturalWidth,"
            + "y:(e.clientY-r.top)/r.height*img.naturalHeight}};"
            + "img.addEventListener('click',function(e){"
            + "var p=rel(e);"
            + "fetch('/input',{method:'POST',body:"
            + "JSON.stringify({type:'tap',x:p.x,y:p.y})})});"
            + "var sx,sy,ex,ey;"
            + "img.addEventListener('touchstart',function(e){"
            + "e.preventDefault();var p=rel(e.touches[0]);sx=p.x;sy=p.y});"
            + "img.addEventListener('touchend',function(e){"
            + "e.preventDefault();var p=rel(e.changedTouches[0]);"
            + "fetch('/input',{method:'POST',body:JSON.stringify("
            + "{type:'swipe',x1:sx,y1:sy,x2:p.x,y2:p.y,duration:300})})});"
            + "</script></body></html>";
}
