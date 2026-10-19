package com.tencentx.pinyin.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Xml;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.tencentx.pinyin.settings.Prefs;

/** Minimal HTTPS WebDAV client used by the read-only SAF provider. */
public final class WebDavManager {

    private static final String KEY_URL = "webdav.url";
    private static final String KEY_USERNAME = "webdav.username";
    private static final String KEY_PASSWORD = "webdav.password";
    private static final MediaType XML_TYPE = MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:displayname/><d:resourcetype/><d:getcontentlength/>"
            + "<d:getcontenttype/><d:getlastmodified/>"
            + "</d:prop></d:propfind>";

    private WebDavManager() {
    }

    public static boolean isConfigured(@NonNull final Context context) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        return !Prefs.getString(prefs, KEY_URL, "").isBlank();
    }

    @NonNull
    public static Client fromPreferences(@NonNull final Context context) throws IOException {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final String rawUrl = Prefs.getString(prefs, KEY_URL, "").strip();
        if (rawUrl.isEmpty()) {
            throw new IOException("WebDAV server is not configured");
        }

        final HttpUrl parsed;
        try {
            parsed = HttpUrl.get(rawUrl);
        } catch (@NonNull final IllegalArgumentException e) {
            throw new IOException("Invalid WebDAV URL", e);
        }
        if (!"https".equals(parsed.scheme()) || parsed.query() != null
            || parsed.fragment() != null || !parsed.username().isEmpty()
            || !parsed.password().isEmpty()) {
            throw new IOException("WebDAV requires an HTTPS URL without embedded credentials or query");
        }

        HttpUrl baseUrl = parsed;
        if (!baseUrl.encodedPath().endsWith("/")) {
            baseUrl = HttpUrl.get(baseUrl.toString() + "/");
        }

        final String username = Prefs.getString(prefs, KEY_USERNAME, "");
        final String password = Prefs.getString(prefs, KEY_PASSWORD, "");
        final OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false);
        if (!username.isEmpty()) {
            final String authorization = Credentials.basic(username, password,
                                                           StandardCharsets.UTF_8);
            builder.addInterceptor(chain -> chain.proceed(
                    chain.request().newBuilder()
                            .header("Authorization", authorization)
                            .build()));
        }
        return new Client(baseUrl, builder.build());
    }

    public static final class Client {

        @NonNull
        private final HttpUrl baseUrl;
        @NonNull
        private final OkHttpClient httpClient;

        private Client(@NonNull final HttpUrl baseUrl,
                       @NonNull final OkHttpClient httpClient) {
            this.baseUrl = baseUrl;
            this.httpClient = httpClient;
        }

        @NonNull
        public String getDisplayName() {
            return baseUrl.host();
        }

        @NonNull
        public List<Entry> list(@NonNull final String path,
                                final boolean directory,
                                final int depth) throws IOException {
            final Request request = new Request.Builder()
                    .url(urlFor(path, directory))
                    .header("Depth", String.valueOf(depth))
                    .method("PROPFIND", RequestBody.create(XML_TYPE, PROPFIND_BODY))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() != 207 && !response.isSuccessful()) {
                    throw new IOException("WebDAV request failed with HTTP " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("WebDAV returned an empty response");
                }
                return parseMultiStatus(response.body().byteStream());
            } catch (@NonNull final XmlPullParserException e) {
                throw new IOException("Invalid WebDAV XML response", e);
            }
        }

        @NonNull
        public Request newGetRequest(@NonNull final String path) throws IOException {
            return new Request.Builder().url(urlFor(path, false)).get().build();
        }

        @NonNull
        public Call newGetCall(@NonNull final String path) throws IOException {
            return httpClient.newCall(newGetRequest(path));
        }

        private HttpUrl urlFor(@NonNull final String path,
                               final boolean directory) throws IOException {
            final String normalized = path.replace('\\', '/');
            final StringBuilder url = new StringBuilder(baseUrl.toString());
            if (!normalized.isEmpty()) {
                for (final String segment : normalized.split("/")) {
                    if (segment.isEmpty()) {
                        continue;
                    }
                    if (".".equals(segment) || "..".equals(segment)) {
                        throw new IOException("Invalid WebDAV path");
                    }
                    if (url.charAt(url.length() - 1) != '/') {
                        url.append('/');
                    }
                    url.append(Uri.encode(segment, "-._~"));
                }
            }
            if (directory && url.charAt(url.length() - 1) != '/') {
                url.append('/');
            }
            try {
                final HttpUrl result = HttpUrl.get(url.toString());
                if (!sameOrigin(result) || !result.encodedPath().startsWith(baseUrl.encodedPath())) {
                    throw new IOException("WebDAV path escaped the configured root");
                }
                return result;
            } catch (@NonNull final IllegalArgumentException e) {
                throw new IOException("Invalid WebDAV path", e);
            }
        }

        @Nullable
        private String pathFromHref(@NonNull final String href) {
            final HttpUrl resolved = baseUrl.resolve(href);
            if (resolved == null || !sameOrigin(resolved)
                || !resolved.encodedPath().startsWith(baseUrl.encodedPath())) {
                return null;
            }
            String path = Uri.decode(resolved.encodedPath().substring(baseUrl.encodedPath().length()));
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            for (final String segment : path.split("/")) {
                if (".".equals(segment) || "..".equals(segment)) {
                    return null;
                }
            }
            return path;
        }

        private boolean sameOrigin(@NonNull final HttpUrl candidate) {
            return baseUrl.scheme().equals(candidate.scheme())
                   && baseUrl.host().equals(candidate.host())
                   && baseUrl.port() == candidate.port();
        }

        @NonNull
        private List<Entry> parseMultiStatus(@NonNull final java.io.InputStream input)
                throws IOException, XmlPullParserException {
            final List<Entry> entries = new ArrayList<>();
            final XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            parser.setInput(input, StandardCharsets.UTF_8.name());

            Entry current = null;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    final String tag = localName(parser.getName());
                    if ("response".equals(tag)) {
                        current = new Entry();
                    } else if (current != null) {
                        switch (tag) {
                            case "href":
                                current.href = parser.nextText();
                                break;
                            case "displayname":
                                current.name = parser.nextText();
                                break;
                            case "getcontentlength":
                                current.size = parseLong(parser.nextText(), -1L);
                                break;
                            case "getcontenttype":
                                current.mimeType = parser.nextText();
                                break;
                            case "getlastmodified":
                                current.lastModified = parseDate(parser.nextText());
                                break;
                            case "collection":
                                current.directory = true;
                                break;
                            default:
                                break;
                        }
                    }
                } else if (event == XmlPullParser.END_TAG
                           && "response".equals(localName(parser.getName()))
                           && current != null) {
                    final String path = current.href != null ? pathFromHref(current.href) : null;
                    if (path != null) {
                        current.path = path;
                        if (current.name == null || current.name.isEmpty()) {
                            current.name = lastSegment(path);
                        }
                        entries.add(current);
                    }
                    current = null;
                }
                event = parser.next();
            }
            return entries;
        }

        @NonNull
        private static String localName(@NonNull final String name) {
            final int colon = name.lastIndexOf(':');
            return colon >= 0 ? name.substring(colon + 1) : name;
        }

        private static long parseLong(@Nullable final String value, final long fallback) {
            try {
                return value != null ? Long.parseLong(value.strip()) : fallback;
            } catch (@NonNull final NumberFormatException e) {
                return fallback;
            }
        }

        private static long parseDate(@Nullable final String value) {
            try {
                return value != null
                       ? ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                       .toInstant().toEpochMilli()
                       : 0L;
            } catch (@NonNull final RuntimeException e) {
                return 0L;
            }
        }

        @NonNull
        private static String lastSegment(@NonNull final String path) {
            final int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    public static final class Entry {

        @Nullable
        private String href;
        @Nullable
        private String path;
        @Nullable
        private String name;
        @Nullable
        private String mimeType;
        private long size = -1L;
        private long lastModified;
        private boolean directory;

        private Entry() {
        }

        @NonNull
        public String getPath() {
            return path != null ? path : "";
        }

        @NonNull
        public String getName() {
            return name != null ? name : "";
        }

        @Nullable
        public String getMimeType() {
            return mimeType;
        }

        public long getSize() {
            return size;
        }

        public long getLastModified() {
            return lastModified;
        }

        public boolean isDirectory() {
            return directory;
        }
    }
}