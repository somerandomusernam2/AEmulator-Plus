// The binder front end inside a guest process: turns /dev/binder ioctls into messages for binderd64 (bproto.h).
// Included by run64.c; runs inside the SIGSYS handler, so no libc and little stack.

#include "bproto.h"

// syscalls defined in run64.c: NR_pwrite64, NR_sched_yield, NR_getpid, NR_gettid, NR_sendmsg, NR_recvmsg

#define BINDER_WRITE_READ 0xc0306201u
#define BINDER_SET_MAX_THREADS 0x40046205u
#define BINDER_SET_CONTEXT_MGR 0x40046207u
#define BINDER_THREAD_EXIT 0x40046208u
#define BINDER_VERSION 0xc0046209u
#define BC_TRANSACTION 0x40406300u
#define BC_REPLY 0x40406301u
#define BC_TRANSACTION_SG 0x40486311u
#define BC_REPLY_SG 0x40486312u
#define BC_FREE_BUFFER 0x40086303u
#define BC_AEMU_MAX_THREADS 0x4004633du
#define BC_AEMU_CTX_MGR 0x0000633eu
#define BR_NOOP 0x0000720cu
#define T_FD 0x66642a85u
#define T_FDA 0x66646185u
#define T_PTR 0x70742a85u
#define EAGAIN 11
#define EINTR 4
#define EBADF 9

struct bwr { u64 write_size, write_consumed, write_buffer, read_size, read_consumed, read_buffer; };
struct btd { u64 target, cookie; u32 code, flags; int sender_pid; u32 sender_euid; u64 data_size, offsets_size, buffer, offsets; };
// hwbinder (HIDL) objects: a buffer the driver copies to the other side and re-points, and descriptors kept in one
struct bbo { u32 type, flags; u64 buffer, length, parent, parent_offset; };
struct bfda { u32 type, pad; u64 num_fds, parent, parent_offset; };
#define A8(x) (((x) + 7) & ~7UL)
struct iov { void *base; u64 len; };
struct msgh { void *name; u32 namelen; struct iov *iov; u64 iovlen; void *control; u64 controllen; int flags; };
struct cmsg { u64 len; int level, type; };

static char g_bsock[108];                       // the daemon's socket (AEMU64_BINDER)
static char g_hwbsock[108];                     // AEMU64_HWBINDER
static char g_vndbsock[108];                    // AEMU64_VNDBINDER
static int g_bpid;                              // the process the state below belongs to
static int g_bfds[8];                           // descriptors open("/dev/binder", etc.) returned, +1
static char g_bfd_socks[8][108];
#define NTH 509
static struct bth { int tid, bfd, sock; char *scratch; } g_bth[NTH];
#define SCRATCH (INLINE_MAX + 8192)

// buffers of delivered transactions live in the area the guest mapped from the binder descriptor
struct bhdr { u64 size, id; u32 used, big; u64 pad; };
static u64 g_bmap, g_bmap_size;
static volatile int g_block;

static void block(void) { while (__atomic_exchange_n(&g_block, 1, __ATOMIC_ACQUIRE)) S0(NR_sched_yield); }
static void bunlock(void) { __atomic_store_n(&g_block, 0, __ATOMIC_RELEASE); }

static void *balloc(u64 n, u64 id) {
    n = (n + 15) & ~15UL;
    block();
    if (g_bmap) {
        u64 end = g_bmap + g_bmap_size;
        for (struct bhdr *h = (struct bhdr *)g_bmap; (u64)h < end;) {
            struct bhdr *nx = (struct bhdr *)((u64)(h + 1) + h->size);
            if (!h->used) {
                while ((u64)nx < end && !nx->used) { h->size += sizeof *nx + nx->size; nx = (struct bhdr *)((u64)(h + 1) + h->size); }
                if (h->size >= n) {
                    if (h->size >= n + sizeof *h + 64) {
                        struct bhdr *r = (struct bhdr *)((u64)(h + 1) + n);
                        r->size = h->size - n - sizeof *h; r->used = 0; r->big = 0;
                        h->size = n;
                    }
                    h->used = 1; h->big = 0; h->id = id;
                    bunlock();
                    return h + 1;
                }
            }
            h = nx;
        }
    }
    bunlock();
    u64 total = (n + sizeof(struct bhdr) + 4095) & ~4095UL;
    struct bhdr *h = (struct bhdr *)S6(NR_mmap, 0, total, 3, 0x22, -1, 0);
    if ((u64)h >= (u64)-4095) return 0;
    h->size = total; h->used = 1; h->big = 1; h->id = id;
    return h + 1;
}

