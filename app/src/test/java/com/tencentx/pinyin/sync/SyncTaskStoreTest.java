package com.tencentx.pinyin.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SyncTaskStore} — JSON persistence of sync tasks.
 *
 * <p>{@code Context.getFilesDir()} is mocked to a {@link TemporaryFolder} so
 * that the on-disk store lives in a per-test scratch directory. The private
 * {@code toJson}/{@code fromJson} helpers are exercised via reflection so the
 * source file need not be changed.</p>
 */
public class SyncTaskStoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private Context newContext() {
        final Context ctx = mock(Context.class);
        when(ctx.getFilesDir()).thenReturn(tmp.getRoot());
        return ctx;
    }

    private static SyncTask newTask(final String id, final String name) {
        final SyncTask t = new SyncTask();
        t.setId(id);
        t.setName(name);
        t.setRemoteHost("host");
        t.setRemotePort(2222);
        t.setRemoteUser("user");
        t.setRemotePassword("pass");
        t.setRemotePath("/remote");
        t.setLocalPath("/local");
        return t;
    }

    private static SyncTask findById(final List<SyncTask> tasks, final String id) {
        for (final SyncTask t : tasks) {
            if (t.getId().equals(id)) {
                return t;
            }
        }
        throw new AssertionError("no task with id " + id);
    }

    // ----- reflection helpers -------------------------------------------------

    private static JSONObject toJson(final SyncTask task) throws Exception {
        final Method m = SyncTaskStore.class.getDeclaredMethod("toJson", SyncTask.class);
        m.setAccessible(true);
        return (JSONObject) m.invoke(null, task);
    }

    private static SyncTask fromJson(final JSONObject o) throws Exception {
        final Method m = SyncTaskStore.class.getDeclaredMethod("fromJson", JSONObject.class);
        m.setAccessible(true);
        return (SyncTask) m.invoke(null, o);
    }

    private static void writeStore(final Context ctx, final String content) throws IOException {
        final File dir = SyncTaskStore.getDir(ctx);
        Files.writeString(new File(dir, "tasks.json").toPath(), content);
    }

    // ----- load / save --------------------------------------------------------

    @Test
    public void load_emptyWhenNoFile() {
        final Context ctx = newContext();
        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertNotNull(loaded);
        assertTrue(loaded.isEmpty());
    }

    @Test
    public void save_createsFile() {
        final Context ctx = newContext();
        SyncTaskStore.save(ctx, List.of(newTask("a", "A")));
        assertTrue(new File(SyncTaskStore.getDir(ctx), "tasks.json").exists());
    }

    @Test
    public void save_emptyListThenLoad() {
        final Context ctx = newContext();
        SyncTaskStore.save(ctx, new ArrayList<>());
        assertTrue(SyncTaskStore.load(ctx).isEmpty());
    }

    @Test
    public void saveThenLoad_roundtripPreservesPersistedFields() {
        final Context ctx = newContext();
        final SyncTask a = newTask("a", "nameA");
        a.setRemoteType(SyncTask.RemoteType.WEBDAV);
        a.setRemotePort(22);
        a.setUseTls(false);
        a.setDirection(SyncTask.Direction.UPLOAD);
        a.setStatus(SyncTask.Status.SUCCEEDED);
        a.setLastRunEpochMs(123L);
        a.setLastError("none");
        a.setTransferredBytes(999L); // intentionally not persisted
        a.setTotalBytes(888L);       // intentionally not persisted

        final SyncTask b = newTask("b", "nameB");

        SyncTaskStore.save(ctx, List.of(a, b));
        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(2, loaded.size());

        final SyncTask la = findById(loaded, "a");
        assertEquals("nameA", la.getName());
        assertEquals(SyncTask.RemoteType.WEBDAV, la.getRemoteType());
        assertEquals("host", la.getRemoteHost());
        assertEquals(22, la.getRemotePort());
        assertEquals("user", la.getRemoteUser());
        assertEquals("pass", la.getRemotePassword());
        assertEquals("/remote", la.getRemotePath());
        assertEquals("/local", la.getLocalPath());
        assertEquals(SyncTask.Direction.UPLOAD, la.getDirection());
        assertFalse(la.isUseTls());
        assertEquals(SyncTask.Status.SUCCEEDED, la.getStatus());
        assertEquals(123L, la.getLastRunEpochMs());
        assertEquals("none", la.getLastError());
        // transferredBytes / totalBytes are not part of the persisted JSON
        assertEquals(0L, la.getTransferredBytes());
        assertEquals(0L, la.getTotalBytes());

        assertEquals("nameB", findById(loaded, "b").getName());
    }

    // ----- upsert -------------------------------------------------------------

    @Test
    public void upsert_addsNewTask() {
        final Context ctx = newContext();
        SyncTaskStore.upsert(ctx, newTask("a", "A"));
        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(1, loaded.size());
        assertEquals("a", loaded.get(0).getId());
    }

    @Test
    public void upsert_replacesExistingById() {
        final Context ctx = newContext();
        SyncTaskStore.upsert(ctx, newTask("a", "old"));
        final SyncTask updated = newTask("a", "new");
        updated.setStatus(SyncTask.Status.FAILED);
        SyncTaskStore.upsert(ctx, updated);

        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(1, loaded.size());
        assertEquals("new", loaded.get(0).getName());
        assertEquals(SyncTask.Status.FAILED, loaded.get(0).getStatus());
    }

    @Test
    public void upsert_keepsOtherTasks() {
        final Context ctx = newContext();
        SyncTaskStore.upsert(ctx, newTask("a", "A"));
        SyncTaskStore.upsert(ctx, newTask("b", "B"));
        SyncTaskStore.upsert(ctx, newTask("a", "A2"));

        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(2, loaded.size());
        assertEquals("A2", findById(loaded, "a").getName());
        assertEquals("B", findById(loaded, "b").getName());
    }

    // ----- delete -------------------------------------------------------------

    @Test
    public void delete_removesTask() {
        final Context ctx = newContext();
        SyncTaskStore.upsert(ctx, newTask("a", "A"));
        SyncTaskStore.upsert(ctx, newTask("b", "B"));
        SyncTaskStore.delete(ctx, "a");

        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(1, loaded.size());
        assertEquals("b", loaded.get(0).getId());
    }

    @Test
    public void delete_nonexistentIsNoOp() {
        final Context ctx = newContext();
        SyncTaskStore.upsert(ctx, newTask("a", "A"));
        SyncTaskStore.delete(ctx, "ghost");

        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(1, loaded.size());
        assertEquals("a", loaded.get(0).getId());
    }

    @Test
    public void delete_removesLogAndConfigFiles() throws IOException {
        final Context ctx = newContext();
        final SyncTask a = newTask("a", "A");
        SyncTaskStore.upsert(ctx, a);

        final File logFile = SyncTaskStore.getLogFile(ctx, a);
        final File confFile = SyncTaskStore.getConfigFile(ctx, a);
        logFile.getParentFile().mkdirs();
        Files.writeString(logFile.toPath(), "log");
        Files.writeString(confFile.toPath(), "conf");
        assertTrue(logFile.exists());
        assertTrue(confFile.exists());

        SyncTaskStore.delete(ctx, "a");

        assertFalse(logFile.exists());
        assertFalse(confFile.exists());
        assertTrue(SyncTaskStore.load(ctx).isEmpty());
    }

    // ----- file helpers -------------------------------------------------------

    @Test
    public void getDir_createsAndReturnsSyncDir() {
        final Context ctx = newContext();
        final File dir = SyncTaskStore.getDir(ctx);
        assertTrue(dir.exists());
        assertEquals("sync", dir.getName());
    }

    @Test
    public void getLogFile_andGetConfigFile_paths() {
        final Context ctx = newContext();
        final SyncTask t = newTask("xyz", "X");
        assertEquals(new File(SyncTaskStore.getDir(ctx), "xyz.log"),
                     SyncTaskStore.getLogFile(ctx, t));
        assertEquals(new File(SyncTaskStore.getDir(ctx), "xyz.conf"),
                     SyncTaskStore.getConfigFile(ctx, t));
    }

    // ----- load robustness ----------------------------------------------------

    @Test
    public void load_malformedJsonReturnsEmpty() throws IOException {
        final Context ctx = newContext();
        writeStore(ctx, "{not json");
        assertTrue(SyncTaskStore.load(ctx).isEmpty());
    }

    @Test
    public void load_skipsMalformedEntriesKeepingValidOnes() throws IOException {
        final Context ctx = newContext();
        // one valid entry, one with an invalid status enum (fromJson -> null)
        writeStore(ctx, "["
                + "{\"id\":\"good\",\"name\":\"g\",\"status\":\"IDLE\"},"
                + "{\"id\":\"bad\",\"status\":\"NOPE\"}"
                + "]");
        final List<SyncTask> loaded = SyncTaskStore.load(ctx);
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).getId());
    }

    // ----- toJson / fromJson (private, via reflection) ------------------------

    @Test
    public void toJson_containsAllPersistedFields() throws Exception {
        final SyncTask t = newTask("x", "n");
        t.setRemoteType(SyncTask.RemoteType.WEBDAV);
        t.setRemotePort(8080);
        t.setUseTls(false);
        t.setDirection(SyncTask.Direction.UPLOAD);
        t.setStatus(SyncTask.Status.FAILED);
        t.setLastRunEpochMs(555L);
        t.setLastError("err");

        final JSONObject o = toJson(t);
        assertEquals("x", o.getString("id"));
        assertEquals("n", o.getString("name"));
        assertEquals("WEBDAV", o.getString("remoteType"));
        assertEquals("host", o.getString("remoteHost"));
        assertEquals(8080, o.getInt("remotePort"));
        assertEquals("user", o.getString("remoteUser"));
        assertEquals("pass", o.getString("remotePass"));
        assertEquals("/remote", o.getString("remotePath"));
        assertEquals("/local", o.getString("localPath"));
        assertEquals("UPLOAD", o.getString("direction"));
        assertFalse(o.getBoolean("useTls"));
        assertEquals("FAILED", o.getString("status"));
        assertEquals(555L, o.getLong("lastRun"));
        assertEquals("err", o.getString("lastError"));
    }

    @Test
    public void fromJson_full() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("id", "id1");
        o.put("name", "nm");
        o.put("remoteType", "WEBDAV");
        o.put("remoteHost", "h");
        o.put("remotePort", 9);
        o.put("remoteUser", "u");
        o.put("remotePass", "p");
        o.put("remotePath", "/r");
        o.put("localPath", "/l");
        o.put("direction", "UPLOAD");
        o.put("useTls", false);
        o.put("status", "RUNNING");
        o.put("lastRun", 42L);
        o.put("lastError", "e");

        final SyncTask t = fromJson(o);
        assertNotNull(t);
        assertEquals("id1", t.getId());
        assertEquals("nm", t.getName());
        assertEquals(SyncTask.RemoteType.WEBDAV, t.getRemoteType());
        assertEquals("h", t.getRemoteHost());
        assertEquals(9, t.getRemotePort());
        assertEquals("u", t.getRemoteUser());
        assertEquals("p", t.getRemotePassword());
        assertEquals("/r", t.getRemotePath());
        assertEquals("/l", t.getLocalPath());
        assertEquals(SyncTask.Direction.UPLOAD, t.getDirection());
        assertFalse(t.isUseTls());
        assertEquals(SyncTask.Status.RUNNING, t.getStatus());
        assertEquals(42L, t.getLastRunEpochMs());
        assertEquals("e", t.getLastError());
    }

    @Test
    public void fromJson_missingOptionalFieldsUseDefaults() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("id", "onlyId");

        final SyncTask t = fromJson(o);
        assertNotNull(t);
        assertEquals("onlyId", t.getId());
        assertEquals("", t.getName());
        assertEquals(SyncTask.RemoteType.SFTP, t.getRemoteType());
        assertEquals("", t.getRemoteHost());
        assertEquals(0, t.getRemotePort());
        assertEquals("", t.getRemoteUser());
        assertEquals("", t.getRemotePassword());
        assertEquals("", t.getRemotePath());
        assertEquals("", t.getLocalPath());
        assertEquals(SyncTask.Direction.DOWNLOAD, t.getDirection());
        assertTrue("useTls default is true", t.isUseTls());
        assertEquals(SyncTask.Status.IDLE, t.getStatus());
        assertEquals(0L, t.getLastRunEpochMs());
        assertEquals("", t.getLastError());
    }

    @Test
    public void fromJson_invalidRemoteTypeReturnsNull() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("id", "x");
        o.put("remoteType", "FTP");
        assertNull(fromJson(o));
    }

    @Test
    public void fromJson_invalidStatusReturnsNull() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("id", "x");
        o.put("status", "BOGUS");
        assertNull(fromJson(o));
    }

    @Test
    public void fromJson_invalidDirectionReturnsNull() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("id", "x");
        o.put("direction", "SIDEWAYS");
        assertNull(fromJson(o));
    }

    @Test
    public void fromJson_missingIdReturnsNull() throws Exception {
        final JSONObject o = new JSONObject();
        o.put("name", "noId");
        assertNull(fromJson(o));
    }

    @Test
    public void toJsonFromJson_roundtripPreservesPersistedFields() throws Exception {
        final SyncTask t = newTask("rt", "rtname");
        t.setRemoteType(SyncTask.RemoteType.WEBDAV);
        t.setRemotePort(1234);
        t.setUseTls(false);
        t.setDirection(SyncTask.Direction.UPLOAD);
        t.setStatus(SyncTask.Status.CANCELLED);
        t.setLastRunEpochMs(777L);
        t.setLastError("le");
        t.setTransferredBytes(999L);
        t.setTotalBytes(888L);

        final SyncTask back = fromJson(toJson(t));
        assertNotNull(back);
        assertEquals("rt", back.getId());
        assertEquals("rtname", back.getName());
        assertEquals(SyncTask.RemoteType.WEBDAV, back.getRemoteType());
        assertEquals("host", back.getRemoteHost());
        assertEquals(1234, back.getRemotePort());
        assertEquals("user", back.getRemoteUser());
        assertEquals("pass", back.getRemotePassword());
        assertEquals("/remote", back.getRemotePath());
        assertEquals("/local", back.getLocalPath());
        assertEquals(SyncTask.Direction.UPLOAD, back.getDirection());
        assertFalse(back.isUseTls());
        assertEquals(SyncTask.Status.CANCELLED, back.getStatus());
        assertEquals(777L, back.getLastRunEpochMs());
        assertEquals("le", back.getLastError());
        // not persisted by design
        assertEquals(0L, back.getTransferredBytes());
        assertEquals(0L, back.getTotalBytes());
    }
}
