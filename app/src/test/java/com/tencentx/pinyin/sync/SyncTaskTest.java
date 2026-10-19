package com.tencentx.pinyin.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SyncTask} — the file-sync data model.
 *
 * <p>Pure JVM, no Android dependencies.</p>
 */
public class SyncTaskTest {

    /** @return a task with all four {@code isConfigured()} fields set. */
    private SyncTask makeConfigured() {
        final SyncTask t = new SyncTask();
        t.setName("docs");
        t.setRemoteHost("example.com");
        t.setRemotePath("/remote/docs");
        t.setLocalPath("/sdcard/docs");
        return t;
    }

    @Test
    public void defaultConstructor_setsDefaults() {
        final SyncTask t = new SyncTask();
        assertNotNull(t.getId());
        assertFalse("id must not be empty", t.getId().isEmpty());
        assertEquals("", t.getName());
        assertEquals(SyncTask.RemoteType.SFTP, t.getRemoteType());
        assertEquals("", t.getRemoteHost());
        assertEquals(0, t.getRemotePort());
        assertEquals("", t.getRemoteUser());
        assertEquals("", t.getRemotePassword());
        assertEquals("", t.getRemotePath());
        assertEquals("", t.getLocalPath());
        assertEquals(SyncTask.Direction.DOWNLOAD, t.getDirection());
        assertTrue("useTls defaults to true", t.isUseTls());
        assertEquals(SyncTask.Status.IDLE, t.getStatus());
        assertEquals(0L, t.getLastRunEpochMs());
        assertEquals(0L, t.getTransferredBytes());
        assertEquals(0L, t.getTotalBytes());
        assertEquals("", t.getLastError());
    }

    @Test
    public void isConfigured_trueWhenAllRequiredFieldsSet() {
        assertTrue(makeConfigured().isConfigured());
    }

    @Test
    public void isConfigured_falseWhenNameBlank() {
        final SyncTask t = makeConfigured();
        t.setName("");
        assertFalse(t.isConfigured());
    }

    @Test
    public void isConfigured_falseWhenNameWhitespaceOnly() {
        final SyncTask t = makeConfigured();
        t.setName("   ");
        assertFalse(t.isConfigured());
    }

    @Test
    public void isConfigured_falseWhenRemoteHostBlank() {
        final SyncTask t = makeConfigured();
        t.setRemoteHost("");
        assertFalse(t.isConfigured());
    }

    @Test
    public void isConfigured_falseWhenRemotePathBlank() {
        final SyncTask t = makeConfigured();
        t.setRemotePath("");
        assertFalse(t.isConfigured());
    }

    @Test
    public void isConfigured_falseWhenLocalPathBlank() {
        final SyncTask t = makeConfigured();
        t.setLocalPath("");
        assertFalse(t.isConfigured());
    }

    @Test
    public void resetProgress_clearsTransientFieldsButNotStatusOrLastRun() {
        final SyncTask t = makeConfigured();
        t.setTransferredBytes(12345L);
        t.setTotalBytes(67890L);
        t.setLastError("boom");
        t.setStatus(SyncTask.Status.RUNNING);
        t.setLastRunEpochMs(999L);

        t.resetProgress();

        assertEquals(0L, t.getTransferredBytes());
        assertEquals(0L, t.getTotalBytes());
        assertEquals("", t.getLastError());
        // resetProgress must NOT touch status or lastRun (transient != progress)
        assertEquals(SyncTask.Status.RUNNING, t.getStatus());
        assertEquals(999L, t.getLastRunEpochMs());
    }

    @Test
    public void equals_sameIdTrueEvenWhenOtherFieldsDiffer() {
        final SyncTask a = new SyncTask();
        final SyncTask b = new SyncTask();
        b.setId(a.getId());
        b.setName("other");
        assertTrue(a.equals(b));
        assertTrue(b.equals(a));
    }

    @Test
    public void equals_differentIdFalse() {
        final SyncTask a = new SyncTask();
        final SyncTask b = new SyncTask();
        assertFalse(a.equals(b));
    }