static u64 bfree(u64 ptr) {
    struct bhdr *h = (struct bhdr *)ptr - 1;
    u64 id = h->id;
    if (h->big) S2(NR_munmap, h, h->size);
    else { block(); h->used = 0; bunlock(); }
    return id;
}

static int is_binder_fd(int fd) {
    for (int i = 0; i < 8; i++) if (g_bfds[i] == fd + 1) return 1;
    return 0;
}

static const char *sock_for_fd(int fd) {
    for (int i = 0; i < 8; i++) {
        if (g_bfds[i] == fd + 1 && g_bfd_socks[i][0]) return g_bfd_socks[i];
    }
    return g_bsock;
}

static long bconnect(int main_, int cloexec, int bfd, const char *sock_path) {
    long s = S3(NR_socket, 1, 5 /*SOCK_SEQPACKET*/ | (cloexec ? 02000000 : 0), 0);
    if (s < 0) return s;
    struct sockaddr_un sa; sa.family = 1;
    if (!sock_path || !sock_path[0]) sock_path = g_bsock;
    u64 l = slen(sock_path); memcpy(sa.path, sock_path, l + 1);
    long r = S3(NR_connect, s, &sa, 2 + l + 1);
    if (r < 0) { S1(NR_close, s); return r; }
    struct c_hello m = { { OP_HELLO, 0, 0, (u32)g_uid }, (u32)S0(NR_getpid), (u32)S0(NR_gettid), (u32)main_, (u32)(main_ ? s : bfd) };
    struct iov v = { &m, sizeof m }; struct msgh mh = { 0, 0, &v, 1, 0, 0, 0 };
    S3(NR_sendmsg, s, &mh, 0x4000 /*MSG_NOSIGNAL*/);
    return s;
}

static struct bth *bthread(int bfd, int create) {
    int tid = (int)S0(NR_gettid);
    u32 i = (u32)tid % NTH, j = i;
    for (int n = 0; n < NTH; n++, i = (i + 1) % NTH) {
        struct bth *t = &g_bth[i];
        if (t->tid == tid && t->bfd == bfd) return t;
        if (t->tid == 0) break;
    }
    if (!create) return 0;
    for (int n = 0; n < NTH; n++, j = (j + 1) % NTH) {      // a free slot, or one left by a thread that exited
        struct bth *t = &g_bth[j];
        int z = t->tid;
        if ((z == 0 || z == -1) && __atomic_compare_exchange_n(&t->tid, &z, tid, 0, __ATOMIC_ACQ_REL, __ATOMIC_RELAXED)) {
            t->bfd = bfd; t->sock = 0;
            return t;
        }
    }
    return 0;
}

static long binder_open(long flags, const char *dev_name) {
    const char *target_sock = g_bsock;
    if (dev_name) {
        if (seq(dev_name, "/dev/hwbinder") && g_hwbsock[0]) target_sock = g_hwbsock;
        else if (seq(dev_name, "/dev/vndbinder") && g_vndbsock[0]) target_sock = g_vndbsock;
    }
    if (!target_sock[0]) return -ENOENT;
    int pid = (int)S0(NR_getpid);
    if (g_bpid != pid) {                        // a fresh process (or a fork child): nothing is inherited
        memset(g_bth, 0, sizeof g_bth); memset(g_bfds, 0, sizeof g_bfds);
        memset(g_bfd_socks, 0, sizeof g_bfd_socks);
        g_bmap = 0; g_block = 0; g_bpid = pid;
    }
    long s = bconnect(1, (flags & 02000000) != 0, -1, target_sock);
    if (s < 0) return s;
    for (int i = 0; i < 8; i++) if (!g_bfds[i]) {
        g_bfds[i] = (int)s + 1;
        u64 l = slen(target_sock); if (l < sizeof g_bfd_socks[i]) memcpy(g_bfd_socks[i], target_sock, l + 1);
        break;
    }
    struct bth *t = bthread((int)s, 1);
    if (t) t->sock = (int)s;
    return s;
}

static long binder_mmap(u64 addr, u64 len, long flags) {
    // qemu-arm maps at an address of its choice (MAP_FIXED inside the guest's 4 GB): keep it
    u64 m = (u64)S6(NR_mmap, addr, len, 3, 0x22 | (flags & 0x10), -1, 0);
    if (m >= (u64)-4095) return (long)m;
    block();
    struct bhdr *h = (struct bhdr *)m; h->size = len - sizeof *h; h->used = 0; h->big = 0;
    g_bmap = m; g_bmap_size = len;
    bunlock();
    return (long)m;
}

