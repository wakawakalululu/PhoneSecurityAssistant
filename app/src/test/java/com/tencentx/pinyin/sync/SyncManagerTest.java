package com.tencentx.pinyin.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import android.content.Context;

import com.tencentx.pinyin.remote.BinaryManager;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SyncManager} — rclone progress parsing, size parsing,
 * unit multipliers, {@link SyncManager.Progress#percent()} and
 * {@link SyncManager#writeConfig(android.content.Context, SyncTask, String)}.
 *
 * <p>The private helpers {@code parseSize} and {@code unitMultiplier}, and the
 * package-private {@code Progress} constructor, are exercised via reflection so
 * that the source file need not be changed. {@code Context} is mocked with
 * Mockito (subclass mock maker — no javaagent required for abstract classes).</p>
 */
public class SyncManagerTest {

    private static final long KIB = 1024L;
    private static final long MIB = 1024L * 1024;
    private static final long GIB = 1024L * 1024 * 1024;
    private static final long TIB = 1024L * 1024 * 1024 * 1024;
    private static final long PIB = 1024L * 1024 * 1024 * 1024 * 1024;
    private static final long EIB = 1024L * 1024 * 1024 * 1024 * 1024 * 1024;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    // ----- reflection helpers -------------------------------------------------

    private static long parseSize(final String text) throws Exception {
        final Method m = SyncManager.class.getDeclaredMethod("parseSize", String.class);
        m.setAccessible(true);
        final Object result = (text == null)
                ? m.invoke(null, new Object[]{null})
                : m.invoke(null, text);
        return ((Number) result).longValue();
    }

    private static double unitMultiplier(final String unit) throws Exception {
        final Method m = SyncManager.class.getDeclaredMethod("unitMultiplier", String.class);
        m.setAccessible(true);
        return ((Number) m.invoke(null, unit)).doubleValue();
    }

    private static SyncManager.Progress newProgress(final long transferred,
                                                    final long total) throws Exception {
        final Constructor<?> c =
                SyncManager.Progress.class.getDeclaredConstructor(long.class, long.class);
        c.setAccessible(true);
        return (SyncManager.Progress) c.newInstance(transferred, total);
    }

    private Context newContext() {
        final Context ctx = mock(Context.class);
        when(ctx.getFilesDir()).thenReturn(tmp.getRoot());
        return ctx;
    }

    private static String read(final File f) throws Exception {
        return Files.readString(f.toPath());
    }

    // ----- parseProgress ------------------------------------------------------

    @Test
    public void parseProgress_typicalGiB() {
        final SyncManager.Progress p = SyncManager.parseProgress(
                "Transferred:   \t 1.206 GiB / 10.000 GiB, 12%, 5.243 MiB/s, ETA 1m30s");
        assertNotNull(p);
        assertEquals((long) (1.206 * GIB), p.getTransferred());
        assertEquals((long) (10.000 * GIB), p.getTotal());
        assertEquals(12, p.percent());
    }

    @Test
    public void parseProgress_zeroOverZero() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:      0 B / 0 B, 0%, 0 B/s");
        assertNotNull(p);
        assertEquals(0L, p.getTransferred());
        assertEquals(0L, p.getTotal());
        assertEquals(-1, p.percent());
    }

    @Test
    public void parseProgress_dashTotalYieldsZeroTotal() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:    5.000 MiB / -, 0%, 1.000 MiB/s");
        assertNotNull(p);
        assertEquals((long) (5.000 * MIB), p.getTransferred());
        assertEquals(0L, p.getTotal());
        assertEquals(-1, p.percent());
    }

    @Test
    public void parseProgress_mibOverGib() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:    500.000 MiB / 1.000 GiB, 50%, 10 MiB/s");
        assertNotNull(p);
        assertEquals((long) (500.000 * MIB), p.getTransferred());
        assertEquals(GIB, p.getTotal());
    }

    @Test
    public void parseProgress_kibOverMib() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:    1.500 KiB / 1.000 MiB, 0%, 1 KiB/s");
        assertNotNull(p);
        assertEquals((long) (1.5 * KIB), p.getTransferred());
        assertEquals(MIB, p.getTotal());
    }

    @Test
    public void parseProgress_decimalUnits() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:    1.500 KB / 1.000 MB, 0%, 1 KB/s");
        assertNotNull(p);
        assertEquals(1500L, p.getTransferred());
        assertEquals(1_000_000L, p.getTotal());
    }

    @Test
    public void parseProgress_tibOverPib() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:    2.000 TiB / 1.000 PiB, 0%, 1 TiB/s");
        assertNotNull(p);
        assertEquals(2L * TIB, p.getTransferred());
        assertEquals(PIB, p.getTotal());
    }

    @Test
    public void parseProgress_pibOverEib() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:    1.000 PiB / 1.000 EiB, 0%, 1 PiB/s");
        assertNotNull(p);
        assertEquals(PIB, p.getTransferred());
        assertEquals(EIB, p.getTotal());
    }

    @Test
    public void parseProgress_zeroTransferredOverGib() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:      0 B / 1.000 GiB, 0%, 0 B/s");
        assertNotNull(p);
        assertEquals(0L, p.getTransferred());
        assertEquals(GIB, p.getTotal());
        assertEquals(0, p.percent());
    }

    @Test
    public void parseProgress_withTabsAndExtraColumns() {
        final SyncManager.Progress p =
                SyncManager.parseProgress("Transferred:\t\t3.500 GiB / 7.000 GiB, 50%, 10 MiB/s");
        assertNotNull(p);
        assertEquals((long) (3.5 * GIB), p.getTransferred());
        assertEquals((long) (7.0 * GIB), p.getTotal());
        assertEquals(50, p.percent());
    }

    @Test
    public void parseProgress_nonSummaryLineReturnsNull() {
        assertNull(SyncManager.parseProgress("Elapsed: 1s"));
        assertNull(SyncManager.parseProgress("Checks:    100 / 100, 100%"));
    }

    @Test
    public void parseProgress_emptyLineReturnsNull() {
        assertNull(SyncManager.parseProgress(""));
    }

    // ----- parseSize (private, via reflection) --------------------------------

    @Test
    public void parseSize_gib() throws Exception {
        assertEquals((long) (1.206 * GIB), parseSize("1.206 GiB"));
    }

    @Test
    public void parseSize_mib() throws Exception {
        assertEquals((long) (5.243 * MIB), parseSize("5.243 MiB"));
    }

    @Test
    public void parseSize_kib() throws Exception {
        assertEquals((long) (1.5 * KIB), parseSize("1.5 KiB"));
    }

    @Test
    public void parseSize_plainBytes() throws Exception {
        assertEquals(100L, parseSize("100 B"));
    }

    @Test
    public void parseSize_zeroB() throws Exception {
        assertEquals(0L, parseSize("0 B"));
    }

    @Test
    public void parseSize_dash() throws Exception {
        assertEquals(0L, parseSize("-"));
    }

    @Test
    public void parseSize_null() throws Exception {
        assertEquals(0L, parseSize(null));
    }

    @Test
    public void parseSize_blank() throws Exception {
        assertEquals(0L, parseSize(""));
        assertEquals(0L, parseSize("   "));
    }

    @Test
    public void parseSize_decimalUnits() throws Exception {
        assertEquals(1500L, parseSize("1.5 KB"));
        assertEquals(2_000_000L, parseSize("2 MB"));
        assertEquals(3_000_000_000L, parseSize("3 GB"));
        assertEquals(1_000_000_000_000L, parseSize("1 TB"));
        assertEquals(1_000_000_000_000_000L, parseSize("1 PB"));
        assertEquals(1_000_000_000_000_000_000L, parseSize("1 EB"));
    }

    @Test
    public void parseSize_binaryLargeUnits() throws Exception {
        assertEquals(TIB, parseSize("1 TiB"));
        assertEquals(PIB, parseSize("1 PiB"));
        assertEquals(EIB, parseSize("1 EiB"));
    }

    @Test
    public void parseSize_surroundingWhitespaceTrimmed() throws Exception {
        assertEquals((long) (1.206 * GIB), parseSize("  1.206 GiB  "));
    }

    @Test
    public void parseSize_unknownUnitReturnsZero() throws Exception {
        assertEquals(0L, parseSize("1 XB"));
    }

    @Test
    public void parseSize_noUnitReturnsZero() throws Exception {
        assertEquals(0L, parseSize("100"));
    }

    @Test
    public void parseSize_garbageReturnsZero() throws Exception {
        assertEquals(0L, parseSize("abc"));
    }

    // ----- unitMultiplier (private, via reflection) ---------------------------

    @Test
    public void unitMultiplier_binaryUnits() throws Exception {
        assertEquals(1.0, unitMultiplier("B"), 0.0);
        assertEquals(1024.0, unitMultiplier("KiB"), 0.0);
        assertEquals(1048576.0, unitMultiplier("MiB"), 0.0);
        assertEquals(1073741824.0, unitMultiplier("GiB"), 0.0);
        assertEquals(1099511627776.0, unitMultiplier("TiB"), 0.0);
        assertEquals(1125899906842624.0, unitMultiplier("PiB"), 0.0);
        assertEquals(1152921504606846976.0, unitMultiplier("EiB"), 0.0);
    }

    @Test
    public void unitMultiplier_decimalUnits() throws Exception {
        assertEquals(1000.0, unitMultiplier("KB"), 0.0);
        assertEquals(1_000_000.0, unitMultiplier("MB"), 0.0);
        assertEquals(1_000_000_000.0, unitMultiplier("GB"), 0.0);
        assertEquals(1_000_000_000_000.0, unitMultiplier("TB"), 0.0);
        assertEquals(1_000_000_000_000_000.0, unitMultiplier("PB"), 0.0);
        assertEquals(1_000_000_000_000_000_000.0, unitMultiplier("EB"), 0.0);
    }

    @Test
    public void unitMultiplier_unknownUnitDefaultsToOne() throws Exception {
        assertEquals(1.0, unitMultiplier("XB"), 0.0);
        assertEquals(1.0, unitMultiplier("nope"), 0.0);
    }

    // ----- Progress.percent (constructor private, via reflection) -------------

    @Test
    public void percent_normal() throws Exception {
        assertEquals(50, newProgress(500, 1000).percent());
    }

    @Test
    public void percent_totalZeroReturnsMinusOne() throws Exception {
        assertEquals(-1, newProgress(500, 0).percent());
    }

    @Test
    public void percent_totalNegativeReturnsMinusOne() throws Exception {
        assertEquals(-1, newProgress(500, -1).percent());
    }

    @Test
    public void percent_cappedAt100() throws Exception {
        assertEquals(100, newProgress(1500, 1000).percent());
    }

    @Test
    public void percent_transferredZero() throws Exception {
        assertEquals(0, newProgress(0, 1000).percent());
    }

    @Test
    public void percent_exact100() throws Exception {
        assertEquals(100, newProgress(1000, 1000).percent());
    }

    @Test
    public void percent_truncatesDown() throws Exception {
        assertEquals(99, newProgress(999, 1000).percent());
    }

    // ----- writeConfig --------------------------------------------------------

    @Test
    public void writeConfig_sftpFull() throws Exception {
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("abc");
        t.setRemoteType(SyncTask.RemoteType.SFTP);
        t.setRemoteHost("example.com");
        t.setRemotePort(22);
        t.setRemoteUser("user");

        final File out = SyncManager.writeConfig(ctx, t, "OBSCURED");
        assertTrue(out.exists());
        assertEquals(new File(SyncTaskStore.getDir(ctx), "abc.conf"), out);

        final String expected = "[remote]\n"
                + "type = sftp\n"
                + "host = example.com\n"
                + "port = 22\n"
                + "user = user\n"
                + "pass = OBSCURED\n"
                + "disable_hashcheck = true\n";
        assertEquals(expected, read(out));
    }

    @Test
    public void writeConfig_sftpOmitsPortUserPassWhenEmpty() throws Exception {
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("min");
        t.setRemoteType(SyncTask.RemoteType.SFTP);
        t.setRemoteHost("h");
        t.setRemotePort(0);
        t.setRemoteUser("");

        final File out = SyncManager.writeConfig(ctx, t, "");
        final String expected = "[remote]\n"
                + "type = sftp\n"
                + "host = h\n"
                + "disable_hashcheck = true\n";
        assertEquals(expected, read(out));
    }

    @Test
    public void writeConfig_webdavHttpNoPort() throws Exception {
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("w1");
        t.setRemoteType(SyncTask.RemoteType.WEBDAV);
        t.setRemoteHost("h");
        t.setRemotePort(0);
        t.setUseTls(false);

        final File out = SyncManager.writeConfig(ctx, t, "");
        final String expected = "[remote]\n"
                + "type = webdav\n"
                + "url = http://h\n";
        assertEquals(expected, read(out));
    }

    @Test
    public void writeConfig_webdavHttpsWithPortUserPass() throws Exception {
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("w2");
        t.setRemoteType(SyncTask.RemoteType.WEBDAV);
        t.setRemoteHost("h");
        t.setRemotePort(5005);
        t.setRemoteUser("u");
        t.setUseTls(true);

        final File out = SyncManager.writeConfig(ctx, t, "P");
        final String expected = "[remote]\n"
                + "type = webdav\n"
                + "url = https://h:5005\n"
                + "user = u\n"
                + "pass = P\n";
        assertEquals(expected, read(out));
    }

    @Test
    public void writeConfig_returnsExistingConfigFile() throws Exception {
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("ret");
        t.setRemoteType(SyncTask.RemoteType.SFTP);
        t.setRemoteHost("h");

        final File out = SyncManager.writeConfig(ctx, t, "");
        assertTrue(out.exists());
        assertTrue(out.getName().endsWith(".conf"));
        assertTrue(out.getParentFile().getName().equals("sync"));
    }

    // ----- binary availability / command building (stub BinaryManager) --------
    // These exercise the code paths reachable without a real rclone binary:
    // BinaryManager/Prefs stubs make resolveBinary() return null, so the
    // command-building paths surface as IOException.

    @Test
    public void isBinaryAvailable_returnsFalseWithStubBinaryManager() {
        // BinaryManager.isAvailable stub always returns false
        assertFalse(SyncManager.isBinaryAvailable(newContext()));
    }

    @Test
    public void resolveBinary_returnsNullWithStubBinaryManager() {
        assertNull(SyncManager.resolveBinary(newContext()));
    }

    @Test
    public void getSummary_returnsStringForMissingBinary() {
        final Context ctx = mock(Context.class);
        when(ctx.getFilesDir()).thenReturn(tmp.getRoot());
        when(ctx.getString(anyInt())).thenReturn("summary-text");
        // isBinaryAvailable() is false with the stub -> getString(missing)
        assertEquals("summary-text", SyncManager.getSummary(ctx));
    }

    @Test
    public void obscure_emptyPasswordReturnsEmpty() throws Exception {
        assertEquals("", SyncManager.obscure(newContext(), ""));
    }

    @Test
    public void obscure_nonEmptyThrowsWhenBinaryMissing() {
        final Context ctx = newContext();
        try {
            SyncManager.obscure(ctx, "secret");
            fail("expected IOException because rclone binary is unavailable");
        } catch (final IOException expected) {
            // resolveBinary() returns null with the stub -> IOException
        }
    }

    @Test
    public void buildCommand_throwsWhenBinaryMissing() {
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setName("n");
        t.setRemoteHost("h");
        t.setRemotePath("/r");
        t.setLocalPath("/l");
        try {
            SyncManager.buildCommand(ctx, t);
            fail("expected IOException because rclone binary is unavailable");
        } catch (final IOException expected) {
            // resolveBinary() returns null -> IOException
        }
    }

    // ----- obscure / buildCommand happy paths with a fake rclone binary --------
    // BinaryManager is mocked statically to return a temp shell script that
    // stands in for rclone, letting the process-execution paths run end-to-end.

    private File fakeRclone(final String output) throws IOException {
        final File exe = File.createTempFile("fake-rclone", ".sh");
        exe.deleteOnExit();
        Files.writeString(exe.toPath(), "#!/bin/sh\necho '" + output + "'\n");
        exe.setExecutable(true);
        return exe;
    }

    @SuppressWarnings("unchecked")
    private void stubBinaryManager(final MockedStatic<BinaryManager> bm,
                                   final Context ctx,
                                   final File fakeExe) {
        bm.when(() -> BinaryManager.resolve(eq(ctx), eq("rclone"), anyString()))
          .thenReturn(fakeExe);
        bm.when(() -> BinaryManager.buildCommand(eq(ctx), eq(fakeExe), anyList()))
          .thenAnswer(inv -> {
              final List<String> args = inv.getArgument(2);
              final List<String> cmd = new ArrayList<>();
              cmd.add(fakeExe.getAbsolutePath());
              cmd.addAll(args);
              return cmd;
          });
    }

    @Test
    public void obscure_runsRcloneAndReturnsStdout() throws Exception {
        final File fakeExe = fakeRclone("OBSCURED_TOKEN");
        final Context ctx = newContext();
        try (MockedStatic<BinaryManager> bm = mockStatic(BinaryManager.class)) {
            stubBinaryManager(bm, ctx, fakeExe);
            assertEquals("OBSCURED_TOKEN", SyncManager.obscure(ctx, "pw"));
        } finally {
            fakeExe.delete();
        }
    }

    @Test
    public void buildCommand_download_buildsCopyFromRemoteToLocal() throws Exception {
        final File fakeExe = fakeRclone("OBSC");
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("cmd-dl");
        t.setName("n");
        t.setRemoteHost("h");
        t.setRemotePort(22);
        t.setRemoteUser("u");
        t.setRemotePassword("pw");
        t.setRemotePath("/r");
        t.setLocalPath("/l");
        t.setDirection(SyncTask.Direction.DOWNLOAD);
        try (MockedStatic<BinaryManager> bm = mockStatic(BinaryManager.class)) {
            stubBinaryManager(bm, ctx, fakeExe);
            final List<String> cmd = SyncManager.buildCommand(ctx, t);
            assertEquals(fakeExe.getAbsolutePath(), cmd.get(0));
            assertTrue(cmd.contains("copy"));
            assertTrue(cmd.contains("--config"));
            assertTrue(cmd.contains("--progress"));
            assertTrue(cmd.contains("--stats"));
            assertTrue(cmd.contains("1s"));
            assertTrue(cmd.contains("remote:/r"));
            assertTrue(cmd.contains("/l"));
        } finally {
            fakeExe.delete();
        }
    }

    @Test
    public void buildCommand_upload_buildsCopyFromLocalToRemote() throws Exception {
        final File fakeExe = fakeRclone("OBSC");
        final Context ctx = newContext();
        final SyncTask t = new SyncTask();
        t.setId("cmd-ul");
        t.setName("n");
        t.setRemoteHost("h");
        t.setRemotePath("/r");
        t.setLocalPath("/l");
        t.setDirection(SyncTask.Direction.UPLOAD);
        try (MockedStatic<BinaryManager> bm = mockStatic(BinaryManager.class)) {
            stubBinaryManager(bm, ctx, fakeExe);
            final List<String> cmd = SyncManager.buildCommand(ctx, t);
            assertTrue(cmd.contains("/l"));
            assertTrue(cmd.contains("remote:/r"));
            // UPLOAD: local is src, remote is dst -> /l precedes remote:/r
            assertTrue(cmd.indexOf("/l") < cmd.indexOf("remote:/r"));
        } finally {
            fakeExe.delete();
        }
    }
}
