package com.tencentx.pinyin.webdav;

import android.content.ContentProviderClient;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

import com.tencentx.pinyin.R;
import com.tencentx.pinyin.remote.WebDavManager;

/** Read-only WebDAV documents exposed through Android's Storage Access Framework. */
public final class WebDavDocumentsProvider extends DocumentsProvider {

    private static final String ROOT_ID = "webdav-root";
    private static final String DIRECTORY_PREFIX = "dir:";
    private static final String FILE_PREFIX = "file:";
    private static final ExecutorService IO_EXECUTOR = Executors.newFixedThreadPool(3);

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor queryRoots(@Nullable final String[] projection) {
        final MatrixCursor cursor = new MatrixCursor(
                projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        if (getContext() == null || !WebDavManager.isConfigured(getContext())) {
            return cursor;
        }

        try {
            final WebDavManager.Client client = WebDavManager.fromPreferences(getContext());
            final MatrixCursor.RowBuilder row = cursor.newRow();
            add(row, cursor, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
            add(row, cursor, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
            add(row, cursor, DocumentsContract.Root.COLUMN_TITLE,
                getContext().getString(R.string.webdav_provider_label));
            add(row, cursor, DocumentsContract.Root.COLUMN_SUMMARY, client.getDisplayName());
            add(row, cursor, DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
            add(row, cursor, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
            add(row, cursor, DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher);
        } catch (@NonNull final IOException ignored) {
            // Invalid or incomplete settings should not expose a broken root.
        }
        return cursor;
    }

    @Nullable
    @Override
    public Cursor queryDocument(@NonNull final String documentId,
                                @Nullable final String[] projection)
            throws FileNotFoundException {
        final MatrixCursor cursor = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        if (ROOT_ID.equals(documentId)) {
            final WebDavManager.Client client = getClient();
            addDocumentRow(cursor, ROOT_ID,
                           getContext().getString(R.string.webdav_provider_label),
                           DocumentsContract.Document.MIME_TYPE_DIR, -1L, 0L, 0);
            return cursor;
        }

        final RemoteDocument document = decodeDocumentId(documentId);
        final WebDavManager.Client client = getClient();
        try {
            final List<WebDavManager.Entry> entries = client.list(
                    document.path, document.directory, 0);
            for (final WebDavManager.Entry entry : entries) {
                if (entry.getPath().equals(document.path)) {
                    addEntry(cursor, entry);
                    return cursor;
                }
            }
        } catch (@NonNull final IOException e) {
            throw fileNotFound("Unable to read WebDAV item", e);
        }
        throw new FileNotFoundException("WebDAV item not found");
    }

    @Nullable
    @Override
    public Cursor queryChildDocuments(@NonNull final String parentDocumentId,
                                      @Nullable final String[] projection,
                                      @Nullable final String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor cursor = new MatrixCursor(
                projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final RemoteDocument parent = ROOT_ID.equals(parentDocumentId)
                                      ? new RemoteDocument("", true)
                                      : decodeDocumentId(parentDocumentId);
        if (!parent.directory) {
            throw new FileNotFoundException("Not a WebDAV directory");
        }

        try {
            final WebDavManager.Client client = getClient();
            for (final WebDavManager.Entry entry : client.list(parent.path, true, 1)) {
                if (!entry.getPath().equals(parent.path)
                    && isDirectChild(parent.path, entry.getPath())) {
                    addEntry(cursor, entry);
                }
            }
        } catch (@NonNull final IOException e) {
            throw fileNotFound("Unable to list WebDAV directory", e);
        }
        return cursor;
    }

    @Override
    public boolean isChildDocument(@NonNull final String parentDocumentId,
                                  @NonNull final String documentId) {
        if (ROOT_ID.equals(parentDocumentId)) {
            try {
                decodeDocumentId(documentId);
                return true;
            } catch (@NonNull final FileNotFoundException ignored) {
                return false;
            }
        }
        try {
            final RemoteDocument parent = decodeDocumentId(parentDocumentId);
            final RemoteDocument child = decodeDocumentId(documentId);
            return parent.directory && !parent.path.equals(child.path)
                     && isDescendant(parent.path, child.path);
        } catch (@NonNull final FileNotFoundException ignored) {
            return false;
        }
    }

    @NonNull
    @Override
    public ParcelFileDescriptor openDocument(@NonNull final String documentId,
                                            @NonNull final String mode,
                                            @Nullable final CancellationSignal signal)
            throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("WebDAV provider is read-only");
        }
        final RemoteDocument document = decodeDocumentId(documentId);
        if (document.directory) {
            throw new FileNotFoundException("Cannot open a directory");
        }

        final WebDavManager.Client client = getClient();
        final Call call;
        try {
            call = client.newGetCall(document.path);
        } catch (@NonNull final IOException e) {
            throw fileNotFound("Invalid WebDAV file path", e);
        }

        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createReliablePipe();
        } catch (@NonNull final IOException e) {
            throw fileNotFound("Unable to open WebDAV stream", e);
        }

        if (signal != null) {
            signal.setOnCancelListener(call::cancel);
        }
        IO_EXECUTOR.execute(() -> streamResponse(call, pipe[1], signal));
        return pipe[0];
    }

    @NonNull
    private WebDavManager.Client getClient() throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("Provider is detached");
        }
        try {
            return WebDavManager.fromPreferences(getContext());
        } catch (@NonNull final IOException e) {
            throw fileNotFound("WebDAV is not configured", e);
        }
    }

    private void streamResponse(@NonNull final Call call,
                                @NonNull final ParcelFileDescriptor destination,
                                @Nullable final CancellationSignal signal) {
        final ParcelFileDescriptor.AutoCloseOutputStream output =
                new ParcelFileDescriptor.AutoCloseOutputStream(destination);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                destination.closeWithError("WebDAV returned HTTP " + response.code());
                return;
            }
            final ResponseBody body = response.body();
            if (body == null) {
                destination.closeWithError("WebDAV returned an empty file");
                return;
            }
            try (InputStream input = body.byteStream()) {
                final byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (signal != null) {
                        signal.throwIfCanceled();
                    }
                    output.write(buffer, 0, count);
                }
                output.close();
            }
        } catch (@NonNull final IOException e) {
            try {
                destination.closeWithError(e.getMessage() != null
                                           ? e.getMessage() : "WebDAV transfer failed");
            } catch (@NonNull final IOException ignored) {
                // The descriptor may already have been closed by cancellation.
            }
        } finally {
            if (signal != null) {
                signal.setOnCancelListener(null);
            }
        }
    }

    private static void addEntry(@NonNull final MatrixCursor cursor,
                                 @NonNull final WebDavManager.Entry entry) {
        final String mimeType = entry.isDirectory()
                                ? DocumentsContract.Document.MIME_TYPE_DIR
                                : entry.getMimeType() != null
                                  ? entry.getMimeType() : "application/octet-stream";
        addDocumentRow(cursor, encodeDocumentId(entry.getPath(), entry.isDirectory()),
                       entry.getName(), mimeType, entry.getSize(), entry.getLastModified(), 0);
    }

    private static void addDocumentRow(@NonNull final MatrixCursor cursor,
                                       @NonNull final String id,
                                       @NonNull final String name,
                                       @NonNull final String mimeType,
                                       final long size,
                                       final long lastModified,
                                       final int flags) {
        final MatrixCursor.RowBuilder row = cursor.newRow();
        add(row, cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        add(row, cursor, DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        add(row, cursor, DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType);
        add(row, cursor, DocumentsContract.Document.COLUMN_SIZE, size);
        add(row, cursor, DocumentsContract.Document.COLUMN_LAST_MODIFIED, lastModified);
        add(row, cursor, DocumentsContract.Document.COLUMN_FLAGS, flags);
    }

    private static void add(@NonNull final MatrixCursor.RowBuilder row,
                            @NonNull final MatrixCursor cursor,
                            @NonNull final String column,
                            @Nullable final Object value) {
        if (cursor.getColumnIndex(column) >= 0) {
            row.add(column, value);
        }
    }

    @NonNull
    private static String encodeDocumentId(@NonNull final String path,
                                           final boolean directory) {
        final String prefix = directory ? DIRECTORY_PREFIX : FILE_PREFIX;
        return prefix + Base64.encodeToString(path.getBytes(StandardCharsets.UTF_8),
                                              Base64.URL_SAFE | Base64.NO_WRAP
                                              | Base64.NO_PADDING);
    }

    @NonNull
    private static RemoteDocument decodeDocumentId(@NonNull final String id)
            throws FileNotFoundException {
        final boolean directory;
        final String encoded;
        if (id.startsWith(DIRECTORY_PREFIX)) {
            directory = true;
            encoded = id.substring(DIRECTORY_PREFIX.length());
        } else if (id.startsWith(FILE_PREFIX)) {
            directory = false;
            encoded = id.substring(FILE_PREFIX.length());
        } else {
            throw new FileNotFoundException("Invalid WebDAV document ID");
        }
        try {
            final String path = new String(Base64.decode(encoded,
                                                         Base64.URL_SAFE | Base64.NO_WRAP
                                                         | Base64.NO_PADDING),
                                           StandardCharsets.UTF_8);
            if (path.startsWith("/") || path.contains("\\")) {
                throw new FileNotFoundException("Invalid WebDAV document path");
            }
            for (final String segment : path.split("/")) {
                if (".".equals(segment) || "..".equals(segment)) {
                    throw new FileNotFoundException("Invalid WebDAV document path");
                }
            }
            return new RemoteDocument(path, directory);
        } catch (@NonNull final IllegalArgumentException e) {
            throw fileNotFound("Invalid WebDAV document ID", e);
        }
    }

    private static boolean isDirectChild(@NonNull final String parent,
                                         @NonNull final String child) {
        final String prefix = parent.isEmpty() ? "" : parent + "/";
        if (!child.startsWith(prefix)) {
            return false;
        }
        return !child.substring(prefix.length()).contains("/");
    }

    private static boolean isDescendant(@NonNull final String parent,
                                        @NonNull final String child) {
        final String prefix = parent.isEmpty() ? "" : parent + "/";
        return child.startsWith(prefix) && child.length() > prefix.length();
    }

    @NonNull
    private static FileNotFoundException fileNotFound(@NonNull final String message,
                                                       @NonNull final Exception cause) {
        final FileNotFoundException exception = new FileNotFoundException(message);
        exception.initCause(cause);
        return exception;
    }

    private static final class RemoteDocument {
        @NonNull
        private final String path;
        private final boolean directory;

        private RemoteDocument(@NonNull final String path, final boolean directory) {
            this.path = path;
            this.directory = directory;
        }
    }
}