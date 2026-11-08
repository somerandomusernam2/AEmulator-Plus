/* Added for AEmulator Plus, 2026-11-06: old LG KDZ import (RC4 + CAB + legacy DZ). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import app.aemu.importer.tools.FirmwareToolset.Artifact;
import app.aemu.importer.tools.FirmwareToolset.Role;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * The ".dz" inside the RC4/CAB KDZ files of 2009-2010 LG phones (GW620, GT540, GM750 ... Qualcomm MSM72xx / MSM7x27).
 * It is not the chunked DZ of later phones ({@link LgKdz}); it starts with "MSTXMETX" and its layout was worked out
 * from a GW620 V10i package:
 *
 * <pre>
 * header   "MSTXMETX", u32 version (3), u32 entry count, FILETIME, model[8] @0x18, build @0x20, platform @0x70, ...
 *          (0x13C bytes; the first entry is found by its marker, so a different header length still works)
 * entry    "SSTX" u32 ?, u32 partition type @8, u32 0, u32 compressed size @0x10, name[128] @0x14,
 *          u32 total partition size @0x94, u32 chunk number @0x98, FF*8, md5(uncompressed chunk) @0xA4,
 *          second 16-byte hash @0xB4 (not used), then the gzip stream (header = 0xC4 bytes)
 * </pre>
 *
 * Entries follow one another; a partition too big for one entry is split into "System.mbn_0", "System.mbn_1" ...
 * (chunk numbers 0, 1, ...), and the chunks are simply concatenated. What the emulator needs is the system image
 * (a raw YAFFS2 dump with its 64-byte spare areas, which the importer's YAFFS2 reader takes as it is) and the
 * boot image ("zImage_Ramdisk.mbn", a normal ANDROID! image). The Qualcomm boot chain (amss, qcsbl, oemsbl,
 * appsboot, the *hd.mbn headers, partition.mbn) is skipped.
 */
final class LgLegacyDz {
    private LgLegacyDz() {}

    static final byte[] MAGIC = "MSTXMETX".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENTRY = "SSTX".getBytes(StandardCharsets.US_ASCII);
    private static final int ENTRY_HEADER = 0xC4;
    private static final long MAX_IMAGE = 64L << 30;
    private static final Pattern CHUNKED = Pattern.compile("^(.*\\.[A-Za-z0-9]+)_(\\d+)$");

