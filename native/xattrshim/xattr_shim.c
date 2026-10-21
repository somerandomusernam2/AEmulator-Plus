/*
 * libaemuxattr.so - LD_PRELOAD shim for 32-bit bionic guests run through qemu-arm on the phone's kernel.
 *
 * The phone refuses the extended-attribute system calls (ENOSYS). The firmware's installd needs them for every app:
 * lgetxattr("security.selinux") before/after restorecon, and getxattr/setxattr("user.default") to mark the default
 * storage. Any failure there aborts createAppData(), the app's data directory is never made and system_server dies in
 * SettingsProvider. There is no SELinux in the guest, so:
 *   - get*xattr("security.selinux") answers a fixed label (the same one before and after, so nothing gets relabelled),
 *   - get*xattr of any other name answers ENODATA ("no such attribute", what a filesystem without it says),
 *   - set*xattr succeeds and stores nothing.
 * The real system call is always tried first; the emulation only runs when the kernel answers ENOSYS.
 * No dependencies except bionic's __errno (see the DT_NEEDED libc.so).
 *
 * Second job, same library (the native arm64 engine preloads only this one into a 32-bit-only firmware): socket() for
 * netlink families that qemu-arm refuses. qemu-user lets a guest open only NETLINK_ROUTE, _KOBJECT_UEVENT and _AUDIT
 * and answers every other protocol with EPROTONOSUPPORT *before* any host system call is made, so the engine's runner
 * (which already answers such sockets itself) never sees the call. netd (9.0) exits for it: "Unable to create
 * NFLogListener ... Protocol not supported" (NETLINK_NETFILTER), after XFRM and NFLOG failed the same way. Here the
 * refused open is retried as NETLINK_KOBJECT_UEVENT, which qemu passes on and the runner turns into a socket that
 * answers by itself (bind/setsockopt succeed, requests are acknowledged). Only a refused open is retried.
 */
#define EXPORT __attribute__((visibility("default")))
extern int *__errno(void);

#define EPERM   1
#define EACCES  13
#define EPROTONOSUPPORT 93
#define EAFNOSUPPORT    97
#define ENODATA 61
#define ERANGE  34
#define ENOSYS  38

static long sys6(long nr, long a, long b, long c, long d, long e, long f)
{
    register long r7 __asm__("r7") = nr;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    register long r4 __asm__("r4") = e;
    register long r5 __asm__("r5") = f;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5) : "memory");
    return r0;
}

static const char LABEL[] = "u:object_r:app_data_file:s0";   /* 27 characters + NUL */

/* Paths whose label something verifies against sepolicy's file_contexts. system_server's ClatCoordinator JNI
 * (libservice-connectivity.so, JNI_OnLoad) compares the label of the clatd binary, its directory and the pinned eBPF
 * objects with the expected ones and abort()s the whole of system_server on any mismatch, which is the zygote
 * "crash". With one fixed label for every path that check can never pass, so these paths answer what file_contexts
 * says. Everything else keeps LABEL (installd reads and writes back the same one, nothing is relabelled). */
static int starts(const char *p, const char *pre)
{
    for (; *pre; p++, pre++) if (*p != *pre) return 0;
    return 1;
}

static int same(const char *p, const char *q)
{
    for (; *p && *q; p++, q++) if (*p != *q) return 0;
    return *p == *q;
}

static const char *label_for(const char *path)
{
    if (!path) return LABEL;
    if (same(path, "/apex/com.android.tethering/bin/for-system")) return "u:object_r:system_file:s0";
    if (same(path, "/apex/com.android.tethering/bin/for-system/clatd"))
        return "u:object_r:clatd_exec:s0";
    if (same(path, "/sys/fs/bpf")) return "u:object_r:fs_bpf:s0";
    if (same(path, "/sys/fs/bpf/net_shared") || starts(path, "/sys/fs/bpf/net_shared/")) return "u:object_r:fs_bpf_net_shared:s0";
    if (starts(path, "/sys/fs/bpf/net_private")) return "u:object_r:fs_bpf_net_private:s0";
    if (starts(path, "/sys/fs/bpf/netd_shared")) return "u:object_r:fs_bpf_netd_shared:s0";
    if (starts(path, "/sys/fs/bpf/netd_readonly")) return "u:object_r:fs_bpf_netd_readonly:s0";
    if (starts(path, "/sys/fs/bpf/tethering")) return "u:object_r:fs_bpf_tethering:s0";
    if (starts(path, "/sys/fs/bpf/loader")) return "u:object_r:fs_bpf_loader:s0";
    if (starts(path, "/sys/fs/bpf/")) return "u:object_r:fs_bpf:s0";
    return LABEL;
}