static long bsendv(int sock, struct iov *v, int n, int *fds, int nfds);

static long bsend(int sock, void *a, u64 an, void *b, u64 bn, void *c, u64 cn, int *fds, int nfds) {
    struct iov v[4]; int n = 0; static const u64 zero;
    v[n].base = a; v[n++].len = an;
    if (bn) { v[n].base = b; v[n++].len = bn; if (cn && (bn & 7)) { v[n].base = (void *)&zero; v[n++].len = 8 - (bn & 7); } }
    if (cn) { v[n].base = c; v[n++].len = cn; }
    return bsendv(sock, v, n, fds, nfds);
}

static long bsendv(int sock, struct iov *v, int n, int *fds, int nfds) {
    struct { struct cmsg h; int fd[254]; } cm;
    struct msgh mh = { 0, 0, v, (u64)n, 0, 0, 0 };
    if (nfds) {
        cm.h.len = sizeof cm.h + (u64)nfds * 4; cm.h.level = 1; cm.h.type = 1;
        memcpy(cm.fd, fds, (u64)nfds * 4);
        mh.control = &cm; mh.controllen = (sizeof cm.h + (u64)nfds * 4 + 7) & ~7UL;
    }
    long r;
    do r = S3(NR_sendmsg, sock, &mh, 0x4000); while (r == -EINTR);
    return r;
}

static char *bscratch(struct bth *t) {
    if (!t->scratch) {
        u64 m = (u64)S6(NR_mmap, 0, SCRATCH, 3, 0x22, -1, 0);
        if (m >= (u64)-4095) return 0;
        t->scratch = (char *)m;
    }
    return t->scratch;
}

static long btxn(struct bth *t, u32 cmd, struct btd *d, u32 flags, u32 read_size) {
    struct c_txn m = { { OP_TXN, flags, read_size, (u32)g_uid }, cmd, (u32)d->target, d->code, d->flags, d->data_size, d->offsets_size, 0 };
    int fds[254], nfds = 0;
    u64 *offs = (u64 *)d->offsets, no = d->offsets_size / 8, extra = 0;
    for (u64 i = 0; i < no; i++) {
        if (offs[i] + 24 > d->data_size) continue;
        u32 *o = (u32 *)(d->buffer + offs[i]);
        if (o[0] == T_FD && nfds < 252) fds[1 + nfds++] = (int)o[2];
        else if (o[0] == T_PTR) extra += A8(((struct bbo *)o)->length);
        else if (o[0] == T_FDA) {
            struct bfda *f = (struct bfda *)o;
            if (f->parent >= i) return -EINVAL;
            struct bbo *par = (struct bbo *)(d->buffer + offs[f->parent]);
            int *src = (int *)(par->buffer + f->parent_offset);
            if (par->type != T_PTR || f->num_fds > (u64)(252 - nfds)) return -EINVAL;
            for (u64 k = 0; k < f->num_fds; k++) fds[1 + nfds++] = src[k];
        }
    }
    m.extra_size = extra;
    u64 dsz = A8(d->data_size), total = dsz + d->offsets_size + extra;
    if (total > INLINE_MAX) {
        long mf = S2(NR_memfd_create, "aemu-binder", 1);
        if (mf < 0) return mf;
        S4(NR_pwrite64, mf, d->buffer, d->data_size, 0);
        S4(NR_pwrite64, mf, d->offsets, d->offsets_size, dsz);
        u64 at = dsz + d->offsets_size;
        if (extra) for (u64 i = 0; i < no; i++) {
            struct bbo *o = (struct bbo *)(d->buffer + offs[i]);
            if (offs[i] + 40 > d->data_size || o->type != T_PTR) continue;
            if (o->length) S4(NR_pwrite64, mf, o->buffer, o->length, at);
            at += A8(o->length);
        }
        m.h.flags |= F_BIG; fds[0] = (int)mf;
        long r = bsend(t->sock, &m, sizeof m, 0, 0, 0, 0, fds, nfds + 1);
        S1(NR_close, mf);
        return r;
    }
    if (!extra) return bsend(t->sock, &m, sizeof m, (void *)d->buffer, d->data_size, (void *)d->offsets, d->offsets_size, fds + 1, nfds);
    static const u64 zero;
    char *x = bscratch(t);
    if (!x) return -ENOMEM;
    u64 at = 0;
    for (u64 i = 0; i < no; i++) {
        struct bbo *o = (struct bbo *)(d->buffer + offs[i]);
        if (offs[i] + 40 > d->data_size || o->type != T_PTR) continue;
        if (o->length) memcpy(x + at, (void *)o->buffer, o->length);
        for (u64 k = o->length; k < A8(o->length); k++) x[at + k] = 0;
        at += A8(o->length);
    }
    struct iov v[5]; int n = 0;
    v[n].base = &m; v[n++].len = sizeof m;
    v[n].base = (void *)d->buffer; v[n++].len = d->data_size;
    if (d->data_size & 7) { v[n].base = (void *)&zero; v[n++].len = 8 - (d->data_size & 7); }
    v[n].base = (void *)d->offsets; v[n++].len = d->offsets_size;
    v[n].base = x; v[n++].len = extra;
    return bsendv(t->sock, v, n, fds + 1, nfds);
}

