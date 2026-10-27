/*
 * Modified for AEmulator Plus, 2026-10-27: new file. SquashFS 3.1 reader (the Logitech Revue / Google TV 1.x boot.img).
 */
package app.aemu.importer.tools;

import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reader for SquashFS <b>3.1</b>, little endian, zlib: the format of the first-generation Google TV (Logitech Revue,
 * Android 2.1 "Eclair" with a glibc userland) {@code boot.img}, which is a plain SquashFS root file system and not an
 * "ANDROID!" image. {@link FirmwareToolset.SquashReader} only understands 4.x; the on-disk layout changed in 4.0, so this is
 * a separate class. It feeds the same {@link FirmwareToolset.SquashVisitor}.
 *
 * <p>Layout (all little endian, packed; checked file by file against unsquashfs on the Revue image):
 * <pre>
 *  superblock (119 bytes): magic 0, major u16 28, minor u16 30, block_log u16 34, flags u8 36, root inode ref u64 43,
 *      block_size u32 51, fragments u32 55, bytes_used u64 63, inode_table u64 87, directory_table u64 95,
 *      fragment_index u64 103
 *  inode: u16 (type = low 4 bits, mode = high 12), uid u8, guid u8, mtime u32, inode_number u32   (12 bytes)
 *    dir (1):     nlink u32, (file_size:19 | offset:13) u32, start_block u32, parent u32
 *    reg (2):     start_block u64, fragment u32, offset u32, file_size u32, block sizes u32[]
 *    symlink (3): nlink u32, size u16, target[size]
 *    4..7:        devices, fifo, socket (not reported)
 *    8:           extended dir: nlink u32, (file_size:27 | offset:19) 6 bytes, start_block u32, i_count u16, parent u32, index[]
 *    9:           extended file (4 GiB+): refused instead of guessed
 *  directory: header = count u8 (entries - 1), start_block u32, inode_number u32 (9 bytes), then entries =
 *      (offset:13 | type:3) u16, size u8 (name length - 1), inode_number s16 (5 bytes) + name
 *  metadata block: u16 header (bit 15 = stored, else zlib), data block / fragment size: bit 24 = stored, 0 = sparse
 * </pre>
 */
final class Squash3 implements Closeable {
    private static final int NO_FRAG = 0xffffffff;
    private static final int UNCOMP = 1 << 24;
    private static final int META = 8192;

    private final RandomAccessFile r;
    private final Consumer<String> log;
    private long base;
    private int block;
    private long rootRef;
    private byte[] inodes, dirs;
    private Map<Long, Integer> imap, dmap;
    private long[] fragStart = new long[0];
    private int[] fragSize = new int[0];

    Squash3(File f, Consumer<String> log) throws IOException {
        this.r = new RandomAccessFile(f, "r");
        this.log = log;
        try { load(); } catch (IOException | RuntimeException e) { r.close(); throw e; }
    }

    /** True if the file starts with a little-endian SquashFS 3.x superblock. */
    static boolean probe(File f) {
        try (RandomAccessFile x = new RandomAccessFile(f, "r")) {
            if (x.length() < 119) return false;
            byte[] h = new byte[32];
            x.readFully(h);
            return h[0] == 'h' && h[1] == 's' && h[2] == 'q' && h[3] == 's' && u16(h, 28) == 3;
        } catch (IOException e) { return false; }
    }

    private static int u16(byte[] b, int o) { return (b[o] & 255) | (b[o + 1] & 255) << 8; }
    private static long u32(byte[] b, int o) { return (b[o] & 255L) | (b[o + 1] & 255L) << 8 | (b[o + 2] & 255L) << 16 | (b[o + 3] & 255L) << 24; }
    private static long u64(byte[] b, int o) { return u32(b, o) | u32(b, o + 4) << 32; }

    private byte[] readAt(long pos, int n) throws IOException {
        if (pos < 0 || n < 0 || pos + n > r.length()) throw new EOFException("SquashFS 3: read outside the image");
        r.seek(pos);
        byte[] b = new byte[n];
        r.readFully(b);
        return b;
    }

