/* Host test runner: replays jobs captured from the firmware's own V3D_Compose code against v3demu_core.h.
 * usage: replay case.bin out_fb.bin [out_pool.bin]
 * case.bin: u32 pool_phys, pool_len, fb_phys, fb_len, nprot, {start,len}*nprot, nsnap,
 *           fb_len bytes of initial framebuffer, then per snapshot: u32 job[7], u32 pool_len, pool bytes.
 * Later snapshots only refresh memory outside the protected ranges (those hold data the core itself produced). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../v3demu_core.h"

static uint32_t g_pool_phys, g_pool_len, g_fb_phys, g_fb_len;
static uint8_t *g_pool, *g_fb;

static uint8_t *map(void *ctx, uint32_t phys, uint32_t *avail) {
    (void)ctx;
    if (phys >= g_pool_phys && phys < g_pool_phys + g_pool_len) { *avail = g_pool_phys + g_pool_len - phys; return g_pool + (phys - g_pool_phys); }
    if (phys >= g_fb_phys && phys < g_fb_phys + g_fb_len) { *avail = g_fb_phys + g_fb_len - phys; return g_fb + (phys - g_fb_phys); }
    return 0;
}
static uint32_t rd(FILE *f) { uint32_t v; if (fread(&v, 4, 1, f) != 1) { fprintf(stderr, "short read\n"); exit(2); } return v; }

int main(int argc, char **argv) {
    FILE *f = fopen(argv[1], "rb"); if (!f) return 2;
    g_pool_phys = rd(f); g_pool_len = rd(f); g_fb_phys = rd(f); g_fb_len = rd(f);
    uint32_t nprot = rd(f), prot[64][2];
    for (uint32_t i = 0; i < nprot; i++) { prot[i][0] = rd(f); prot[i][1] = rd(f); }
    uint32_t nsnap = rd(f);
    g_pool = calloc(1, g_pool_len); g_fb = calloc(1, g_fb_len);
    if (fread(g_fb, 1, g_fb_len, f) != g_fb_len) return 2;
    struct v3d_mem mem = { map, 0, 0, 0, 0, getenv("V3D_MAX_DRAWS") ? (uint32_t)atoi(getenv("V3D_MAX_DRAWS")) : 0 };
    for (uint32_t s = 0; s < nsnap; s++) {
        uint32_t job[7]; for (int i = 0; i < 7; i++) job[i] = rd(f);
        uint32_t len = rd(f);
        uint8_t *snap = malloc(len); if (fread(snap, 1, len, f) != len) return 2;
        if (s == 0) memcpy(g_pool, snap, len);
        else {
            uint8_t *keep = malloc(g_pool_len); memcpy(keep, g_pool, g_pool_len);
            memcpy(g_pool, snap, len);
            for (uint32_t i = 0; i < nprot; i++) memcpy(g_pool + (prot[i][0] - g_pool_phys), keep + (prot[i][0] - g_pool_phys), prot[i][1]);
            free(keep);
        }
        free(snap);
        v3d_exec_job(&mem, job);
        fprintf(stderr, "job %u: flags=%u draws=%u tiles=%u unsupported=%u\n", s, job[0], mem.stats_draws, mem.stats_tiles, mem.stats_unsupported);
        mem.stats_draws = mem.stats_tiles = 0;
    }
    FILE *o = fopen(argv[2], "wb"); fwrite(g_fb, 1, g_fb_len, o); fclose(o);
    if (argc > 3) { o = fopen(argv[3], "wb"); fwrite(g_pool, 1, g_pool_len, o); fclose(o); }
    return mem.stats_unsupported ? 3 : 0;
}