static long bread(struct bth *t, struct bwr *w) {
    if (!bscratch(t)) return -ENOMEM;
    struct d_hdr h;
    struct { struct cmsg h; int fd[254]; } cm;
    struct iov v[2] = { { &h, sizeof h }, { t->scratch, SCRATCH } };
    struct msgh mh = { 0, 0, v, 2, &cm, sizeof cm, 0 };
    long r;
    do {
        mh.controllen = sizeof cm;
        r = S3(NR_recvmsg, t->sock, &mh, 0x40000000 /*MSG_CMSG_CLOEXEC*/);
    } while (r == -EINTR || (r >= (long)sizeof h && (h.flags & F_WAKE)));
    if (r < (long)sizeof h) return r < 0 ? r : -EBADF;
    if (h.flags & F_AGAIN) return -EAGAIN;
    int nfds = 0, *fds = cm.fd;
    if (mh.controllen >= sizeof cm.h && cm.h.level == 1 && cm.h.type == 1) nfds = (int)((cm.h.len - sizeof cm.h) / 4);
    u32 *out = (u32 *)(w->read_buffer + w->read_consumed);
    *out++ = BR_NOOP;
    memcpy(out, t->scratch, h.br_size); out = (u32 *)((char *)out + h.br_size);
    if (h.flags & F_TXN) {
        u64 dsz = A8(h.data_size), total = dsz + h.offsets_size + h.extra_size;
        char *buf = balloc(total ? total : 8, h.id);
        if (!buf) return -ENOMEM;
        if (h.flags & F_BIG) {
            u64 got = 0;
            while (got < total) { long k = S4(NR_pread64, fds[0], buf + got, total - got, got); if (k <= 0) break; got += (u64)k; }
            S1(NR_close, fds[0]); fds++; nfds--;
        } else memcpy(buf, t->scratch + h.br_size, total);
        u64 *offs = (u64 *)(buf + dsz), no = h.offsets_size / 8; int k = 0;
        char *sg = (char *)(offs + no), *sgend = buf + total;
        for (u64 i = 0; i < no; i++) {
            u32 *o = (u32 *)(buf + offs[i]);
            if (o[0] == T_FD) { o[2] = k < nfds ? (u32)fds[k] : (u32)-1; o[3] = 0; k++; }
            else if (o[0] == T_PTR) {           // the buffer is here now; the pointer to it inside its parent follows
                struct bbo *b = (struct bbo *)o;
                if (A8(b->length) > (u64)(sgend - sg)) { b->buffer = 0; b->length = 0; continue; }
                b->buffer = (u64)sg; sg += A8(b->length);
                if ((b->flags & 1) && b->parent < i) {
                    struct bbo *par = (struct bbo *)(buf + offs[b->parent]);
                    if (par->type == T_PTR && b->parent_offset + 8 <= par->length) *(u64 *)(par->buffer + b->parent_offset) = b->buffer;
                }
            } else if (o[0] == T_FDA) {
                struct bfda *f = (struct bfda *)o;
                if (f->parent >= i) continue;
                struct bbo *par = (struct bbo *)(buf + offs[f->parent]);
                if (par->type != T_PTR || f->parent_offset + f->num_fds * 4 > par->length) continue;
                int *dst = (int *)(par->buffer + f->parent_offset);
                for (u64 j = 0; j < f->num_fds; j++, k++) dst[j] = k < nfds ? fds[k] : -1;
            }
        }
        struct btd d = { h.ptr, h.cookie, h.code, h.tflags, h.sender_pid, h.sender_euid, h.data_size, h.offsets_size, (u64)buf, (u64)offs };
        *out++ = h.cmd; memcpy(out, &d, sizeof d); out = (u32 *)((char *)out + sizeof d);
    } else for (int i = 0; i < nfds; i++) S1(NR_close, fds[i]);
    w->read_consumed = (u64)((char *)out - (char *)w->read_buffer);
    return 0;
}