static unsigned slen(const char *s) { unsigned n = 0; while (s[n]) n++; return n; }

static int is_selinux(const char *name)
{
    static const char want[] = "security.selinux";
    if (!name) return 0;
    for (unsigned i = 0; i < sizeof want; i++) if (name[i] != want[i]) return 0;
    return 1;
}

static long fail(int e) { *__errno() = e; return -1; }

static long emu_get(const char *path, const char *name, void *value, unsigned long size)
{
    if (!is_selinux(name)) return fail(ENODATA);
    const char *lab = label_for(path);
    unsigned n = slen(lab) + 1;                          /* the value includes its NUL, as the kernel's does */
    if (size == 0) return (long)n;
    if (size < n) return fail(ERANGE);
    char *o = (char *)value;
    for (unsigned i = 0; i < n; i++) o[i] = lab[i];
    return (long)n;
}

/* ARM EABI numbers */
#define NR_SETXATTR  226
#define NR_LSETXATTR 227
#define NR_FSETXATTR 228
#define NR_GETXATTR  229
#define NR_LGETXATTR 230
#define NR_FGETXATTR 231

/* path is the file's path for the path-based calls and NULL for fgetxattr (a descriptor has none here) */
static long do_get(long nr, long target, const char *path, const char *name, void *value, unsigned long size)
{
    long r = sys6(nr, target, (long)name, (long)value, (long)size, 0, 0);
    if (r >= 0) return r;
    if (r == -ENOSYS) return emu_get(path, name, value, size);
    return fail((int)-r);
}

static long do_set(long nr, long target, const char *name, const void *value, unsigned long size, int flags)
{
    long r = sys6(nr, target, (long)name, (long)value, (long)size, flags, 0);
    if (r >= 0) return 0;
    if (r == -ENOSYS) return 0;
    return fail((int)-r);
}

EXPORT long getxattr(const char *path, const char *name, void *value, unsigned long size)  { return do_get(NR_GETXATTR,  (long)path, path, name, value, size); }
EXPORT long lgetxattr(const char *path, const char *name, void *value, unsigned long size) { return do_get(NR_LGETXATTR, (long)path, path, name, value, size); }
EXPORT long fgetxattr(int fd, const char *name, void *value, unsigned long size)           { return do_get(NR_FGETXATTR, fd, (const char *)0, name, value, size); }
EXPORT int setxattr(const char *path, const char *name, const void *value, unsigned long size, int flags)  { return (int)do_set(NR_SETXATTR,  (long)path, name, value, size, flags); }
EXPORT int lsetxattr(const char *path, const char *name, const void *value, unsigned long size, int flags) { return (int)do_set(NR_LSETXATTR, (long)path, name, value, size, flags); }
EXPORT int fsetxattr(int fd, const char *name, const void *value, unsigned long size, int flags)           { return (int)do_set(NR_FSETXATTR, fd, name, value, size, flags); }

/* ---- netlink families the translator refuses ---- */
#define NR_SOCKET 281
#define AF_NETLINK_ 16
#define NETLINK_ROUTE_ 0
#define NETLINK_UEVENT_ 15

EXPORT int socket(int domain, int type, int protocol)
{
    long r = sys6(NR_SOCKET, domain, type, protocol, 0, 0, 0);
    if (r >= 0) return (int)r;
    if (domain != AF_NETLINK_ || protocol == NETLINK_ROUTE_ || protocol == NETLINK_UEVENT_) return (int)fail((int)-r);
    int t = type & 15;                                   /* SOCK_DGRAM or SOCK_RAW, as netlink users open them */
    if (t != 2 && t != 3) return (int)fail((int)-r);
    if (r != -EPROTONOSUPPORT && r != -EAFNOSUPPORT && r != -EPERM && r != -EACCES) return (int)fail((int)-r);
    long f = sys6(NR_SOCKET, AF_NETLINK_, type, NETLINK_UEVENT_, 0, 0, 0);
    if (f >= 0) return (int)f;
    return (int)fail((int)-r);                           /* the caller sees the original refusal */
}

/* ---- eBPF: qemu-arm answers ENOSYS to every bpf command it does not translate, before any host call ---- */
/* (the 64-bit side stands the maps in for - run64.c, case 280 - but only sees what qemu passes on: OBJ_GET never
 * gets there. netd of the 2025 networking module opens a pinned program first and aborts on the refusal.)
 * The real call is tried first; on ENOSYS the same stand-ins as run64.c answer here. bionic's syscall() is
 * interposed, which is what bpf::bpf() in libbpf_android and the module calls. */
