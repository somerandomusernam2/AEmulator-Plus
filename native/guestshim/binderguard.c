/*
 * libaemubinderguard.so — guest preload that contains the "bare object" fault of the stand's binder daemon
 * (see binderguard_core.h). It is NOT preloaded by default: the app arms it for one VM only after it has seen the
 * daemon's own fault report together with a hung transaction in that VM's binderd.log (BinderGuard.kt), and it stays
 * inert unless AEMU_GUARD is set (1 = rewrite, probe = log only).
 *
 * It sits in LD_PRELOAD in front of libaemushim.so and hands every ioctl on to the shim's ioctl (found with dlopen of
 * the already-loaded shim; libc's as a last resort), so the shim's binder ordering, GPU and input emulation all stay
 * as they are. Only BINDER_WRITE_READ is looked at.
 *
 * Events go to the file named by AEMU_GUARD_LOG (a host path, like DHD_NET_LOG): "[guard pid=.. tid=..] ..." lines.
 * Built like libaemushim (build-guard.sh): freestanding, raw syscalls, imports only environ, dlopen and dlsym.
 */
#define EXPORT __attribute__((visibility("default")))
#include "binderguard_core.h"

extern char **environ;
extern void *dlopen(const char *, int);
extern void *dlsym(void *, const char *);

__attribute__((naked, noinline)) static long sys3(long n, long a, long b, long c) {
    __asm__ volatile("push {r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; svc #0; pop {r7}; bx lr");
}
__attribute__((naked, noinline)) static long sys6(long n, long a, long b, long c, long d, long e, long f) {
    __asm__ volatile(
        "push {r4, r5, r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3; ldr r3, [sp, #12]; ldr r4, [sp, #16]; ldr r5, [sp, #20]; svc #0; pop {r4, r5, r7}; bx lr");
}

static void *guard_alloc(u32 len) {
    long r = sys6(192 /* mmap2 */, 0, (long)((len + 4095u) & ~4095u), 3 /* RW */, 0x22 /* PRIVATE|ANONYMOUS */, -1, 0);
    return (r < 0 && r > -4096) ? 0 : (void *)r;
}
static void guard_free(void *p, u32 len) { sys3(91 /* munmap */, (long)p, (long)((len + 4095u) & ~4095u), 0); }

static const char *genv(const char *name) {
    for (char **e = environ; e && *e; e++) {
        const char *s = *e, *n = name;
        while (*n && *s == *n) { s++; n++; }
        if (!*n && *s == '=') return s + 1;
    }
    return 0;
}
static int g_mode = -1;   /* 0 off, 1 rewrite, 2 probe */
static const char *g_log;
static int mode(void) {
    if (g_mode < 0) {
        const char *v = genv("AEMU_GUARD");
        g_log = genv("AEMU_GUARD_LOG");
        g_mode = !v || !*v || v[0] == '0' ? 0 : (v[0] == 'p' ? 2 : 1);
    }
    return g_mode;
}

static char *put_s(char *o, const char *s) { while (*s) *o++ = *s++; return o; }
static char *put_u(char *o, u32 v) { char t[12]; int n = 0; do { t[n++] = (char)('0' + v % 10); v /= 10; } while (v); while (n) *o++ = t[--n]; return o; }
static char *put_x(char *o, u32 v) { o = put_s(o, "0x"); for (int i = 28; i >= 0; i -= 4) { int d = (v >> i) & 15; *o++ = (char)(d < 10 ? '0' + d : 'a' + d - 10); } return o; }

static volatile u32 g_events;
static void guard_event(int kind, u32 cmd, u32 code, u32 sender_pid, u32 type, u32 binder, u32 cookie) {
    if (!g_log) return;
    u32 n = __sync_add_and_fetch(&g_events, 1);
    if (n > 200 && (n % 100)) return;   /* a storm is counted, not written line by line */
    char line[256], *o = line;
    o = put_s(o, "[guard pid="); o = put_u(o, (u32)sys3(20 /* getpid */, 0, 0, 0));
    o = put_s(o, " tid="); o = put_u(o, (u32)sys3(224 /* gettid */, 0, 0, 0)); o = put_s(o, "] ");
    switch (kind) {
    case 1: o = put_s(o, "rewrote foreign"); break;
    case 2: o = put_s(o, "saw foreign"); break;
    case 3: o = put_s(o, "knowledge table full, guard now passes everything"); break;
    default: o = put_s(o, "no room for a private copy, object left as it is"); break;
    }
    if (kind <= 2 || kind == 4) {
        o = put_s(o, cmd == BR_REPLY_G ? " object in BR_REPLY" : " object in BR_TRANSACTION");
        o = put_s(o, " code="); o = put_u(o, code);
        o = put_s(o, " from_pid="); o = put_u(o, sender_pid);
        if (kind <= 2) { o = put_s(o, " type="); o = put_x(o, type); o = put_s(o, " binder="); o = put_x(o, binder); o = put_s(o, " cookie="); o = put_x(o, cookie); }
    }
    o = put_s(o, " #"); o = put_u(o, n); *o++ = '\n';
    long fd = sys3(5 /* open */, (long)g_log, 0x441 /* O_WRONLY|O_CREAT|O_APPEND */, 0666);
    if (fd >= 0) { sys3(4 /* write */, fd, (long)line, (long)(o - line)); sys3(6 /* close */, fd, 0, 0); }
}

static int (*g_real)(int, int, void *);
EXPORT int ioctl(int fd, int req, void *arg);
static void resolve(void) {
    if (g_real) return;
    int (*f)(int, int, void *) = 0;
    void *h = dlopen("/system/lib/libaemushim.so", 2 /* RTLD_NOW */);
    if (h) f = (int (*)(int, int, void *))dlsym(h, "ioctl");
    if (!f || f == ioctl) {
        h = dlopen("libc.so", 2);
        f = h ? (int (*)(int, int, void *))dlsym(h, "ioctl") : 0;
    }
    if (f && f != ioctl) g_real = f;
}
__attribute__((constructor)) static void guard_init(void) { mode(); resolve(); }

EXPORT int ioctl(int fd, int req, void *arg) {
    if (!g_real) resolve();
    if (!g_real) return -1;   /* cannot happen: libc always has one */
    if ((unsigned)req != BWR_IOCTL || !arg || mode() == 0) return g_real(fd, req, arg);
    struct g_bwr *b = (struct g_bwr *)arg;
    int enforce = g_mode == 1;
    guard_before(b);   /* also in probe mode: it is what teaches the table which objects are local */
    long from = b->read_consumed;
    int r = g_real(fd, req, arg);
    if (r >= 0 && b->read_consumed > from) guard_after(b, from, enforce);
    return r;
}