static long bwrite_read(struct bth *t, struct bwr *w) {
    u32 small[256]; u32 ns = 0;
    struct c_hdr sh = { OP_BCS, 0, (u32)w->read_size, (u32)g_uid };
    int want = w->read_size > w->read_consumed, asked = 0;
    char *p = (char *)w->write_buffer + w->write_consumed, *end = (char *)w->write_buffer + w->write_size;
    long r = 0;
    while (p + 4 <= end) {
        u32 cmd = *(u32 *)p, sz = (cmd >> 16) & 0x3fff;
        if (p + 4 + sz > end) break;
        if (cmd == BC_TRANSACTION_SG || cmd == BC_REPLY_SG) cmd = cmd == BC_REPLY_SG ? BC_REPLY : BC_TRANSACTION;   // the buffers are found by their objects
        if (cmd == BC_TRANSACTION || cmd == BC_REPLY) {
            if (ns) { if ((r = bsend(t->sock, &sh, sizeof sh, small, ns * 4, 0, 0, 0, 0)) < 0) return r; ns = 0; }
            int last = p + 4 + sz >= end && want;
            if ((r = btxn(t, cmd, (struct btd *)(p + 4), last ? F_READ : 0, (u32)w->read_size)) < 0) return r;
            asked = last;
        } else {
            if (ns + 8 > 256) { if ((r = bsend(t->sock, &sh, sizeof sh, small, ns * 4, 0, 0, 0, 0)) < 0) return r; ns = 0; }
            if (cmd == BC_FREE_BUFFER) {
                u64 ptr; memcpy(&ptr, p + 4, 8);
                u64 id = bfree(ptr);
                small[ns++] = BC_AEMU_FREE; memcpy(small + ns, &id, 8); ns += 2;
            } else if (sz <= 16) {
                small[ns++] = cmd; memcpy(small + ns, p + 4, sz); ns += (sz + 3) / 4;
            }
            asked = 0;
        }
        p += 4 + sz;
    }
    w->write_consumed = w->write_size;
    if (ns) {
        if (want) sh.flags = F_READ;
        if ((r = bsend(t->sock, &sh, sizeof sh, small, ns * 4, 0, 0, 0, 0)) < 0) return r;
        asked = want;
    }
    if (!want) return 0;
    if (!asked) {
        struct c_hdr rh = { OP_READ, F_READ, (u32)w->read_size, (u32)g_uid };
        if ((r = bsend(t->sock, &rh, sizeof rh, 0, 0, 0, 0, 0, 0)) < 0) return r;
    }
    return bread(t, w);
}

// IPCThreadState::stopProcess closes the driver so that the threads waiting in it return (bootanimation ends that
// way): closing a socket does not wake its readers, shutting it down does
static void binder_closed(int fd) {
    for (int i = 0; i < NTH; i++) {
        struct bth *t = &g_bth[i];
        if (t->bfd == fd && t->tid > 0 && t->sock > 0) S2(NR_shutdown, t->sock, 2);
    }
}

// ---------------------------------------------------------------------------------------------------- protocol 7
// A 32-bit firmware built with BINDER_IPC_32BIT (Android Go / 32-bit-only 8.x) insists on binder protocol 7: the
// driver answers BINDER_VERSION with 7 and every structure it passes is laid out with 32-bit pointers
// (binder_write_read 24 bytes, binder_transaction_data 40, flat_binder_object 16, ...). binderd64 speaks protocol 8
// (64-bit fields), so for such a program (qemu-arm, AEMU64_BINDER_PROTOCOL=7) the front end translates at the
// boundary: guest -> daemon on BINDER_WRITE_READ, daemon -> guest on the way back. Programs that are not 32-bit
// translated ones, and firmware that wants 8, never come here.

#define BWR32 0xc0186201u                  /* _IOWR('b', 1, 24 bytes) */
#define BC_INCREFS_DONE64 0x40106308u
#define BC_ACQUIRE_DONE64 0x40106309u
#define BC_REQUEST_DEATH64 0x400c630eu
#define BC_CLEAR_DEATH64 0x400c630fu
#define BC_DEAD_BINDER_DONE64 0x40086310u
#define BR_TRANSACTION64 0x80407202u
#define BR_REPLY64 0x80407203u
#define BR_TRANSACTION32 0x80287202u
#define BR_REPLY32 0x80287203u
#define T_BINDER 0x73622a85u
#define T_WBINDER 0x77622a85u
#define T_HANDLE 0x73682a85u
#define T_WHANDLE 0x77682a85u

static int g_bproto;                        // AEMU64_BINDER_PROTOCOL: what the firmware's libbinder wants (0: the engine's 8)
static int bcompat32(void) { return g_is32 && g_bproto == 7; }

