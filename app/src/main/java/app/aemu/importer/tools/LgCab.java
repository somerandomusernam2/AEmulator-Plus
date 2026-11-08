/* Added for AEmulator Plus, 2026-11-06: old LG KDZ import (RC4 + CAB + legacy DZ). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.importer.tools;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Old LG ".kdz" files (KP500, GS290, KS360, GW620 ... phones of about 2008-2010): a Microsoft CAB archive encrypted
 * with RC4 as one continuous stream. The key is not stored anywhere: it is the MD5 of a password built from the
 * decimal digits of the file size (see {@link #deriveKey}). Inside the cabinet sits the usual LG {@code .dz} (or a
 * BIN/TOT) next to a small updater DLL.
 *
 * <p>The key derivation and the table below are ported from michaelwright235/kdz_decrypt_encrypt (kdz.py,
 * MIT License, Copyright (c) 2022 Michael Wright; the licence text is in docs/third-party/kdz_decrypt_encrypt-LICENSE.txt).
 * The RC4 cipher and the CAB reader are written here: Python's pycryptodome is not available on Android, and no
 * CAB reader was in the project.
 *
 * <p>The cabinet is never written to disk decrypted: {@link #decrypting} decrypts while {@link #extract} reads it.
 * Only stored (0) and MSZIP (1) folders are supported; they are what the KDZ cabinets use. Quantum and LZX folders,
 * and files continued in another cabinet, are reported as unsupported.
 */
final class LgCab {
    private LgCab() {}

    /** Password alphabets of kdz.py, one per digit position counted from the end of the decimal size. */
    private static final String[] KEY_TABLE = {
        "JS16bglqyz", "CLU150ejnk", "AEIPRW037c", "47afilmpwx", "BFH6cgkrvz", "DGKMNOZ28k", "CJQTX49eno",
        "A03780ckwx", "8ioqrtvwxy", "LGCNSkang0", "dy8913ksh7", "KLNPQSVY03", "b2PXfFSHv3"
    };

    private static final int CAB_HEADER = 36;
    private static final long MAX_TABLE = 16L << 20;

    // ================================================================== key + cipher

    /** RC4 key for a KDZ of {@code fileSize} bytes, or null when the size has more digits than the table covers. */
    static byte[] deriveKey(long fileSize) {
        if (fileSize <= 0) return null;
        String sz = Long.toString(fileSize);
        int n = sz.length();
        if (n > KEY_TABLE.length) return null;
        StringBuilder pw = new StringBuilder(n);
        for (int i = 0; i < n; i++) pw.append(KEY_TABLE[n - i - 1].charAt((sz.charAt(i) - '0') & 0xF));
        try {
            return MessageDigest.getInstance("MD5").digest(pw.toString().getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // MD5 is mandatory on every Java platform
        }
    }

    /** Plain RC4. (javax.crypto's "RC4" is not guaranteed on Android, and 10 lines are cheaper than a provider lookup.) */
    static final class Rc4 {
        private final int[] s = new int[256];
        private int i, j;

        Rc4(byte[] key) {
            for (int k = 0; k < 256; k++) s[k] = k;
            int jj = 0;
            for (int k = 0; k < 256; k++) {
                jj = (jj + s[k] + (key[k % key.length] & 255)) & 255;
                int t = s[k]; s[k] = s[jj]; s[jj] = t;
            }
        }

        /** XORs the keystream into b[off, off+len). Encrypts and decrypts alike. */
        void crypt(byte[] b, int off, int len) {
            int ii = i, jj = j;
            for (int k = off, end = off + len; k < end; k++) {
                ii = (ii + 1) & 255;
                int si = s[ii];
                jj = (jj + si) & 255;
                int sj = s[jj];
                s[ii] = sj; s[jj] = si;
                b[k] ^= (byte) s[(si + sj) & 255];
            }
            i = ii; j = jj;
        }

        /** Advances the keystream by n bytes without touching any data. */
        void skip(long n) {
            int ii = i, jj = j;
            for (long k = 0; k < n; k++) {
                ii = (ii + 1) & 255;
                int si = s[ii];
                jj = (jj + si) & 255;
                s[ii] = s[jj]; s[jj] = si;
            }
            i = ii; j = jj;
        }
    }

    // ================================================================== detection

    /** True when the 12 first bytes decrypt (with the key of a file of {@code size} bytes) to a CAB header. */
    static boolean isEncryptedKdz(byte[] head, long size) {
        if (head == null || head.length < 12 || size < CAB_HEADER) return false;
        byte[] key = deriveKey(size);
        if (key == null) return false;
        byte[] d = Arrays.copyOf(head, 12);
        new Rc4(key).crypt(d, 0, 12);
        return isCabHeader(d);
    }

    static boolean isEncryptedKdz(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[12];
            if (r.length() < 12) return false;
            r.readFully(h);
            return isEncryptedKdz(h, r.length());
        } catch (IOException e) {
            return false;
        }
    }

