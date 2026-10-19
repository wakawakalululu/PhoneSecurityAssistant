package com.tencentx.pinyin.remote;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.tencentx.pinyin.privilege.RootUtils;
import com.tencentx.pinyin.settings.Prefs;

/**
 * Locate, import and download the external helper binaries
 * (frpc, tailscale, tailscaled, nezha-agent).
 * <p>
 * Three sources are supported, in order of preference:
 * <ol>
 *     <li>an explicit path set by the user</li>
 *     <li>a binary copied into the app its "tools" directory (import/download)</li>
 *     <li>a binary bundled in the APK as "{@code lib<name>.so}" (jniLibs)</li>
 * </ol>
 * <p>
 * <strong>Important:</strong> Android 10+ does not allow an app to execute files
 * from its own data directory. Bundled (jniLibs) binaries are extracted by the
 * installer into the app its native library directory and can be executed directly;
 * imported/downloaded binaries can only be executed when root ("su") is available.
 */
public final class BinaryManager {

    private static final String TAG = "BinaryManager";

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private BinaryManager() {
    }

    /**
     * The directory where imported/downloaded binaries and configuration files are kept.
     *
     * @param context Current context
     *
     * @return directory (created if needed)
     */
    @NonNull
    public static File getToolsDir(@NonNull final Context context) {
        final File dir = new File(context.getFilesDir(), "tools");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * The log file of a tool.
     *
     * @param context  Current context
     * @param name     of the tool
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    public static File getLogFile(@NonNull final Context context,
                                  @NonNull final String name) {
        return new File(getToolsDir(context), name + ".log");
    }

    /**
     * The location of a binary bundled in the APK as "{@code lib<name>.so}".
     * <p>
     * Note: characters which are not valid in a shared library name
     * (such as "{@code -}") are replaced with "{@code _}"; i.e. the logical name
     * "nezha-agent" is bundled as "libnezha_agent.so".
     *
     * @param context Current context
     * @param name    of the binary, e.g. "frpc"
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    public static File getBundledBinary(@NonNull final Context context,
                                        @NonNull final String name) {
        final String libName = "lib" + name.replace('-', '_') + ".so";
        return new File(context.getApplicationInfo().nativeLibraryDir, libName);
    }

    /**
     * The location of an imported/downloaded binary.
     *
     * @param context Current context
     * @param name    of the binary, e.g. "frpc"
     *
     * @return file (not necessarily existing)
     */
    @NonNull
    public static File getImportedBinary(@NonNull final Context context,
                                         @NonNull final String name) {
        return new File(getToolsDir(context), name);
    }

    /**
     * Resolve the binary to use.
     * <p>
     * Order: user-set path, imported/downloaded, bundled.
     *
     * @param context      Current context
     * @param name         of the binary, e.g. "frpc"
     * @param explicitPath (optional) user configured path
     *
     * @return the existing file, or {@code null} when nothing was found
     */
    @Nullable
    public static File resolve(@NonNull final Context context,
                               @NonNull final String name,
                               @Nullable final String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            final File file = new File(explicitPath.strip());
            if (file.exists()) {
                return file;
            }
        }

        final File imported = getImportedBinary(context, name);
        if (imported.exists()) {
            return imported;
        }

        final File bundled = getBundledBinary(context, name);
        if (bundled.exists()) {
            return bundled;
        }

        return null;
    }

    /**
     * Check if a binary is available, from any source.
     *
     * @param context      Current context
     * @param name         of the binary
     * @param explicitPath (optional) user configured path
     *
     * @return {@code true} if it can be resolved
     */
    public static boolean isAvailable(@NonNull final Context context,
                                      @NonNull final String name,
                                      @Nullable final String explicitPath) {
        return resolve(context, name, explicitPath) != null;
    }

    /**
     * Whether the file can only be executed with root privileges.
     * <p>
     * Only binaries in the native library directory are executable by the app itself.
     *
     * @param context Current context
     * @param file    to check
     *
     * @return flag
     */
    public static boolean needsRoot(@NonNull final Context context,
                                    @NonNull final File file) {
        return !file.getAbsolutePath()
                     .startsWith(context.getApplicationInfo().nativeLibraryDir);
    }