static u32 bobj32(u32 type) {
    switch (type) {
    case T_BINDER: case T_WBINDER: case T_HANDLE: case T_WHANDLE: case T_FD: return 16;
    case T_PTR: return 24;
    case T_FDA: return 20;
    }
    return 0;
}
static u32 bobj64(u32 type) { return type == T_PTR ? 40 : type == T_FDA ? 32 : bobj32(type) ? 24 : 0; }

static void bcopy_fw(char *d, const char *s, u64 n) { for (u64 i = 0; i < n; i++) d[i] = s[i]; }   // d <= s, may overlap

#define MAXBLK 8
struct bblk { void *p; u64 n; };

static void bblk_free(struct bblk *b, int n) { for (int i = 0; i < n; i++) if (b[i].p) S2(NR_munmap, b[i].p, b[i].n); }

// 32-bit binder_transaction_data s[10] -> d; the parcel is rewritten into a new mapping (returned in *blk) when it holds objects
static long txn32to64(const u32 *s, struct btd *d, struct bblk *blk) {
    d->target = s[0]; d->cookie = s[1]; d->code = s[2]; d->flags = s[3]; d->sender_pid = (int)s[4]; d->sender_euid = s[5];
    u32 dsz = s[6], osz = s[7], no = osz / 4;
    const char *data = (const char *)(u64)s[8];
    const u32 *offs = (const u32 *)(u64)s[9];
    blk->p = 0; blk->n = 0;
    if (!no) { d->data_size = dsz; d->offsets_size = 0; d->buffer = s[8]; d->offsets = s[9]; return 0; }
    u64 grow = 0, pos = 0;
    for (u32 i = 0; i < no; i++) {
        u32 off = offs[i], sz;
        if (off < pos || (u64)off + 4 > dsz) return -EINVAL;
        u32 type = *(const u32 *)(data + off);
        if (!(sz = bobj32(type)) || (u64)off + sz > dsz) return -EINVAL;
        grow += bobj64(type) - sz; pos = (u64)off + sz;
    }
    u64 ndsz = dsz + grow, total = ((A8(ndsz) + (u64)no * 8) + 4095) & ~4095UL;
    char *nd = (char *)S6(NR_mmap, 0, total, 3, 0x22, -1, 0);
    if ((u64)nd >= (u64)-4095) return -ENOMEM;
    blk->p = nd; blk->n = total;
    u64 *no64 = (u64 *)(nd + A8(ndsz));
    u64 p32 = 0, p64 = 0;
    for (u32 i = 0; i < no; i++) {
        u32 off = offs[i];
        memcpy(nd + p64, data + p32, off - p32); p64 += off - p32; p32 = off;
        const u32 *in = (const u32 *)(data + off);
        u32 type = in[0], *o = (u32 *)(nd + p64);
        no64[i] = p64;
        if (type == T_PTR) {
            struct bbo *b = (struct bbo *)o;
            b->type = type; b->flags = in[1]; b->buffer = in[2]; b->length = in[3]; b->parent = in[4]; b->parent_offset = in[5];
        } else if (type == T_FDA) {
            struct bfda *f = (struct bfda *)o;
            f->type = type; f->pad = in[1]; f->num_fds = in[2]; f->parent = in[3]; f->parent_offset = in[4];
        } else {
            o[0] = type; o[1] = in[1]; *(u64 *)(o + 2) = in[2]; *(u64 *)(o + 4) = in[3];
        }
        p32 += bobj32(type); p64 += bobj64(type);
    }
    memcpy(nd + p64, data + p32, dsz - p32);
    d->data_size = ndsz; d->offsets_size = (u64)no * 8; d->buffer = (u64)nd; d->offsets = (u64)no64;
    return 0;
}