    /** "MSCF" and the reserved field after it equal to zero. */
    static boolean isCabHeader(byte[] h) {
        return h != null && h.length >= 8 && h[0] == 'M' && h[1] == 'S' && h[2] == 'C' && h[3] == 'F'
            && h[4] == 0 && h[5] == 0 && h[6] == 0 && h[7] == 0;
    }

    // ================================================================== byte sources

    /** Random-access byte source; the decrypting one only runs the cipher forward, so reading in order is cheap. */
    interface Src extends Closeable {
        long length();
        void readFully(long pos, byte[] b, int off, int len) throws IOException;
    }

    static Src plain(File f) throws IOException {
        final RandomAccessFile r = new RandomAccessFile(f, "r");
        return new Src() {
            @Override public long length() { try { return r.length(); } catch (IOException e) { return 0; } }
            @Override public void readFully(long pos, byte[] b, int off, int len) throws IOException {
                r.seek(pos); r.readFully(b, off, len);
            }
            @Override public void close() throws IOException { r.close(); }
        };
    }

    /**
     * The same file with the RC4 layer removed. RC4 cannot seek: a read behind the cipher's position starts the
     * keystream again from byte 0, a read ahead of it skips keystream. The cabinet reader goes front to back, so
     * neither costs anything in practice.
     */
    static Src decrypting(File f, final byte[] key) throws IOException {
        final RandomAccessFile r = new RandomAccessFile(f, "r");
        return new Src() {
            private Rc4 rc = new Rc4(key);
            private long cur;
            @Override public long length() { try { return r.length(); } catch (IOException e) { return 0; } }
            @Override public void readFully(long pos, byte[] b, int off, int len) throws IOException {
                if (pos < cur) { rc = new Rc4(key); cur = 0; }
                if (pos > cur) { rc.skip(pos - cur); cur = pos; }
                r.seek(pos);
                r.readFully(b, off, len);
                rc.crypt(b, off, len);
                cur = pos + len;
            }
            @Override public void close() throws IOException { r.close(); }
        };
    }

    // ================================================================== CAB layout

    static final class Member {
        String name; long size, uoff; int folder, index;
    }

    static final class Folder {
        long dataOff; int blocks, method;
    }

    static final class Cab {
        long size, declaredSize; int cbBlockReserve;
        final List<Folder> folders = new ArrayList<>();
        final List<Member> members = new ArrayList<>();
    }

    private static int u8(byte[] b, int o) { return b[o] & 255; }
    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static long u32(byte[] b, int o) { return LgKdz.u32(b, o); }

    private static byte[] read(Src s, long pos, int n) throws IOException {
        if (n < 0 || pos < 0 || pos + n > s.length()) throw new IOException("CAB: read past the end of the file");
        byte[] b = new byte[n];
        s.readFully(pos, b, 0, n);
        return b;
    }

