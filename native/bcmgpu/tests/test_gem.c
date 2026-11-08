/* Host test of the /dev/gememalloc emulation (shared pool file, allocator, ioctl layer). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#define BCMGPU_HOST 1
#define BG_POOL_FILE "/tmp/.aemu-gem-test"
static long sys3(long n, long a, long b, long c) { (void)n; (void)a; (void)b; (void)c; return 0; }
#include "../bcmgpu.h"

static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)

int main(void) {
    unlink("/tmp/.aemu-gem-test");
    /* the build-machine run uses a private pool file: patch the path by opening through bg_pool_open's constant */
    int fd = bcmgpu_open("/dev/gememalloc", 0);
    CHECK(fd > 0);
    uint32_t a[2] = { 0, 3 * 4096 + 5 };
    CHECK(bcmgpu_ioctl(fd, BG_IOC_ALLOC, a) == 0);
    CHECK(a[0] == BG_POOL_PHYS_BASE + BG_DATA_START);          /* first block follows the header page */
    uint32_t b[2] = { 0, 4096 };
    CHECK(bcmgpu_ioctl(fd, BG_IOC_ALLOC, b) == 0);
    CHECK(b[0] == a[0] + 4 * 4096);                            /* size rounded up to pages */
    /* mmap(fd, offset = phys) must hit the same bytes the emulator sees through its own mapping */
    uint8_t *m = mmap(0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, (off_t)b[0]);
    CHECK(m != MAP_FAILED);
    m[10] = 0x5a;
    uint32_t av = 0; uint8_t *p = bg_map(0, b[0], &av);
    CHECK(p && p[10] == 0x5a && av >= 4096);
    /* a second process sees the same allocator state */
    pid_t c = fork();
    if (c == 0) {
        g_bg.hdr = 0; g_bg.pool = 0; g_bg.pool_fd = 0;
        int fd2 = bcmgpu_open("/dev/gememalloc", 0);
        uint32_t x[2] = { 0, 4096 };
        _exit(bcmgpu_ioctl(fd2, BG_IOC_ALLOC, x) == 0 && x[0] == b[0] + 4096 ? 0 : 1);
    }
    int st = 0; waitpid(c, &st, 0); CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);
    /* free + reuse + coalescing */
    CHECK(bcmgpu_ioctl(fd, BG_IOC_FREE, a) == 0);               /* hole at the start */
    uint32_t d[2] = { 0, 4096 }; CHECK(bcmgpu_ioctl(fd, BG_IOC_ALLOC, d) == 0 && d[0] == a[0]);   /* first fit */
    uint32_t big[2] = { 0, 0x7000000 }; CHECK(bcmgpu_ioctl(fd, BG_IOC_ALLOC, big) == 0);
    uint32_t big2[2] = { 0, 0x7000000 }; CHECK(bcmgpu_ioctl(fd, BG_IOC_ALLOC, big2) != 0 && big2[0] == 0);   /* pool exhausted */
    CHECK(bcmgpu_ioctl(fd, BG_IOC_FREE, big) == 0);
    CHECK(bcmgpu_ioctl(fd, BG_IOC_ALLOC, big2) == 0);            /* the tail went back to the bump pointer */
    uint32_t bogus = BG_POOL_PHYS_BASE + 0x12345000; CHECK(bcmgpu_ioctl(fd, BG_IOC_FREE, &bogus) != 0);
    CHECK(bcmgpu_ioctl(fd, 0x1234, a) == -25);                  /* unknown ioctl on the allocator */
    CHECK(bcmgpu_ioctl(77, BG_IOC_ALLOC, a) == -4096);          /* not our descriptor */
    int cache = bcmgpu_open("/dev/cache-ops", 0); CHECK(cache > 0 && bcmgpu_ioctl(cache, BG_IOC_CACHE, 0) == 0);
    int v3d = bcmgpu_open("/dev/v3d", 0); uint32_t w[3] = { 1, 0, 0xffffffffu };
    CHECK(v3d > 0 && bcmgpu_ioctl(v3d, BG_IOC_V3D_WAIT, w) == 0 && w[1] == 3);
    uint32_t job[7] = { 0 }; CHECK(bcmgpu_ioctl(v3d, BG_IOC_V3D_SUBMIT, job) == 0);   /* an empty job is harmless */
    bcmgpu_closed(v3d); CHECK(bcmgpu_ioctl(v3d, BG_IOC_V3D_WAIT, w) == -4096);
    printf(fails ? "FAILED (%d)\n" : "gememalloc emulation: all checks passed\n", fails);
    unlink(BG_POOL_FILE);
    return fails != 0;
}