// the transaction a delivered buffer (64-bit layout, made by bread) holds -> 32-bit binder_transaction_data out[10];
// the parcel is shrunk where it lies, so the buffer keeps its place in the mapping (and BC_FREE_BUFFER its header)
static long txn64to32(const struct btd *d, u32 *out) {
    char *buf = (char *)d->buffer;
    u64 dsz = d->data_size, no = d->offsets_size / 8;
    u64 *offs = (u64 *)d->offsets;
    u64 nd = dsz;
    if (no) {
        u64 po = 0, pn = 0;
        for (u64 i = 0; i < no; i++) {
            u64 off = offs[i];
            if (off < po || off + 4 > dsz) return -EINVAL;
            u32 type = *(u32 *)(buf + off), s64 = bobj64(type), s32 = bobj32(type);
            if (!s64 || off + s64 > dsz) return -EINVAL;
            bcopy_fw(buf + pn, buf + po, off - po); pn += off - po;
            u32 w[10]; memcpy(w, buf + off, s64 > 40 ? 40 : s64);
            u32 *o = (u32 *)(buf + pn);
            offs[i] = pn;                                           // the new offset, narrowed below
            if (type == T_PTR) {
                const struct bbo *b = (const struct bbo *)w;
                o[0] = type; o[1] = b->flags; o[2] = (u32)b->buffer; o[3] = (u32)b->length; o[4] = (u32)b->parent; o[5] = (u32)b->parent_offset;
            } else if (type == T_FDA) {
                const struct bfda *f = (const struct bfda *)w;
                o[0] = type; o[1] = f->pad; o[2] = (u32)f->num_fds; o[3] = (u32)f->parent; o[4] = (u32)f->parent_offset;
            } else {
                o[0] = type; o[1] = w[1]; o[2] = w[2]; o[3] = w[4];
            }
            pn += s32; po = off + s64;
        }
        bcopy_fw(buf + pn, buf + po, dsz - po); pn += dsz - po;
        nd = pn;
        u32 *no32 = (u32 *)(buf + A8(nd));                          // lies at or below the u64 array it is made from
        for (u64 i = 0; i < no; i++) no32[i] = (u32)offs[i];
        out[9] = (u32)(u64)no32;
    } else out[9] = (u32)d->offsets;
    out[0] = (u32)d->target; out[1] = (u32)d->cookie; out[2] = d->code; out[3] = d->flags;
    out[4] = (u32)d->sender_pid; out[5] = d->sender_euid;
    out[6] = (u32)nd; out[7] = (u32)(no * 4); out[8] = (u32)(u64)buf;
    return 0;
}