    static boolean isMagic(byte[] h) {
        if (h == null || h.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (h[i] != MAGIC[i]) return false;
        return true;
    }

    static final class Entry {
        String name; long type, compSize, totalSize, chunk, dataPos; byte[] md5;
    }

    /** Walks the entry chain. */
    static List<Entry> readEntries(RandomAccessFile r, long base, long len) throws IOException {
        long end = base + len;
        byte[] head = LgKdz.readAt(r, base, (int) Math.min(len, 0x1000));
        long pos = -1;
        for (int i = 8; i + ENTRY.length <= head.length; i++) {
            if (matches(head, i, ENTRY)) { pos = base + i; break; }
        }
        if (pos < 0) throw new IOException("LG DZ (old): no partition entry found");
        List<Entry> list = new ArrayList<>();
        while (pos + ENTRY_HEADER <= end) {
            byte[] h = LgKdz.readAt(r, pos, ENTRY_HEADER);
            if (h.length < ENTRY_HEADER || !matches(h, 0, ENTRY)) break;           // the trailer after the last entry
            Entry e = new Entry();
            e.type = LgKdz.u32(h, 8);
            e.compSize = LgKdz.u32(h, 0x10);
            e.name = cstr(h, 0x14, 0x80);
            e.totalSize = LgKdz.u32(h, 0x94);
            e.chunk = LgKdz.u32(h, 0x98);
            e.md5 = Arrays.copyOfRange(h, 0xA4, 0xB4);
            e.dataPos = pos + ENTRY_HEADER;
            if (e.name == null || e.name.isEmpty() || e.compSize < 0 || e.dataPos + e.compSize > end) {
                if (list.isEmpty()) throw new IOException("LG DZ (old): damaged entry at 0x" + Long.toHexString(pos - base));
                break;
            }
            list.add(e);
            pos = e.dataPos + e.compSize;
        }
        if (list.isEmpty()) throw new IOException("LG DZ (old): no partition entries");
        return list;
    }

    static List<Artifact> extract(RandomAccessFile r, long base, long len, File work, Consumer<String> log) throws IOException {
        if (!work.exists() && !work.mkdirs()) throw new IOException("cannot create work directory");
        byte[] hd = LgKdz.readAt(r, base, (int) Math.min(len, 0x100));
        String model = cstr(hd, 0x18, 8), build = cstr(hd, 0x20, 16), platform = cstr(hd, 0x70, 8);
        List<Entry> entries = readEntries(r, base, len);

        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        StringBuilder names = new StringBuilder();
        for (Entry e : entries) {
            Matcher m = CHUNKED.matcher(e.name);
            groups.computeIfAbsent(m.matches() ? m.group(1) : e.name, k -> new ArrayList<>()).add(e);
            names.append(names.length() > 0 ? ", " : "").append(e.name);
        }
        log.accept("LG DZ (old, MSTX)" + (model != null && !model.isEmpty() ? " " + model : "") + (build != null && !build.isEmpty() ? " " + build : "")
            + (platform != null && !platform.isEmpty() ? " [" + platform + "]" : "") + ": " + entries.size() + " entries: " + names);

        List<Artifact> out = new ArrayList<>();
        IOException firstError = null;
        boolean sys = false, boot = false, rec = false;
        for (Map.Entry<String, List<Entry>> g : groups.entrySet()) {
            Role role = roleFor(g.getKey());
            if (role == null || (role == Role.SYSTEM && sys) || (role == Role.BOOT && boot) || (role == Role.RECOVERY && rec)) {
                log.accept("LG DZ (old): skipping " + g.getKey());
                continue;
            }
            String file = role == Role.SYSTEM ? "system.img" : role == Role.BOOT ? "boot.img" : "recovery.img";
            File dst = new File(work, file);
            List<Entry> parts = new ArrayList<>(g.getValue());
            if (parts.size() > 1) parts.sort((a, b) -> Long.compare(a.chunk, b.chunk));
            try {
                long total = assemble(r, parts, dst, log);
                out.add(new Artifact(dst, role));
                if (role == Role.SYSTEM) sys = true; else if (role == Role.BOOT) boot = true; else rec = true;
                log.accept("LG DZ (old): " + g.getKey() + " -> " + file + " (" + (total >> 20) + " MB" + (parts.size() > 1 ? ", " + parts.size() + " chunks" : "") + ")");
            } catch (IOException | RuntimeException ex) {
                dst.delete();
                log.accept("LG DZ (old): " + g.getKey() + " failed: " + ex.getMessage());
                if (firstError == null) firstError = ex instanceof IOException ? (IOException) ex : new IOException(ex);
            }
        }
        if (out.isEmpty()) {
            if (firstError != null) throw firstError;
            throw new IOException("LG DZ (old): no system/boot/recovery partition (found: " + names + ")");
        }
        return out;
    }

    /**
     * Only the images the emulator uses. Strict on purpose: "appsboot.mbn" (the Qualcomm application bootloader) must
     * not pass for a boot image, which {@code FirmwareToolset.roleFor} would do on the word "boot".
     */
    static Role roleFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.startsWith("system")) return Role.SYSTEM;
        if (n.startsWith("recovery")) return Role.RECOVERY;
        if (n.startsWith("zimage") || n.startsWith("boot") || n.startsWith("kernel")) return Role.BOOT;
        return null;
    }

    /** Concatenates the chunks of one partition, each gunzipped and checked against the MD5 in its header. */
    private static long assemble(RandomAccessFile r, List<Entry> parts, File dst, Consumer<String> log) throws IOException {
        long declared = parts.get(0).totalSize;
        if (declared > MAX_IMAGE) throw new IOException("image size implausible: " + declared);
        long total = 0;
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (java.io.OutputStream w = new java.io.BufferedOutputStream(new java.io.FileOutputStream(dst), 1 << 20)) {
            for (Entry e : parts) {
                MessageDigest md;
                try { md = MessageDigest.getInstance("MD5"); } catch (Exception ex) { throw new IOException(ex); }
                try (InputStream in = open(r, e)) {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    long got = 0;
                    while ((n = in.read(buf, 0, buf.length)) > 0) {
                        md.update(buf, 0, n);
                        w.write(buf, 0, n);
                        got += n;
                        if (total + got > MAX_IMAGE) throw new IOException("image size implausible");
                    }
                    total += got;
                }
                if (!Arrays.equals(md.digest(), e.md5)) log.accept("LG DZ (old): " + e.name + " MD5 mismatch (image may be corrupt)");
            }
        }
        if (declared != total) log.accept("LG DZ (old): " + parts.get(0).name + " expanded to " + total + " bytes, header says " + declared);
        return total;
    }

    private static InputStream open(RandomAccessFile r, Entry e) throws IOException {
        BufferedInputStream raw = new BufferedInputStream(new LgKdz.RangeIn(r, e.dataPos, e.compSize), 1 << 20);
        raw.mark(4);
        int b0 = raw.read(), b1 = raw.read();
        raw.reset();
        if (b0 == 0x1F && b1 == 0x8B) return new GZIPInputStream(raw, 1 << 16);
        throw new IOException("entry " + e.name + " is not gzip data");
    }

    private static boolean matches(byte[] b, int off, byte[] m) {
        if (off + m.length > b.length) return false;
        for (int i = 0; i < m.length; i++) if (b[off + i] != m[i]) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int max) {
        if (off >= b.length) return null;
        int e = off, lim = Math.min(b.length, off + max);
        while (e < lim && b[e] != 0) { int c = b[e] & 255; if (c < 0x20 || c > 0x7E) return null; e++; }
        return new String(b, off, e - off, StandardCharsets.US_ASCII);
    }
}