typedef unsigned int u32;
typedef unsigned char u8;
#define NR_BPF     386
#define NR_OPENAT  322
#define NR_PREAD64 180
#define AT_FDCWD_  (-100L)
#define O_RDONLY_CLOEXEC 02000000L
#define O_RDWR_CLOEXEC   02000002L
#define ENOENT_ 2

static long open_null(void) { return sys6(NR_OPENAT, AT_FDCWD_, (long)"/dev/null", O_RDWR_CLOEXEC, 0, 0, 0); }

static long read_note(long fd, u32 *note) { return sys6(NR_PREAD64, fd, (long)note, 12, 0, 0, 0); }

__attribute__((noinline)) static long bpf_emu(long cmd, u32 *at, long size)
{
    switch (cmd) {
    case 7: {                                   /* OBJ_GET: the note of the pinned map stands for it, else /dev/null */
        static const char dir[] = "/dev/.aemu-bpf/";
        const char *path = at ? (const char *)(unsigned long)at[0] : 0, *base = path;
        char hp[256];
        for (const char *c = path; c && *c; c++) if (*c == '/') base = c + 1;
        if (base) {
            unsigned n = 0, d = sizeof dir - 1;
            while (base[n]) n++;
            if (d + n + 1 <= sizeof hp) {
                for (unsigned i = 0; i < d; i++) hp[i] = dir[i];
                for (unsigned i = 0; i <= n; i++) hp[d + i] = base[i];
                /* read-write: netd of the 2025 networking module takes the exclusive lock of a map on the descriptor
                 * (F_OFD_SETLK, F_WRLCK) and aborts on the EBADF a read-only one gives; each OBJ_GET is its own open
                 * file, so the locks conflict as they do on real maps */
                long fd = sys6(NR_OPENAT, AT_FDCWD_, (long)hp, O_RDWR_CLOEXEC, 0, 0, 0);
                if (fd < 0) fd = sys6(NR_OPENAT, AT_FDCWD_, (long)hp, O_RDONLY_CLOEXEC, 0, 0, 0);
                if (fd >= 0) return fd;
            }
        }
        return open_null();
    }
    case 15: {                                  /* OBJ_GET_INFO_BY_FD: { fd, info_len, info } */
        if (!at || size < 16) return 0;
        u32 note[3], len = at[1];
        u8 *info = (u8 *)(unsigned long)at[2];
        if (!info) return 0;
        for (u32 i = 0; i < len; i++) info[i] = 0;
        if (read_note((long)at[0], note) == 12 && len >= 20) {
            u32 *w = (u32 *)info;
            w[0] = note[0]; w[1] = 1; w[2] = note[1]; w[3] = note[2]; w[4] = 1;   /* type, id, key, value, max */
        }
        return 0;
    }
    case 0: case 5: case 14: case 17:           /* MAP_CREATE, PROG_LOAD, MAP_GET_FD_BY_ID, RAW_TRACEPOINT_OPEN */
        return open_null();
    case 1: {                                   /* MAP_LOOKUP_ELEM: an array map has every key, zeros */
        u32 note[3];
        if (at && size >= 24 && read_note((long)at[0], note) == 12 && (note[0] == 2 || note[0] == 6) && note[2] && note[2] <= 4096) {
            u8 *v = (u8 *)(unsigned long)at[4];
            if (v) { for (u32 i = 0; i < note[2]; i++) v[i] = 0; return 0; }
        }
        return -ENOENT_;
    }
    case 3: case 4: return -ENOENT_;            /* MAP_DELETE_ELEM, MAP_GET_NEXT_KEY */
    case 16:                                    /* PROG_QUERY: one program attached */
        if (at && size >= 28) {
            u32 *ids = (u32 *)(unsigned long)at[4];
            if (ids && at[6]) ids[0] = 1;
            at[6] = 1;
        }
        return 0;
    default: return 0;                          /* MAP_UPDATE_ELEM, OBJ_PIN, PROG_ATTACH/DETACH... */
    }
}

EXPORT long syscall(long nr, ...)
{
    __builtin_va_list ap;
    __builtin_va_start(ap, nr);
    long a = __builtin_va_arg(ap, long), b = __builtin_va_arg(ap, long), c = __builtin_va_arg(ap, long);
    long d = __builtin_va_arg(ap, long), e = __builtin_va_arg(ap, long), f = __builtin_va_arg(ap, long);
    __builtin_va_end(ap);
    long r = sys6(nr, a, b, c, d, e, f);
    if (nr == NR_BPF && r == -ENOSYS) r = bpf_emu(a, (u32 *)(unsigned long)b, c);
    if ((unsigned long)r > (unsigned long)-4096L) { *__errno() = (int)-r; return -1; }
    return r;
}