static long bwrite_read32(struct bth *t, u32 *w32) {
    u64 ws = w32[0], wc = w32[1], rs = w32[3], rc = w32[4];
    if (wc > ws) return -EINVAL;
    u64 rwant = rs > rc ? rs - rc : 0;
    u64 wcap = (ws - wc) * 2 + 128, total = (wcap + rwant + 128 + 4095) & ~4095UL;
    char *mem = (char *)S6(NR_mmap, 0, total, 3, 0x22, -1, 0);
    if ((u64)mem >= (u64)-4095) return -ENOMEM;
    char *wconv = mem, *rtmp = mem + wcap;
    struct bblk blk[MAXBLK]; int nb = 0;
    long r = 0;
    // guest commands -> the daemon's layout
    const char *p = (const char *)(u64)w32[2] + wc, *end = (const char *)(u64)w32[2] + ws;
    char *q = wconv;
    while (p + 4 <= end) {
        u32 cmd = *(const u32 *)p, sz = (cmd >> 16) & 0x3fff, nr = cmd & 0xff;
        if (p + 4 + sz > end) break;
        const u32 *a = (const u32 *)(p + 4);
        if (((cmd >> 8) & 0xff) == 'c' && (nr == 0 || nr == 1 || nr == 0x11 || nr == 0x12) && sz >= 40) {
            if (nb >= MAXBLK) { r = -ENOMEM; break; }
            struct btd d;
            if ((r = txn32to64(a, &d, &blk[nb])) < 0) break;
            if (blk[nb].p) nb++;
            u32 c64 = (nr == 0 || nr == 0x11) ? BC_TRANSACTION : BC_REPLY;
            memcpy(q, &c64, 4); memcpy(q + 4, &d, sizeof d); q += 4 + sizeof d;
        } else if (((cmd >> 8) & 0xff) == 'c' && nr == 3 && sz == 4) {
            u32 c64 = BC_FREE_BUFFER; u64 v = a[0];
            memcpy(q, &c64, 4); memcpy(q + 4, &v, 8); q += 12;
        } else if (((cmd >> 8) & 0xff) == 'c' && (nr == 8 || nr == 9) && sz == 8) {
            u32 c64 = nr == 8 ? BC_INCREFS_DONE64 : BC_ACQUIRE_DONE64; u64 v[2] = { a[0], a[1] };
            memcpy(q, &c64, 4); memcpy(q + 4, v, 16); q += 20;
        } else if (((cmd >> 8) & 0xff) == 'c' && (nr == 0x0e || nr == 0x0f) && sz == 8) {
            u32 c64 = nr == 0x0e ? BC_REQUEST_DEATH64 : BC_CLEAR_DEATH64; u64 v = a[1];
            memcpy(q, &c64, 4); memcpy(q + 4, &a[0], 4); memcpy(q + 8, &v, 8); q += 16;
        } else if (((cmd >> 8) & 0xff) == 'c' && nr == 0x10 && sz == 4) {
            u32 c64 = BC_DEAD_BINDER_DONE64; u64 v = a[0];
            memcpy(q, &c64, 4); memcpy(q + 4, &v, 8); q += 12;
        } else { memcpy(q, p, 4 + sz); q += 4 + sz; }
        p += 4 + sz;
    }
    if (r < 0) { bblk_free(blk, nb); S2(NR_munmap, mem, total); return r; }
    struct bwr b64 = { (u64)(q - wconv), 0, (u64)wconv, rwant, 0, (u64)rtmp };
    r = bwrite_read(t, &b64);
    bblk_free(blk, nb);
    if (r < 0) { S2(NR_munmap, mem, total); return r; }
    w32[1] = (u32)ws;
    // the daemon's answers -> the guest's layout (never longer than what the daemon was allowed to write)
    u32 *o = (u32 *)(u64)(w32[5] + rc);
    const char *s = rtmp, *se = rtmp + b64.read_consumed;
    char *oq = (char *)o;
    while (s + 4 <= se) {
        u32 cmd = *(const u32 *)s, sz = (cmd >> 16) & 0x3fff, nr = cmd & 0xff;
        if (s + 4 + sz > se) break;
        const u32 *a = (const u32 *)(s + 4);
        u32 c32;
        if (cmd == BR_TRANSACTION64 || cmd == BR_REPLY64) {
            u32 out[10]; struct btd d; memcpy(&d, s + 4, sizeof d);
            if ((r = txn64to32(&d, out)) < 0) break;
            c32 = cmd == BR_TRANSACTION64 ? BR_TRANSACTION32 : BR_REPLY32;
            memcpy(oq, &c32, 4); memcpy(oq + 4, out, 40); oq += 44;
        } else if (((cmd >> 8) & 0xff) == 'r' && nr >= 7 && nr <= 0x0a && sz == 16) {
            u32 v[2] = { (u32)*(const u64 *)a, (u32)*(const u64 *)(a + 2) };
            c32 = 0x80087200u | nr;
            memcpy(oq, &c32, 4); memcpy(oq + 4, v, 8); oq += 12;
        } else if (((cmd >> 8) & 0xff) == 'r' && (nr == 0x0f || nr == 0x10) && sz == 8) {
            u32 v = (u32)*(const u64 *)a;
            c32 = 0x80047200u | nr;
            memcpy(oq, &c32, 4); memcpy(oq + 4, &v, 4); oq += 8;
        } else { memcpy(oq, s, 4 + sz); oq += 4 + sz; }
        s += 4 + sz;
    }
    if (r >= 0) w32[4] = (u32)(rc + (u64)(oq - (char *)o));
    S2(NR_munmap, mem, total);
    return r;
}

static long binder_ioctl(int fd, u32 cmd, u64 arg) {
    struct bth *t = bthread(fd, 1);
    if (!t) return -ENOMEM;
    if (!t->sock) {
        long s = bconnect(0, 1, fd, sock_for_fd(fd));
        if (s < 0) { t->tid = 0; return s; }
        t->sock = (int)s;
    }
    if (bcompat32()) {                          // protocol 7 firmware (see above)
        if (cmd == BWR32) return bwrite_read32(t, (u32 *)arg);
        if (cmd == BINDER_VERSION) { *(int *)arg = 7; return 0; }
        if (cmd == BINDER_WRITE_READ) return -EINVAL;
    }
    switch (cmd) {
    case BINDER_WRITE_READ: return bwrite_read(t, (struct bwr *)arg);
    case BINDER_VERSION: *(int *)arg = 8; return 0;
    case BINDER_SET_MAX_THREADS: case BINDER_SET_CONTEXT_MGR: {
        struct { struct c_hdr h; u32 c, v; } m = { { OP_BCS, 0, 0, (u32)g_uid }, cmd == BINDER_SET_MAX_THREADS ? BC_AEMU_MAX_THREADS : BC_AEMU_CTX_MGR, arg ? *(u32 *)arg : 0 };
        return bsend(t->sock, &m, cmd == BINDER_SET_MAX_THREADS ? sizeof m : sizeof m - 4, 0, 0, 0, 0, 0, 0) < 0 ? -EBADF : 0;
    }
    case BINDER_THREAD_EXIT:
        if (t->sock != fd) S1(NR_close, t->sock);
        t->sock = 0; t->bfd = -1; t->tid = -1;      // the slot stays taken: probing must not stop here
        return 0;
    }
    return -EINVAL;
}
