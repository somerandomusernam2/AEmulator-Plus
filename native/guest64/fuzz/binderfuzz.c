// binderfuzz — drives binderd64 over its real wire protocol (bproto.h) with a model of libbinder clients:
// processes with several threads, nested calls, replies, objects (nodes, handles, fds, memfd-sized buffers),
// reference counting, death notifications, loopers, and process / thread kills at any moment.
//
//   binderfuzz <binderd binary> fuzz  <first seed> <last seed> <steps> [-H]
//   binderfuzz <binderd binary> regress
//
// -H adds a hostile client: bogus BC_AEMU_FREE ids, over-released handles, replies the thread does not owe,
// calls to its own process, truncated transactions.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <dirent.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include "bproto.h"

typedef uint64_t u64; typedef uint32_t u32;
#define BC_TRANSACTION 0x40406300u
#define BC_REPLY 0x40406301u
#define BC_INCREFS 0x40046304u
#define BC_ACQUIRE 0x40046305u
#define BC_RELEASE 0x40046306u
#define BC_DECREFS 0x40046307u
#define BC_INCREFS_DONE 0x40106308u
#define BC_ACQUIRE_DONE 0x40106309u
#define BC_REGISTER_LOOPER 0x0000630bu
#define BC_ENTER_LOOPER 0x0000630cu
#define BC_REQUEST_DEATH_NOTIFICATION 0x400c630eu
#define BC_CLEAR_DEATH_NOTIFICATION 0x400c630fu
#define BC_DEAD_BINDER_DONE 0x40086310u
#define BC_AEMU_MAX_THREADS 0x4004633du
#define BC_AEMU_CTX_MGR 0x0000633eu
#define BR_TRANSACTION 0x80407202u
#define BR_REPLY 0x80407203u
#define BR_DEAD_REPLY 0x00007205u
#define BR_TRANSACTION_COMPLETE 0x00007206u
#define BR_INCREFS 0x80107207u
#define BR_ACQUIRE 0x80107208u
#define BR_RELEASE 0x80107209u
#define BR_DECREFS 0x8010720au
#define BR_SPAWN_LOOPER 0x0000720du
#define BR_DEAD_BINDER 0x8008720fu
#define BR_CLEAR_DEATH_NOTIFICATION_DONE 0x80087210u
#define BR_FAILED_REPLY 0x00007211u
#define T_BINDER 0x73622a85u
#define T_WEAK_BINDER 0x77622a85u
#define T_HANDLE 0x73682a85u
#define T_WEAK_HANDLE 0x77682a85u
#define T_FD 0x66642a85u
#define TF_ONE_WAY 1
#define A8(x) (((x) + 7) & ~7UL)
struct fbo { u32 type, flags; u64 binder, cookie; };

// ------------------------------------------------------------------------------------------ rng, daemon
static u64 rs = 88172645463325252ull;
static u32 rnd(u32 n) { rs ^= rs << 13; rs ^= rs >> 7; rs ^= rs << 17; return n ? (u32)((rs >> 11) % n) : 0; }
static int chance(int pct) { return (int)rnd(100) < pct; }

static pid_t dpid; static char sockpath[200], errlog[200];
static const char *dbin;
static int dead, dstatus, hostile, verbose_log;

static void start_daemon(void) {
    snprintf(sockpath, sizeof sockpath, "/tmp/bfuzz-%d.sock", getpid());
    unlink(sockpath);
    dpid = fork();
    if (!dpid) {
        int fd = open(errlog, O_WRONLY | O_CREAT | O_TRUNC, 0644); dup2(fd, 2); dup2(fd, 1);
        setenv("ASAN_OPTIONS", "detect_leaks=0:abort_on_error=0:handle_segv=1:allocator_may_return_null=0", 1);
        setenv("UBSAN_OPTIONS", "print_stacktrace=1:halt_on_error=1", 1);
        execl(dbin, dbin, sockpath, (char *)0); _exit(127);
    }
    for (int i = 0; i < 500; i++) { if (access(sockpath, F_OK) == 0) return; usleep(10000); }
    fprintf(stderr, "daemon did not start\n"); exit(2);
}
static void stop_daemon(void) {
    if (dpid > 0 && !dead) { kill(dpid, SIGKILL); waitpid(dpid, 0, 0); }
    unlink(sockpath);
}
static int dial(void) {
    int s = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    struct sockaddr_un sa = { .sun_family = AF_UNIX }; strcpy(sa.sun_path, sockpath);
    for (int i = 0; i < 200; i++) { if (connect(s, (struct sockaddr *)&sa, sizeof sa) == 0) return s; usleep(5000); }
    close(s); return -1;
}
static int xsend(int fd, const void *buf, size_t n, int *fds, int nfds);
static int probe_fd = -1;
static int daemon_fds(void) {
    char p[64]; snprintf(p, sizeof p, "/proc/%d/fd", dpid); DIR *d = opendir(p); if (!d) return -1;
    int n = 0; while (readdir(d)) n++; closedir(d); return n - 2;
}
static void sync_daemon(void);
static void wait_daemon_fds(int expected_max) {      // closes are seen by the daemon as end-of-file: it closes its side when it has handled them
    for (int i = 0; i < 5000 && !dead; i++) { int n = daemon_fds(); if (n >= 0 && n <= expected_max) break; usleep(200); }
    sync_daemon();
}
static void sync_daemon(void) {       // a non-blocking read on a process of its own: answered once everything sent before is handled
    if (dead) return;
    struct c_hdr h = { OP_BCS, F_READ | F_NONBLOCK, 256, 0 };
    if (send(probe_fd, &h, sizeof h, MSG_NOSIGNAL) < 0) goto died;
    struct pollfd p = { probe_fd, POLLIN, 0 };
    for (;;) {
        int r = poll(&p, 1, 20000);
        if (r <= 0) goto died;
        char b[512]; ssize_t n = recv(probe_fd, b, sizeof b, 0);
        if (n < 0) goto died;
        if (n >= (ssize_t)sizeof(struct d_hdr) && (((struct d_hdr *)b)->flags & F_AGAIN)) return;
        if (n == 0) goto died;
    }
died:
    { int st; if (waitpid(dpid, &st, WNOHANG) == dpid) dstatus = st; else { kill(dpid, SIGKILL); waitpid(dpid, &st, 0); dstatus = st; } dead = 1; }
}
static int xsend(int fd, const void *buf, size_t n, int *fds, int nfds) {
    if (dead) return -1;
    struct iovec iov = { (void *)buf, n }; struct msghdr mh = { 0 };
    char cb[CMSG_SPACE(sizeof(int) * 254)];
    mh.msg_iov = &iov; mh.msg_iovlen = 1;
    if (nfds) {
        mh.msg_control = cb; mh.msg_controllen = CMSG_SPACE(sizeof(int) * nfds);
        struct cmsghdr *c = CMSG_FIRSTHDR(&mh); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS;
        c->cmsg_len = CMSG_LEN(sizeof(int) * nfds); memcpy(CMSG_DATA(c), fds, sizeof(int) * nfds);
    }
    ssize_t r; do r = sendmsg(fd, &mh, MSG_NOSIGNAL); while (r < 0 && errno == EINTR);
    // the daemon has taken the message once the sender's queue is empty; it handles it before it looks at anything else,
    // so a round trip on the probe connection afterwards means: handled
    if (r >= 0) for (int i = 0; i < 400000; i++) { int q = 0; if (ioctl(fd, TIOCOUTQ, &q) < 0 || q == 0) break; if (i > 1000) usleep(20); }
    sync_daemon();
    return r < 0 ? -errno : 0;
}