    private static byte[] inflate(byte[] raw, int expected) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(raw);
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(Math.max(expected, 256));
            byte[] b = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(b);
                if (n > 0) { o.write(b, 0, n); continue; }
                if (inf.needsInput() || inf.needsDictionary()) break;
            }
            return o.toByteArray();
        } catch (DataFormatException ex) {
            throw new IOException("SquashFS 3 zlib block is corrupt: " + ex.getMessage(), ex);
        } finally { inf.end(); }
    }

    private void load() throws IOException {
        // the superblock is normally at 0; tolerate a short header in front of it like the 4.x reader does
        long len = r.length();
        base = -1;
        byte[] probe = new byte[32];
        for (long at = 0; at + 119 <= len && at < (64L << 20) && base < 0; at += 4096) {
            r.seek(at);
            r.readFully(probe);
            if (probe[0] == 'h' && probe[1] == 's' && probe[2] == 'q' && probe[3] == 's' && u16(probe, 28) == 3) base = at;
        }
        if (base < 0) throw new IOException("no SquashFS 3.x superblock");
        byte[] s = readAt(base, 119);
        int minor = u16(s, 30);
        if (minor != 1) throw new IOException("SquashFS 3." + minor + " is not supported (only 3.1)");
        block = (int) u32(s, 51);
        int blog = u16(s, 34);
        if (block < 4096 || block > (1 << 20) || (block & (block - 1)) != 0 || block != (1 << blog))
            throw new IOException("SquashFS 3: bad block size " + block);
        rootRef = u64(s, 43);
        long inodeTable = u64(s, 87), dirTable = u64(s, 95), fragIndex = u64(s, 103);
        int fragments = (int) u32(s, 55);
        long end = len - base;
        if (inodeTable <= 0 || dirTable <= inodeTable || fragIndex < dirTable || fragIndex > end)
            throw new IOException("SquashFS 3: bad table offsets");

        // fragment entries: the index holds 64-bit pointers to metadata blocks of 512 entries (16 bytes each)
        long dirEnd = fragIndex;
        if (fragments > 0) {
            int nptr = (fragments + 511) / 512;
            byte[] pb = readAt(base + fragIndex, nptr * 8);
            fragStart = new long[fragments];
            fragSize = new int[fragments];
            int fi = 0;
            for (int j = 0; j < nptr; j++) {
                long p = u64(pb, j * 8);
                if (p > dirTable && p < dirEnd) dirEnd = p;   // the directory table ends where the fragment metadata begins
                byte[] f = metaAt(p).data;
                for (int k = 0; k + 16 <= f.length && fi < fragments; k++, fi++) {
                    fragStart[fi] = u64(f, k * 16);
                    fragSize[fi] = (int) u32(f, k * 16 + 8);
                }
            }
            if (fi < fragments) throw new IOException("SquashFS 3: fragment table is short");
        }
        MetaRun it = loadMeta(inodeTable, dirTable);
        inodes = it.buf; imap = it.map;
        MetaRun dt = loadMeta(dirTable, dirEnd);
        dirs = dt.buf; dmap = dt.map;
        log.accept("SquashFS 3.1: block=" + block + " fragments=" + fragments + " root=0x" + Long.toHexString(rootRef));
    }

    private static final class Meta { final byte[] data; final long next; Meta(byte[] d, long n) { data = d; next = n; } }
    private static final class MetaRun { final byte[] buf; final Map<Long, Integer> map; MetaRun(byte[] b, Map<Long, Integer> m) { buf = b; map = m; } }

    private Meta metaAt(long off) throws IOException {
        byte[] h = readAt(base + off, 2);
        int v = u16(h, 0), sz = v & 0x7fff;
        if (sz == 0 || sz > META) throw new IOException("bad SquashFS 3 metadata block at " + off);
        byte[] raw = readAt(base + off + 2, sz);
        return new Meta((v & 0x8000) != 0 ? raw : inflate(raw, META), off + 2 + sz);
    }

    private MetaRun loadMeta(long start, long end) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        Map<Long, Integer> map = new HashMap<>();
        for (long off = start; off < end; ) {
            map.put(off - start, buf.size());
            Meta m = metaAt(off);
            buf.write(m.data, 0, m.data.length);
            off = m.next;
        }
        return new MetaRun(buf.toByteArray(), map);
    }

    private static final class Inode {
        int type, mode, frag, fragOff, dirOff;
        long size, start, dirStart;
        int[] sizes = new int[0];
        String target = "";
    }

    private Inode inode(long ref) throws IOException {
        Integer b = imap.get(ref >>> 16);
        if (b == null) throw new IOException("SquashFS 3: inode reference outside the table");
        int p = b + (int) (ref & 0xffff), q = p + 12;
        if (p < 0 || q > inodes.length) throw new IOException("SquashFS 3: inode outside the table");
        int w = u16(inodes, p);
        Inode x = new Inode();
        x.type = w & 15;
        x.mode = (w >>> 4) & 07777;
        switch (x.type) {
            case 1: {
                long fo = u32(inodes, q + 4);
                x.size = fo & 0x7ffff;
                x.dirOff = (int) (fo >>> 19);
                x.dirStart = u32(inodes, q + 8);
                break;
            }
            case 2: {
                x.start = u64(inodes, q);
                x.frag = (int) u32(inodes, q + 8);
                x.fragOff = (int) u32(inodes, q + 12);
                x.size = u32(inodes, q + 16);
                int n = x.frag == NO_FRAG ? (int) ((x.size + block - 1) / block) : (int) (x.size / block);
                if (q + 20 + 4L * n > inodes.length) throw new IOException("SquashFS 3: block list outside the table");
                x.sizes = new int[n];
                for (int i = 0; i < n; i++) x.sizes[i] = (int) u32(inodes, q + 20 + i * 4);
                break;
            }
            case 3: {
                int ln = u16(inodes, q + 4);
                if (q + 6 + ln > inodes.length) throw new IOException("SquashFS 3: symlink outside the table");
                x.target = new String(inodes, q + 6, ln, StandardCharsets.UTF_8);
                break;
            }
            case 8: {   // extended directory (big folders): nlink u32, (file_size:27 | offset:19) in 6 bytes, start_block u32, i_count u16, parent u32
                long fo = u32(inodes, q + 4) | (long) u16(inodes, q + 8) << 32;
                x.size = fo & ((1L << 27) - 1);
                x.dirOff = (int) ((fo >>> 27) & ((1L << 19) - 1));
                x.dirStart = u32(inodes, q + 10);
                x.type = 1;   // from here on it is an ordinary directory (the index after the header is not needed)
                break;
            }
            case 9:
                throw new IOException("SquashFS 3: extended file inode (4 GiB+) is not supported");
            default: break;   // devices, fifos, sockets
        }
        return x;
    }

    private static final class Entry { final String name; final long ref; Entry(String n, long r) { name = n; ref = r; } }

    private List<Entry> list(Inode d) throws IOException {
        List<Entry> out = new ArrayList<>();
        Integer b = dmap.get(d.dirStart);
        if (b == null) return out;
        int p = b + d.dirOff, end = p + (int) d.size - 3;
        if (end > dirs.length) throw new IOException("SquashFS 3: directory outside the table");
        while (p + 9 <= end) {
            int count = dirs[p] & 255;
            long start = u32(dirs, p + 1);
            p += 9;
            for (int k = 0; k <= count && p + 5 <= end; k++) {
                int w = u16(dirs, p), off = w & 0x1fff, ln = dirs[p + 2] & 255;
                p += 5;
                if (p + ln + 1 > dirs.length) throw new IOException("SquashFS 3: directory entry outside the table");
                out.add(new Entry(new String(dirs, p, ln + 1, StandardCharsets.UTF_8), (start << 16) | off));
                p += ln + 1;
            }
        }
        return out;
    }

    private void stream(OutputStream o, Inode in) throws IOException {
        long rem = in.size, pos = in.start;
        for (int sz : in.sizes) {
            long n = Math.min(block, rem);
            int c = sz & 0x00ffffff;
            if (c == 0) zeros(o, n);
            else {
                byte[] raw = readAt(base + pos, c);
                pos += c;
                byte[] data = (sz & UNCOMP) != 0 ? raw : inflate(raw, block);
                int take = (int) Math.min(n, data.length);
                o.write(data, 0, take);
                if (take < n) zeros(o, n - take);
            }
            rem -= n;
            if (rem <= 0) break;
        }
        if (rem > 0 && in.frag != NO_FRAG) {
            if (in.frag < 0 || in.frag >= fragStart.length) throw new IOException("SquashFS 3: bad fragment index");
            int fs = fragSize[in.frag], c = fs & 0x00ffffff;
            byte[] raw = readAt(base + fragStart[in.frag], c);
            byte[] data = (fs & UNCOMP) != 0 ? raw : inflate(raw, block);
            int off = Math.min(in.fragOff, data.length), take = (int) Math.min(rem, data.length - off);
            o.write(data, off, take);
            rem -= take;
        }
        if (rem > 0) zeros(o, rem);
    }

    private static void zeros(OutputStream o, long n) throws IOException {
        byte[] z = new byte[(int) Math.min(n, 65536)];
        while (n > 0) { int k = (int) Math.min(n, z.length); o.write(z, 0, k); n -= k; }
    }

    /** Visits every folder, link and regular file (the root folder itself is not reported), like the 4.x reader. */
    void walk(FirmwareToolset.SquashVisitor v) throws Exception { walk(v, rootRef, "", new HashSet<Long>()); }

    private void walk(FirmwareToolset.SquashVisitor v, long ref, String rel, Set<Long> seen) throws Exception {
        Inode in = inode(ref);
        if (in.type == 1) {
            if (!rel.isEmpty()) v.dir(rel, in.mode);
            if (!seen.add(ref)) return;
            for (Entry e : list(in)) {
                if (e.name.equals(".") || e.name.equals("..") || e.name.indexOf('/') >= 0) continue;
                walk(v, e.ref, rel.isEmpty() ? e.name : rel + "/" + e.name, seen);
            }
        } else if (in.type == 2) {
            OutputStream o = v.file(rel, in.mode, in.size);
            if (o != null) {
                try { stream(o, in); } finally { o.close(); }
                v.fileEnd(rel);
            }
        } else if (in.type == 3) {
            v.link(rel, in.target);
        }
    }

    @Override public void close() throws IOException { r.close(); }
}
