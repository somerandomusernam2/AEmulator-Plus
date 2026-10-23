// Guest shim for x86-32 guests (LD_PRELOAD, bionic firmware): host-path hiding for zygote's fd-table check.
//
// Android 7.0+ zygote walks /proc/self/fd before every fork and aborts the whole system ("Not whitelisted : /dhd.owners",
// "Unable to construct file descriptor table", exit 134) when an open descriptor does not lead to a whitelisted guest path.
// The translator keeps its ownership table (/dhd.owners) open in every guest process, the stdio of guest services are
// pipes or the host's log file, and bound unix sockets carry the phone's real image path. The ARM build of libaemushim.so
// (aemushim.c, guest_fd_name / getsockname) answers readlink()/readlinkat()/getsockname() so that the guest sees only
// its own paths; this is the same logic for x86-32, with raw int 0x80 system calls and no libc beyond bionic's __errno.
//
// The same file carries the other hooks an Android 7.x userland needs on the phone's kernel (what each one fixes is
// noted at the function): extended attributes (installd), seccomp (mediaextractor/mediacodec), SO_MARK (netd), alarm
// clocks (system_server). The rest of aemushim.c (power, mounts, execve of scripts, netmgr, ...) is not ported.
//
// build: native/guestshim/build-x86.sh  ->  app/src/main/assets/engines/common-x86/libaemushim.so

#define EXPORT __attribute__((visibility("default")))

#define NR_readlink     85
#define NR_readlinkat  305
#define NR_socketcall  102
#define SC_GETSOCKNAME   6
#define SC_SETSOCKOPT   14
#define NR_prctl       172
#define NR_timerfd_create 322
#define NR_setxattr    226
#define NR_lsetxattr   227
#define NR_fsetxattr   228
#define NR_getxattr    229
#define NR_lgetxattr   230
#define NR_fgetxattr   231
#define ENODATA         61
#define ERANGE          34
#define ENOSYS          38
#define EOPNOTSUPP      95

extern int *__errno(void);

// ebx is the GOT register under -fPIC, so the first argument travels in edi and is swapped into ebx only around the call
static inline long sys4(long nr, long a, long b, long c, long d)
{
    long r;
    __asm__ volatile("xchgl %%edi, %%ebx\n\tint $0x80\n\txchgl %%edi, %%ebx"
                     : "=a"(r), "+D"(a)
                     : "0"(nr), "c"(b), "d"(c), "S"(d)
                     : "memory");
    return r;
}

static inline long sys3(long nr, long a, long b, long c) { return sys4(nr, a, b, c, 0); }

// five arguments: ebx is saved and loaded by hand (the operand for it is any other register)
static inline long sys5(long nr, long a, long b, long c, long d, long e)
{
    long r;
    __asm__ volatile("pushl %%ebx\n\tmovl %2, %%ebx\n\tint $0x80\n\tpopl %%ebx"
                     : "=a"(r)
                     : "0"(nr), "r"(a), "c"(b), "d"(c), "S"(d), "D"(e)
                     : "memory");
    return r;
}

static int fail(long r) { *__errno() = (int)-r; return -1; }

static int starts_at(const char *s, int n, int at, const char *m, int mlen)
{
    if (at + mlen > n) return 0;
    for (int k = 0; k < mlen; k++) if (s[at + k] != m[k]) return 0;
    return 1;
}

// ".../files/images/<id>/root/<rest>" -> "/<rest>"
static int strip_root(char *s, int n)
{
    static const char m[] = "/files/images/";
    for (int i = 0; i + 14 < n; i++) {
        if (!starts_at(s, n, i, m, 14)) continue;
        int j = i + 14;
        while (j < n && s[j] != '/') j++;
        if (j + 5 > n || s[j + 1] != 'r' || s[j + 2] != 'o' || s[j + 3] != 'o' || s[j + 4] != 't') return n;
        int from = j + 5, len = n - from;
        if (len <= 0) { s[0] = '/'; return 1; }
        for (int q = 0; q < len; q++) s[q] = s[from + q];
        return len;
    }
    return n;
}

static int whole(const char *s, int n, const char *m)
{
    int k = 0;
    while (k < n && m[k] && s[k] == m[k]) k++;
    return k == n && !m[k];
}