    /**
     * Combine an executable with its arguments into a full command line.
     * <p>
     * When the executable can only be run by root, the command is wrapped
     * in "{@code su -c '...'}".
     *
     * @param context Current context
     * @param exe     to run
     * @param args    arguments
     *
     * @return the command to pass to {@link ProcessBuilder}
     *
     * @throws IOException when root is required but not available
     */
    @NonNull
    public static List<String> buildCommand(@NonNull final Context context,
                                            @NonNull final File exe,
                                            @NonNull final List<String> args)
            throws IOException {
        final List<String> command = new ArrayList<>();
        if (needsRoot(context, exe)) {
            final String override = Prefs.getSuPath(Prefs.getSharedPreferences(context));
            final String su = RootUtils.getSuPath(override);
            if (su == null) {
                throw new IOException(name(exe)
                                      + ": this binary is not executable by the app."
                                      + " Root access is required.");
            }
            final StringBuilder sb = new StringBuilder(quote(exe.getAbsolutePath()));
            for (final String arg : args) {
                sb.append(' ').append(quote(arg));
            }
            command.add(su);
            command.add("-c");
            command.add(sb.toString());
        } else {
            command.add(exe.getAbsolutePath());
            command.addAll(args);
        }
        return command;
    }

    @NonNull
    private static String name(@NonNull final File file) {
        return file.getName();
    }

