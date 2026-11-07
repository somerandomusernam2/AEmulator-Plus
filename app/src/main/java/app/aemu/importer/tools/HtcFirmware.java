/* Modified for AEmulator Plus, 2026-10-14: RUU installers that hold rom.nbh instead of rom.zip (HTC Sapphire / Magic). 2026-10-16: RUUs whose images are split over rom.zip + rom1.zip ... GPL-3.0; see NOTICE.md. */
package app.aemu.importer.tools;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HTC firmware containers that carry boot / recovery / system images:
 *
 *  1. RUU installers (RUU_*.exe, "older" and "newer" alike). These are InstallShield 15-18 setups: the
 *     PE overlay holds a list of embedded files (data1.hdr, data1.cab, ...). data1.hdr describes the
 *     cabinet, data1.cab holds rom.zip (deflate chunks). rom.zip is streamed straight out of the
 *     cabinet, so no multi-hundred-megabyte temporary copy is needed. The oldest RUUs (Sapphire / Magic,
 *     Dream) carry a Dream-style rom.nbh there instead (see 3.); that one is spilled to a temporary file.
 *  2. rom.zip itself: HTC prepends a 256-byte RSA signature to the ZIP and to the *_signed.img files
 *     (boot_signed.img, recovery_signed.img on S-ON devices of the Sense 4 era).
 *  3. Dream-style NBH (DREAIMG.nbh, SAPPIMG.nbh ...): 256-byte signature, a header with a UTF-32LE
 *     "HTCIMAGE" tag, a table of section types and offsets, then the raw partition images.
 *
 * Output names are normalized to boot.img / recovery.img / system.img, which is what Importer expects.
 * Only the JDK is used so the class is unit-testable off-device and stays at Java 8 level.
 */
public final class HtcFirmware {
    private HtcFirmware() {}

    /** One extracted file. kind is "boot", "recovery", "system" or "archive". */
    public static final class Item {
        public final File file;
        public final String kind;
        Item(File file, String kind) { this.file = file; this.kind = kind; }
    }

    private static final int SIG = 256;                  // HTC RSA signature length
    private static final long MAX_HDR = 64L << 20;       // data1.hdr sanity limit
    private static final byte[] ANDROID = "ANDROID!".getBytes(StandardCharsets.US_ASCII);

    // ================================================================== sniffing

    public static boolean isMz(byte[] h) { return h.length >= 64 && h[0] == 'M' && h[1] == 'Z'; }