static long guest_fd_name_(char *buf, long n, unsigned long size)
{
    n = strip_root(buf, (int)n);
    // stdio of guest processes: pipes to the host's log readers, or the log file itself
    int logfile = 0;
    {
        static const char m[] = "/files/images/";
        for (int i = 0; i + 14 < n && !logfile; i++) {
            if (!starts_at(buf, (int)n, i, m, 14)) continue;
            for (int j = i + 14; j + 5 <= n; j++)
                if (buf[j] == '/' && buf[j + 1] == 'r' && buf[j + 2] == 'u' && buf[j + 3] == 'n' && buf[j + 4] == '/') { logfile = 1; break; }
        }
    }
    // the property area stays open here (on Android it is only mapped)
    if (n > 0 && whole(buf, (int)n, "/dev/__properties__")) logfile = 1;
    if (size >= 9 && (logfile || (n > 5 && buf[0] == 'p' && buf[1] == 'i' && buf[2] == 'p' && buf[3] == 'e' && buf[4] == ':'))) {
        static const char dn[] = "/dev/null";
        for (int m = 0; m < 9; m++) buf[m] = dn[m];
        return 9;
    }
    // /dev/alarm is served by qemu as a memfd (patch 0096). A 6.0 zygote keeps the descriptor of its elapsed-time helper
    // open, and the fork check then aborts it: "Not whitelisted : /memfd:guest-alarm (deleted)", every app start fails.
    // Shown as /dev/null like the pipes above; the child reopens /dev/null over it and the helper falls back to the clock.
    {
        static const char al[] = "/memfd:guest-alarm";
        if (size >= 9 && n >= 18 && starts_at(buf, (int)n, 0, al, 18)) {
            static const char dn[] = "/dev/null";
            for (int m = 0; m < 9; m++) buf[m] = dn[m];
            return 9;
        }
    }
    // qemu's ownership table: shown as a framework jar (TreeFixer links system/framework/aemu-owners.jar to it)
    if (whole(buf, (int)n, "/dhd.owners")) {
        static const char as[] = "/system/framework/aemu-owners.jar";
        unsigned long m = 0;
        while (as[m] && m < size) { buf[m] = as[m]; m++; }
        n = (long)m;
    }
    return n;
}

// readlink does not terminate the name, and the host path it returned is longer than the guest one left behind: clear the tail
static long guest_fd_name(char *buf, long n, unsigned long size)
{
    long r = guest_fd_name_(buf, n, size);
    for (long i = r; i < n; i++) buf[i] = 0;
    return r;
}