// ------------------------------------------------------------------------------------------ client model
#define MAXP 8
#define MAXT 5
#define MAXH 64
#define MAXB 32
struct cth { int fd, used, waiting, calls, owait, nserve, is_main, looper, nreply, ntxn, nst, pend, rep_wait; char st[24]; u32 out[96]; int nout; u32 br[24]; int nbr; };
struct cproc {
    int alive, pid, key, nth, want_thread, ctx; struct cth th[MAXT];
    u32 h[MAXH]; int nh; int us[1024], uw[1024];
    struct { u64 id; int th; } buf[MAXB]; int nb; u32 dn_h[8]; u64 dn_c[8]; int ndn; u64 cookie_seq;
};
static struct cproc P[MAXP];
static int pidseq = 100;
static struct { long txn, oneway, replies, nested, kills, kills_busy, thread_exits, dead_reply, failed_reply, bufs_freed, hostile, spawned, bigtxn, fdtxn, brs; } S;
#define RB (1 << 18)
static char rb[RB], bigbuf[1 << 17];

static void flush_out(struct cth *t, u32 flags, u32 rsz) {
    if (!t->nout && !flags) return;
    char m[sizeof(struct c_hdr) + sizeof t->out]; struct c_hdr h = { OP_BCS, flags, rsz, 1000 };
    memcpy(m, &h, sizeof h); memcpy(m + sizeof h, t->out, t->nout * 4);
    xsend(t->fd, m, sizeof h + t->nout * 4, 0, 0); t->nout = 0;
}
static void need(struct cth *t, int n) { if (t->nout + n > 96) flush_out(t, 0, 0); }
static void q0(struct cth *t, u32 c) { need(t, 1); t->out[t->nout++] = c; }
static void q4(struct cth *t, u32 c, u32 a) { need(t, 2); t->out[t->nout++] = c; t->out[t->nout++] = a; }
static void q8(struct cth *t, u32 c, u64 a) { need(t, 3); t->out[t->nout++] = c; memcpy(&t->out[t->nout], &a, 8); t->nout += 2; }
static void q12(struct cth *t, u32 c, u32 a, u64 b) { need(t, 4); t->out[t->nout++] = c; t->out[t->nout++] = a; memcpy(&t->out[t->nout], &b, 8); t->nout += 2; }
static void q16(struct cth *t, u32 c, u64 a, u64 b) { need(t, 5); t->out[t->nout++] = c; memcpy(&t->out[t->nout], &a, 8); memcpy(&t->out[t->nout + 2], &b, 8); t->nout += 4; }

static void send_read(struct cth *t, u32 rsz) {
    if (t->waiting || dead) return;
    t->waiting = 1;
    if (t->nout) flush_out(t, F_READ, rsz);
    else { struct c_hdr h = { OP_READ, F_READ, rsz, 1000 }; xsend(t->fd, &h, sizeof h, 0, 0); }
}
static u32 pick_rsz(void) { static const u32 s[] = { 256, 256, 256, 1024, 128, 64 }; return s[rnd(6)]; }

static char top_of(struct cth *t) { return t->nst ? t->st[t->nst - 1] : 0; }
static void push_st(struct cth *t, char c) { if (t->nst < 24) t->st[t->nst++] = c; }
static void settle(struct cth *t) { while (t->nst && t->st[t->nst - 1] == 'C' && t->pend > 0) { t->nst--; t->pend--; } if (!t->nst) t->pend = 0; }
static void learn(struct cproc *p, u32 h) { for (int i = 0; i < p->nh; i++) if (p->h[i] == h) return; if (p->nh < MAXH) p->h[p->nh++] = h; else p->h[rnd(MAXH)] = h; }
static int held(struct cproc *p, u32 h) { return h < 1024 && (p->us[h] > 0 || p->uw[h] > 0); }
static u32 pick_held(struct cproc *p) { u32 c[MAXH]; int n = 0; for (int i = 0; i < p->nh; i++) if (held(p, p->h[i])) c[n++] = p->h[i]; return n ? c[rnd(n)] : (u32)-1; }
static void acquire(struct cproc *p, struct cth *t, u32 h, int strong) {
    q4(t, BC_INCREFS, h); if (h < 1024) p->uw[h]++;
    if (strong) { q4(t, BC_ACQUIRE, h); if (h < 1024) p->us[h]++; }
}
static void release_one(struct cproc *p, struct cth *t, u32 h) {
    if (h >= 1024) return;
    if (p->us[h] > 0) { q4(t, BC_RELEASE, h); p->us[h]--; }
    if (p->uw[h] > 0 && (p->us[h] == 0 || chance(30))) { q4(t, BC_DECREFS, h); p->uw[h]--; }
}