    /** "HTCIMAGE" as UTF-32LE (one wchar_t = 4 bytes per letter) right behind the 256-byte signature. */
    public static boolean isDreamNbh(byte[] h) {
        if (h.length < SIG + 32) return false;
        byte[] tag = "HTCIMAGE".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < tag.length; i++) {
            int o = SIG + i * 4;
            if (h[o] != tag[i] || h[o + 1] != 0 || h[o + 2] != 0 || h[o + 3] != 0) return false;
        }
        return true;
    }

    /** 256 signature bytes followed by a ZIP or an Android boot image (and not one of those at offset 0). */
    public static boolean isSignedWrapper(byte[] h) {
        if (h.length < SIG + 8) return false;
        if (startsAt(h, 0, ANDROID) || (h[0] == 'P' && h[1] == 'K')) return false;
        return startsAt(h, SIG, ANDROID) || (h[SIG] == 'P' && h[SIG + 1] == 'K' && h[SIG + 2] == 3 && h[SIG + 3] == 4);
    }

    /** Cheap first-bytes test used by the importer to route a file to the toolset. */
    public static boolean looksLikeContainer(byte[] h) { return isMz(h) || isDreamNbh(h) || isSignedWrapper(h); }

    /** Full test for an RUU installer: MZ plus a readable InstallShield overlay with data1.hdr + data1.cab. */
    public static boolean isRuuExe(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] mz = new byte[2];
            if (r.length() < 1024) return false;
            r.readFully(mz);
            if (mz[0] != 'M' || mz[1] != 'Z') return false;
            if (r.length() > (64L << 20) && looksInno(r)) return true;
            List<OvFile> files = readOverlay(r);
            return find(files, "data1.hdr") != null && find(files, "data1.cab") != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ================================================================== RUU installer

    public static List<Item> extractRuu(File exe, File work, Consumer<String> log) throws IOException {
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("cannot create " + work);
        try (RandomAccessFile r = new RandomAccessFile(exe, "r")) {
            // Newer RUUs (HTC One M7 on Sense 6, ...) are Inno Setup installers, not InstallShield ones
            if (r.length() > (64L << 20) && looksInno(r)) return extractInnoRuu(r, work, log);
            List<OvFile> files = readOverlay(r);
            OvFile hdr = find(files, "data1.hdr");
            OvFile cab = find(files, "data1.cab");
            if (hdr == null || cab == null) throw new IOException("not an InstallShield RUU (no data1.hdr / data1.cab)");
            if (hdr.size <= 0 || hdr.size > MAX_HDR) throw new IOException("implausible data1.hdr size");
            byte[] hb = new byte[(int) hdr.size];
            r.seek(hdr.offset);
            r.readFully(hb);
            IsCabinet cabinet = new IsCabinet(hb);
            log.accept("RUU: InstallShield " + cabinet.major + " cabinet, " + cabinet.files.size() + " files");

            List<IsFile> roms = cabinet.romZips();
            if (roms.isEmpty()) {
                // Dream / Magic / Hero era RUUs (SAPPIMG, DREAIMG ...) ship a Dream-style rom.nbh instead of rom.zip
                IsFile nbh = cabinet.find("rom.nbh");
                if (nbh == null) nbh = cabinet.largestWithExt(".nbh");
                if (nbh != null) return extractRuuNbh(r, cab, nbh, work, log);
                throw new IOException("RUU: no rom.zip or rom.nbh inside the installer");
            }

            // Newer Sapphire / Magic RUUs split the firmware over several archives: rom.zip carries only
            // hboot / radio / boot_special.img, and boot / recovery / system sit in rom1.zip (rom2.zip ...).
            // Walk them in order and merge, stopping as soon as boot, recovery and system were all found.
            List<Item> out = new ArrayList<>();
            Set<String> got = new HashSet<>();
            IOException last = null;
            for (IsFile rom : roms) {
                if (got.size() == 3) break;
                log.accept("RUU: " + rom.name + " " + (rom.usize >> 20) + " MB");
                try (InputStream raw = new IsFileStream(r, cab.offset, cab.size, rom)) {
                    extractRomZip(raw, work, log, got, out);
                } catch (IOException e) {
                    // one unreadable archive is not fatal while another one may still deliver the images
                    last = e;
                    log.accept("RUU: " + rom.name + ": " + e.getMessage());
                }
            }
            if (out.isEmpty()) {
                if (last != null) throw last;
                throw new IOException("RUU: none of " + roms.size() + " firmware archive(s) holds a boot, recovery or system image");
            }
            return out;
        }
    }

    /**
     * rom.nbh of an old RUU: the NBH reader needs random access, so the file is first streamed out of the
     * cabinet into a temporary file (about 100 MB), unpacked, and the copy is removed again.
     */
    private static List<Item> extractRuuNbh(RandomAccessFile r, OvFile cab, IsFile nbh, File work, Consumer<String> log) throws IOException {
        log.accept("RUU: " + nbh.name + " " + (nbh.usize >> 20) + " MB (Dream-style NBH)");
        File tmp = new File(work, "ruu-rom.nbh.tmp");
        try {
            try (InputStream raw = new IsFileStream(r, cab.offset, cab.size, nbh);
                 OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int k;
                while ((k = raw.read(buf)) > 0) os.write(buf, 0, k);
            }
            return extractDreamNbh(tmp, work, log);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Streams a (possibly 256-byte-signed) rom.zip and keeps only boot / recovery / system.
     * Stops reading as soon as all three were found.
     */
    static List<Item> extractRomZip(InputStream raw, File work, Consumer<String> log) throws IOException {
        List<Item> out = new ArrayList<>();
        extractRomZip(raw, work, log, new HashSet<>(), out);
        if (out.isEmpty()) throw new IOException("rom.zip holds no boot, recovery or system image");
        return out;
    }

    /**
     * Same, but adds to [out] / [got]: kinds that are already in [got] (found in an earlier archive of the
     * same RUU) are skipped. An archive without any of them is not an error here; the caller decides.
     */
    static void extractRomZip(InputStream raw, File work, Consumer<String> log, Set<String> got, List<Item> out) throws IOException {
        extractRomZip(raw, work, log, got, out, null);
    }

    /** system_1.img, system_2.img ... of a Large Zip RUU: raw pieces of one image (800 MiB each), joined by the caller. */
    private static final java.util.regex.Pattern SYSTEM_PIECE = java.util.regex.Pattern.compile("^system_(\\d+)(?:_signed)?\\.img$");

    /** [pieces] (nullable) collects system_N.img files of a split system image, keyed by N. */
    static void extractRomZip(InputStream raw, File work, Consumer<String> log, Set<String> got, List<Item> out,
                              SortedMap<Integer, File> pieces) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(raw, 1 << 16), SIG + 8);
        byte[] head = new byte[SIG + 4];
        int n = readUpTo(pb, head);
        boolean plainZip = n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
        boolean signedZip = n >= SIG + 4 && head[SIG] == 'P' && head[SIG + 1] == 'K' && head[SIG + 2] == 3 && head[SIG + 3] == 4;
        if (!plainZip && !signedZip) throw new IOException("rom.zip: not a ZIP (neither at offset 0 nor behind a 256-byte signature)");
        pb.unread(head, signedZip && !plainZip ? SIG : 0, n - (signedZip && !plainZip ? SIG : 0));

        try (ZipInputStream zin = new ZipInputStream(pb)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String base = baseName(e.getName()).toLowerCase(Locale.US);
                if (base.equals("android-info.txt")) {
                    String info = new String(readBounded(zin, 4096), StandardCharsets.ISO_8859_1).replace('\r', ' ').replace('\n', ' ');
                    log.accept("RUU: android-info: " + info.trim());
                    continue;
                }
                String kind = kindOfName(base);
                if (kind == null && pieces != null && !got.contains("system")) {
                    java.util.regex.Matcher pm = SYSTEM_PIECE.matcher(base);
                    if (pm.matches() && !pieces.containsKey(Integer.parseInt(pm.group(1)))) {
                        int idx = Integer.parseInt(pm.group(1));
                        File pf = new File(work, "system.part" + idx);
                        copyImage(zin, pf, "system");
                        pieces.put(idx, pf);
                        log.accept("RUU: " + e.getName() + " -> " + pf.getName() + " (" + pf.length() + " bytes)");
                    }
                    continue;
                }
                if (kind == null || got.contains(kind)) continue;
                File dst = new File(work, kind + ".img");
                copyImage(zin, dst, kind);
                got.add(kind);
                out.add(new Item(dst, kind));
                log.accept("RUU: " + e.getName() + " -> " + dst.getName() + " (" + dst.length() + " bytes)");
                if (got.size() == 3) break;
            }
        }
    }

    /** boot / recovery / system, plain or signed, with or without the .img suffix. */
    static String kindOfName(String lowerBase) {
        switch (lowerBase) {
            case "boot.img": case "boot_signed.img": case "boot_signed": return "boot";
            case "recovery.img": case "recovery_signed.img": case "recovery_signed": return "recovery";
            case "system.img": case "system_signed.img": case "system_signed": return "system";
            default: return null;
        }
    }

    /** Copies one image; a 256-byte HTC signature in front of an ANDROID! image is dropped. */
    private static void copyImage(InputStream in, File dst, String kind) throws IOException {
        byte[] head = new byte[SIG + 8];
        int n = readUpTo(in, head);
        int skip = 0;
        if (!kind.equals("system") && n >= SIG + 8 && !startsAt(head, 0, ANDROID) && startsAt(head, SIG, ANDROID)) skip = SIG;
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16)) {
            os.write(head, skip, n - skip);
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
        }
    }

    // ------------------------------------------------------------------ PE overlay (embedded file list)

    static final class OvFile {
        final String name; final long offset; final long size;
        OvFile(String n, long o, long s) { name = n; offset = o; size = s; }
    }

    static OvFile find(List<OvFile> l, String name) {
        for (OvFile f : l) if (f.name.equalsIgnoreCase(name)) return f;
        return null;
    }

    /** First byte behind the last PE section. */
    static long overlayStart(RandomAccessFile r) throws IOException {
        byte[] dos = new byte[64];
        r.seek(0);
        r.readFully(dos);
        long pe = u32(dos, 0x3c);
        if (pe < 64 || pe > r.length() - 256) throw new IOException("not a PE file");
        byte[] nt = new byte[24];
        r.seek(pe);
        r.readFully(nt);
        if (nt[0] != 'P' || nt[1] != 'E') throw new IOException("bad PE signature");
        int sections = (nt[6] & 255) | ((nt[7] & 255) << 8);
        int opt = (nt[20] & 255) | ((nt[21] & 255) << 8);
        if (sections == 0 || sections > 96) throw new IOException("bad PE section count");
        long end = 0;
        byte[] sh = new byte[40];
        for (int i = 0; i < sections; i++) {
            r.seek(pe + 24 + opt + i * 40L);
            r.readFully(sh);
            long rawSize = u32(sh, 16), rawPtr = u32(sh, 20);
            if (rawPtr + rawSize > end) end = rawPtr + rawSize;
        }
        if (end <= 0 || end >= r.length()) throw new IOException("no overlay behind the PE sections");
        return end;
    }

    /**
     * InstallShield wrapper records: name, path, version, decimal size (four NUL-terminated strings),
     * then size bytes of payload. Older setups use 8-bit text; newer ones UTF-16LE, with a 4-byte file
     * count in front of the first record. The encoding is chosen by which parse finds data1.hdr + data1.cab.
     */
    static List<OvFile> readOverlay(RandomAccessFile r) throws IOException {
        long start = overlayStart(r);
        // 8-bit text; UTF-16 behind a 4-byte file count; UTF-16 without it
        long[] at = {start, start + 4, start};
        boolean[] wide = {false, true, true};
        for (int i = 0; i < at.length; i++) {
            List<OvFile> l = parseRecords(r, at[i], wide[i]);
            if (find(l, "data1.hdr") != null && find(l, "data1.cab") != null) return l;
        }
        return new ArrayList<>();
    }

    private static List<OvFile> parseRecords(RandomAccessFile r, long p, boolean wide) throws IOException {
        List<OvFile> out = new ArrayList<>();
        long len = r.length();
        byte[] buf = new byte[2048];
        while (p < len && out.size() < 4096) {
            int n = (int) Math.min(buf.length, len - p);
            r.seek(p);
            r.readFully(buf, 0, n);
            String[] f = new String[4];
            int q = 0;
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                int e = q;
                if (wide) { while (e + 1 < n && !(buf[e] == 0 && buf[e + 1] == 0)) e += 2; ok = e + 1 < n; }
                else { while (e < n && buf[e] != 0) e++; ok = e < n; }
                if (!ok || e - q > 600) { ok = false; break; }
                f[k] = new String(buf, q, e - q, wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
                q = e + (wide ? 2 : 1);
            }
            if (!ok || f[3].isEmpty() || f[3].length() > 12 || !f[3].matches("[0-9]+")) break;
            long size = Long.parseLong(f[3]);
            long dataOff = p + q;
            if (dataOff + size > len) break;
            String path = f[1].replace('\\', '/');
            String base = path.substring(path.lastIndexOf('/') + 1);
            out.add(new OvFile(base.isEmpty() ? f[0] : base, dataOff, size));
            p = dataOff + size;
        }
        return out;
    }

    // ------------------------------------------------------------------ InstallShield cabinet (data1.hdr)

    static final class IsFile {
        String name = ""; int flags; long usize, csize, doff; int dirIndex;
    }

    /**
     * Reader for the header file of an InstallShield cabinet, version 6 and newer (IS 6 ... 2012+).
     * Layout (all little endian), relative to cab_descriptor_offset (CD):
     *   CD+0x0c file table offset, CD+0x14 table size, CD+0x1c directory count,
     *   CD+0x28 file count, CD+0x2c offset of the descriptors inside the file table.
     * A file descriptor is 0x57 bytes: flags(2) usize(4) -(4) csize(4) -(4) data offset(4) -(4)
     * md5(16) -(16) name offset(4) directory(2) ...
     * Names are UTF-16 from major version 17 on.
     */
    static final class IsCabinet {
        final byte[] h; final int major; final boolean wide; final List<IsFile> files = new ArrayList<>();

        IsCabinet(byte[] h) throws IOException {
            this.h = h;
            if (h.length < 0x30 || u32(h, 0) != 0x28635349L) throw new IOException("not an InstallShield header (ISc( expected)");
            long ver = u32(h, 4);
            long top = ver >> 24;
            int m;
            if (top == 1) m = (int) ((ver >> 12) & 0xf);
            else if (top == 2 || top == 4) { m = (int) (ver & 0xffff); if (m != 0) m /= 100; }
            else throw new IOException("unknown InstallShield version 0x" + Long.toHexString(ver));
            if (m < 6) throw new IOException("InstallShield " + m + " cabinets (<= 5) are not supported");
            major = m;
            wide = m >= 17;
            try {
                long cd = u32(h, 12);
                long tableOff = u32(h, (int) (cd + 0x0c));
                long dirCount = u32(h, (int) (cd + 0x1c));
                long fileCount = u32(h, (int) (cd + 0x28));
                long descOff = u32(h, (int) (cd + 0x2c));
                long base = cd + tableOff;
                if (fileCount > 200_000 || dirCount > 100_000 || base + descOff + fileCount * 0x57 > h.length)
                    throw new IOException("corrupt InstallShield file table");
                for (long i = 0; i < fileCount; i++) {
                    int p = (int) (base + descOff + i * 0x57);
                    IsFile f = new IsFile();
                    f.flags = (int) (u32(h, p) & 0xffff);
                    f.usize = u32(h, p + 2);
                    f.csize = u32(h, p + 10);
                    f.doff = u32(h, p + 18);
                    f.name = nameAt(base + u32(h, p + 0x3a));
                    f.dirIndex = (int) (u32(h, p + 0x3e) & 0xffff);
                    files.add(f);
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("corrupt InstallShield header: " + e.getMessage(), e);
            }
        }

        private String nameAt(long o) {
            if (o < 0 || o >= h.length) return "";
            int s = (int) o, e = s;
            if (wide) { while (e + 1 < h.length && !(h[e] == 0 && h[e + 1] == 0)) e += 2; return new String(h, s, e - s, StandardCharsets.UTF_16LE); }
            while (e < h.length && h[e] != 0) e++;
            return new String(h, s, e - s, StandardCharsets.ISO_8859_1);
        }

        IsFile find(String name) {
            for (IsFile f : files) if (f.name.equalsIgnoreCase(name)) return f;
            return null;
        }

        /**
         * The firmware archives of an RUU in flashing order: rom.zip, rom1.zip, rom2.zip ... (a name without a
         * number sorts first). Falls back to the largest .zip when nothing is called rom*.zip.
         */
        List<IsFile> romZips() {
            List<IsFile> l = new ArrayList<>();
            for (IsFile f : files) if (f.name.toLowerCase(Locale.US).matches("rom\\d*\\.zip")) l.add(f);
            if (l.isEmpty()) {
                IsFile big = largestWithExt(".zip");
                if (big != null) l.add(big);
                return l;
            }
            l.sort((a, b) -> Integer.compare(romZipIndex(a.name), romZipIndex(b.name)));
            return l;
        }

        private static int romZipIndex(String n) {
            String d = n.toLowerCase(Locale.US).replaceAll("[^0-9]", "");
            return d.isEmpty() ? -1 : Integer.parseInt(d);
        }

        IsFile largestWithExt(String ext) {
            IsFile best = null;
            for (IsFile f : files) if (f.name.toLowerCase(Locale.US).endsWith(ext) && (best == null || f.usize > best.usize)) best = f;
            return best;
        }
    }

    /**
     * Decompressed bytes of one file in data1.cab. A compressed file (flag 0x04) is a run of
     * [uint16 length][raw deflate data] blocks, each inflating to at most 64 KB.
     */
    static final class IsFileStream extends InputStream {
        private final RandomAccessFile raf; private final long cabEnd;
        private long pos; private long compLeft; private long outLeft;
        private final boolean compressed;
        private final Inflater inf = new Inflater(true);
        private byte[] in = new byte[70000]; private byte[] out = new byte[1 << 16];
        private int outPos, outLen;

        IsFileStream(RandomAccessFile raf, long cabOffset, long cabSize, IsFile f) throws IOException {
            this.raf = raf;
            this.cabEnd = cabOffset + cabSize;
            if ((f.flags & 1) != 0) throw new IOException(f.name + ": split across cabinets, not supported");
            if ((f.flags & 2) != 0) throw new IOException(f.name + ": obfuscated cabinet entry, not supported");
            this.compressed = (f.flags & 4) != 0;
            this.pos = cabOffset + f.doff;
            this.compLeft = compressed ? f.csize : f.usize;
            this.outLeft = f.usize;
            if (pos + compLeft > cabEnd) throw new IOException(f.name + ": data runs past the end of data1.cab");
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) <= 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (outLeft <= 0) return -1;
            while (outPos >= outLen) {
                if (!fill()) return -1;
            }
            int k = (int) Math.min(Math.min(len, outLen - outPos), outLeft);
            System.arraycopy(out, outPos, b, off, k);
            outPos += k;
            outLeft -= k;
            return k;
        }

        private boolean fill() throws IOException {
            outPos = outLen = 0;
            if (compLeft <= 0) return false;
            if (!compressed) {
                int n = (int) Math.min(out.length, compLeft);
                raf.seek(pos);
                raf.readFully(out, 0, n);
                pos += n; compLeft -= n; outLen = n;
                return true;
            }
            if (compLeft < 2) throw new IOException("truncated cabinet block");
            raf.seek(pos);
            int n = raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
            pos += 2; compLeft -= 2;
            if (n > compLeft) throw new IOException("cabinet block longer than the file");
            if (in.length < n + 4) in = new byte[n + 4];
            raf.readFully(in, 0, n);
            pos += n; compLeft -= n;
            inf.reset();
            inf.setInput(in, 0, n);
            boolean padded = false;
            try {
                while (true) {
                    if (outLen == out.length) out = Arrays.copyOf(out, out.length * 2);
                    int r = inf.inflate(out, outLen, out.length - outLen);
                    if (r > 0) { outLen += r; continue; }
                    if (inf.finished()) break;
                    if (inf.needsDictionary()) throw new IOException("cabinet block needs a dictionary");
                    if (inf.needsInput()) {
                        if (padded) break;
                        // some writers omit the final sync marker of a block
                        in[n] = 0; in[n + 1] = 0; in[n + 2] = (byte) 0xff; in[n + 3] = (byte) 0xff;
                        inf.setInput(in, n, 4);
                        padded = true;
                    }
                }
            } catch (DataFormatException e) {
                throw new IOException("corrupt deflate data in cabinet: " + e.getMessage(), e);
            }
            return outLen > 0 || compLeft > 0;
        }

        @Override public void close() { inf.end(); }
    }

    // ================================================================== signed wrappers

    /** Drops the 256-byte signature of a signed rom.zip or *_signed.img. */
    public static List<Item> unwrapSigned(File f, File work, Consumer<String> log) throws IOException {
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("cannot create " + work);
        byte[] head = new byte[SIG + 8];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n = readUpTo(in, head);
            if (n < SIG + 4) throw new IOException("file too short");
        }
        boolean zip = head[SIG] == 'P' && head[SIG + 1] == 'K';
        String lower = f.getName().toLowerCase(Locale.US);
        String kind = zip ? "archive" : lower.contains("recovery") ? "recovery" : "boot";
        File dst = new File(work, zip ? "rom.zip" : kind + ".img");
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(f), 1 << 16);
             OutputStream os = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16)) {
            skipFully(in, SIG);
            byte[] buf = new byte[1 << 16];
            int k;
            while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
        }
        log.accept("HTC: removed 256-byte signature from " + f.getName() + " -> " + dst.getName());
        List<Item> l = new ArrayList<>();
        l.add(new Item(dst, kind));
        return l;
    }

    // ================================================================== Dream-style NBH

    /**
     * Layout (verified on DREAIMG.nbh RC29):
     *   0x000 256-byte RSA signature
     *   0x100 "HTCIMAGE" as UTF-32LE, 0x120 model id (ASCII, e.g. DREA10000)
     *   0x140 section types (uint32 each, 0 ends the list), 0x1c0 section offsets (uint32 each)
     * A section's payload starts 0x100 bytes after its table offset and runs up to the next section's
     * payload; the last one runs to the end of the file. (Boot and recovery spans equal the size declared in
     * their ANDROID! headers, SPL is 512 KiB, system and userdata are whole 2048+64 YAFFS2 chunks.)
     * Types: 0x200 SPL, 0xB05 recovery, 0xB04 boot, 0x600 splash, 0xB02 system, 0xB06 userdata, 0x301 radio.
     */
    public static List<Item> extractDreamNbh(File f, File work, Consumer<String> log) throws IOException {
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("cannot create " + work);
        List<Item> out = new ArrayList<>();
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long len = r.length();
            byte[] hd = new byte[0x200];
            if (len < hd.length) throw new IOException("truncated NBH");
            r.readFully(hd);
            if (!isDreamNbh(hd)) throw new IOException("not a Dream-style NBH");
            int count = 0;
            long[] type = new long[16], off = new long[16];
            while (count < 16) {
                type[count] = u32(hd, 0x140 + count * 4);
                off[count] = u32(hd, 0x1c0 + count * 4);
                if (type[count] == 0 || off[count] == 0) break;
                count++;
            }
            if (count == 0) throw new IOException("NBH has no sections");
            int e = 0x120;
            while (e < 0x140 && hd[e] != 0) e++;
            log.accept("NBH: model " + new String(hd, 0x120, e - 0x120, StandardCharsets.US_ASCII) + ", " + count + " sections");

            // payload bias: 0x100 normally; fall back to 0 if the boot header sits right at the table offset
            long bias = 0x100;
            for (int i = 0; i < count; i++) {
                if (type[i] != 0xB04) continue;
                if (!(off[i] + 0x100 + 8 <= len && startsAt(readAt(r, off[i] + 0x100, 8), 0, ANDROID))
                        && off[i] + 8 <= len && startsAt(readAt(r, off[i], 8), 0, ANDROID)) bias = 0;
            }
            for (int i = 0; i < count; i++) {
                long s = off[i] + bias, t = i + 1 < count ? off[i + 1] + bias : len;
                if (s >= t || t > len) throw new IOException("NBH section " + i + " out of range (corrupt or truncated file)");
                String kind = type[i] == 0xB04 ? "boot" : type[i] == 0xB05 ? "recovery" : type[i] == 0xB02 ? "system" : null;
                if (kind == null) { log.accept("NBH: skipping section 0x" + Long.toHexString(type[i]) + " (" + (t - s) + " bytes)"); continue; }
                long size = t - s;
                if (!kind.equals("system")) {
                    byte[] bh = readAt(r, s, 48);
                    if (!startsAt(bh, 0, ANDROID)) throw new IOException("NBH " + kind + " section has no ANDROID! header");
                    long declared = androidImageSize(bh);
                    if (declared > 0 && declared <= size) size = declared;
                }
                File dst = new File(work, kind + ".img");
                r.seek(s);
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(dst), 1 << 16)) {
                    byte[] buf = new byte[1 << 16];
                    long left = size;
                    while (left > 0) {
                        int k = r.read(buf, 0, (int) Math.min(buf.length, left));
                        if (k <= 0) throw new IOException("unexpected end of NBH");
                        os.write(buf, 0, k);
                        left -= k;
                    }
                }
                out.add(new Item(dst, kind));
                log.accept("NBH: section 0x" + Long.toHexString(type[i]) + " -> " + dst.getName() + " (" + size + " bytes)");
            }
        }
        if (out.isEmpty()) throw new IOException("NBH holds no boot, recovery or system section");
        return out;
    }

    /** Page-aligned size of a boot image from its header, or -1. */
    static long androidImageSize(byte[] b) {
        if (b.length < 40 || !startsAt(b, 0, ANDROID)) return -1;
        long k = u32(b, 8), rd = u32(b, 16), sec = u32(b, 24), page = u32(b, 36);
        if (page < 512 || page > 65536) return -1;
        return page + align(k, page) + align(rd, page) + align(sec, page);
    }

    private static long align(long v, long a) { return (v + a - 1) / a * a; }

    // ================================================================== small helpers

    private static long u32(byte[] b, int o) {
        if (o < 0 || o + 4 > b.length) throw new IllegalArgumentException("read past end of header");
        return (b[o] & 255L) | ((b[o + 1] & 255L) << 8) | ((b[o + 2] & 255L) << 16) | ((b[o + 3] & 255L) << 24);
    }

    private static boolean startsAt(byte[] b, int off, byte[] p) {
        if (off < 0 || b.length < off + p.length) return false;
        for (int i = 0; i < p.length; i++) if (b[off + i] != p[i]) return false;
        return true;
    }

    private static String baseName(String n) {
        n = n.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }

    private static byte[] readAt(RandomAccessFile r, long pos, int n) throws IOException {
        byte[] b = new byte[n];
        r.seek(pos);
        r.readFully(b);
        return b;
    }

    private static int readUpTo(InputStream in, byte[] b) throws IOException {
        int o = 0, k;
        while (o < b.length && (k = in.read(b, o, b.length - o)) > 0) o += k;
        return o;
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        byte[] b = new byte[max];
        int n = readUpTo(in, b);
        return Arrays.copyOf(b, n);
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        while (n > 0) {
            long k = in.skip(n);
            if (k <= 0) { if (in.read() < 0) throw new IOException("unexpected end of file"); k = 1; }
            n -= k;
        }
    }


    // ================================================================== Inno Setup RUU (HTC One M7 Sense 6 ...)

    /*
     * The installer is an Inno Setup 5.x setup whose only payload is HTC's rom.zip, stored (not compressed) in one
     * Inno data chunk ("zlb\x1a" + data). That rom.zip is a "Large Zip":
     *   0x000  "LaR@eZip", u32 starts[8], u32 lengths[8], u32 count, u32 unknown, padding up to 0x100
     *   0x100  the archives; archive i begins at 0x100 + starts[i] (little endian), lengths[i] bytes long
     * Each archive is a 256-byte signature, an 0x80-byte header "Htc@egi$" (u16 key map index, big endian; u8 chunk
     * count; the build number) and a ZIP whose first 1 MiB of every (chunks << 20) bytes is AES-128-CBC encrypted
     * (the IV restarts with every chunk). The key and IV are picked out of a 96-byte per-device key block by the key
     * map entry of the header. Format and tables: ruuveal (github.com/kmdm/ruuveal), GPL-3.0.
     */

    private static final byte[] INNO_TAG = "Inno Setup Setup Data".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LARGEZIP = "LaR@eZip".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTCZIP = "Htc@egi$".getBytes(StandardCharsets.US_ASCII);
    private static final int LZ_HEADER = 0x100, HZ_HEADER = 0x80, AES_READ = 0x8000, AES_WINDOW = 0x8000 * 0x20, KEYDATA_LEN = 96;

    // 5952 bytes
    private static final String KEYDATA_HEX =
        "06d83125fe4818109d8651cf684eb9984a34a246428b275b5886bd45d59d0be0b332fc3d537512a26e85fdd14ed86c8d" +
        "3edc235b3fde4d86fc2c1465007670e0277a3c3152e18fe85f6349325932ec5aeb96ae901bd4ccea355520a80404f6bc" +
        "854cb00a5b7e421d0de7c88521088ff1d82c881c56f891fc0442f63d332738ed02570d7981b4a7d8161c39062082b8b4" +
        "db44df3e84b875ddbd08d21e3241a0927da6dfc7474a2b78b0de4201f1c7c11540742eb3f5ee3b56eab0496d27ba2c0a" +
        "b91333ff04eac92806a68a46cf2e9293f8bff466ce87fe66b46fbff7c238c7f9b2c92eb2fdc7d8084167f6f4e680d033" +
        "01e5c33f78f1857d6863b1b8c36c7fce3e07d2a96a8979712079ae0023a1e3970165bfcf3a5463d2a4fdf1ca0e044f9a" +
        "b50def7d6468e8b39e6815de4440715e49e5ad6f0811254ddb8e129de9b101083f25842923e927a9a49ca5a876f95c5f" +
        "fd9eff04a8118bee80047cfa01d8402d2861ec9bcde92e87337782cbde51a97ba4bf58c507122d1bc2e5e695a2a3d590" +
        "9fec36b93b0275b945093537d28f5ecb289189f8d33db90bf6fc4546e0b0193edf3a1f825940f77d182dee9ee6792eb6" +
        "5a2a32a1f19b7d28607c8c26291a09ea6de041dd011ed864c40abb082f0557d52d1f09ff2ddcd5cc76395aadbbea05ae" +
        "29550d9c783d1877345ef171fa314553fc74e47caf1517e0c3513551581d71fe6fc5f6399ed3367d09327eaf8d93cea4" +
        "3e10e9e4140fa8ade16a933b86cdb247675fa1ce1ca4e3136aa278e77142b22826fa448804a99c8fffc273e4baada2c8" +
        "9fec36b93b0275b945093537d28f5ecb289189f8d33db90bf6fc4546e0b0193edf3a1f825940f77d182dee9ee6792eb6" +
        "5a2a32a1f19b7d28607c8c26291a09ea6de041dd011ed864c40abb082f0557d52d1f09ff2ddcd5cc76395aadbbea05ae" +
        "338233790addc032fee734630255bb97442c971bb335c30dbfaad6b65f36291a591d3cccbeb65607b3226cfb77384465" +
        "a2bfdb62abe00385adda50276bf835fe91c16bf23d6e922bbda11b6f9b5365abd60e26cf735d0ddf3482a647a8667d70" +
        "338233790addc032fee734630255bb97442c971bb335c30dbfaad6b65f36291a591d3cccbeb65607b3226cfb77384465" +
        "a2bfdb62abe00385adda50276bf835fe91c16bf23d6e922bbda11b6f9b5365abd60e26cf735d0ddf3482a647a8667d70" +
        "338233790addc032fee734630255bb97442c971bb335c30dbfaad6b65f36291a591d3cccbeb65607b3226cfb77384465" +
        "a2bfdb62abe00385adda50276bf835fe91c16bf23d6e922bbda11b6f9b5365abd60e26cf735d0ddf3482a647a8667d70" +
        "cf715c5998ebad63c0ded623107a946fd4731fb498b10a4530486af3fbaa3ade681cfe8bb866a73606cfb7698548a5cd" +
        "0ae88341ca98de296410e6082202ce527b94767bdd144854aae7609781c3efc0be1fe11f44e7bd2c7a73cd1a93ba80af" +
        "583682db45162951e37d6f4ad35623a980342031af947dea576203c7b2bee48594ffa9d6fcf86dbfe87b493f6e1c0f31" +
        "09373b73313526482e0a7dfb2d02f2c694be31f19379163efb911d8554421a9c2ab42a79a738e99b67cee5a687ec534d" +
        "0da390afce6867f4dfd679eaba502dab215f74b2b1faff41ba05b0ff4e174ee698807c5c6f8beca02b00d068b8ed91c9" +
        "e9a6c4560e0b5a8cc809166e1c64456cc7b3a989d1acb6bd65a10198e04bf52928c7c71e3fdead2c18894bb2a9b46e99" +
        "9ce66500598163e1697552e90f9021613a8fe31a4ed7bdb9276603535c089e3ac0ceb1162fbc7c3787c28029f4eee389" +
        "71df17188b7db70e68e7b1b109adaa25cc409e8635a6c734ed72c16e0bf4eb2393ad10043c0547de77411727acafdfd7" +
        "0ccada01fb70d9729515c9409326ba49279a106e826d2ba299504740299fa0a2f893daef5fb9a13405a28db7eddc4888" +
        "b322f25812d0c48eaa9af498007cd97fc7f450bf5e705e5599021719c45bbe4ee5f72d784295776d23eacdafd9969ef9" +
        "4f9b802915c9afa8ad014c45bc5fd02033234a9f2e065f631dcf8f7b2121bad7b997e2ff9d133205ea523fb780c48f1a" +
        "a89b5814fb2289697f510ee9129e50e70c15fcec20ab06ad83d0ed3b5043407a2c2a61d42662892ee0140c1bf489df50" +
        "d20bc70dbc5601459d5e3027efbc57348314575adbec706bd4be05ce874b9f757233350ed3c3ab7e550e988bee3f4cd8" +
        "082d1217df30b27a2a343a6afd0bfe7cee08dea177c9c79d9b547dbacc6697bd0d07cb1954626b6332d43006b5433738" +
        "60ec11b638544cea2c08c219346e4c81245f725edd893c69c012469f697e134399d3a32673fb4826ec69b5f57d00581c" +
        "1a30ea3f686244a22b9674ecfd4a102b469eca1968b7459e3a612795b8f870d3f9880a656133a01a17d3b442b9e1ecb4" +
        "60ec11b638544cea2c08c219346e4c81245f725edd893c69c012469f697e134399d3a32673fb4826ec69b5f57d00581c" +
        "1a30ea3f686244a22b9674ecfd4a102b469eca1968b7459e3a612795b8f870d3f9880a656133a01a17d3b442b9e1ecb4" +
        "3f301d8861787ff69e6dd5903b95a29fee1d6c5f93bf120320e960fadb1be171cb90f4196c22222aa768afa800216fe5" +
        "12d5db4738b9644b8ed408ca1860227b407386ba86decd877355eed2ced10ff613339a83212a92403356287011ae8955" +
        "4565159f28c275543424171ee548976c7b59561dad7351fdacdb4741b74b00fcb0169cd8d8112d0c35442a1a8cc18607" +
        "1add24c75075c5fc500c3d08573e040754a0e02cb10d39e6516301dd2587e43f640807b47dccb1ced8eed6302cda3780" +
        "bf41fa837acb694f07920eb61ac569ff689aeea517ee1413732ca9ace26f680e1aebcd989189282a35d1f12b5e0d5bd1" +
        "01ab2b9c26e4cb249c9b9a15eb8887788a95de34528861d433e39b8a2608717462a1fa1fd33f914ac84128ececf8df78" +
        "bf41fa837acb694f07920eb61ac569ff689aeea517ee1413732ca9ace26f680e1aebcd989189282a35d1f12b5e0d5bd1" +
        "01ab2b9c26e4cb249c9b9a15eb8887788a95de34528861d433e39b8a2608717462a1fa1fd33f914ac84128ececf8df78" +
        "cf8aa22e0251738fa457de14fcd50bdb01fb36bc7988f87e5e99c84a85bec15f8253a608c60e23cb0ad455e4f1d18df2" +
        "e8c751ef51a6a465e25e88df47f1c09e0fccda8ec5576d795dcba079d344ebb30e1722420a65af7236f01e2f1b2ee0bd" +
        "188dcf984516492db57659ae68fe1f81b9eded8990d911a7da50ee8036c05281f194032135a09b667dbf96ab4cbaaaa0" +
        "1f6c5792a024beed1c4bb6ff37471dfedf9e6c4d7e5f8efb74bef38dc4fee0fd0d940902bd896192812031a500b9673d" +
        "b796f68d944747af2dc43ccb292462ca1ac178fe5f8f751149adcc3b7c3aeeb8986a107a19d92f1b7c1f5215a4a41035" +
        "7bb6514ddcd8592ab15d02c5583648bd4c090b05018311da0fee4bf99306590ed2084f6f200dba0a0196de9d8d684225" +
        "d772c7867eaadc56299b7583f67b430fae7efbed4a49f913b6a929d0d6852760c711a5be67450eb9178e684abfc37f25" +
        "135a408e15dc636edb964da6939234a3c4c58432d78a8196d54f2ed9728e6e5f4b006ac16c0d32d81586a717be96981e" +
        "4a4165103be842457bc9031af6aaa3ad1fc9bb47edcda4eb23fddb2fd5b0aa4b2804057059d3b35b01e3ae29975dcb6d" +
        "36819d80a09797188429721cfc6fceb75a0b1cac24f7bbe95dbb615e06e3bd0fe5b05b68850ec7d17ec6f583f6f5e09a" +
        "0b7083b54bb8da694c9da8c13936fca42dbf72523c8f54f5b24a057a9f58a09e9396dc4ca64b3326bb5d436206c574d7" +
        "cc40db576a475e409942194ef0f901b3ab4bc76aaa2200c8bda682541eb111d83f833bb8c6b88083d677b6c891ebac2b" +
        "fb0ce85b5df34f13b0cb951b4b21608eaf01dff60c69666207fe58b9bafe4a4dfc7665b3e3b50cd38800bf3b0a8740b9" +
        "d698f42825138b8ac61ee4571d192a156cd5442951006ced90e41d02135233e82fd84ecb293a77a0f92006714d0dc2f5" +
        "d4ad38c157f52accd3e27ad4116f4a494c33df232c874025f8eb006307a6580277ad793f22c61fb2ceed6ad4a4f7eac0" +
        "e475f836d6db4de504e2cdeaa62aa4945e24946a252f8d6f85017fa40a496ccff392c17b2c6be64081c514440c9c6702" +
        "08ab5f1e3df9ee439cb470e28eb627c6fe4904af033b6b01bf3536ba8748fca643c993d6185fd34e455a9dfe4b461cf4" +
        "51c04bcfc689b87748d9870bc5f6b91be004af18961fe90821a7147e1cdb44cae9aa7e6d50c579d068f9a535bbbc6ded" +
        "08ab5f1e3df9ee439cb470e28eb627c6fe4904af033b6b01bf3536ba8748fca643c993d6185fd34e455a9dfe4b461cf4" +
        "51c04bcfc689b87748d9870bc5f6b91be004af18961fe90821a7147e1cdb44cae9aa7e6d50c579d068f9a535bbbc6ded" +
        "9bb74dfb131616273400b484bccb6c50c2bfb9263f86f11bccde2606ad914a1f0fa797cc8eda2eb7fa4aff245b72e882" +
        "325b213f020fd7e10f511753668bee7c1d08cf16dc6334be9d408847af394554011c5a269d53cfb273a3abf7581a168e" +
        "9bb74dfb131616273400b484bccb6c50c2bfb9263f86f11bccde2606ad914a1f0fa797cc8eda2eb7fa4aff245b72e882" +
        "325b213f020fd7e10f511753668bee7c1d08cf16dc6334be9d408847af394554011c5a269d53cfb273a3abf7581a168e" +
        "7c3c01d5663b14ade3f49852a3e95b668df31eb9057cf51a6aeff16de36dfbfe5cc20315cb975bcbb8e85d1bfcb9543c" +
        "c9059a2eeaf8fd944c5e67c1be5abf15f55b0a8ad34cd68ae90bd8043a1dd4be41a0bded36801e038eeeb8c8c10e6cac" +
        "9ae0ca4bbf7065dc214629fdbacbecfa3252c8539bf971c05ed0c573753ea59ddba0043341323df20333aaa15a447296" +
        "d312a27b003480526d571cb7d5982765963108fc63582d0239649f279432dacde2c7dbc738367a6144c4faa1834bdfcd" +
        "e4dba4692a6628b501f776fec7fe973d655154268f669bfd47c624aba3be5311d158619c588ab71aa0ac9accb52f0dbc" +
        "488df350f77c7520ce67a3050d1e5e722bcd75081c2b0e64d0f3483cfc981eaa1c358fc7b9c2fb7ce78ed19513e96717" +
        "d09fd051bd1fb7a9d4bd78d05d6ccdeeeb45f690eccff4067e7ef80984791ba2f9a04f4785ce96464e5b726711e69ccc" +
        "7151f81d8f0cc9e2da97223029429d8807384efc4dbbfa93f7b185ef3060aa2daf76ef0d464cd753b86b596dcc509155" +
        "a61d35e3a7dee05534ce8d1cb9f82841a855d7256def8ac5a347d23d88f4f68bae8e4496f4caa56fb5e674d63f489000" +
        "8762b0971bd8f70357e5c9e8dcfbdcef3ea992cafdc28672b7bc64b39b56ffeb217d9d0ff87195ee40241dbafae35dc4" +
        "6eba8b2a8964dd88b5520484b0d35f1074943e4c70cd485719b81a64a147311106160af54f9f877813ad95e83a130cc9" +
        "079dd76c574754c323adfd734cc3492b98c6cd34f772a74a0c761cabe825806d196e5614e30eb964cd04fb7460458b04" +
        "0d33907d5bc6a31e288a12e4dc9d8f88d924bdf4f6ed43032f531e889b623b919e3c45ed5fbbbceb7c584765fd4c4bfd" +
        "87f5bca7d27a33685db2415d614204fe0306f7a4cdb30e495fba038f626fea04be6a0ca50d2df2a14f08c05fdf3ac5c1" +
        "1f4bb98bf19fa84189e6e2a7608aba128238b938e1e4944ef00d5c6d6d42f53a7ee5c9c6002fbdb31c7465991ba873a7" +
        "7b23768e42a1c19a928da8227e7ff3231c038c28db9c7b6d17081443ebc66fb647d6196e42c009d3b1d7d675f8bf27aa" +
        "21245cde04fa9155a35904926acaa21dd4aaedc682e4a9898e00d8b51d5de94e33ba2dafccfb8a73282a739666bd17d8" +
        "5417d962865101aca539b19e0a9b1af68bc0efe995351a0d7ccdaf760ee91e1f6ef7ac8c203924ae60c8c907b8d8900f" +
        "9a8f07c97259ed814722fd5be025cba8185dbc452ab06e702b6854d9d8e2a1fc928a68afa70701f7d3b2ae20074d7e0d" +
        "68f272cc9b49df6d40fe1315bc723b4e3cb185a1f7a58167b389cddc8a9ff175ee268874eb87a1b9accf64db4161f977" +
        "cd003d77aa5f81c5aec92385bfdc55ebd600f23cb04ac9d5c7a1396f801ffea1a6b94aa617231761bdeebc9ec0f4bf9b" +
        "faf5ebc7ac82a2c96f1a74c46d94f0dad0bcb9ef7b41ddaff8cf63d2b278239e6558dbaed2797ef3b7f4cff8fe592f6a" +
        "f22e24b74f7f32c5bcb46400d5445f488ea6d2632472064254c3b785cadf2341dc1a600078f7f2095b919de284397814" +
        "e961432e68b9fa0c0097aa59419c8453019530ce519f647cb08fb46b7e90accfb30e1c02d0d3b3c91670cfd9f6df949a" +
        "662208bc5a7c9195b502817c15e31a405c8be5d111773fad0448139c3116e99de70620202a1f77755559d38504f73cc0" +
        "301728569c88fcaa70dbd00424bd7285a084ae61a207cd485fb35c256ddb2f0be050a56cb4322c8d6b050836f60751ae" +
        "b559b47c8962460286fff5a5cdda91854e37221de7852976d032299a36c4664c2626edf6ad3fd4e33ec9c3ae5361b4fb" +
        "57daf664c4d778b78999445eae154a1e3a4a5f0555d1859daab66d660f863a8368a988b4c8f2ad84da753ec48099d637" +
        "b559b47c8962460286fff5a5cdda91854e37221de7852976d032299a36c4664c2626edf6ad3fd4e33ec9c3ae5361b4fb" +
        "57daf664c4d778b78999445eae154a1e3a4a5f0555d1859daab66d660f863a8368a988b4c8f2ad84da753ec48099d637" +
        "60b7608ef7932a4f9169fa2c535ead9f9ae733e4843c380ab30a034b4d689e99470d3779e4c0c614780ff08b72442b8c" +
        "99952423969b5ed2b3061be3e28e7ffd012df3f65c8bc8e874ba1c87ff2302b12fc0dd47378c0d3c0c38ceeb68205cae" +
        "60b7608ef7932a4f9169fa2c535ead9f9ae733e4843c380ab30a034b4d689e99470d3779e4c0c614780ff08b72442b8c" +
        "99952423969b5ed2b3061be3e28e7ffd012df3f65c8bc8e874ba1c87ff2302b12fc0dd47378c0d3c0c38ceeb68205cae" +
        "60b7608ef7932a4f9169fa2c535ead9f9ae733e4843c380ab30a034b4d689e99470d3779e4c0c614780ff08b72442b8c" +
        "99952423969b5ed2b3061be3e28e7ffd012df3f65c8bc8e874ba1c87ff2302b12fc0dd47378c0d3c0c38ceeb68205cae" +
        "60b7608ef7932a4f9169fa2c535ead9f9ae733e4843c380ab30a034b4d689e99470d3779e4c0c614780ff08b72442b8c" +
        "99952423969b5ed2b3061be3e28e7ffd012df3f65c8bc8e874ba1c87ff2302b12fc0dd47378c0d3c0c38ceeb68205cae" +
        "60b7608ef7932a4f9169fa2c535ead9f9ae733e4843c380ab30a034b4d689e99470d3779e4c0c614780ff08b72442b8c" +
        "99952423969b5ed2b3061be3e28e7ffd012df3f65c8bc8e874ba1c87ff2302b12fc0dd47378c0d3c0c38ceeb68205cae" +
        "60b7608ef7932a4f9169fa2c535ead9f9ae733e4843c380ab30a034b4d689e99470d3779e4c0c614780ff08b72442b8c" +
        "99952423969b5ed2b3061be3e28e7ffd012df3f65c8bc8e874ba1c87ff2302b12fc0dd47378c0d3c0c38ceeb68205cae" +
        "3f3606aaf4236174465c6a82e75bff464d53dd9db41ec15f6277ce342e692faa4307fd85225e6357e6a80050ccc38c56" +
        "f2e1bf585f806ee0449df8e88124e0ef6bacbb4ccd614dc12d41ef53508c33590075cbd40c1ea7fe91c35e267836cfba" +
        "92f29900a45cbbb5726f4de3869dc7abcb340fedf232fabc155a10109a878fbb5b41b39f038e351dbb0a11d1073a8d7c" +
        "ea1e77415105ae15afa5e471e46ba14963108b42946de6207790577d8b0fedc1ee9c614ec5da9dfbde707cc4945bd1c0" +
        "3011e4745cd0209a9e2ddc28eb9ad8b024c7100b450a91e5df048c7cc882a9a410a19d0f3836ffcb9f6c570c3daf792c" +
        "93c911f0e24b7e0d9c2fe4ea18bb7ca889a92f60318464abd2551668667f29b90fe8fe4f515945e87f28121573143341" +
        "e98e5e66da055c808a3c2328e18b7d88b18a6c309a6f270b2b975f914962c63fd544d2448919be7e23b9d3a4c6cde5c5" +
        "9de120f1c1ba05365de9afce10a05b9692c527c29b451acf00ab65ba1ff1e8707e95f885aff0602877c4a5572f148f4f" +
        "0bd08972e99f1cfeb90f6a7476beef721361d784befd64f4665b91e47bb6f599f5b6e91a97a2edfa75d14872b461efb3" +
        "912ca879a4f86fe466ee1984f24e37b208603d2757aee4c7fc70ccbe464866cebc2f11e761af83f936546b06015d547b" +
        "b6533d299a1acf03a4797dd6ce51372897f4908d5530b8c9cd8aaa3501c7a66449b1e205a5177d0b5216b40c9b9758e7" +
        "f2197faa3fb17bb3784e08157242c93df7fef6d5181d4fe8c4a554479a89b72cfc72121e65bed614fcc983dd696c1fbf";

    // 6400 bytes
    private static final String KEYMAP_HEX =
        "2d1a374c5c5203495722504f1f0841022f15054e19251a462b4e57270258500f52073b4f3a3e38314008000011410240" +
        "56070e500c293638570d3f39050f485817244731425f022308420359234539592c284939515f51480d30211240494a37" +
        "0d110830114a33592c36522f5b0b28273352402451114c3e424d3102371b19242d21541e4b0717171d494618544e2008" +
        "20000c52125930342601165d5d2f020a3036281b3e1f325b4858541c26142447543139060a491a302b100e283f10120f" +
        "463a0a24391c002114543d3b4842023c133b231d041d2d2f2e3b574d4b093d322347363c04163d584a5b33123d154f30" +
        "30522d350f5a043d363c2b01450813494f2a253340432c0b3e3f1d5b550c2b253e385a4e323f2b481b364a003e5d290e" +
        "272f4148124d33302d500b223d16271b4e0209004115495c4b133c0a50065818151940070713373443223600383d1c26" +
        "3f0526205a4f5c46023930531f284b14410b1b285e525c415412422c503e322f2358301e280c040a45143d441c08585d" +
        "1313261145023339155545051317143650245418313822564c401b084813465c074c4d2c4e004643352b2848431d1f33" +
        "41132b522c2d49180d0401565747325e135f0b41005125155c2d5e3f4a5d520c501d5e1c4b4715582b162e233d000150" +
        "404c31203d36351a0413592e102c3a4149195d1400124d0c281b2f055c10353c5d075c1a3d313421450e30353a0a1623" +
        "235418044605500f205f541c0f0a584c1154072e061b302b290000034a57074e4b1f523124422024415541305f191d50" +
        "4e045e341f0e5f490e3f2c5936134701121913163b133b5c485c2c27552917430d56172d0456565336022c0c16534e28" +
        "0c011f27545a233d362f042b391c4f46524653363d4929532c553f42482d4a340e095b03435e40190e44252740540d12" +
        "3a0149572a524a56482a1810374325464c2049105f09094d2d0e140e0201003d4249140d3c5f4304095c14403f39260b" +
        "3a4f5b1938050606135a54165b54333e3e474b5a260e5e2f0a5250290c56352646303f5e152504291f381f1a2d32584b" +
        "5923450032232f3c555f4601561b271c2b461a210b5f4a2a374925045b1d2f542114545317040f0d03550e595016555c" +
        "5c101d480f47522610170b0b153a5f364f5409063858533b4e02151e580a5a545a171c493e0f104e061b595b55591124" +
        "2d1a2b05531e404100353f58403a2d1a3129041018145e1f0f585a04510c295e0634035932231a13593a4b1914581325" +
        "2217353a0b34391a0c141f3d20283b063c1e404e413a011a144d130845272e471e43022a573b44034f43404f4b5b3627" +
        "1916163b5057354524494d4a505b310e3f3338364f5d1a1e203a4e0c3624134f1a090a4a01400f25293d0f5938210757" +
        "34200e235d28221d02102938141d082e2612582732082d5b453c345d3d3c55123c0335390b57564d4700265b1d0e0943" +
        "00424b334a180e0f34430c125f01243b4439542f112a1d582a233427111d4b115f3624292e133802564514352638504b" +
        "52445a030e175b193a0f202b2d0b3d0c2141364f544e322a33465f3a5f4f0551335f5422572f1b111f3b3d4c275a3848" +
        "3b0e18105d2a3a10103a2a4f290f203d4f543f46245a372335144f5c4e27250a161d1a13475403372e0e07385d27554c" +
        "1c3412202e4a43033e32402d3945174f023142292526403434470c314f015e4b15104b433a0e2619204626592b3d490d" +
        "0e2b1613315747051e53174d3415384905030c4011133931593f2b255c54124a5f295d310045161e182d4c2d22241628" +
        "0803481916214b0f4056143c4a27264950235a5048114e411e3a0e413f0449470731405d322b0d5201012e4b28541558" +
        "574f48200017011f510f00101329575b3a38584c43453e25470c100f4005081755303735271854190734095b5d415617" +
        "594e033c1441015b4d524a0d3732250c433c420a3516233c4b2d37280e2d40475c23235005054b323735404e48453b2b" +
        "011d35361339133e462a073458271b344a3e242f234f023a0422294c47041748014d1f542612330c3c3a4014415b280b" +
        "192c1b1d1b5d57201f004c0644044e25314d1a375f4d231c074330281e391317052e34012b0c212a4c4d3011315f1642" +
        "2c30192c5d1c28445f380c5d112015172e29185a151924024635531814095a401a544c1710143c504d484d5e48425557" +
        "0c4d51214655032d0a56251e401f5f5a134b5104402d542d56210b1e044115100e4611551c1502264b27252b2604251a" +
        "2f571e0f24521c5a13081957492e473755592c114e0e1739351c055c200a163001143f06063c00594419310d2818441d" +
        "51510e3f5f255915423e51422807122a5b3210414e503a320a0b1f1204430f55141d1554220e09442c5a075441195e3c" +
        "2b0e1d195f372b49234a3b070e4a3c02475156093f5f2e0b3935005a2e5e163a4d13532c4b1f154e495055371a313a01" +
        "03304a2210582e490d0e243c4c3a16394e490519085a47322a1c09442d4325305410530408012d160f51325b0c28153a" +
        "115a535a553a2c5f361544033909342d59073142483e3857104a321c1227560322295d57430956191f1a1c580430053d" +
        "17161f5f5437360421490013105617121f5409423e405b5d5a58353e081a1c20301b5f24533609141f09280f001f225f" +
        "130b22514b1d2e265543045e5d201e2d1c5d320f333b2332442b22244a44243e4f262f1b433e41392145175f46350c02" +
        "323e51461914583e201a020a3e06480e2c582950360a09372f2016553523370701282d5b1d06393d201b273f22104d2e" +
        "48561e5e0027350f474c451c4f1c043045112b0217441f18004657025624101e1a2f1c1a3632095e5e4e5a4d4b3e1d30" +
        "502912470d315f4d17362f4e5a404c140f090e253b182319461e46315c03412c0c53141a0453475c2a174a0457361946" +
        "1f274b3a1f0f53060d3917091d5916292c0a23315d0b2d0702570c392d255f2d2c4a074c593b32065449501142461b0f" +
        "503e402d294d352b4421445146431e522e263e0741104e355a3e471c24022b54204b214a3856551c171a0e3d5d0c100b" +
        "324e13133f4129191f103523524117120c183c454f3241462c2f24293c14154e02284221090b3a085b500b4d11023f1d" +
        "1a1c42092e04505a3354034f08181e4a20404c092b065126565c53073e3225394e47225c4b52565f265a2e0e524c3913" +
        "0c251c372b0e5d224a500929230e425156044e215724205d5e4f0c511b4504280a005f150e3d37390d2142102f240205" +
        "2830275f34273d33164924122e081a1808592d57364530244612341516165b3e46221e1b495b2e40245252323a0c2a22" +
        "0557191c1c2940023b1458510b531051150e4c3e491a1e4d4c501f263c49492220421e3d0c5e1f275217595d0a492f5f" +
        "571b1e20161c0d020d0c092955524b15340932204752481a094117540a263341225101380e4e1a1b5a23244f5510440a" +
        "59172a2149523b521332261d595a5e1b4b5f33592d4d542751191646093b5002525b233b4d3e0d0111141e0a0e1d0559" +
        "5c18332a0527315620281d29030d0c35080f50564e3d373f5155495f522e392f460c392b130a0214321f1d150c294a15" +
        "191a4b0758224649570f492a1d0239430e120e015c50150f0f33041c3c4f1155093c3c013e024b1551143f4e16583104" +
        "4a204547105b36000e3a5c2a094d0053091c54475f1f5c50131c1f0914500d3f505326412e3c211c165d26004a265353" +
        "43271a424716123a1231440702314632044c131228342e1e11551e5b5b112e3e394800003f531a51045e5846103e1914" +
        "2a0c2732213551320a4f0e06013c241a25051a443834353c132e03034c1c1757281e0929343a5c1e2a0a042b2609252b" +
        "0e3f0f26534502465305491f0141560a3f0033133a0f124459160f201f144b0d533b332720360d531b37125d18490757" +
        "491a4b032a5d4803533723134c4f201f2a54462a0a341d054b0f020358493a214405054e424d5136045529504429504e" +
        "5d3658074a554d35042f183d59533e3d38432b1b101c3115115a0535235503210c5b083650354c350504525e37303b10" +
        "54460b04023c1954165f0919540d3a404823171958034e3d47203b1f30560f241c1a091f3622534c215c455609201652" +
        "232d4b1b10195958391457494b064e07005706165959425b5627513f4707114a155c0605153f3d2e3334185e1a06461a" +
        "3d2c30362612315c1a021b014a2d2c3f0932445f51222d04364543512b294b08555c3f5b4e50370853534a1d00165c09" +
        "2840085942165d595b202a0749150f1f512e1a201f32285225520f05480b4e102b37494e4d27472847512f31061f3038" +
        "4d2a584c5c001e01332d061b18350c240c3552395c3901442a1155103025285d2f002a2c01480d1416542f0e291b3235" +
        "51244e4d3d4f1147400638100b004e1b411847420054565648054551211706521c543f39443101241739152319033e5a" +
        "5b051c3b39521222585753594e5a4b0a4e0a24323b2537535e2c56570f14520a594e2532413734192e28521d221d0710" +
        "272b2343503a162e060c061500385f5926242b473c00000a0852072a0f0f1a171a3d5a4b5750595d5c5f523c3731353e" +
        "3640251220265c2858043208134d5f0d0a39580229523f055132410943564719360c0b5712085f0b0c32535f5f520c09" +
        "2b450b54374b39085d1a114010383a4625451d374d1d42392f35382e072517320a0306214e3f292b391b4b4933251058" +
        "0b0d30380a521239282a472f2f3f423942483a1008443b413f060b122c5b0b17081b2f530d410c154c54251b33073555" +
        "2f0f051753203912262425521f1049070b195a583a070e263b33224e1a5743294648411a085a2c0e1e31013d412a444c" +
        "431f451e2633440146462f003d1209043a4a1e22442a30025c111f3d3c440a1f032f1d2902420a48281928062b110a45" +
        "3c0807203237032e29024c054656044905225207043c2f2c55371200485c4524444c25170408250d0a11123147565a4c" +
        "584d333c0942493f595b3f423825461c514b13355319423e0a344f522b491e031632402014295f0e041e303c23563955" +
        "422c2a15454d33502122222c4c401002525022471901551d20053a433b53185d1f4353051026351129573e15174e170a" +
        "1e1a51371b26341b0b0e3f46015724011a17462a1d1b3c26125a1b29283313462d045d280a11241520033c013a004255" +
        "17285f14433b3b3515563f1d0952431657403f0152035752463333003316552a1e353e02505917063056231949073020" +
        "274f015932584b580b5f583e552e481303075553000d3910435d2a2c443a2c4b290e453b061033524f2b10445959385c" +
        "000d50405a29313e063b4a2a1517561e051b594b2b2c3d1b574e3f3127572d07055d275f07581d4d330858485f4e0604" +
        "095f4f140b0d0f035b4f14224641292b1f502b262948535c304b252f192b13220b4317165026594b550d4d3c2f56072e" +
        "4612340f3b274c0b53111b0c1c2e0f0751265e414c370c4225595e544f452216385605135d515e5042195d5f280c4619" +
        "12443b3e1c472041415e15102417265c4d0c4f4a5d4d3b2047181f4f044548162a2354464b55070c331c1c5733233320" +
        "2f224a0c50052c171d2b0601112e173b324c011d2108295404452c17485f171702012432075049045c2f060d5d5d282f" +
        "29092c4a11553f551b0b0c034a031b4c041f1e4b4f47304b161658545300031d0930075a05464f20313b241c3e1f0823" +
        "3e074e2d4e1e194534513908321c253b4c2c3632530552242116403f152942533031215e2f1a43444b5c4c1d59113925" +
        "3d4f5710344a3555405515561e57492e084a2d3844501c102d480d2659464b163543274a0d3c1f4d313423300c0d5e14" +
        "370b2c5c5c280c2910192f49401a00153d073f4a435f1814333b441f282334002e403c0a09481359414243213d43171a" +
        "2a56450d555d010918262841493c21575d5d020625155f0658420835051f2f2f55541c2b311e144a243c2b0d594c0456" +
        "29463c4e5c1b34543e3c09233b1853110d4f3c1e0d5008310c131e455f031b0849575645530b1931270254035b271448" +
        "17300604000f160c02345202174d4a41454106182c204953021d365d252a251c5a2c205a1b16471d2b195f42064a232b" +
        "0b2a43172a0c0a0c0a414a2f0b0f2b461b4b20160207340d00134f073d5332485d165f07024a130c0b5d1b164d463c48" +
        "125d5f544413014526302c04235e2c00540c0757561b4301583f572525140e17510d0b15200c5a261d262a4005374139" +
        "4328101943545a1c33312138452f50361c5b4c1c482643452d0d253224460b070f1c0032505a2e032c503b515f2b285c" +
        "0754182f1a5b14474919590d4045552f413541313010343c404f2d1f5b351b42293451442f050b585e45061e2a3b4d0b" +
        "102f1c201f505c3f1f0a3e1a3f5a5c490e4d0d1d321816115d5c2f07175d12272c2e282b5e044a5e0e28182e02551750" +
        "22240e351c04465a4055015752145e5e222629012b535f195c57275e2c1e2f4f221d043f214a19413f1a38320e163011" +
        "3d3a12480d514109280948550717242a34284935120216315c4e430b04541c210e0e491b3f2b2507140d5c1b04002538" +
        "280e0d1a50232c4d510f58354354375142204d212b12081f5f451b4345401b0e2f08081f0b340c3c244411073848391a" +
        "49261b5418245457090f3a4e2f353c3e1d455e28194a043d2f5525471e5e4247045d3b5c012f330a1e4d392e22550c1f" +
        "3a0a4734352b110420164b3e540d25380b41344c5047374f34101d3645295520141c544947454d083c182630262c0911" +
        "0d1d3d3d05142c190449504a13250a27423e1029033d113f5538505b04594c51562a2e3b3e5b554344252d374a173e0c" +
        "554e36380b2718415f083c4341091417132353311e2814424d215a171838244d073a2612011e53410610042719193e0c" +
        "3c313d3a59325c2633563e4c0f4239161c3f281d5d5c5e430c030a255c4911185a4e5254202e5a54051840145a590a16" +
        "393214162e523a1a35441f112d3029081f5b3c3f2a36332f4f5323292d2d20463f343c4e0616081c5b282d0858571057" +
        "524c375c034a2b521e2e5b4b5b1b311b2f4d09360451523f195f475256384929240025074b5139495f35143b50255620" +
        "123f36163008552a071d1c3e55450759462c01115d3a5a5d0f4e3840130e00254d163c1d1e1127052e434323294a5d4f" +
        "175e0054183a51282809483b172840443e5c023c0e29411c4d0520562f1d45465b251b133f0c1b475603020d2b425209" +
        "3f34252d3d47490a4c09001b062542012a5d15094910503f14324d3f151f2934332e4110152a5b41145b3d5a011f5c2b" +
        "5c513545010505551832150d513e21044c0254020c4f23002b005b2c5f57575b280c212952261f0a3834170952380d3e" +
        "1a0120263144275c44222844595f1f210c202a3e2649485e5d3f072f57144e11560e1727323e0357402b3b192a3a3a16" +
        "5b0554212e1c200c3b073b125c292332181b594a593c411a075c3311174e0812333c130159134d141b092717322a492a" +
        "4522551f5e363945134c362a3a3e1c0d1b2f4f54433c485e450f55583a3e021f40573e3e2e17032144394b1e58070c53" +
        "165b2759170f573d1f2c35590a37584a2f37083d2e0b5e122529111d101d10261837202f46574c4524013e0e39373948" +
        "4e41051c2d430f524c204f5c3d3f02355602441c3a31025e32204c4b57251325471822143b3146275135030e1406430a" +
        "082726425828200b290d5600120a265902484d3e1933454a4849585c4f3b4637424d5a3b551a261e071c1f1a26455329" +
        "0d214726542c503d5548194403005c454d5600421026015823005249452552524619581b4648381b10510053313c385e" +
        "321921221f025a42024c0c27123e59585851531e192b39095d195c2e55340d072d2e0a4d30040f12511b39435a323b32" +
        "030f505c3a2905372301465835530043010a10110e1f233f3b3c22154e3e47514d174d0720521f4353053c08381c2b19" +
        "263b2a145b2d341609562b57145228410955492927084d1a4d290205452e1e4b09283f4455135a3e4a05155e571d2040" +
        "12094a391137333e403543054301502d290f115e030c3c4d11312b494e4b2900341339252a0c030a21460f45273f1230" +
        "2f232e320f4a5f211b2a4a495613490a070230314e331c505a0b35214b47525a0a002c5a2b2b5b463545300b3859163f" +
        "5c46512a594d1a53584f152336471d2127491b525416190a3b291513222b335e5104294b51233e4913334d295a0a4a22" +
        "344554085b0d123636282a39335d170501205052440e1b574248201c534b1e0730134f0c000242170a4c501d4947222a" +
        "08125c4c0118232300233f330e5e1a3f510a4b514c0d08565a38532320164e28084a540942172c233a4c36292a51485b" +
        "3b134c2700355d3a4d505e0d062c154f1609383900453c1b3112443b432c361e3f02253f17021a05535812392408081a" +
        "51413352260f4d37221112053d28045c2a091b420c15473f4d39581121010c13423f45484f321f5143115600393a3c43" +
        "245725304d4c4f3a06472c2728383a4a171f324631313754420e545b28103f4c08441c35314b0f37333b5e3b5319264b" +
        "1838312a0a491e2c3753285f03074c4b4b48205c341013474b52221f0b480a03011b2d0b444c371b3f3f1b2226070e12" +
        "4f2e4e233e024a0a540d295f351342360e1021535c390e1b18293d3f102b31005a1f0318014e02553b0b34101e17272d" +
        "27480003210e5e1a181b5928470a0821094c390b1a3c0055271545462c4c533335533636411450392f290216134a171d" +
        "365128300d0805341d4b5a29372d5c4c0012234226131b561d5d4c3048044d5e35552f223e34363b5f1105173e41033f" +
        "3426213a1a1c10175a5d474241152056";

    private static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    /** The Inno Setup loader keeps its version tags in the first few hundred KB of the file. */
    static boolean looksInno(RandomAccessFile r) throws IOException {
        byte[] b = new byte[1 << 19];
        r.seek(0);
        int n = 0, k;
        while (n < b.length && (k = r.read(b, n, b.length - n)) > 0) n += k;
        return n > 2 && b[0] == 'M' && b[1] == 'Z' && indexOf(b, 0, n, INNO_TAG) >= 0;
    }

    private static int indexOf(byte[] h, int from, int to, byte[] p) {
        outer:
        for (int i = from; i + p.length <= to; i++) {
            if (h[i] != p[0]) continue;
            for (int j = 1; j < p.length; j++) if (h[i + j] != p[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Offset of the first "LaR@eZip" with a sane header, or -1. */
    static long findLargeZip(RandomAccessFile r) throws IOException {
        byte[] buf = new byte[4 << 20];
        long pos = 0, len = r.length();
        int keep = LARGEZIP.length - 1, have = 0;
        while (pos < len) {
            r.seek(pos);
            int want = (int) Math.min(buf.length - have, len - pos), n = 0, k;
            while (n < want && (k = r.read(buf, have + n, want - n)) > 0) n += k;
            if (n <= 0) break;
            int end = have + n, at = 0;
            while ((at = indexOf(buf, at, end, LARGEZIP)) >= 0) {
                long abs = pos - have + at;
                if (abs + LZ_HEADER <= len) {
                    byte[] h = readAt(r, abs, LZ_HEADER);
                    long count = u32(h, 72);
                    if (count >= 1 && count <= 8 && u32(h, 8) == LZ_HEADER) return abs;
                }
                at++;
            }
            pos += n;
            have = Math.min(keep, end);
            System.arraycopy(buf, end - have, buf, 0, have);
        }
        return -1;
    }

    private static List<Item> extractInnoRuu(RandomAccessFile r, File work, Consumer<String> log) throws IOException {
        long lz = findLargeZip(r);
        if (lz < 0) throw new IOException("RUU: Inno Setup installer without an HTC firmware archive (no Large Zip inside)");
        byte[] hd = readAt(r, lz, LZ_HEADER);
        int count = (int) u32(hd, 72);
        log.accept("RUU: Inno Setup installer, Large Zip of " + count + " archive(s)");
        List<Item> out = new ArrayList<>();
        Set<String> got = new HashSet<>();
        SortedMap<Integer, File> pieces = new TreeMap<>();
        int[] device = {-1};
        IOException last = null;
        for (int i = 0; i < count; i++) {
            long start = lz + LZ_HEADER + u32(hd, 8 + i * 4), length = u32(hd, 40 + i * 4);
            try (InputStream raw = openLargeZipPart(r, start, length, device, log)) {
                log.accept("RUU: archive " + (i + 1) + "/" + count + " " + (length >> 20) + " MB");
                extractRomZip(raw, work, log, got, out, pieces);
            } catch (KeyUnknownException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                log.accept("RUU: archive " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (!pieces.isEmpty() && !got.contains("system")) {
            File sys = new File(work, "system.img");
            // the first piece becomes the image, the others are appended to it (no second 800 MiB copy)
            File first = pieces.remove(pieces.firstKey());
            if (sys.exists() && !sys.delete()) throw new IOException("cannot replace " + sys);
            if (!first.renameTo(sys)) throw new IOException("cannot move " + first);
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(sys, true), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                for (File pf : pieces.values()) {
                    try (InputStream in = new java.io.FileInputStream(pf)) {
                        int k;
                        while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                    }
                    //noinspection ResultOfMethodCallIgnored
                    pf.delete();
                }
            }
            got.add("system");
            out.add(new Item(sys, "system"));
            log.accept("RUU: system image joined from " + (pieces.size() + 1) + " piece(s), " + sys.length() + " bytes");
        }
        if (out.isEmpty()) {
            if (last != null) throw last;
            throw new IOException("RUU: none of " + count + " firmware archive(s) holds a boot, recovery or system image");
        }
        return out;
    }

    /** The key block of this firmware is not among the known ones: decrypting is not possible. */
    static final class KeyUnknownException extends IOException {
        KeyUnknownException(String m) { super(m); }
    }

    /** One archive of the Large Zip as a plain ZIP stream (decrypted on the fly when it is encrypted). */
    private static InputStream openLargeZipPart(RandomAccessFile r, long start, long length, int[] device, Consumer<String> log) throws IOException {
        long p = start;
        byte[] h = readAt(r, p, HZ_HEADER);
        if (!startsAt(h, 0, HTCZIP)) {            // a 256-byte signature in front of it
            p += SIG;
            h = readAt(r, p, HZ_HEADER);
        }
        if (!startsAt(h, 0, HTCZIP)) {
            // no HTC header at all: a plain (possibly signed) ZIP
            return new SliceStream(r, start, length);
        }
        int keymap = ((h[8] & 255) << 8) | (h[9] & 255);
        int chunks = h[10] & 255;
        StringBuilder ver = new StringBuilder();
        for (int i = 11; i < 31; i++) if ((h[i] & 255) != 0xff && h[i] != 0) ver.append((char) (h[i] & 255));
        long data = p + HZ_HEADER;
        long chunkSize = (long) chunks << 20;
        long size = Math.min(length, r.length() - data);
        // (the original: a ZIP shorter than one chunk is not encrypted at all)
        long nChunks = chunkSize <= 0 || size < chunkSize ? 0 : size / chunkSize;
        if (nChunks == 0) return new SliceStream(r, data, size);
        byte[] c0 = readAt(r, data, 16);
        if (device[0] < 0) {
            log.accept("RUU: encrypted archives, build " + ver + ", key map " + keymap + ", " + chunks + " chunk(s) of 1 MiB");
            device[0] = findDevice(c0, keymap);
            if (device[0] < 0) throw new KeyUnknownException("RUU: the firmware archives are HTC-encrypted (build " + ver + ", key map " + keymap
                + ") and none of the " + (hex(KEYDATA_HEX).length / KEYDATA_LEN) + " known device key blocks opens them: the key block of this build (96 bytes, read out of its hboot, "
                + "see ruuveal / bruutveal) is missing");
            log.accept("RUU: key block " + device[0] + " of the known set fits");
        }
        byte[][] ki = keyIv(device[0], keymap);
        return new HtcAesStream(r, data, size, ki[0], ki[1], chunkSize, nChunks);
    }

    /** key and IV: bytes of the device's 96-byte key block, picked by the 32 indices of the key map entry. */
    private static byte[][] keyIv(int device, int keymapIndex) {
        byte[] kd = hex(KEYDATA_HEX), km = hex(KEYMAP_HEX);
        int e = (keymapIndex - 1) << 5;
        if (device < 0 || (device + 1) * KEYDATA_LEN > kd.length || keymapIndex < 1 || e + 32 > km.length) return null;
        byte[] key = new byte[16], iv = new byte[16];
        for (int i = 0; i < 16; i++) {
            key[i] = kd[device * KEYDATA_LEN + (km[e + i] & 255)];
            iv[i] = kd[device * KEYDATA_LEN + (km[e + i + 16] & 255)];
        }
        return new byte[][]{key, iv};
    }

    /** The key block whose key makes the first encrypted block a ZIP local header, or -1. */
    private static int findDevice(byte[] c0, int keymapIndex) throws IOException {
        int devices = hex(KEYDATA_HEX).length / KEYDATA_LEN;
        for (int d = 0; d < devices; d++) {
            byte[][] ki = keyIv(d, keymapIndex);
            if (ki == null) return -1;
            try {
                Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ki[0], "AES"), new IvParameterSpec(ki[1]));
                byte[] p = c.doFinal(c0);
                if (p[0] == 'P' && p[1] == 'K' && p[2] == 3 && p[3] == 4) return d;
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException("AES unavailable: " + e.getMessage());
            }
        }
        return -1;
    }

    /** [length] bytes of the file from [start], read as they are. */
    private static final class SliceStream extends InputStream {
        private final RandomAccessFile r; private long pos; private final long end;
        SliceStream(RandomAccessFile r, long start, long length) { this.r = r; this.pos = start; this.end = start + length; }
        @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
        @Override public int read(byte[] b, int o, int n) throws IOException {
            if (pos >= end) return -1;
            r.seek(pos);
            int k = r.read(b, o, (int) Math.min(n, end - pos));
            if (k > 0) pos += k;
            return k;
        }
    }

    /**
     * The ZIP of an archive with the AES layer removed: of every chunk (chunks MiB) the first 1 MiB is CBC-encrypted,
     * read in blocks of 32 KiB, the IV restarting with the chunk. Only the first [nChunks] chunks are encrypted.
     */
    private static final class HtcAesStream extends InputStream {
        private final RandomAccessFile r; private final long start, size, chunkSize, nChunks;
        private final SecretKeySpec key; private final IvParameterSpec iv;
        private final byte[] buf = new byte[AES_READ];
        private long pos; private int bp, bl; private Cipher cipher;

        HtcAesStream(RandomAccessFile r, long start, long size, byte[] key, byte[] iv, long chunkSize, long nChunks) {
            this.r = r; this.start = start; this.size = size; this.chunkSize = chunkSize; this.nChunks = nChunks;
            this.key = new SecretKeySpec(key, "AES"); this.iv = new IvParameterSpec(iv);
        }

        private boolean fill() throws IOException {
            if (pos >= size) return false;
            int want = (int) Math.min(AES_READ, size - pos), n = 0, k;
            r.seek(start + pos);
            while (n < want && (k = r.read(buf, n, want - n)) > 0) n += k;
            if (n <= 0) return false;
            long idx = pos / chunkSize, in = pos % chunkSize;
            if (idx < nChunks) {
                try {
                    if (in == 0 || cipher == null) {
                        cipher = Cipher.getInstance("AES/CBC/NoPadding");
                        cipher.init(Cipher.DECRYPT_MODE, key, iv);
                    }
                    if (in < AES_WINDOW) {
                        int whole = n & ~15;
                        if (whole > 0) cipher.update(buf, 0, whole, buf, 0);
                    }
                } catch (java.security.GeneralSecurityException e) {
                    throw new IOException("AES: " + e.getMessage());
                }
            }
            pos += n; bp = 0; bl = n;
            return true;
        }

        @Override public int read() throws IOException {
            if (bp >= bl && !fill()) return -1;
            return buf[bp++] & 255;
        }

        @Override public int read(byte[] b, int o, int n) throws IOException {
            if (bp >= bl && !fill()) return -1;
            int k = Math.min(n, bl - bp);
            System.arraycopy(buf, bp, b, o, k);
            bp += k;
            return k;
        }
    }

    /** Dispatches on content; handy for tests. */
    public static List<Item> extractAny(File f, File work, Consumer<String> log) throws IOException {
        byte[] head = new byte[0x300];
        try (InputStream in = new java.io.FileInputStream(f)) { head = Arrays.copyOf(head, readUpTo(in, head)); }
        if (isDreamNbh(head)) return extractDreamNbh(f, work, log);
        if (isSignedWrapper(head)) return unwrapSigned(f, work, log);
        if (isMz(head)) return extractRuu(f, work, log);
        throw new IOException("not an HTC RUU / NBH / signed image");
    }
}