    /** Parses the header, folder table and file table. */
    static Cab parse(Src s) throws IOException {
        Cab cab = new Cab();
        cab.size = s.length();
        byte[] h = read(s, 0, CAB_HEADER);
        if (!isCabHeader(h)) throw new IOException("CAB: bad signature");
        cab.declaredSize = u32(h, 8);
        long coffFiles = u32(h, 16);
        int nFolders = u16(h, 26), nFiles = u16(h, 28), flags = u16(h, 30);
        if (cab.declaredSize > cab.size) {
            throw new IOException("CAB: file is " + cab.size + " bytes but the cabinet says " + cab.declaredSize + " (truncated download?)");
        }
        long pos = CAB_HEADER;
        int cbFolderReserve = 0;
        if ((flags & 4) != 0) {                                        // reserve area present
            byte[] r = read(s, pos, 4);
            int cbHeader = u16(r, 0);
            cbFolderReserve = u8(r, 2);
            cab.cbBlockReserve = u8(r, 3);
            pos += 4 + cbHeader;
        }
        if ((flags & 1) != 0) { pos = skipCString(s, pos); pos = skipCString(s, pos); }   // previous cabinet + disk
        if ((flags & 2) != 0) { pos = skipCString(s, pos); pos = skipCString(s, pos); }   // next cabinet + disk
        for (int i = 0; i < nFolders; i++) {
            byte[] f = read(s, pos, 8);
            Folder fo = new Folder();
            fo.dataOff = u32(f, 0);
            fo.blocks = u16(f, 4);
            fo.method = u16(f, 6) & 0x0F;
            cab.folders.add(fo);
            pos += 8 + cbFolderReserve;
        }
        // file table: from coffFiles to wherever it ends (bounded by the next structure, or the end of the file)
        long end = cab.size;
        for (Folder fo : cab.folders) if (fo.dataOff > coffFiles && fo.dataOff < end) end = fo.dataOff;
        long tableLen = end - coffFiles;
        if (coffFiles < pos || tableLen <= 0 || tableLen > MAX_TABLE) throw new IOException("CAB: bad file table");
        byte[] t = read(s, coffFiles, (int) tableLen);
        int p = 0;
        for (int i = 0; i < nFiles; i++) {
            if (p + 16 > t.length) throw new IOException("CAB: file table is cut short");
            Member m = new Member();
            m.index = i;
            m.size = u32(t, p);
            m.uoff = u32(t, p + 4);
            m.folder = u16(t, p + 8);
            int attr = u16(t, p + 14);
            int e = p + 16;
            while (e < t.length && t[e] != 0) e++;
            if (e >= t.length) throw new IOException("CAB: file name is not terminated");
            m.name = new String(t, p + 16, e - (p + 16), (attr & 0x80) != 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
            p = e + 1;
            cab.members.add(m);
        }
        return cab;
    }

    private static long skipCString(Src s, long pos) throws IOException {
        byte[] b = new byte[1];
        for (int n = 0; n < 256; n++) {
            s.readFully(pos++, b, 0, 1);
            if (b[0] == 0) return pos;
        }
        throw new IOException("CAB: header string is not terminated");
    }

    // ================================================================== extraction

    /**
     * Writes every member accepted by {@code want} to {@code outDir/<index>/<safe name>} and returns the files (in
     * cabinet order). A folder is decoded only as far as its last wanted byte, and not at all when nothing in it is wanted.
     */
    static List<File> extract(Src s, Cab cab, File outDir, Predicate<Member> want, Consumer<String> log) throws IOException {
        List<File> result = new ArrayList<>();
        File[] files = new File[cab.members.size()];
        for (Member m : cab.members) {
            if (!want.test(m)) continue;
            if (m.folder >= 0xFFFD) throw new IOException("CAB: " + m.name + " is continued in another cabinet (not supported)");
            if (m.folder >= cab.folders.size()) throw new IOException("CAB: " + m.name + " points at a missing folder");
            File dir = new File(outDir, Integer.toString(m.index));
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            files[m.index] = new File(dir, safeName(m.name));
        }
        for (int fi = 0; fi < cab.folders.size(); fi++) {
            List<Member> mine = new ArrayList<>();
            long need = 0;
            for (Member m : cab.members) {
                if (m.folder != fi || files[m.index] == null) continue;
                mine.add(m);
                need = Math.max(need, m.uoff + m.size);
            }
            if (mine.isEmpty()) continue;
            decodeFolder(s, cab, cab.folders.get(fi), mine, files, need, log);
        }
        for (File f : files) if (f != null) result.add(f);
        return result;
    }

    private static void decodeFolder(Src s, Cab cab, Folder fo, List<Member> mine, File[] files, long need, Consumer<String> log) throws IOException {
        if (fo.method != 0 && fo.method != 1) {
            String name = fo.method == 2 ? "Quantum" : fo.method == 3 ? "LZX" : "type " + fo.method;
            throw new IOException("CAB: " + name + " compression is not supported");
        }
        OutputStream[] outs = new OutputStream[files.length];
        for (Member m : mine) if (m.size == 0) new FileOutputStream(files[m.index]).close();
        Inflater inf = fo.method == 1 ? new Inflater(true) : null;
        byte[] prev = new byte[0];
        byte[] outBuf = new byte[1 << 15];
        long pos = fo.dataOff, upos = 0;
        try {
            for (int b = 0; b < fo.blocks && upos < need; b++) {
                byte[] hd = read(s, pos, 8);
                int cbData = u16(hd, 4), cbUncomp = u16(hd, 6);
                if (cbUncomp == 0) throw new IOException("CAB: data block continued in another cabinet (not supported)");
                byte[] data = read(s, pos + 8 + cab.cbBlockReserve, cbData);
                pos += 8L + cab.cbBlockReserve + cbData;
                byte[] out;
                if (fo.method == 0) {
                    if (cbData != cbUncomp) throw new IOException("CAB: stored block has two sizes");
                    out = data;
                } else {
                    if (cbData < 2 || data[0] != 'C' || data[1] != 'K') throw new IOException("CAB: MSZIP block " + b + " has no CK signature");
                    if (cbUncomp > outBuf.length) throw new IOException("CAB: MSZIP block " + b + " is larger than 32 KB");
                    inf.reset();
                    if (prev.length > 0) inf.setDictionary(prev, 0, prev.length);   // each block is deflated against the one before
                    inf.setInput(data, 2, cbData - 2);
                    int got = 0;
                    try {
                        while (got < cbUncomp) {
                            int n = inf.inflate(outBuf, got, cbUncomp - got);
                            if (n == 0) break;                       // finished, or out of input: checked below
                            got += n;
                        }
                    } catch (DataFormatException e) {
                        throw new IOException("CAB: MSZIP block " + b + " is corrupt: " + e.getMessage());
                    }
                    if (got != cbUncomp) throw new IOException("CAB: MSZIP block " + b + " expands to " + got + " bytes, expected " + cbUncomp);
                    out = Arrays.copyOf(outBuf, cbUncomp);
                }
                for (Member m : mine) {
                    if (m.size == 0) continue;
                    long a = Math.max(m.uoff, upos), z = Math.min(m.uoff + m.size, upos + cbUncomp);
                    if (a >= z) continue;
                    if (outs[m.index] == null) outs[m.index] = new java.io.BufferedOutputStream(new FileOutputStream(files[m.index]), 1 << 16);
                    outs[m.index].write(out, (int) (a - upos), (int) (z - a));
                    if (z == m.uoff + m.size) { outs[m.index].close(); outs[m.index] = null; }
                }
                prev = out;
                upos += cbUncomp;
            }
            if (upos < need) throw new IOException("CAB: folder ends after " + upos + " bytes, files need " + need);
        } finally {
            if (inf != null) inf.end();
            for (OutputStream o : outs) if (o != null) try { o.close(); } catch (IOException ignored) {}
        }
    }

    static String safeName(String n) {
        String s = n.replace('\\', '/');
        s = s.substring(s.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "entry" : s;
    }
}