// a buffer is freed by the thread that holds its Parcel: its ACQUIREs for the handles inside went out before, in the same stream
static void free_buf(struct cproc *p, struct cth *t, int i) {
    struct cth *o = p->buf[i].th >= 0 && p->th[p->buf[i].th].used ? &p->th[p->buf[i].th] : t;
    q8(o, 0x4008633cu /* BC_AEMU_FREE */, p->buf[i].id);
    if (o != t && !o->waiting) flush_out(o, 0, 0);
    p->buf[i] = p->buf[--p->nb]; S.bufs_freed++;
}
static int pick_buf(struct cproc *p, struct cth *t) {
    int c[MAXB], n = 0, me = (int)(t - p->th);
    for (int i = 0; i < p->nb; i++) if (p->buf[i].th == me || p->buf[i].th < 0 || hostile) c[n++] = i;
    return n ? c[rnd(n)] : -1;
}
static void on_msg(struct cproc *p, struct cth *t, char *b, ssize_t n, int *fds, int nfds) {
    struct d_hdr *h = (struct d_hdr *)b;
    if (n < (ssize_t)sizeof *h) goto out;
    if (h->flags & F_WAKE) goto out;
    t->waiting = 0;
    if (h->flags & F_AGAIN) goto out;
    u32 *w = (u32 *)(b + sizeof *h); int nw = h->br_size / 4;
    for (int i = 0; i < nw;) {
        u32 c = w[i++]; S.brs++;
        if (t->nbr < 24) t->br[t->nbr++] = c;
        switch (c) {
        case BR_TRANSACTION_COMPLETE: if (t->rep_wait) t->rep_wait = 0; else if (t->owait > 0) t->owait--; break;
        case BR_DEAD_REPLY: case BR_FAILED_REPLY:
            if (c == BR_DEAD_REPLY) S.dead_reply++; else S.failed_reply++;
            if (t->calls > 0) t->calls--;
            if (t->rep_wait) t->rep_wait = 0; else if (t->owait > 0) t->owait--; else { t->pend++; settle(t); }
            break;
        case BR_SPAWN_LOOPER: p->want_thread = 1; break;
        case BR_INCREFS: case BR_ACQUIRE: {
            u64 ptr, ck; memcpy(&ptr, w + i, 8); memcpy(&ck, w + i + 2, 8); i += 4;
            if (chance(97)) q16(t, c == BR_ACQUIRE ? BC_ACQUIRE_DONE : BC_INCREFS_DONE, ptr, ck);
            break;
        }
        case BR_RELEASE: case BR_DECREFS: i += 4; break;
        case BR_DEAD_BINDER: { u64 ck; memcpy(&ck, w + i, 8); i += 2; q8(t, BC_DEAD_BINDER_DONE, ck); break; }
        case BR_CLEAR_DEATH_NOTIFICATION_DONE: i += 2; break;
        default: fprintf(stderr, "client: unknown BR %x\n", c); i = nw;
        }
    }
    if (h->flags & F_TXN) {
        u64 dsz = A8(h->data_size), no = h->offsets_size / 8, total = dsz + h->offsets_size + h->extra_size;
        char *data; int k = 0;
        if (h->flags & F_BIG) {
            data = bigbuf; u64 got = 0;
            if (total > sizeof bigbuf) total = sizeof bigbuf;
            while (nfds > 0 && got < total) { ssize_t r = pread(fds[0], data + got, total - got, got); if (r <= 0) break; got += r; }
            if (nfds > 0) { close(fds[0]); k = 1; }
        } else data = b + sizeof *h + h->br_size;
        u64 *offs = (u64 *)(data + dsz);
        for (u64 i = 0; i < no; i++) {
            struct fbo *o = (struct fbo *)(data + offs[i]);
            if (o->type == T_HANDLE || o->type == T_WEAK_HANDLE) {
                u32 hd = (u32)o->binder; learn(p, hd);
                if (!hostile || chance(80)) acquire(p, t, hd, o->type == T_HANDLE);   // libbinder: acquire_object
            }
        }
        if (h->cmd == BR_REPLY) { if (t->calls > 0) t->calls--; S.replies++; t->nreply++; t->pend++; settle(t); }
        else { if (t->calls > 0) S.nested++; t->nserve++; t->ntxn++; push_st(t, 'S'); }
        if (p->nb >= MAXB) free_buf(p, t, 0);
        p->buf[p->nb].id = h->id; p->buf[p->nb++].th = (int)(t - p->th);
        (void)k;
    }
out:
    for (int i = 0; i < nfds; i++) close(fds[i]);
}

static int recv_one(struct cth *t, char **b, ssize_t *n, int *fds, int *nfds) {
    char cb[CMSG_SPACE(sizeof(int) * 254)];
    struct iovec iov = { rb, RB }; struct msghdr mh = { 0 };
    mh.msg_iov = &iov; mh.msg_iovlen = 1; mh.msg_control = cb; mh.msg_controllen = sizeof cb;
    ssize_t r = recvmsg(t->fd, &mh, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
    if (r < 0) return 0;
    *nfds = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&mh); c; c = CMSG_NXTHDR(&mh, c))
        if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) { *nfds = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int); memcpy(fds, CMSG_DATA(c), *nfds * sizeof(int)); }
    *b = rb; *n = r;
    return r > 0 ? 1 : -1;
}
static void drain(void) {
    for (int i = 0; i < MAXP; i++) if (P[i].alive) for (int j = 0; j < MAXT; j++) {
        struct cth *t = &P[i].th[j]; if (!t->used) continue;
        for (;;) { char *b; ssize_t n; int fds[254], nf; int r = recv_one(t, &b, &n, fds, &nf); if (r <= 0) break; on_msg(&P[i], t, b, n, fds, nf); }
    }
}