    @Test
    public void equals_selfTrue() {
        final SyncTask a = new SyncTask();
        assertTrue(a.equals(a));
    }

    @Test
    public void equals_nullFalse() {
        final SyncTask a = new SyncTask();
        assertFalse(a.equals(null));
    }

    @Test
    public void equals_nonSyncTaskFalse() {
        final SyncTask a = new SyncTask();
        assertFalse(a.equals("not a task"));
        assertFalse(a.equals(42));
    }

    @Test
    public void hashCode_consistentWithEquals() {
        final SyncTask a = new SyncTask();
        final SyncTask b = new SyncTask();
        b.setId(a.getId());
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void hashCode_equalsIdHashCode() {
        final SyncTask a = new SyncTask();
        assertEquals(a.getId().hashCode(), a.hashCode());
    }

    @Test
    public void toString_containsNameAndDirection() {
        final SyncTask t = makeConfigured();
        t.setDirection(SyncTask.Direction.UPLOAD);
        final String s = t.toString();
        assertTrue(s.contains("docs"));
        assertTrue(s.contains("UPLOAD"));
    }

    @Test
    public void setters_updateAllFields() {
        final SyncTask t = new SyncTask();
        t.setId("id-1");
        t.setName("n");
        t.setRemoteType(SyncTask.RemoteType.WEBDAV);
        t.setRemoteHost("h");
        t.setRemotePort(22);
        t.setRemoteUser("u");
        t.setRemotePassword("p");
        t.setRemotePath("/r");
        t.setLocalPath("/l");
        t.setDirection(SyncTask.Direction.UPLOAD);
        t.setUseTls(false);
        t.setStatus(SyncTask.Status.FAILED);
        t.setLastRunEpochMs(123L);
        t.setTransferredBytes(10L);
        t.setTotalBytes(20L);
        t.setLastError("err");

        assertEquals("id-1", t.getId());
        assertEquals("n", t.getName());
        assertEquals(SyncTask.RemoteType.WEBDAV, t.getRemoteType());
        assertEquals("h", t.getRemoteHost());
        assertEquals(22, t.getRemotePort());
        assertEquals("u", t.getRemoteUser());
        assertEquals("p", t.getRemotePassword());
        assertEquals("/r", t.getRemotePath());
        assertEquals("/l", t.getLocalPath());
        assertEquals(SyncTask.Direction.UPLOAD, t.getDirection());
        assertFalse(t.isUseTls());
        assertEquals(SyncTask.Status.FAILED, t.getStatus());
        assertEquals(123L, t.getLastRunEpochMs());
        assertEquals(10L, t.getTransferredBytes());
        assertEquals(20L, t.getTotalBytes());
        assertEquals("err", t.getLastError());
    }

    @Test
    public void enums_complete() {
        assertEquals(2, SyncTask.RemoteType.values().length);
        assertEquals(SyncTask.RemoteType.SFTP, SyncTask.RemoteType.valueOf("SFTP"));
        assertEquals(SyncTask.RemoteType.WEBDAV, SyncTask.RemoteType.valueOf("WEBDAV"));

        assertEquals(2, SyncTask.Direction.values().length);
        assertEquals(SyncTask.Direction.DOWNLOAD, SyncTask.Direction.valueOf("DOWNLOAD"));
        assertEquals(SyncTask.Direction.UPLOAD, SyncTask.Direction.valueOf("UPLOAD"));

        assertEquals(5, SyncTask.Status.values().length);
        assertEquals(SyncTask.Status.IDLE, SyncTask.Status.valueOf("IDLE"));
        assertEquals(SyncTask.Status.RUNNING, SyncTask.Status.valueOf("RUNNING"));
        assertEquals(SyncTask.Status.SUCCEEDED, SyncTask.Status.valueOf("SUCCEEDED"));
        assertEquals(SyncTask.Status.FAILED, SyncTask.Status.valueOf("FAILED"));
        assertEquals(SyncTask.Status.CANCELLED, SyncTask.Status.valueOf("CANCELLED"));
    }
}
