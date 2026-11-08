/*
 * bcmgpu.h - emulation of the Broadcom BCM2153x graphics device nodes for the AEmulator guest.
 *
 * Included by aemushim.c after sys3()/fail(); hooks are called from its open()/openat()/ioctl()/close().
 * Nothing in the firmware is patched: its gralloc, libui and libsurfaceflinger run as shipped, and see:
 *
 *   /dev/gememalloc  physically contiguous allocator.  ioctl 0xc0044201 {phys out, size in}, 0x40044202 (free by phys),
 *                    mmap(fd, offset = phys).  Backed by ONE regular file shared by every process (a "physical address" is
 *                    POOL_PHYS_BASE + file offset), so a buffer allocated by an app and mapped by SurfaceFlinger through the
 *                    fd in the gralloc handle is the same memory, exactly like on the phone.  The allocator's book-keeping lives
 *                    in the first page of the pool, under a spin lock, so every process allocates from the same state.
 *   /dev/v3d         ioctl 0x401c5687 submits a job (bin list + render list, physical addresses), 0xc00c5688 waits for it.
 *                    The job is executed on the spot by v3demu_core.h; its render target is the framebuffer.
 *   /dev/cache-ops   cache maintenance: nothing to do.
 *
 * Physical address map seen by the firmware:
 *   0 .. fb0 size                    the framebuffer (fb0 reports smem_start = 0)
 *   POOL_PHYS_BASE .. +POOL_SIZE     gememalloc pool
 */
#ifndef BCMGPU_H
#define BCMGPU_H

#include "v3demu_core.h"

#ifndef BG_POOL_FILE
#define BG_POOL_FILE "/dev/.aemu-gememalloc"
#endif
#define BG_FB_FILE "/dev/graphics/fb0"
#define BG_POOL_PHYS_BASE 0x10000000u
#define BG_POOL_SIZE 0x08000000u            /* 128 MB, sparse: only touched pages cost memory */
#define BG_PAGE 4096u
#define BG_MAX_BLOCKS 200

#define BG_IOC_ALLOC 0xc0044201u
#define BG_IOC_FREE 0x40044202u
#define BG_IOC_V3D_SUBMIT 0x401c5687u
#define BG_IOC_V3D_WAIT 0xc00c5688u
#define BG_IOC_CACHE 0x6301u
#define BG_POST_MAGIC 0x31544f50u   /* "POT1": the post record in the last 16 bytes of fb0 {magic, render target offset, job count, 1 while a job runs} */

#define BG_KIND_V3D 1
#define BG_KIND_CACHE 2
#define BG_KIND_GEM 3

/* ------------------------------------------------------------------------------------------------ system calls */

#ifdef BCMGPU_HOST            /* unit tests on the build machine */
#include <fcntl.h>
#include <sched.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
static long bg_open(const char *p, int fl, int mode) { long r = open(p, fl, mode); return r < 0 ? -1 : r; }
static long bg_close(int fd) { return close(fd); }
static long bg_size(int fd) { return (long)lseek(fd, 0, SEEK_END); }
static long bg_truncate(int fd, unsigned long len) { return ftruncate(fd, (off_t)len); }
static long bg_chmod(int fd, int mode) { return fchmod(fd, mode); }
static void *bg_mmap(unsigned long len, int fd, unsigned long off) {
    void *p = mmap(0, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t)off); return p == MAP_FAILED ? 0 : p;
}
static void bg_punch(int fd, unsigned long off, unsigned long len) { (void)fd; (void)off; (void)len; }
static void bg_yield(void) { sched_yield(); }
#else
__attribute__((naked, noinline)) static long bg_sys6(long n, long a, long b, long c, long d, long e, long f) {
    __asm__ volatile("push {r4, r5, r6, r7}; mov r7, r0; mov r0, r1; mov r1, r2; mov r2, r3;"
                     "ldr r3, [sp, #16]; ldr r4, [sp, #20]; ldr r5, [sp, #24]; svc #0; pop {r4, r5, r6, r7}; bx lr");
}
static long bg_open(const char *p, int fl, int mode) { return sys3(5, (long)p, fl | 0400000 /* O_LARGEFILE */, mode); }
static long bg_close(int fd) { return sys3(6, fd, 0, 0); }
static long bg_size(int fd) { return sys3(19 /* lseek */, fd, 0, 2 /* SEEK_END */); }
static long bg_truncate(int fd, unsigned long len) { return sys3(93 /* ftruncate */, fd, (long)len, 0); }
static long bg_chmod(int fd, int mode) { return sys3(94 /* fchmod */, fd, mode, 0); }
static void *bg_mmap(unsigned long len, int fd, unsigned long off) {
    long r = bg_sys6(192 /* mmap2 */, 0, (long)len, 3 /* RW */, 1 /* MAP_SHARED */, fd, (long)(off >> 12));
    return (unsigned long)r > (unsigned long)-4096 ? 0 : (void *)r;
}
static void bg_punch(int fd, unsigned long off, unsigned long len) {
    bg_sys6(352 /* fallocate */, fd, 3 /* PUNCH_HOLE | KEEP_SIZE */, (long)off, 0, (long)len, 0);
}
static void bg_yield(void) { sys3(158, 0, 0, 0); }
#endif