static struct cth *new_thread(struct cproc *p, int is_main) {
    for (int j = 0; j < MAXT; j++) if (!p->th[j].used) {
        struct cth *t = &p->th[j]; memset(t, 0, sizeof *t);
        t->fd = dial(); if (t->fd < 0) return 0;
        t->used = 1; t->is_main = is_main; p->nth++;
        if (is_main) p->key = t->fd;
        struct c_hello m = { { OP_HELLO, 0, 0, 1000 }, (unsigned)p->pid, (unsigned)(p->pid * 10 + j), (unsigned)is_main, (unsigned)p->key };
        xsend(t->fd, &m, sizeof m, 0, 0);
        return t;
    }
    return 0;
}
static void spawn_proc(int i) {
    struct cproc *p = &P[i]; memset(p, 0, sizeof *p);
    p->pid = ++pidseq; p->alive = 1;
    struct cth *t = new_thread(p, 1);
    int have_ctx = 0; for (int k = 0; k < MAXP; k++) if (k != i && P[k].alive && P[k].ctx) have_ctx = 1;
    if (!have_ctx) { p->ctx = 1; q0(t, BC_AEMU_CTX_MGR); }
    q4(t, BC_AEMU_MAX_THREADS, 3);
    flush_out(t, 0, 0);
    S.spawned++;
}
static void kill_proc(int i) {
    struct cproc *p = &P[i];
    int busy = 0;
    for (int j = 0; j < MAXT; j++) if (p->th[j].used && p->th[j].nst) busy = 1;
    S.kills++; if (busy) S.kills_busy++;
    // the kernel closes descriptors in table order; the main connection is usually first, not always
    int order[MAXT], no = 0; for (int j = 0; j < MAXT; j++) if (p->th[j].used) order[no++] = j;
    if (chance(35) && no > 1) { int a = rnd(no), b = rnd(no); int x = order[a]; order[a] = order[b]; order[b] = x; }
    int remaining = no, main_closed = 0;
    for (int k = 0; k < no; k++) {
        struct cth *t = &p->th[order[k]]; int base = daemon_fds(), drop;
        if (t->is_main) { drop = remaining; main_closed = 1; remaining = 0; } else { drop = main_closed ? 0 : 1; if (!main_closed) remaining--; }
        close(t->fd); t->used = 0;
        if (drop && base >= 0) wait_daemon_fds(base - drop); else sync_daemon();
    }
    p->alive = 0;
}

// -- transactions
struct tx { char *data; u64 offs[8]; int no, nfd, fds[4]; u64 dsz; };
static char txdata[1 << 16];
static u64 node_ptr(struct cproc *p) { return 0x7f0000000000ull + ((u64)p->pid << 16) + 0x10 * rnd(6); }
static void mk_objects(struct cproc *p, struct tx *x, int allow_fd, u32 junk_handle) {
    x->data = txdata; x->no = 0; x->nfd = 0;
    int nobj = chance(45) ? 0 : 1 + rnd(3); u64 at = 0;
    for (int i = 0; i < nobj; i++) {
        struct fbo o = { 0 }; int k = rnd(10);
        if (k < 4) { u64 ptr = node_ptr(p); o.type = chance(70) ? T_BINDER : T_WEAK_BINDER; o.binder = ptr; o.cookie = ptr + 8; }
        else if (k < 8 && p->nh) { u32 hh = hostile ? p->h[rnd(p->nh)] : pick_held(p); if (hh == (u32)-1) continue; o.type = chance(75) ? T_HANDLE : T_WEAK_HANDLE; o.binder = hh; if (junk_handle && chance(10)) o.binder = junk_handle; }
        else if (k == 8 && allow_fd && x->nfd < 3) { int fd = open("/dev/null", O_RDONLY | O_CLOEXEC); if (fd < 0) continue; o.type = T_FD; o.binder = fd; x->fds[x->nfd++] = fd; }
        else continue;
        memcpy(txdata + at, &o, sizeof o); x->offs[x->no++] = at; at += sizeof o;
    }
    u64 pay = rnd(40); for (u64 i = 0; i < pay; i++) txdata[at + i] = (char)rnd(256);
    x->dsz = at + pay;
}
static int send_txn(struct cth *t, u32 cmd, u32 handle, u32 code, u32 tflags, struct tx *x, int read, int big, int truncate) {
    u64 offsz = (u64)x->no * 8, total = A8(x->dsz) + offsz;
    flush_out(t, 0, 0);
    struct c_txn m = { { OP_TXN, read ? F_READ : 0, pick_rsz(), 1000 }, cmd, handle, code, tflags, x->dsz, offsz, 0 };
    if (read) t->waiting = 1;
    int r, fds[8], nf = 0, memfd = -1;
    if (big) {
        u64 bs = 50000 + rnd(15000); // a buffer past INLINE_MAX, objects first
        u64 bdsz = A8(bs > x->dsz ? bs : x->dsz); static char bdata[1 << 17];
        memset(bdata, 0x5a, bdsz); memcpy(bdata, txdata, x->dsz);
        m.data_size = bs > x->dsz ? bs : x->dsz; total = bdsz + offsz;
        memcpy(bdata + bdsz, x->offs, offsz);
        memfd = memfd_create("fz", MFD_CLOEXEC); if (memfd < 0) return -1;
        if (truncate) { if (write(memfd, bdata, total / 2) < 0) {} }          // a descriptor shorter than it claims
        else if (write(memfd, bdata, total) < 0) {}
        m.h.flags |= F_BIG; fds[nf++] = memfd;
        for (int i = 0; i < x->nfd; i++) fds[nf++] = x->fds[i];
        r = xsend(t->fd, &m, sizeof m, fds, nf); close(memfd); S.bigtxn++;
    } else {
        static char msg[sizeof m + (1 << 16)];
        memcpy(msg, &m, sizeof m); memcpy(msg + sizeof m, txdata, x->dsz);
        u64 len = sizeof m + x->dsz;
        if (x->no) { while (len & 7) msg[len++] = 0; memcpy(msg + len, x->offs, offsz); len += offsz; }
        if (truncate) { len = sizeof m + rnd(8); }                         // sizes in the header claim more than was sent
        for (int i = 0; i < x->nfd; i++) fds[nf++] = x->fds[i];
        r = xsend(t->fd, msg, len, fds, nf);
        if (x->nfd) S.fdtxn++;
    }
    for (int i = 0; i < x->nfd; i++) close(x->fds[i]);
    (void)total;
    return r;
}
static void do_call(struct cproc *p, struct cth *t, u32 handle, int oneway) {
    struct tx x; mk_objects(p, &x, 1, hostile ? 99999 : 0);
    u32 fl = oneway ? TF_ONE_WAY : 0;
    send_txn(t, BC_TRANSACTION, handle, rnd(8), fl, &x, 1, chance(2), 0);
    if (oneway) { t->owait++; S.oneway++; } else { t->calls++; push_st(t, 'C'); }
    S.txn++;
}
static void do_reply(struct cproc *p, struct cth *t) {
    struct tx x; mk_objects(p, &x, 1, 0);
    send_txn(t, BC_REPLY, 0, 0, 0, &x, 1, chance(1), 0);
    if (t->nserve > 0) t->nserve--;
    if (top_of(t) == 'S') t->nst--;
    t->rep_wait = 1; settle(t);
}
static u32 pick_handle(struct cproc *p) {
    // handle 0 is the context manager (its own process never calls it); the others are proxies the process holds
    u32 hh = hostile ? (p->nh ? p->h[rnd(p->nh)] : 0) : pick_held(p);
    if (hh == (u32)-1 || (!p->ctx && chance(20))) return p->ctx ? 77 : 0;
    return hh;
}

