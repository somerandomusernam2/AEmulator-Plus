/*
 * binderguard_core.h — the decision logic of libaemubinderguard.so, free of system calls so that tests/binderguard_test.c
 * can run it on a host (the callers supply guard_alloc / guard_free / guard_event, see binderguard.c).
 *
 * The problem it contains: the stand's binder daemon (libbinderd_kk.so, no source) now and then delivers an object
 * "bare" — a flat_binder_object of type BINDER_TYPE_BINDER whose binder/cookie are the SENDER's pointers — to a
 * process that does not own it (its own log calls this "ОТВЕТ ГОЛЫМ ОБЪЕКТОМ", and it ends in a system_server whose
 * binder threads all hang in Parcel.readStrongBinder). A kernel driver gives a process a BINDER_TYPE_BINDER object only
 * for a node the process itself owns, and a process can only own what it has flattened before (BC_TRANSACTION/BC_REPLY
 * offsets) or been told about (BR_INCREFS / BR_ACQUIRE). Anything else of that type is foreign: libbinder would use
 * the cookie as a local IBinder* and call into another process's memory.
 *
 * What is done with a foreign object: binder and cookie are cleared in a private copy of the transaction data, which
 * readStrongBinder() answers with a null binder. The caller gets an error instead of a deadlock; nothing is invented.
 * BC_FREE_BUFFER of the copy is turned back into the original buffer so the daemon's accounting stays as it was.
 *
 * Layout: 32-bit binder (protocol 7), the one libbinderd_kk speaks. flat_binder_object = {type, flags, binder, cookie},
 * binder_transaction_data = 40 bytes (+24 data_size, +28 offsets_size, +32 buffer, +36 offsets).
 */
#ifndef BINDERGUARD_CORE_H
#define BINDERGUARD_CORE_H

typedef unsigned int u32;
typedef unsigned long ulong_g;

#define BWR_IOCTL        0xc0186201u
#define BC_TRANSACTION_G 0x40286300u
#define BC_REPLY_G       0x40286301u
#define BC_FREE_BUFFER_G 0x40046303u
#define BR_TRANSACTION_G 0x80287202u
#define BR_REPLY_G       0x80287203u
#define BR_INCREFS_G     0x80087207u
#define BR_ACQUIRE_G     0x80087208u
#define BR_ATTEMPT_ACQ_G 0x800c720bu
#define TYPE_BINDER_G    0x73622a85u
#define TYPE_WEAK_G      0x77622a85u
#define GUARD_MAX_DATA   (1u << 20)
#define GUARD_MAX_OFFS   (4096u * 4u)

struct g_bwr { long write_size, write_consumed; ulong_g write_buffer; long read_size, read_consumed; ulong_g read_buffer; };

/* supplied by the embedding file */
static void *guard_alloc(u32 len);
static void guard_free(void *p, u32 len);
/* kind: 1 foreign object rewritten, 2 foreign object seen (probe), 3 knowledge table full, 4 no room to rewrite */
static void guard_event(int kind, u32 cmd, u32 code, u32 sender_pid, u32 type, u32 binder, u32 cookie);

#define KN 8192u
static struct { u32 binder, cookie; } g_known[KN];
static volatile int g_known_lock;
static u32 g_known_n;
static int g_known_full;

#define PN 64
static struct { u32 priv, orig, len; } g_priv[PN];
static volatile int g_priv_lock;

static void glock(volatile int *l) { while (__sync_lock_test_and_set(l, 1)) { } }
static void gunlock(volatile int *l) { __sync_lock_release(l); }
static u32 ghash(u32 cookie) { return (cookie * 2654435761u) >> 19 & (KN - 1); }

static void known_add(u32 binder, u32 cookie) {
    if (!cookie) return;
    glock(&g_known_lock);
    if (g_known_n >= KN / 4 * 3) {
        if (!g_known_full) { g_known_full = 1; gunlock(&g_known_lock); guard_event(3, 0, 0, 0, 0, 0, 0); return; }
        gunlock(&g_known_lock);
        return;
    }
    for (u32 i = ghash(cookie), n = 0; n < KN; i = (i + 1) & (KN - 1), n++) {
        if (!g_known[i].cookie) { g_known[i].binder = binder; g_known[i].cookie = cookie; g_known_n++; break; }
        if (g_known[i].cookie == cookie && g_known[i].binder == binder) break;
    }
    gunlock(&g_known_lock);
}
/* a full table knows nothing for sure: everything is taken as local (fail open) */
static int known_has(u32 binder, u32 cookie) {
    int r = 0;
    glock(&g_known_lock);
    if (g_known_full) r = 1;
    else for (u32 i = ghash(cookie), n = 0; n < KN; i = (i + 1) & (KN - 1), n++) {
        if (!g_known[i].cookie) break;
        if (g_known[i].cookie == cookie && g_known[i].binder == binder) { r = 1; break; }
    }
    gunlock(&g_known_lock);
    return r;
}

static const u32 *td_u32(const unsigned char *td) { return (const u32 *)td; }