/* ------------------------------------------------------------------------------------------------ shared allocator */

struct bg_blk { uint32_t off, size; };
struct bg_hdr {
    uint32_t magic, lock, bump, nalloc, nfree, pad[3];
    struct bg_blk alloc[BG_MAX_BLOCKS], fr[BG_MAX_BLOCKS];
};
#define BG_MAGIC 0x31474541u        /* "AEG1" */
#define BG_DATA_START BG_PAGE       /* the first page of the pool is the header */

struct bg_state {
    int fds[16], kinds[16];
    int pool_fd;                    /* the process' own descriptor for mapping the whole pool */
    struct bg_hdr *hdr;
    uint8_t *pool;
    int fb_fd; uint8_t *fb; uint32_t fb_len;
    volatile uint32_t exec_lock;
    struct v3d_mem mem;
};
static struct bg_state g_bg;

static void bg_lock(volatile uint32_t *l) {
    for (unsigned n = 0; __sync_lock_test_and_set(l, 1); n++) {
        if (n > 200000) break;      /* the holder died: take over */
        bg_yield();
    }
}
static void bg_unlock(volatile uint32_t *l) { __sync_lock_release(l); }

static int bg_pool_open(void) {
    long fd = bg_open(BG_POOL_FILE, 2 /* O_RDWR */ | 0100 /* O_CREAT */ | 02000000 /* O_CLOEXEC */, 0666);
    if (fd < 0) return (int)fd;
    bg_chmod((int)fd, 0666);
    long len = bg_size((int)fd);
    if (len >= 0 && (unsigned long)len < BG_POOL_PHYS_BASE + BG_POOL_SIZE) bg_truncate((int)fd, BG_POOL_PHYS_BASE + BG_POOL_SIZE);
    return (int)fd;
}

static struct bg_hdr *bg_header(int fd) {
    if (g_bg.hdr) return g_bg.hdr;
    void *p = bg_mmap(BG_PAGE, fd, BG_POOL_PHYS_BASE);
    if (!p) return 0;
    struct bg_hdr *h = (struct bg_hdr *)p;
    bg_lock(&h->lock);
    if (h->magic != BG_MAGIC) { h->magic = BG_MAGIC; h->bump = BG_DATA_START; h->nalloc = h->nfree = 0; }
    bg_unlock(&h->lock);
    g_bg.hdr = h;
    return h;
}

/* returns the physical address, or 0 */
static uint32_t bg_alloc(int fd, uint32_t size) {
    struct bg_hdr *h = bg_header(fd);
    if (!h || !size) return 0;
    size = (size + BG_PAGE - 1) & ~(BG_PAGE - 1);
    uint32_t off = 0;
    bg_lock(&h->lock);
    if (h->nalloc < BG_MAX_BLOCKS) {
        for (uint32_t i = 0; i < h->nfree; i++) if (h->fr[i].size >= size) {
            off = h->fr[i].off; h->fr[i].off += size; h->fr[i].size -= size;
            if (!h->fr[i].size) { h->fr[i] = h->fr[--h->nfree]; }
            break;
        }
        if (!off && h->bump + size <= BG_POOL_SIZE) { off = h->bump; h->bump += size; }
        if (off) { h->alloc[h->nalloc].off = off; h->alloc[h->nalloc].size = size; h->nalloc++; }
    }
    bg_unlock(&h->lock);
    return off ? BG_POOL_PHYS_BASE + off : 0;
}