static void hostile_op(struct cproc *p, struct cth *t, int idx) {
    S.hostile++;
    switch (rnd(9)) {
    case 0: {            // bogus buffer ids: garbage, 0, another process's, an id already freed
        u64 v = rnd(4) == 0 ? 0 : rnd(4) == 1 ? 0x4141414141414141ull : rnd(4) == 2 ? (u64)rnd(1 << 20) : ((u64)rnd(1 << 30) << 12);
        for (int i = 0; i < MAXP; i++) if (i != idx && P[i].alive && P[i].nb && chance(40)) v = P[i].buf[rnd(P[i].nb)].id;
        q8(t, 0x4008633cu, v); flush_out(t, 0, 0); break;
    }
    case 1: { u32 h = pick_handle(p); for (int k = rnd(4) + 1; k > 0; k--) q4(t, chance(50) ? BC_RELEASE : BC_DECREFS, h); flush_out(t, 0, 0); break; }
    case 2: { struct tx x; mk_objects(p, &x, 0, 0); send_txn(t, BC_REPLY, 0, 0, 0, &x, chance(50), 0, 0); break; }
    case 3: { if (p->ctx && !t->nst) do_call(p, t, 0, chance(30)); break; }
    case 4: { q4(t, BC_ACQUIRE, rnd(chance(50) ? 8 : 100000)); q4(t, BC_RELEASE, rnd(100000)); flush_out(t, 0, 0); break; }
    case 5: { struct tx x; mk_objects(p, &x, 0, 0); send_txn(t, BC_TRANSACTION, pick_handle(p), 1, 0, &x, 0, 0, 1); break; }
    case 6: { struct tx x; mk_objects(p, &x, 0, 0); send_txn(t, BC_TRANSACTION, pick_handle(p), 1, TF_ONE_WAY, &x, 0, 1, 1); break; }
    case 7: { u32 junk = rnd(1 << 30); if (chance(50)) junk = (junk & 0xffff) | 0x00630000u; q4(t, junk, rnd(10)); flush_out(t, 0, 0); break; }
    case 8: { for (int k = 0; k < 3 && p->nb; k++) { u64 v = p->buf[rnd(p->nb)].id; q8(t, 0x4008633cu, v); q8(t, 0x4008633cu, v); } flush_out(t, 0, 0); break; }   // double free
    }
}

static void idle_op(struct cproc *p, struct cth *t, int idx) {
    if (hostile && chance(28)) { hostile_op(p, t, idx); return; }
    int r = rnd(100);
    if (r < 33) { if (t->nst || t->owait) return; do_call(p, t, pick_handle(p), chance(22)); }
    else if (r < 52) { if (!t->looper) { q0(t, BC_ENTER_LOOPER); t->looper = 1; } send_read(t, pick_rsz()); }
    else if (r < 60) { u32 hh = hostile ? (p->nh ? p->h[rnd(p->nh)] : (u32)-1) : pick_held(p); if (hh != (u32)-1) acquire(p, t, hh, chance(70)); }
    else if (r < 70) { u32 hh = pick_held(p); if (hh != (u32)-1) release_one(p, t, hh); }
    else if (r < 78) { int k = pick_buf(p, t); if (k >= 0) free_buf(p, t, k); }
    else if (r < 83) {
        if (p->ndn < 8 && pick_held(p) != (u32)-1 && chance(60)) { u32 h = pick_held(p); u64 c = ++p->cookie_seq; q12(t, BC_REQUEST_DEATH_NOTIFICATION, h, c); p->dn_h[p->ndn] = h; p->dn_c[p->ndn++] = c; }
        else if (p->ndn) { int k = rnd(p->ndn); q12(t, BC_CLEAR_DEATH_NOTIFICATION, p->dn_h[k], p->dn_c[k]); p->dn_h[k] = p->dn_h[--p->ndn]; p->dn_c[k] = p->dn_c[p->ndn]; }
    }
    else if (r < 86) {
        if (!t->is_main && !t->nst && !t->owait) {      // BINDER_THREAD_EXIT
            flush_out(t, 0, 0); for (int q = 0; q < p->nb; q++) if (p->buf[q].th == (int)(t - p->th)) p->buf[q].th = -1; int base = daemon_fds(); close(t->fd); t->used = 0; p->nth--; S.thread_exits++; wait_daemon_fds(base - 1);
        }
    }
    else send_read(t, pick_rsz());
    flush_out(t, 0, 0);
}