/* objects a process flattens are its own; remember them */
static void note_out_td(const unsigned char *td) {
    const u32 *t = td_u32(td);
    u32 size = t[6], osz = t[7];
    const unsigned char *d = (const unsigned char *)(ulong_g)t[8];
    const u32 *offs = (const u32 *)(ulong_g)t[9];
    if (!d || !offs || osz > GUARD_MAX_OFFS || size > GUARD_MAX_DATA) return;
    for (u32 i = 0; i < osz / 4; i++) {
        u32 o = offs[i];
        if (o + 16 > size || (o & 3)) continue;
        const u32 *obj = (const u32 *)(d + o);
        if (obj[0] == TYPE_BINDER_G || obj[0] == TYPE_WEAK_G) known_add(obj[2], obj[3]);
    }
}

/* before the real ioctl: learn from the write stream, and give the daemon back the original buffer of a private copy */
static void guard_before(struct g_bwr *b) {
    for (long p = b->write_consumed; p + 4 <= b->write_size;) {
        unsigned char *c = (unsigned char *)(b->write_buffer + p);
        u32 cmd = *(u32 *)c;
        long len = 4 + ((cmd >> 16) & 0x3fff);
        if (p + len > b->write_size) break;
        if ((cmd == BC_TRANSACTION_G || cmd == BC_REPLY_G) && len >= 44) note_out_td(c + 4);
        else if (cmd == BC_FREE_BUFFER_G && len >= 8) {
            u32 ptr = *(u32 *)(c + 4);
            glock(&g_priv_lock);
            for (int i = 0; i < PN; i++) if (g_priv[i].priv == ptr && ptr) {
                u32 orig = g_priv[i].orig, plen = g_priv[i].len;
                g_priv[i].priv = 0;
                *(u32 *)(c + 4) = orig;
                gunlock(&g_priv_lock);
                guard_free((void *)(ulong_g)ptr, plen);
                goto next;
            }
            gunlock(&g_priv_lock);
        }
    next:
        p += len;
    }
}

/* one BR_TRANSACTION / BR_REPLY; returns the number of foreign objects found */
static int guard_td(unsigned char *td, u32 cmd, int enforce) {
    u32 *t = (u32 *)td;
    u32 size = t[6], osz = t[7];
    unsigned char *d = (unsigned char *)(ulong_g)t[8];
    const u32 *offs = (const u32 *)(ulong_g)t[9];
    if (!d || !offs || !osz || osz > GUARD_MAX_OFFS || size > GUARD_MAX_DATA || (t[3] & 8 /* TF_STATUS_CODE */)) return 0;
    u32 bad[16], nbad = 0, nseen = 0;
    for (u32 i = 0; i < osz / 4; i++) {
        u32 o = offs[i];
        if (o + 16 > size || (o & 3)) continue;
        const u32 *obj = (const u32 *)(d + o);
        if (obj[0] != TYPE_BINDER_G && obj[0] != TYPE_WEAK_G) continue;
        if ((!obj[2] && !obj[3]) || known_has(obj[2], obj[3])) continue;
        nseen++;
        if (nbad < 16) bad[nbad++] = o;
    }
    if (!nseen) return 0;
    if (!enforce) {
        for (u32 k = 0; k < nbad; k++) {
            const u32 *obj = (const u32 *)(d + bad[k]);
            guard_event(2, cmd, t[2], t[4], obj[0], obj[2], obj[3]);
        }
        return (int)nseen;
    }
    u32 dlen = (size + 3) & ~3u, total = dlen + osz;
    unsigned char *np = (unsigned char *)guard_alloc(total);
    int slot = -1;
    if (np) {
        glock(&g_priv_lock);
        for (int i = 0; i < PN; i++) if (!g_priv[i].priv) { g_priv[i].priv = (u32)(ulong_g)np; g_priv[i].orig = t[8]; g_priv[i].len = total; slot = i; break; }
        gunlock(&g_priv_lock);
    }
    if (slot < 0) {
        if (np) guard_free(np, total);
        guard_event(4, cmd, t[2], t[4], 0, 0, 0);
        return 0;
    }
    for (u32 i = 0; i < size; i++) np[i] = d[i];
    u32 *no = (u32 *)(np + dlen);
    for (u32 i = 0; i < osz / 4; i++) no[i] = offs[i];
    for (u32 k = 0; k < nbad; k++) {
        u32 *obj = (u32 *)(np + bad[k]);
        guard_event(1, cmd, t[2], t[4], obj[0], obj[2], obj[3]);
        obj[2] = 0; obj[3] = 0;
    }
    t[8] = (u32)(ulong_g)np;
    t[9] = (u32)(ulong_g)no;
    return (int)nseen;
}

/* after the real ioctl: rb[from..end) is what libbinder is about to parse */
static void guard_after(struct g_bwr *b, long from, int enforce) {
    for (long p = from; p + 4 <= b->read_consumed;) {
        unsigned char *c = (unsigned char *)(b->read_buffer + p);
        u32 cmd = *(u32 *)c;
        long len = 4 + ((cmd >> 16) & 0x3fff);
        if (p + len > b->read_consumed) break;
        if ((cmd == BR_INCREFS_G || cmd == BR_ACQUIRE_G || cmd == BR_ATTEMPT_ACQ_G) && len >= 12)
            known_add(*(u32 *)(c + (cmd == BR_ATTEMPT_ACQ_G ? 8 : 4)), *(u32 *)(c + (cmd == BR_ATTEMPT_ACQ_G ? 12 : 8)));
        else if ((cmd == BR_TRANSACTION_G || cmd == BR_REPLY_G) && len >= 44) guard_td(c + 4, cmd, enforce);
        p += len;
    }
}
#endif