EXPORT long readlink(const char *path, char *buf, unsigned long size)
{
    long r = sys3(NR_readlink, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}

EXPORT long readlinkat(int dirfd, const char *path, char *buf, unsigned long size)
{
    long r = sys4(NR_readlinkat, dirfd, (long)path, (long)buf, (long)size);
    if (r < 0) return fail(r);
    return guest_fd_name(buf, r, size);
}

struct sockaddr_un_ { unsigned short family; char path[108]; };

EXPORT int getsockname(int fd, void *addr, unsigned *len)
{
    unsigned long args[3] = { (unsigned long)fd, (unsigned long)addr, (unsigned long)len };
    long r = sys3(NR_socketcall, SC_GETSOCKNAME, (long)args, 0);
    if (r < 0) return fail(r);
    struct sockaddr_un_ *u = (struct sockaddr_un_ *)addr;
    if (u && len && *len > 2 && u->family == 1 /* AF_UNIX */ && u->path[0]) {
        int n = (int)*len - 2;
        while (n > 0 && u->path[n - 1] == 0) n--;
        int m = strip_root(u->path, n);
        if (m != n) { u->path[m] = 0; *len = 2 + m + 1; }
    }
    return 0;
}

/* ---- extended attributes ------------------------------------------------------------------------------------------
 * The phone's kernel answers ENOSYS. 7.0+ installd reads and writes "security.selinux" around restorecon and tags app
 * dirs (user.default, user.inode_cache); any failure aborts create_app_data, the app's data dir is never made and
 * system_server dies in SettingsProvider ("Could not open database"). There is no SELinux in the guest: the label read
 * answers a fixed value (the same before and after, so nothing is relabelled), other names are "not set", writes
 * succeed and store nothing. The real call is always tried first. */
static int same_name(const char *n, const char *want)
{
    if (!n) return 0;
    for (; *want; n++, want++) if (*n != *want) return 0;
    return !*n;
}

static long emu_get(const char *name, void *value, unsigned long size)
{
    static const char ctx[] = "u:object_r:system_data_file:s0";
    if (!same_name(name, "security.selinux")) return fail(-ENODATA);
    unsigned long m = sizeof ctx;                         // the value includes its NUL, like the kernel's
    if (!size) return (long)m;
    if (size < m) return fail(-ERANGE);
    for (unsigned long k = 0; k < m; k++) ((char *)value)[k] = ctx[k];
    return (long)m;
}

static long do_get(long nr, long target, const char *name, void *value, unsigned long size)
{
    long r = sys4(nr, target, (long)name, (long)value, (long)size);
    if (r >= 0) return r;
    if (r == -ENOSYS) return emu_get(name, value, size);
    return fail(r);
}

static int do_set(long nr, long target, const char *name, const void *value, unsigned long size, int flags)
{
    long r = sys5(nr, target, (long)name, (long)value, (long)size, flags);
    if (r >= 0 || r == -ENOSYS || r == -EOPNOTSUPP) return 0;
    return fail(r);
}

// 7.0+ installd migrates app data between /data/user_de and /data/data on every boot unless the target dir carries
// user.default (it deletes one copy each time): that marker is reported as present. UserManager reads user.serial on
// the user dirs; any error but ENODATA ("not set yet, write it") makes system_server destroy all data of user 0.
EXPORT long getxattr(const char *path, const char *name, void *value, unsigned long size)
{
    if (same_name(name, "user.default")) return 0;
    if (same_name(name, "user.serial")) return fail(-ENODATA);
    return do_get(NR_getxattr, (long)path, name, value, size);
}
EXPORT long lgetxattr(const char *path, const char *name, void *value, unsigned long size) { return do_get(NR_lgetxattr, (long)path, name, value, size); }
EXPORT long fgetxattr(int fd, const char *name, void *value, unsigned long size)           { return do_get(NR_fgetxattr, fd, name, value, size); }
EXPORT int setxattr(const char *p, const char *n, const void *v, unsigned long s, int fl)  { return do_set(NR_setxattr, (long)p, n, v, s, fl); }
EXPORT int lsetxattr(const char *p, const char *n, const void *v, unsigned long s, int fl) { return do_set(NR_lsetxattr, (long)p, n, v, s, fl); }
EXPORT int fsetxattr(int fd, const char *n, const void *v, unsigned long s, int fl)        { return do_set(NR_fsetxattr, fd, n, v, s, fl); }

/* 7.0+ media services (mediacodec, mediaextractor) put themselves under a seccomp filter through minijail and abort
 * ("prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER): Invalid argument", exit 134): the translator cannot take a guest filter.
 * The app already runs under the phone's own filter, so the guest's is accepted and dropped. */
EXPORT int prctl(int option, unsigned long a2, unsigned long a3, unsigned long a4, unsigned long a5)
{
    if (option == 22 /* PR_SET_SECCOMP */) return 0;
    long r = sys5(NR_prctl, option, (long)a2, (long)a3, (long)a4, (long)a5);
    return r >= 0 ? (int)r : fail(r);
}

/* 5.0+ marks every socket with its network id through fwmarkd (netd), which sets SO_MARK; an app has no CAP_NET_ADMIN,
 * the kernel refuses and every connect() fails (no TCP, no DNS). All sockets leave through the phone's one network, so
 * the mark is accepted and dropped. */
EXPORT int setsockopt(int fd, int level, int name, const void *val, unsigned int len)
{
    if (level == 1 /* SOL_SOCKET */ && name == 36 /* SO_MARK */) return 0;
    unsigned long args[5] = { (unsigned long)fd, (unsigned long)level, (unsigned long)name, (unsigned long)val, len };
    long r = sys3(NR_socketcall, SC_SETSOCKOPT, (long)args, 0);
    return r >= 0 ? (int)r : fail(r);
}

/* The *_ALARM clocks need CAP_WAKE_ALARM, which an app never has: 7.0+ AlarmManagerService then falls back to a handler
 * that throws on listener alarms and kills system_server. The plain clocks behave the same, without wakeups. */
EXPORT int timerfd_create(int clock, int flags)
{
    if (clock == 8 /* REALTIME_ALARM */) clock = 0 /* REALTIME */;
    else if (clock == 9 /* BOOTTIME_ALARM */) clock = 7 /* BOOTTIME */;
    long r = sys3(NR_timerfd_create, clock, flags, 0);
    return r < 0 ? fail(r) : (int)r;
}