static void step(void) {
    // processes come and go: the memory killer takes one at a time, at any moment
    if (chance(2)) { int c = rnd(MAXP); if (P[c].alive) kill_proc(c); }
    for (int i = 0; i < MAXP && !dead; i++) if (!P[i].alive && chance(25)) { spawn_proc(i); }
    // threads blocked in the driver loop: they read on until their call is answered; servers answer
    for (int i = 0; i < MAXP && !dead; i++) if (P[i].alive) {
        struct cproc *p = &P[i];
        if (p->want_thread && p->nth < MAXT) {
            p->want_thread = 0; struct cth *t = new_thread(p, 0);
            if (t) { q0(t, BC_REGISTER_LOOPER); t->looper = 1; send_read(t, 256); }
        }
        for (int j = 0; j < MAXT && !dead; j++) {
            struct cth *t = &p->th[j]; if (!t->used || t->waiting) continue;
            char top = top_of(t);
            if (top == 'S') {
                int r = rnd(100);
                if (r < 78) do_reply(p, t);
                else if (r < 88) { do_call(p, t, pick_handle(p), 0); }       // a nested call before answering
                else if (r < 90) { /* sits on it */ }
                else { int k = pick_buf(p, t); if (k >= 0) free_buf(p, t, k); }
            } else if (top == 'C' || t->owait > 0) {
                if (chance(92)) send_read(t, pick_rsz());
            }
            flush_out(t, 0, 0);
        }
        if (chance(3) && p->nb) { struct cth *t = &p->th[0]; if (t->used && !t->waiting) { int k = pick_buf(p, t); if (k >= 0) { free_buf(p, t, k); flush_out(t, 0, 0); } } }
    }
    // one more random action by a random thread
    int i = rnd(MAXP);
    if (P[i].alive && !dead) {
        struct cproc *p = &P[i]; int j = rnd(MAXT); struct cth *t = &p->th[j];
        if (t->used && !t->waiting && t->nst == 0 && t->owait == 0) idle_op(p, t, i);
        else if (hostile && t->used && !t->waiting && chance(40)) { hostile_op(p, t, i); }
    }
    sync_daemon();
    drain();
}

static void reset_model(void) { for (int i = 0; i < MAXP; i++) if (P[i].alive) for (int j = 0; j < MAXT; j++) if (P[i].th[j].used) close(P[i].th[j].fd); memset(P, 0, sizeof P); }

static int run_seed(u64 seed, long steps) {
    rs = seed * 0x9e3779b97f4a7c15ull + 88172645463325252ull; for (int i = 0; i < 8; i++) rnd(2);
    memset(&S, 0, sizeof S); dead = 0; dstatus = 0;
    snprintf(errlog, sizeof errlog, "/tmp/bfuzz-seed%llu.err", (unsigned long long)seed);
    start_daemon();
    probe_fd = dial();
    struct c_hello m = { { OP_HELLO, 0, 0, 0 }, 1, 1, 1, (unsigned)probe_fd };
    send(probe_fd, &m, sizeof m, MSG_NOSIGNAL);
    memset(P, 0, sizeof P);
    for (int i = 0; i < 5 && !dead; i++) spawn_proc(i);
    long k;
    for (k = 0; k < steps && !dead; k++) step();
    int ok = !dead;
    if (dead) {
        printf("seed %llu: DAEMON DIED at step %ld (%s %d)\n", (unsigned long long)seed, k,
               WIFSIGNALED(dstatus) ? "signal" : "exit", WIFSIGNALED(dstatus) ? WTERMSIG(dstatus) : WEXITSTATUS(dstatus));
        char cmd[300]; snprintf(cmd, sizeof cmd, "grep -m3 -E 'ERROR: AddressSanitizer|runtime error|SUMMARY' %s | cut -c1-200 | sed 's/^/    /'", errlog);
        fflush(stdout); if (system(cmd)) {}
    } else {
        printf("seed %llu: ok  steps=%ld txn=%ld (oneway %ld, big %ld, fd %ld) replies=%ld nested=%ld dead_reply=%ld failed_reply=%ld kills=%ld (%ld with calls in flight) thread_exits=%ld hostile=%ld\n",
               (unsigned long long)seed, steps, S.txn, S.oneway, S.bigtxn, S.fdtxn, S.replies, S.nested, S.dead_reply, S.failed_reply, S.kills, S.kills_busy, S.thread_exits, S.hostile);
        // a clean daemon has nothing on stderr but its banner
        char cmd[300]; snprintf(cmd, sizeof cmd, "grep -c -E 'AddressSanitizer|runtime error' %s", errlog);
        FILE *f = popen(cmd, "r"); int cnt = 0; if (f) { if (fscanf(f, "%d", &cnt) != 1) cnt = 0; pclose(f); }
        if (cnt) { printf("  sanitizer output present in %s\n", errlog); ok = 0; }
    }
    fflush(stdout);
    reset_model(); close(probe_fd); stop_daemon();
    if (ok) unlink(errlog);
    return ok;
}