    /**
     * Shell-quote a single argument, using single quotes.
     *
     * @param s to quote
     *
     * @return quoted string
     */
    @NonNull
    public static String quote(@NonNull final String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Mark a file executable (best effort).
     *
     * @param file to change
     */
    public static void makeExecutable(@NonNull final File file) {
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(true, true);
    }

    /**
     * Copy a user-selected binary into the tools directory.
     *
     * @param context Current context
     * @param name    to store it as
     * @param uri     to copy from
     *
     * @return the imported file
     *
     * @throws IOException on failure
     */
    @NonNull
    public static File importBinary(@NonNull final Context context,
                                    @NonNull final String name,
                                    @NonNull final Uri uri)
            throws IOException {
        final File target = getImportedBinary(context, name);
        final File tmp = new File(getToolsDir(context), name + ".tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                throw new IOException("Failed to open: " + uri);
            }
            copy(in, out, null);
        }
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Failed to store: " + name);
        }
        makeExecutable(target);
        return target;
    }

    /**
     * Download a plain binary file.
     *
     * @param context  Current context
     * @param name     to store it as
     * @param url      to download
     * @param listener (optional) progress callback
     *
     * @return the downloaded file
     *
     * @throws IOException on failure
     */
    @NonNull
    public static File downloadBinary(@NonNull final Context context,
                                      @NonNull final String name,
                                      @NonNull final String url,
                                      @Nullable final ProgressListener listener)
            throws IOException {
        final File target = getImportedBinary(context, name);
        final File tmp = new File(getToolsDir(context), name + ".tmp");
        try (InputStream in = open(url);
             OutputStream out = new FileOutputStream(tmp)) {
            copy(in, out, listener);
        }
        replace(tmp, target);
        makeExecutable(target);
        return target;
    }

    /**
     * Download a "{@code .tar.gz}"/"{@code .tgz}" archive and extract a single member.
     *
     * @param context    Current context
     * @param name       to store the member as
     * @param url        of the archive
     * @param memberName the member to extract; matched on the file name
     * @param listener   (optional) progress callback
     *
     * @return the extracted file
     *
     * @throws IOException on failure
     */
    @NonNull
    public static File downloadTarGz(@NonNull final Context context,
                                     @NonNull final String name,
                                     @NonNull final String url,
                                     @NonNull final String memberName,
                                     @Nullable final ProgressListener listener)
            throws IOException {
        final File target = getImportedBinary(context, name);
        final File tmp = new File(getToolsDir(context), name + ".tmp");
        try (InputStream in = new GZIPInputStream(open(url));
             OutputStream out = new FileOutputStream(tmp)) {
            extractTarMember(in, memberName, out);
        }
        replace(tmp, target);
        makeExecutable(target);
        return target;
    }

    private static void replace(@NonNull final File tmp,
                                @NonNull final File target)
            throws IOException {
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Failed to store: " + target.getName());
        }
    }

    @NonNull
    private static InputStream open(@NonNull final String url)
            throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Sshd4a");
        final int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            //noinspection ResultOfMethodCallIgnored
            connection.disconnect();
            throw new IOException("Download failed; HTTP " + code + " for " + url);
        }
        return connection.getInputStream();
    }

    private static void copy(@NonNull final InputStream in,
                             @NonNull final OutputStream out,
                             @Nullable final ProgressListener listener)
            throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        long done = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            done += read;
            if (listener != null) {
                listener.onProgress(done, -1);
            }
        }
        out.flush();
    }

    /**
     * Minimal tar reader; extracts one member to the given output stream.
     *
     * @param in         of the uncompressed tar stream
     * @param memberName the file name (of the full member path) to extract
     * @param out        to write the member to
     *
     * @throws IOException on failure or when the member is not found
     */
    private static void extractTarMember(@NonNull final InputStream in,
                                         @NonNull final String memberName,
                                         @NonNull final OutputStream out)
            throws IOException {
        final byte[] header = new byte[512];
        while (true) {
            if (!readFully(in, header)) {
                break;
            }
            // all zeroes: end of archive
            boolean empty = true;
            for (final byte b : header) {
                if (b != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                break;
            }

            final String entryName = parseString(header, 0, 100);
            final long size = parseOctal(header, 124, 12);
            final char type = (char) header[156];

            final boolean isFile = type == 0 || type == '0';
            final String baseName = entryName.contains("/")
                                    ? entryName.substring(entryName.lastIndexOf('/') + 1)
                                    : entryName;

            if (isFile && baseName.equals(memberName)) {
                copyN(in, out, size);
                out.flush();
                return;
            }

            // skip the entry data (padded to 512 bytes)
            skipN(in, size);
        }
        throw new IOException("Archive does not contain: " + memberName);
    }

    @NonNull
    private static String parseString(@NonNull final byte[] buffer,
                                      final int offset,
                                      final int length) {
        int end = offset;
        while (end < offset + length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(@NonNull final byte[] buffer,
                                   final int offset,
                                   final int length) {
        long value = 0;
        int i = offset;
        // skip leading spaces/NULs
        while (i < offset + length && (buffer[i] == ' ' || buffer[i] == 0)) {
            i++;
        }
        for (; i < offset + length; i++) {
            final byte b = buffer[i];
            if (b < '0' || b > '7') {
                break;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static boolean readFully(@NonNull final InputStream in,
                                     @NonNull final byte[] buffer)
            throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            final int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) {
                return offset != 0;
            }
            offset += read;
        }
        return true;
    }

    private static void copyN(@NonNull final InputStream in,
                              @NonNull final OutputStream out,
                              final long count)
            throws IOException {
        final byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            final int read = in.read(buffer, 0,
                                     (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                throw new IOException("Unexpected end of archive");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipN(@NonNull final InputStream in,
                              final long count)
            throws IOException {
        // tar data is padded to a 512-byte boundary
        final long padded = (count + 511) / 512 * 512;
        long remaining = padded;
        final byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) {
                return;
            }
            remaining -= read;
        }
    }

    /**
     * Download an archive ({@code .tar.gz}/{@code .tgz}/{@code .zip}) and extract
     * one or more members from it.
     *
     * @param context  Current context
     * @param url      of the archive
     * @param targets  the members to extract
     * @param listener (optional) progress callback
     *
     * @throws IOException on failure, or when a member was not found
     */
    public static void downloadArchive(@NonNull final Context context,
                                       @NonNull final String url,
                                       @NonNull final List<Target> targets,
                                       @Nullable final ProgressListener listener)
            throws IOException {
        if (targets.isEmpty()) {
            return;
        }

        final File dir = getToolsDir(context);
        final Map<String, File> wanted = new LinkedHashMap<>();
        for (final Target target : targets) {
            wanted.put(target.member, new File(dir, target.name + ".tmp"));
        }

        final List<File> done = new ArrayList<>();
        try (InputStream raw = open(url);
             InputStream in = url.toLowerCase().endsWith(".zip")
                              ? new java.util.zip.ZipInputStream(raw)
                              : new GZIPInputStream(raw)) {
            if (url.toLowerCase().endsWith(".zip")) {
                extractZip(in, wanted, done, listener);
            } else {
                extractTar(in, wanted, done, listener);
            }
        } catch (@NonNull final IOException e) {
            for (final File tmp : wanted.values()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            throw e;
        }

        // find the member again by its temp file name
        for (final Target target : targets) {
            final File tmp = wanted.get(target.member);
            if (tmp == null || !done.contains(tmp)) {
                for (final File f : wanted.values()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                throw new IOException("Archive does not contain: " + target.member);
            }
        }

        for (final Target target : targets) {
            final File tmp = wanted.get(target.member);
            final File out = getImportedBinary(context, target.name);
            replace(tmp, out);
            makeExecutable(out);
        }
    }

    /**
     * Extract the {@code in}/{@code out} pair; either uncompressed or gzip'ed tar.
     *
     * @param in       tar stream
     * @param wanted   member base name to temp file
     * @param done     temp files successfully written
     * @param listener (optional) progress callback
     *
     * @throws IOException on failure
     */
    private static void extractTar(@NonNull final InputStream in,
                                   @NonNull final Map<String, File> wanted,
                                   @NonNull final List<File> done,
                                   @Nullable final ProgressListener listener)
            throws IOException {
        final byte[] header = new byte[512];
        while (!wanted.isEmpty()) {
            if (!readFully(in, header)) {
                return;
            }
            boolean empty = true;
            for (final byte b : header) {
                if (b != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                return;
            }

            final String entryName = parseString(header, 0, 100);
            final long size = parseOctal(header, 124, 12);
            final char type = (char) header[156];

            final boolean isFile = type == 0 || type == '0';
            final String baseName = entryName.contains("/")
                                    ? entryName.substring(entryName.lastIndexOf('/') + 1)
                                    : entryName;

            final File target = isFile ? wanted.get(baseName) : null;
            if (target != null) {
                try (OutputStream out = new FileOutputStream(target)) {
                    copyN(in, out, size);
                }
                wanted.remove(baseName);
                done.add(target);
                if (listener != null) {
                    listener.onProgress(done.size(), wanted.size() + done.size());
                }
            } else {
                skipN(in, size);
            }
        }
    }

    /**
     * Extract members from a zip stream.
     *
     * @param in       zip stream
     * @param wanted   member base name to temp file
     * @param done     temp files successfully written
     * @param listener (optional) progress callback
     *
     * @throws IOException on failure
     */
    private static void extractZip(@NonNull final InputStream in,
                                   @NonNull final Map<String, File> wanted,
                                   @NonNull final List<File> done,
                                   @Nullable final ProgressListener listener)
            throws IOException {
        final java.util.zip.ZipInputStream zip = (java.util.zip.ZipInputStream) in;
        while (!wanted.isEmpty()) {
            final java.util.zip.ZipEntry entry = zip.getNextEntry();
            if (entry == null) {
                return;
            }
            if (entry.isDirectory()) {
                continue;
            }
            final String entryName = entry.getName();
            final String baseName = entryName.contains("/")
                                    ? entryName.substring(entryName.lastIndexOf('/') + 1)
                                    : entryName;

            final File target = wanted.get(baseName);
            if (target != null) {
                try (OutputStream out = new FileOutputStream(target)) {
                    copy(in, out, null);
                }
                wanted.remove(baseName);
                done.add(target);
                if (listener != null) {
                    listener.onProgress(done.size(), wanted.size() + done.size());
                }
            }
        }
    }

    /**
     * Progress callback for downloads.
     */
    public interface ProgressListener {
        /**
         * @param bytesDone  bytes downloaded so far
         * @param bytesTotal total, or -1 when unknown
         */
        void onProgress(long bytesDone, long bytesTotal);
    }

    /**
     * One member of an archive which must be stored in the tools directory.
     */
    public static final class Target {

        /** Name to store the member under; i.e. "{@code toolsDir/<name>}". */
        @NonNull
        public final String name;
        /** The file name of the member inside the archive. */
        @NonNull
        public final String member;

        /**
         * Constructor.
         *
         * @param name   to store the member under
         * @param member the file name of the member inside the archive
         */
        public Target(@NonNull final String name,
                      @NonNull final String member) {
            this.name = name;
            this.member = member;
        }
    }
}
