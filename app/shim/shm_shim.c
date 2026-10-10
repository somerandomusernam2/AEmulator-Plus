/*
 * libshmshim.so - LD_PRELOAD for glibc (Google TV / Marvell Berlin) firmware.
 *
 * libshm.so (MV_SHM_Init) opens the kernel devices /dev/shm_cache and /dev/shm_noncache and talks to them with
 * ioctl 0x1f01 (mem info), 0x1f02 (device info), 0x1f11 (alloc), 0x1f12 (free), then mmap()s them. There is no such
 * kernel here, so av_settings / client_auth_service abort in MV_SHM_Init and "media.avsettings" never appears.
 *
 * This shim fakes both devices with one shared file each (ASHMEM_SHIM_DIR, default /data/local/tmp) and a bump
 * allocator whose state lives in the file's first page, so every process sees the same memory.
 * No dependencies at all: raw syscalls only (errno via __errno_location, which libc always provides).
 */
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

extern int *__errno_location(void) __attribute__((weak));
extern char **environ __attribute__((weak));

#define SYS_close     6
#define SYS_ioctl     54
#define SYS_ftruncate 93
#define SYS_openat    322
#define AT_FDCWD      (-100)
#define O_RDWR_CREAT  0x42
#define HDR           4096u
#define DEV_SIZE      (32u << 20)
#define MAXFD         1024

static inline long sc(long nr, long a, long b, long c, long d) {
    register long r7 __asm__("r7") = nr;
    register long r0 __asm__("r0") = a;
    register long r1 __asm__("r1") = b;
    register long r2 __asm__("r2") = c;
    register long r3 __asm__("r3") = d;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3) : "memory");
    return r0;
}
static long ret(long r) {
    if ((unsigned long)r > (unsigned long)-4096L) { if (__errno_location) *__errno_location() = (int)-r; return -1; }
    return r;
}
static int streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }

static unsigned char kind[MAXFD];       /* 1 = shm_cache, 2 = shm_noncache */
struct hdr { uint32_t magic, next; };

static const char *dir(void) {
    if (environ) for (char **e = environ; *e; e++) {
        const char *s = *e, *k = "ASHMEM_SHIM_DIR=";
        int i = 0; while (k[i] && s[i] == k[i]) i++;
        if (!k[i] && s[i]) return s + i;
    }
    return "/data/local/tmp";
}

static int open_dev(int k) {
    char p[160]; int n = 0; const char *d = dir(), *f = k == 1 ? "/shm_cache.bin" : "/shm_noncache.bin";
    while (*d && n < 120) p[n++] = *d++;
    while (*f) p[n++] = *f++;
    p[n] = 0;
    long fd = sc(SYS_openat, AT_FDCWD, (long)p, O_RDWR_CREAT, 0666);
    if (fd < 0) return (int)ret(fd);
    sc(SYS_ftruncate, fd, DEV_SIZE, 0, 0);
    if (fd < MAXFD) kind[fd] = (unsigned char)k;
    return (int)fd;
}

static int is_dev(const char *path) {
    if (streq(path, "/dev/shm_cache")) return 1;
    if (streq(path, "/dev/shm_noncache")) return 2;
    return 0;
}

int open(const char *path, int flags, ...) {
    unsigned mode = 0;
    if (flags & 0100 /*O_CREAT*/) { va_list ap; va_start(ap, flags); mode = va_arg(ap, unsigned); va_end(ap); }
    int k = path ? is_dev(path) : 0;
    if (k) return open_dev(k);
    return (int)ret(sc(SYS_openat, AT_FDCWD, (long)path, flags, mode));
}
int open64(const char *path, int flags, ...) {
    unsigned mode = 0;
    if (flags & 0100) { va_list ap; va_start(ap, flags); mode = va_arg(ap, unsigned); va_end(ap); }
    int k = path ? is_dev(path) : 0;
    if (k) return open_dev(k);
    return (int)ret(sc(SYS_openat, AT_FDCWD, (long)path, flags | 0x20000, mode));
}

int close(int fd) {
    if (fd >= 0 && fd < MAXFD) kind[fd] = 0;
    return (int)ret(sc(SYS_close, fd, 0, 0, 0));
}


static volatile struct hdr *hdrs[3];

static long sc6(long nr, long a, long b, long c, long d, long e, long f) {
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

/* the shared header page of one device, mapped on first use */
static volatile struct hdr *header(int fd, int k) {
    if (hdrs[k]) return hdrs[k];
    long m = sc6(192 /*mmap2*/, 0, HDR, 3, 1 /*MAP_SHARED*/, fd, 0);
    if ((unsigned long)m > (unsigned long)-4096L) return 0;
    volatile struct hdr *h = (volatile struct hdr *)m;
    __sync_val_compare_and_swap(&h->next, 0, HDR);   /* first process sets the bump pointer past the header */
    hdrs[k] = h;
    return h;
}

#define PHYS_CACHE    0x10000000u
#define PHYS_NONCACHE 0x14000000u

int ioctl(int fd, unsigned long req, ...) {
    va_list ap; va_start(ap, req); void *arg = va_arg(ap, void *); va_end(ap);
    int k = (fd >= 0 && fd < MAXFD) ? kind[fd] : 0;
    if (!k || (req >> 8) != 0x1f) return (int)ret(sc(SYS_ioctl, fd, (long)req, (long)arg, 0));
    uint32_t *a = (uint32_t *)arg;
    volatile struct hdr *h = header(fd, k);
    if (!h) { if (__errno_location) *__errno_location() = 12; return -1; }
    switch (req) {
    case 0x1f01:                          /* memory info: total, used, free */
        a[0] = DEV_SIZE - HDR; a[1] = h->next - HDR; a[2] = DEV_SIZE - h->next; a[3] = 0;
        return 0;
    case 0x1f02:                          /* device info: size, ?, physical base */
        a[0] = DEV_SIZE; a[1] = 0; a[2] = k == 1 ? PHYS_CACHE : PHYS_NONCACHE;
        return 0;
    case 0x1f11: {                        /* alloc {size, align} -> {offset} */
        uint32_t size = (a[0] + 31u) & ~31u, align = a[1];
        if (align < 32 || (align & (align - 1))) align = 32;
        for (;;) {
            uint32_t cur = h->next, off = (cur + align - 1) & ~(align - 1);
            if (size == 0 || off + size > DEV_SIZE) { a[0] = 0xffffffffu; if (__errno_location) *__errno_location() = 12; return -1; }
            if (__sync_bool_compare_and_swap(&h->next, cur, off + size)) { a[0] = off; return 0; }
        }
    }
    case 0x1f12:                          /* free: the bump allocator never reuses memory */
        return 0;
    default:
        if (__errno_location) *__errno_location() = 25; /* ENOTTY */
        return -1;
    }
}