// ------------------------------------------------------------------------------------------ regression scenarios
// Each scenario scripts clients by hand: the exact situation a reported bug needs, then what a correct driver does.
static int pump_until(struct cth *t, int ms) {          // wait until the daemon has answered the thread's read
    for (int waited = 0; t->waiting && waited < ms && !dead; waited += 5) { sync_daemon(); drain(); if (t->waiting) usleep(5000); }
    return !t->waiting;
}
static struct cproc *mkproc(int i, int ctx) {
    struct cproc *p = &P[i]; memset(p, 0, sizeof *p); p->pid = ++pidseq; p->alive = 1; new_thread(p, 1);
    p->ctx = ctx; struct cth *t = &p->th[0]; if (ctx) q0(t, BC_AEMU_CTX_MGR); q4(t, BC_AEMU_MAX_THREADS, 4); flush_out(t, 0, 0);
    return p;
}
static void sx(struct cth *t, u32 cmd, u32 handle, u32 flags, int type, u64 binder, int read) {
    struct fbo o = { (u32)type, 0, binder, binder + 8 }; struct tx x = { .data = txdata, .no = 0, .nfd = 0, .dsz = 8 };
    memset(txdata, 0, 64);
    if (type) { memcpy(txdata, &o, sizeof o); x.offs[0] = 0; x.no = 1; x.dsz = sizeof o; }
    send_txn(t, cmd, handle, 7, flags, &x, read, 0, 0);
}
static int has_br(struct cth *t, u32 c) { for (int i = 0; i < t->nbr; i++) if (t->br[i] == c) return 1; return 0; }
static void collect(struct cth *t) { for (int k = 0; k < 4 && !dead; k++) { if (!t->waiting) send_read(t, 256); pump_until(t, 150); } }
static int await_txn(struct cth *t, int n0) { for (int k = 0; k < 20 && !dead; k++) { if (t->ntxn > n0) return 1; if (!t->waiting) send_read(t, 256); pump_until(t, 300); } return t->ntxn > n0; }
static int await_reply(struct cth *t, int n0) { for (int k = 0; k < 20 && !dead; k++) { if (t->nreply > n0) return 1; if (!t->waiting) send_read(t, 256); pump_until(t, 300); } return t->nreply > n0; }
// X gets a handle to a node owned by Y, through the context manager M (what addService / getService do)
static u32 link_procs(struct cproc *M, struct cproc *X, struct cproc *Y, u64 ptr) {
    struct cth *m = &M->th[0], *x = &X->th[0], *y = &Y->th[0];
    int ny = y->nreply, nx = x->nreply, nm = m->ntxn;
    sx(y, BC_TRANSACTION, 0, 0, T_BINDER, ptr, 1);
    await_txn(m, nm); u32 d = M->h[M->nh - 1];
    sx(m, BC_REPLY, 0, 0, 0, 0, 1); await_reply(y, ny); m->nserve = 0;
    nm = m->ntxn; pump_until(m, 300);
    sx(x, BC_TRANSACTION, 0, 0, 0, 0, 1);
    await_txn(m, nm);
    sx(m, BC_REPLY, 0, 0, T_HANDLE, d, 1); await_reply(x, nx); m->nserve = 0; pump_until(m, 300);
    return X->h[X->nh - 1];
}
static void begin(const char *name) {
    snprintf(errlog, sizeof errlog, "/tmp/bfuzz-%s.err", name);
    dead = 0; dstatus = 0; start_daemon(); probe_fd = dial();
    struct c_hello m = { { OP_HELLO, 0, 0, 0 }, 1, 1, 1, (unsigned)probe_fd };
    send(probe_fd, &m, sizeof m, MSG_NOSIGNAL); memset(P, 0, sizeof P); memset(&S, 0, sizeof S);
}
static int finish(const char *name, const char *what, int expectation_ok) {
    int ok;
    if (dead) {
        printf("  %-34s FAIL  binderd died (%s %d)", name, WIFSIGNALED(dstatus) ? "signal" : "exit", WIFSIGNALED(dstatus) ? WTERMSIG(dstatus) : WEXITSTATUS(dstatus));
        char cmd[300]; snprintf(cmd, sizeof cmd, "grep -m1 -E 'ERROR: AddressSanitizer|runtime error' %s | cut -c1-150", errlog);
        printf("\n      "); fflush(stdout); if (system(cmd)) printf("\n");
        ok = 0;
    } else if (!expectation_ok) { printf("  %-34s FAIL  survived, but behaved wrongly: %s\n", name, what); ok = 0; }
    else { printf("  %-34s PASS  %s\n", name, what); ok = 1; }
    fflush(stdout);
    reset_model(); close(probe_fd); stop_daemon();
    return ok;
}