static int bg_free(int fd, uint32_t phys) {
    struct bg_hdr *h = bg_header(fd);
    if (!h || phys < BG_POOL_PHYS_BASE) return -22;
    uint32_t off = phys - BG_POOL_PHYS_BASE, size = 0;
    bg_lock(&h->lock);
    for (uint32_t i = 0; i < h->nalloc; i++) if (h->alloc[i].off == off) { size = h->alloc[i].size; h->alloc[i] = h->alloc[--h->nalloc]; break; }
    if (size) {
        /* merge with free neighbours, give the tail back to the bump pointer */
        for (int again = 1; again;) {
            again = 0;
            for (uint32_t i = 0; i < h->nfree; i++) {
                if (h->fr[i].off + h->fr[i].size == off) { off = h->fr[i].off; size += h->fr[i].size; h->fr[i] = h->fr[--h->nfree]; again = 1; break; }
                if (off + size == h->fr[i].off) { size += h->fr[i].size; h->fr[i] = h->fr[--h->nfree]; again = 1; break; }
            }
        }
        if (off + size == h->bump) h->bump = off;
        else if (h->nfree < BG_MAX_BLOCKS) { h->fr[h->nfree].off = off; h->fr[h->nfree].size = size; h->nfree++; }
        bg_punch(fd, BG_POOL_PHYS_BASE + off, size);       /* reused memory reads as zeros, like fresh pages */
    }
    bg_unlock(&h->lock);
    return size ? 0 : -22;
}

/* ------------------------------------------------------------------------------------------------ physical -> host */

