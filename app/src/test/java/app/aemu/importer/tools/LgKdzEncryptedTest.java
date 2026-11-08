/* Added for AEmulator Plus, 2026-11-06: tests for the encrypted LG KDZ import. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import static org.junit.Assert.*;

import app.aemu.importer.tools.FirmwareToolset.Artifact;
import app.aemu.importer.tools.FirmwareToolset.Role;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Old LG KDZ files: RC4-encrypted CAB (MSZIP) around the legacy "MSTXMETX" DZ. The cabinet and the DZ are built
 * here from scratch (no firmware is shipped with the tests); the key vectors come from the reference
 * implementation (michaelwright235/kdz_decrypt_encrypt, kdz.py).
 */
public class LgKdzEncryptedTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ key + cipher

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    /** Values printed by kdz.py's find_cipher() logic (password -> MD5) for these sizes. */
    @Test public void keyMatchesReferenceImplementation() {
        assertEquals("18c2a1e556bcd4cfb5b2d5e3f793d853", hex(LgCab.deriveKey(121715231L)));   // the GW620 V10i KDZ
        assertEquals("f24a7680c9d6df25e3982706bbd9d13a", hex(LgCab.deriveKey(36L)));
        assertEquals("5647b4e7f25209cd9cbc54dce2fb5561", hex(LgCab.deriveKey(1000L)));
        assertEquals("b5148ca08c9f1362662829f07cc28fa2", hex(LgCab.deriveKey(4294967295L)));
        assertEquals("9637a6c138958932bea5fc9156a455f8", hex(LgCab.deriveKey(9999999999999L)));  // 13 digits: the whole table
    }

    @Test public void keyIsUndefinedBeyondTheTable() {
        assertNull(LgCab.deriveKey(10000000000000L));   // 14 digits
        assertNull(LgCab.deriveKey(0));
    }

    @Test public void rc4MatchesTheJdkCipherAndSkipEqualsDiscarding() throws Exception {
        byte[] key = LgCab.deriveKey(123456L);
        byte[] data = new byte[5000];
        new Random(1).nextBytes(data);
        javax.crypto.Cipher jdk = javax.crypto.Cipher.getInstance("RC4");
        jdk.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "RC4"));
        byte[] expected = jdk.doFinal(data);

        byte[] mine = data.clone();
        new LgCab.Rc4(key).crypt(mine, 0, mine.length);
        assertArrayEquals(expected, mine);

        byte[] tail = Arrays.copyOfRange(data, 3000, 5000);
        LgCab.Rc4 r = new LgCab.Rc4(key);
        r.skip(3000);
        r.crypt(tail, 0, tail.length);
        assertArrayEquals(Arrays.copyOfRange(expected, 3000, 5000), tail);
    }

    // ------------------------------------------------------------------ builders

    private static void le16(ByteArrayOutputStream o, int v) { o.write(v); o.write(v >> 8); }
    private static void le32(ByteArrayOutputStream o, long v) { le16(o, (int) v & 0xFFFF); le16(o, (int) (v >> 16) & 0xFFFF); }
    private static void put32(byte[] b, int off, long v) { for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >> (8 * i)); }

    private static byte[] md5(byte[] b) throws Exception { return MessageDigest.getInstance("MD5").digest(b); }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(raw); }
        return o.toByteArray();
    }

    /** One legacy-DZ entry: "SSTX" header (0xC4 bytes) + gzip data. */
    private static byte[] mstxEntry(String name, int type, long totalSize, int chunk, byte[] raw, boolean gz) throws Exception {
        byte[] data = gz ? gzip(raw) : raw;
        byte[] h = new byte[0xC4];
        System.arraycopy("SSTX".getBytes(StandardCharsets.US_ASCII), 0, h, 0, 4);
        put32(h, 4, 1); put32(h, 8, type); put32(h, 0x10, data.length);
        System.arraycopy(name.getBytes(StandardCharsets.US_ASCII), 0, h, 0x14, name.length());
        put32(h, 0x94, totalSize); put32(h, 0x98, chunk);
        Arrays.fill(h, 0x9C, 0xA4, (byte) 0xFF);
        System.arraycopy(md5(raw), 0, h, 0xA4, 16);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(h); o.write(data);
        return o.toByteArray();
    }

    private static byte[] mstxDz(byte[]... entries) throws Exception {
        byte[] head = new byte[0x13C];
        System.arraycopy("MSTXMETX".getBytes(StandardCharsets.US_ASCII), 0, head, 0, 8);
        put32(head, 8, 3); put32(head, 12, entries.length);
        System.arraycopy("GW620".getBytes(StandardCharsets.US_ASCII), 0, head, 0x18, 5);
        System.arraycopy("V10i".getBytes(StandardCharsets.US_ASCII), 0, head, 0x20, 4);
        System.arraycopy("MSM7200".getBytes(StandardCharsets.US_ASCII), 0, head, 0x70, 7);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(head);
        for (byte[] e : entries) o.write(e);
        o.write(new byte[136]);                       // trailer after the last entry, as in the real files
        return o.toByteArray();
    }

    /** Single-folder cabinet. method 0 = stored, 1 = MSZIP (each block deflated against the previous one), 3 = LZX header only. */
    private static byte[] cab(String[] names, byte[][] contents, int method) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] c : contents) all.write(c);
        byte[] flat = all.toByteArray();

        ByteArrayOutputStream blocks = new ByteArrayOutputStream();
        int nBlocks = 0;
        byte[] prev = new byte[0];
        for (int pos = 0; pos < flat.length; pos += 32768) {
            byte[] blk = Arrays.copyOfRange(flat, pos, Math.min(flat.length, pos + 32768));
            byte[] enc;
            if (method == 1) {
                Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
                if (prev.length > 0) d.setDictionary(prev);
                d.setInput(blk); d.finish();
                ByteArrayOutputStream z = new ByteArrayOutputStream();
                z.write('C'); z.write('K');
                byte[] buf = new byte[65536];
                while (!d.finished()) z.write(buf, 0, d.deflate(buf));
                d.end();
                enc = z.toByteArray();
            } else {
                enc = blk;
            }
            le32(blocks, 0); le16(blocks, enc.length); le16(blocks, blk.length);
            blocks.write(enc);
            nBlocks++;
            prev = blk;
        }
        ByteArrayOutputStream files = new ByteArrayOutputStream();
        long uoff = 0;
        for (int i = 0; i < names.length; i++) {
            le32(files, contents[i].length); le32(files, uoff); le16(files, 0); le16(files, 0x4A21); le16(files, 0x6000); le16(files, 0x20);
            files.write(names[i].getBytes(StandardCharsets.US_ASCII)); files.write(0);
            uoff += contents[i].length;
        }
        int coffFiles = 36 + 8;
        int coffData = coffFiles + files.size();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("MSCF".getBytes(StandardCharsets.US_ASCII)); le32(o, 0);
        le32(o, coffData + blocks.size()); le32(o, 0);
        le32(o, coffFiles); le32(o, 0);
        o.write(3); o.write(1);                        // version 1.3
        le16(o, 1); le16(o, names.length); le16(o, 0); le16(o, 1234); le16(o, 0);
        le32(o, coffData); le16(o, nBlocks); le16(o, method);
        o.write(files.toByteArray());
        o.write(blocks.toByteArray());
        return o.toByteArray();
    }

    /** The KDZ file: the cabinet through RC4 with the size-derived key (as the vendor tool did). */
    private File kdz(byte[] cab, String name) throws Exception {
        byte[] enc = cab.clone();
        new LgCab.Rc4(LgCab.deriveKey(enc.length)).crypt(enc, 0, enc.length);
        File f = new File(tmp.getRoot(), name);
        Files.write(f.toPath(), enc);
        return f;
    }

    private static byte[] random(int n, long seed) { byte[] b = new byte[n]; new Random(seed).nextBytes(b); return b; }

    /** A GW620-like firmware: modem + bootloader blobs that must be skipped, a boot image, and a system split in chunks. */
    private static final class Fw {
        byte[] boot = concat("ANDROID!".getBytes(StandardCharsets.US_ASCII), random(70000, 7));
        byte[] sys0 = random(100000, 11), sys1 = random(65000, 12);
        byte[] system = concat(sys0, sys1);
        byte[] dz;
        Fw(boolean reversedChunks) throws Exception {
            byte[] amss = random(40000, 1), appsboot = random(3000, 2);
            byte[] e0 = mstxEntry("System.mbn_0", 29, system.length, 0, sys0, true);
            byte[] e1 = mstxEntry("System.mbn_1", 29, system.length, 1, sys1, true);
            dz = mstxDz(mstxEntry("amss.mbn", 3, amss.length, 0, amss, true),
                        mstxEntry("appsboot.mbn", 20, appsboot.length, 0, appsboot, true),
                        mstxEntry("zImage_Ramdisk.mbn", 28, boot.length, 0, boot, true),
                        reversedChunks ? e1 : e0, reversedChunks ? e0 : e1);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static byte[] read(Artifact a) throws IOException { return Files.readAllBytes(a.file.toPath()); }

    private static Artifact find(List<Artifact> l, Role r) {
        for (Artifact a : l) if (a.role == r) return a;
        return null;
    }

    // ------------------------------------------------------------------ end to end

    @Test public void encryptedKdzGivesSystemAndBootImages() throws Exception {
        Fw fw = new Fw(false);
        File f = kdz(cab(new String[]{"GW620-V10i.dz", "updater.dll"}, new byte[][]{fw.dz, random(5000, 3)}, 1), "fw.kdz");
        List<String> log = new ArrayList<>();
        List<Artifact> out = LgKdz.extractEncryptedKdz(f, tmp.newFolder("w1"), log::add);

        assertEquals(2, out.size());
        assertArrayEquals(fw.system, read(find(out, Role.SYSTEM)));
        assertArrayEquals(fw.boot, read(find(out, Role.BOOT)));
        assertEquals("system.img", find(out, Role.SYSTEM).file.getName());
        for (String l : log) assertFalse(l, l.contains("mismatch") || l.contains("failed"));
        assertTrue(log.toString(), log.toString().contains("skipping appsboot.mbn"));   // the bootloader must not pass for a boot image
    }

    @Test public void chunksAreJoinedByChunkNumberNotByPosition() throws Exception {
        Fw fw = new Fw(true);
        File f = kdz(cab(new String[]{"fw.dz"}, new byte[][]{fw.dz}, 1), "rev.kdz");
        List<Artifact> out = LgKdz.extractEncryptedKdz(f, tmp.newFolder("w2"), s -> {});
        assertArrayEquals(fw.system, read(find(out, Role.SYSTEM)));
    }

    @Test public void corruptChunkIsReportedButStillImported() throws Exception {
        Fw fw = new Fw(false);
        byte[] dz = fw.dz.clone();
        // flip a byte inside a stored md5 so the content no longer matches its recorded hash
        int sstx = indexOf(dz, "System.mbn_0".getBytes(StandardCharsets.US_ASCII), 0x13C) - 0x14;   // an entry that is really unpacked
        assertEquals("SSTX", new String(dz, sstx, 4, StandardCharsets.US_ASCII));
        dz[sstx + 0xA4] ^= 0x55;
        File f = kdz(cab(new String[]{"fw.dz"}, new byte[][]{dz}, 1), "bad.kdz");
        List<String> log = new ArrayList<>();
        List<Artifact> out = LgKdz.extractEncryptedKdz(f, tmp.newFolder("w3"), log::add);
        assertNotNull(find(out, Role.SYSTEM));
        assertTrue(log.toString(), log.toString().contains("MD5 mismatch"));
    }

    private static int indexOf(byte[] h, byte[] n, int from) {
        outer: for (int i = from; i + n.length <= h.length; i++) {
            for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer;
            return i;
        }
        return -1;
    }

    /**
     * MSZIP blocks are deflated against the previous block. The payload repeats a 30000-byte random unit (just inside
     * zlib's maximum match distance), so the first ~30000 bytes of every 32 KB block can only be expressed as a reference
     * into the previous block: the cabinet is small only if the dictionary was used, and it decodes only if the reader
     * sets the same dictionary.
     */
    @Test public void mszipUsesThePreviousBlockAsDictionary() throws Exception {
        byte[] unit = random(30000, 5);
        byte[] payload = new byte[4 * 32768];
        for (int i = 0; i < payload.length; i++) payload[i] = unit[i % unit.length];
        byte[] c = cab(new String[]{"a.dz"}, new byte[][]{payload}, 1);
        assertTrue("later blocks must have been squeezed through the dictionary: " + c.length, c.length < 38000);
        File cabFile = new File(tmp.getRoot(), "plain.cab");
        Files.write(cabFile.toPath(), c);
        try (LgCab.Src s = LgCab.plain(cabFile)) {
            LgCab.Cab cab = LgCab.parse(s);
            List<File> out = LgCab.extract(s, cab, tmp.newFolder("w4"), m -> true, x -> {});
            assertEquals(1, out.size());
            assertArrayEquals(payload, Files.readAllBytes(out.get(0).toPath()));
        }
    }

    @Test public void storedCabinetAndSeveralMembers() throws Exception {
        byte[] a = random(50000, 21), b = random(10, 22), empty = new byte[0];
        byte[] c = cab(new String[]{"dir\\a.bin", "b.txt", "empty.txt"}, new byte[][]{a, b, empty}, 0);
        File cabFile = new File(tmp.getRoot(), "stored.cab");
        Files.write(cabFile.toPath(), c);
        try (LgCab.Src s = LgCab.plain(cabFile)) {
            LgCab.Cab cab = LgCab.parse(s);
            assertEquals(3, cab.members.size());
            List<File> out = LgCab.extract(s, cab, tmp.newFolder("w5"), m -> true, x -> {});
            assertArrayEquals(a, Files.readAllBytes(out.get(0).toPath()));
            assertEquals("a.bin", out.get(0).getName());                  // path stripped: no escape from the work directory
            assertArrayEquals(b, Files.readAllBytes(out.get(1).toPath()));
            assertEquals(0, out.get(2).length());
        }
    }

    @Test public void lzxCabinetIsRefusedWithAClearMessage() throws Exception {
        byte[] c = cab(new String[]{"a.dz"}, new byte[][]{random(100, 1)}, 3);
        File f = kdz(c, "lzx.kdz");
        try {
            LgKdz.extractEncryptedKdz(f, tmp.newFolder("w6"), s -> {});
            fail("LZX must not be accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("LZX"));
        }
    }

    @Test public void decryptedCabinetIsAcceptedToo() throws Exception {
        Fw fw = new Fw(false);
        File f = new File(tmp.getRoot(), "fw.cab");
        Files.write(f.toPath(), cab(new String[]{"fw.dz"}, new byte[][]{fw.dz}, 1));
        assertEquals("LG_KDZ_CAB", FirmwareToolset.detect(f));
        List<Artifact> out = LgKdz.extractEncryptedKdz(f, tmp.newFolder("w7"), s -> {});
        assertArrayEquals(fw.boot, read(find(out, Role.BOOT)));
    }

    @Test public void bareLegacyDzIsRecognisedAndUnpacked() throws Exception {
        Fw fw = new Fw(false);
        File f = new File(tmp.getRoot(), "bare.dz");
        Files.write(f.toPath(), fw.dz);
        assertEquals("LG_DZ", FirmwareToolset.detect(f));
        List<Artifact> out = LgKdz.extractDz(f, tmp.newFolder("w8"), s -> {});
        assertArrayEquals(fw.system, read(find(out, Role.SYSTEM)));
    }

    @Test public void legacyDzWithoutSystemOrBootIsAnError() throws Exception {
        byte[] amss = random(1000, 1);
        File f = new File(tmp.getRoot(), "modemonly.dz");
        Files.write(f.toPath(), mstxDz(mstxEntry("amss.mbn", 3, amss.length, 0, amss, true)));
        try {
            LgKdz.extractDz(f, tmp.newFolder("w9"), s -> {});
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no system/boot/recovery"));
        }
    }

    // ------------------------------------------------------------------ detection

    @Test public void detectionOfEncryptedKdz() throws Exception {
        Fw fw = new Fw(false);
        File f = kdz(cab(new String[]{"fw.dz"}, new byte[][]{fw.dz}, 1), "named.kdz");
        byte[] head = Arrays.copyOf(Files.readAllBytes(f.toPath()), 4096);
        assertTrue(LgKdz.isEncryptedKdz(f));
        assertTrue(FirmwareToolset.isEncryptedKdz(head, f.length()));
        assertEquals("LG_KDZ_CAB", FirmwareToolset.detect(f));
        // the key depends on the size: the same bytes claimed to be a different length are not a KDZ
        assertFalse(FirmwareToolset.isEncryptedKdz(head, f.length() + 1));
    }

    @Test public void ordinaryFilesAreNotTakenForEncryptedKdz() throws Exception {
        for (long seed = 0; seed < 200; seed++) {
            byte[] noise = random(5000, seed);
            assertFalse(LgCab.isEncryptedKdz(noise, noise.length));
        }
        File notCab = new File(tmp.getRoot(), "x.kdz");
        Files.write(notCab.toPath(), random(3000, 9));
        assertFalse(LgKdz.isEncryptedKdz(notCab));
        assertFalse(LgKdz.isEncryptedKdz(new File(tmp.getRoot(), "missing")));
        assertFalse(LgCab.isEncryptedKdz(new byte[5], 5));
    }
}
