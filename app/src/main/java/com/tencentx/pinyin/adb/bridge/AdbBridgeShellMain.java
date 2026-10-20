package com.tencentx.pinyin.adb.bridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point launched by {@code app_process} from {@code bridge.sh}.
 * <p>
 * This class runs <strong>outside</strong> the application process and must
 * therefore only use {@code android.*} and {@code java.*} classes (no
 * androidx, no {@code R}, no {@code Prefs}). It connects to the in-app
 * {@link AdbBridgeService} over a {@link LocalSocket}, forwards the SSH
 * identity fingerprint and the command, then pipes the reply to
 * {@code stdout}.
 * <p>
 * The command is taken from the {@code -c} argument dropbear passes for an
 * SSH "exec" request, or read from {@code stdin} for an interactive session.
 */
public final class AdbBridgeShellMain {

    /** Abstract-namespace LocalSocket name shared with {@link AdbBridgeService}. */
    static final String SOCKET_NAME = "com.tencentx.pinyin.adb.bridge.IPC";

    /** Environment variable carrying the SSH public-key fingerprint, if any. */
    static final String ENV_FINGERPRINT = "SSH2_PUBLIC_KEY_FP";

    private static final int BUFFER_SIZE = 4096;

    private AdbBridgeShellMain() {
    }

    public static void main(final String[] args) {
        try {
            run(args);
        } catch (final Throwable t) {
            System.err.println("adb-bridge: " + t);
            System.exit(1);
        }
    }

    private static void run(final String[] args) throws Exception {
        final String command = resolveCommand(args);
        if (command == null || command.isBlank()) {
            System.err.println("adb-bridge: no command");
            System.exit(2);
            return;
        }

        String fingerprint = System.getenv(ENV_FINGERPRINT);
        if (fingerprint == null) {
            fingerprint = "";
        }

        final LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(SOCKET_NAME,
                                              LocalSocketAddress.Namespace.ABSTRACT));
        try {
            final OutputStream out = socket.getOutputStream();
            out.write((fingerprint + "\n").getBytes(StandardCharsets.UTF_8));
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            final InputStream in = socket.getInputStream();
            final byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) != -1) {
                System.out.write(buffer, 0, count);
                System.out.flush();
            }
        } finally {
            socket.close();
        }
    }

    private static String resolveCommand(final String[] args) throws IOException {
        if (args.length == 0) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                return r.readLine();
            }
        }
        int cIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if ("-c".equals(args[i])) {
                cIndex = i;
                break;
            }
        }
        if (cIndex >= 0 && cIndex + 1 < args.length) {
            return args[cIndex + 1];
        }
        return String.join(" ", args);
    }
}