static uint8_t *bg_map(void *ctx, uint32_t phys, uint32_t *avail) {
    (void)ctx;
    if (phys >= BG_POOL_PHYS_BASE && phys < BG_POOL_PHYS_BASE + BG_POOL_SIZE) {
        if (!g_bg.pool) {
            if (g_bg.pool_fd <= 0) { int fd = bg_pool_open(); if (fd < 0) return 0; g_bg.pool_fd = fd; }
            g_bg.pool = (uint8_t *)bg_mmap(BG_POOL_SIZE, g_bg.pool_fd, BG_POOL_PHYS_BASE);
            if (!g_bg.pool) return 0;
        }
        uint32_t o = phys - BG_POOL_PHYS_BASE;
        *avail = BG_POOL_SIZE - o;
        return g_bg.pool + o;
    }
    if (phys < BG_POOL_PHYS_BASE) {                              /* framebuffer: fb0 reports smem_start = 0 */
        if (!g_bg.fb) {
            long fd = bg_open(BG_FB_FILE, 2 /* O_RDWR */ | 02000000, 0);
            if (fd < 0) return 0;
            long len = bg_size((int)fd);
            if (len <= 0) { bg_close((int)fd); return 0; }
            g_bg.fb = (uint8_t *)bg_mmap((unsigned long)len, (int)fd, 0);
            if (!g_bg.fb) { bg_close((int)fd); return 0; }
            g_bg.fb_fd = (int)fd; g_bg.fb_len = (uint32_t)len;
        }
        if (phys >= g_bg.fb_len) return 0;
        *avail = g_bg.fb_len - phys;
        return g_bg.fb + phys;
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------------ device nodes */

static int bg_streq(const char *a, const char *b) {
    if (!a) return 0;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}
static void bg_remember(int fd, int kind) {
    for (int i = 0; i < 16; i++) if (g_bg.fds[i] == 0 || g_bg.fds[i] == fd) { g_bg.fds[i] = fd; g_bg.kinds[i] = kind; return; }
}
static int bg_kind(int fd) {
    if (fd <= 0) return 0;
    for (int i = 0; i < 16; i++) if (g_bg.fds[i] == fd) return g_bg.kinds[i];
    return 0;
}

/* raw result; -4096 means "not one of ours" */
static long bcmgpu_open(const char *path, int flags) {
    int kind;
    if (bg_streq(path, "/dev/gememalloc")) kind = BG_KIND_GEM;
    else if (bg_streq(path, "/dev/v3d")) kind = BG_KIND_V3D;
    else if (bg_streq(path, "/dev/cache-ops")) kind = BG_KIND_CACHE;
    else return -4096;
    long fd = kind == BG_KIND_GEM ? bg_pool_open() : bg_open("/dev/null", 2, 0);
    if (fd < 0) return fd;
    if (flags & 02000000) sys3(55 /* fcntl */, fd, 2 /* F_SETFD */, 1 /* FD_CLOEXEC */);
    bg_remember((int)fd, kind);
    return fd;
}

/* the shim's close(): forget a descriptor of ours */
static void bcmgpu_closed(int fd) {
    for (int i = 0; i < 16; i++) if (g_bg.fds[i] == fd && fd > 0) g_bg.fds[i] = 0;
}

/* After every V3D job leave a record at the end of fb0: where the last framebuffer render target lay (a byte offset into fb0, i.e.
 * which page SurfaceFlinger composes in) and a counter that rises with every job. A frame is several jobs; the app shows the page
 * once the counter has stopped rising, so it never shows a frame that is half composed. (The composer never pans fb0 per frame
 * here, so the pan ioctl cannot be used as the "frame done" signal.) */
static volatile uint32_t *bg_fb_rec(void) {
    uint32_t av = 0;
    if (!bg_map(0, 0, &av) || !g_bg.fb || g_bg.fb_len < 4096) return 0;
    return (volatile uint32_t *)(g_bg.fb + (g_bg.fb_len & ~3u) - 16);
}
/* a job starts: its tiles reach fb0 only when it ends, and the page may hold the cleared or half composed frame until then */
static void bg_fb_busy(void) {
    volatile uint32_t *r = bg_fb_rec();
    if (!r) return;
    r[0] = BG_POST_MAGIC;
    r[3] = 1;
    __sync_synchronize();
}
static void bg_fb_post(uint32_t tgt) {
    volatile uint32_t *r = bg_fb_rec();
    if (!r) return;
    r[1] = tgt;
    r[0] = BG_POST_MAGIC;
    __sync_fetch_and_add(&r[2], 1);
    r[3] = 0;
    __sync_synchronize();
}

/* raw result; -4096 means "not one of ours" */
static long bcmgpu_ioctl(int fd, unsigned req, void *arg) {
    int kind = bg_kind(fd);
    if (!kind) return -4096;
    if (kind == BG_KIND_CACHE) return 0;
    if (kind == BG_KIND_GEM) {
        uint32_t *w = (uint32_t *)arg;
        if (req == BG_IOC_ALLOC && w) { uint32_t p = bg_alloc(fd, w[1]); w[0] = p; return p ? 0 : -12; }
        if (req == BG_IOC_FREE && w) return bg_free(fd, w[0]);
        return -25;
    }
    /* BG_KIND_V3D */
    if (req == BG_IOC_V3D_SUBMIT && arg) {
        uint32_t job[7];
        for (int i = 0; i < 7; i++) job[i] = ((uint32_t *)arg)[i];
        g_bg.mem.map = bg_map;
        g_bg.mem.stage_limit = BG_POOL_PHYS_BASE;   /* fb0 is host visible: the core writes a job's framebuffer tiles when the job ends */
        bg_lock(&g_bg.exec_lock);
        bg_fb_busy();
        v3d_exec_job(&g_bg.mem, job);
        bg_fb_post(g_bg.mem.last_fb_tgt);
        bg_unlock(&g_bg.exec_lock);
        return 0;
    }
    if (req == BG_IOC_V3D_WAIT && arg) { ((uint32_t *)arg)[1] = 3; return 0; }   /* the job is always finished: status 3 */
    return -25;
}

#endif /* BCMGPU_H */