// 1. the main bug: A's thread waits for a call to B, is handed a nested call from B meanwhile, then B's process is killed
static int sc_nested(void) {
    begin("nested");
    struct cproc *M = mkproc(0, 1), *A = mkproc(1, 0), *B = mkproc(2, 0);
    struct cth *a1 = &A->th[0], *b1 = &B->th[0];
    u32 hB = link_procs(M, A, B, 0x1000), hA = link_procs(M, B, A, 0x2000);
    int nb = b1->ntxn, na = a1->ntxn;
    sx(a1, BC_TRANSACTION, hB, 0, 0, 0, 1);               // A -> B: b1 serves it
    await_txn(b1, nb);
    sx(b1, BC_TRANSACTION, hA, 0, 0, 0, 1);               // B -> A while serving: lands on a1, which still waits for B
    await_txn(a1, na);
    int nested = a1->nserve > 0 && b1->nserve > 0;
    a1->nbr = 0;
    kill_proc(2);                                          // the memory killer takes B: a1's call can never be answered
    drain();
    sx(a1, BC_REPLY, 0, 0, 0, 0, 1);                       // a1 answers the nested call (its caller is gone)
    collect(a1);
    int told = has_br(a1, BR_DEAD_REPLY);                  // a1 must learn that its call to B died
    if (getenv("DBG")) fprintf(stderr, "nested=%d told=%d\n", nested, told);
    kill_proc(1);                                          // A goes away later: walks whatever a1's stack still holds
    return finish("nested call, callee killed", "a1 got BR_DEAD_REPLY for the call B never answered", nested && told);
}
// 2. BC_REPLY from a thread whose stack top is its own outgoing call
static int sc_badreply(void) {
    begin("badreply");
    struct cproc *M = mkproc(0, 1), *A = mkproc(1, 0), *B = mkproc(2, 0);
    struct cth *a1 = &A->th[0], *b1 = &B->th[0];
    u32 hB = link_procs(M, A, B, 0x1000);
    int nb = b1->ntxn;
    sx(a1, BC_TRANSACTION, hB, 0, 0, 0, 0);               // the call goes out, a1 does not block yet...
    await_txn(b1, nb);
    a1->nbr = 0; int nr = a1->nreply;
    sx(a1, BC_REPLY, 0, 0, 0, 0, 1);                       // ...and replies to a call it was never given
    collect(a1);
    int failed = has_br(a1, BR_FAILED_REPLY);
    sx(b1, BC_REPLY, 0, 0, 0, 0, 1);                       // B answers the real call: it has to be there still
    await_reply(a1, nr);
    if (getenv("DBG")) fprintf(stderr, "badreply failed=%d reply=%d\n", failed, a1->nreply - nr);
    return finish("reply that the thread does not owe", "BR_FAILED_REPLY; B's own reply still reached a1", failed && a1->nreply == nr + 1);
}
// 3. BC_AEMU_FREE with an id that is not one of the process's buffers
static int sc_freeptr(void) {
    begin("freeptr");
    struct cproc *M = mkproc(0, 1), *A = mkproc(1, 0), *B = mkproc(2, 0);
    struct cth *a1 = &A->th[0];
    link_procs(M, A, B, 0x1000);
    q8(a1, 0x4008633cu, 0x4141414141414141ull); flush_out(a1, 0, 0);
    q8(a1, 0x4008633cu, 0); flush_out(a1, 0, 0);
    q8(a1, 0x4008633cu, M->nb ? M->buf[0].id : 8); flush_out(a1, 0, 0);          // another process's buffer
    for (int i = 0; i < 3 && !dead; i++) { q8(a1, 0x4008633cu, (u64)(uintptr_t)&i); flush_out(a1, 0, 0); }
    sync_daemon();
    return finish("BC_AEMU_FREE with a made-up id", "ignored", 1);
}
// 4. a handle released more often than it was acquired, while a buffer still holds it
static int sc_overrelease(void) {
    begin("overrelease");
    struct cproc *M = mkproc(0, 1), *A = mkproc(1, 0), *B = mkproc(2, 0);
    struct cth *a1 = &A->th[0], *b1 = &B->th[0];
    u32 hB = link_procs(M, A, B, 0x1000);
    int nb = b1->ntxn;
    sx(a1, BC_TRANSACTION, hB, TF_ONE_WAY, T_BINDER, 0x3000, 1);       // A hands B one of its own objects
    await_txn(b1, nb);
    u32 d = B->h[B->nh - 1]; u64 id = B->buf[B->nb - 1].id;
    b1->nout = 0;                                                       // B takes no references of its own...
    q4(b1, BC_RELEASE, d); q4(b1, BC_RELEASE, d); q4(b1, BC_DECREFS, d); q4(b1, BC_DECREFS, d);   // ...and releases anyway
    flush_out(b1, 0, 0);
    q8(b1, 0x4008633cu, id); flush_out(b1, 0, 0);                       // free the buffer: it drops the reference it holds
    q4(b1, BC_ACQUIRE, d); q4(b1, BC_RELEASE, d); flush_out(b1, 0, 0);
    sync_daemon();
    return finish("handle released more than acquired", "extra releases ignored", 1);
}
// 5. a call to one's own process
static int sc_selfcall(void) {
    begin("selfcall");
    struct cproc *M = mkproc(0, 1); struct cth *m = &M->th[0];
    sx(m, BC_TRANSACTION, 0, 0, 0, 0, 1);                  // a process calling the context manager, which it is itself
    m->nbr = 0; collect(m);
    int failed = has_br(m, BR_FAILED_REPLY) && !m->nserve;
    m->nbr = 0;
    sx(m, BC_TRANSACTION, 0, TF_ONE_WAY, 0, 0, 1);
    collect(m);
    int failed2 = has_br(m, BR_FAILED_REPLY) && !m->nserve;
    return finish("call to the caller's own process", "BR_FAILED_REPLY, as the kernel driver does", failed && failed2);
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s <binderd> fuzz <seed0> <seed1> <steps> [-H] | regress\n", argv[0]); return 2; }
    dbin = argv[1]; signal(SIGPIPE, SIG_IGN);
    if (!strcmp(argv[2], "regress")) {
        int ok = sc_nested() & sc_badreply() & sc_freeptr() & sc_overrelease() & sc_selfcall();
        return ok ? 0 : 1;
    }
    u64 s0 = strtoull(argv[3], 0, 10), s1 = strtoull(argv[4], 0, 10); long steps = atol(argv[5]);
    hostile = argc > 6 && !strcmp(argv[6], "-H");
    int bad = 0;
    for (u64 s = s0; s <= s1; s++) if (!run_seed(s, steps)) bad++;
    printf("%d of %llu seeds failed\n", bad, (unsigned long long)(s1 - s0 + 1));
    return bad ? 1 : 0;
}
