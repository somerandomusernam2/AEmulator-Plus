/* Differential test: original core vs new core on the same jobs. */
#include "scene.h"
#include "v3demu_ref.h"
#include "../native/bcmgpu/v3demu_core.h"
#include <time.h>

static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }

struct result { double t_ref, t_new; long diff_bytes, diff_px; int maxd; uint32_t unsup_ref, unsup_new; };

static struct mem_img clone_mem(const struct mem_img *m) {
    struct mem_img c = *m; c.pool = malloc(POOL_SZ); c.fb = malloc(FB_BYTES);
    memcpy(c.pool, m->pool, POOL_SZ); memcpy(c.fb, m->fb, FB_BYTES); return c;
}

/* compare as RGB565 / 8888 pixel streams to report the biggest channel difference */
static int chan_diff(const uint8_t *a, const uint8_t *b, int bpp) {
    int mx = 0;
    if (bpp == 2) {
        uint32_t x = a[0] | (a[1] << 8), y = b[0] | (b[1] << 8);
        int d[3] = { (int)(x >> 11) - (int)(y >> 11), (int)((x >> 5) & 63) - (int)((y >> 5) & 63), (int)(x & 31) - (int)(y & 31) };
        for (int i = 0; i < 3; i++) { int v = d[i] < 0 ? -d[i] : d[i]; if (v > mx) mx = v; }
    } else for (int i = 0; i < 4; i++) { int v = (int)a[i] - (int)b[i]; if (v < 0) v = -v; if (v > mx) mx = v; }
    return mx;
}

static int stage_limit_on = 1;

static struct result run_both(struct scene *s, int reps, int do_ref) {
    struct result r; memset(&r, 0, sizeof r);
    uint32_t job[7] = { 3, 0, s->bin_s, s->bin_e, s->rl_s, s->rl_e, 0 };
    struct mem_img mr = clone_mem(&s->m), mn = clone_mem(&s->m);
    struct v3dref_mem refm; memset(&refm, 0, sizeof refm); refm.map = test_map; refm.ctx = &mr; if (getenv("MAXD")) refm.debug_max_draws = atoi(getenv("MAXD"));
    struct v3d_mem newm; memset(&newm, 0, sizeof newm); newm.map = test_map; newm.ctx = &mn; newm.stage_limit = stage_limit_on ? FB_PHYS_LIMIT : 0; if (getenv("MAXD")) newm.debug_max_draws = atoi(getenv("MAXD"));
    double t0 = now();
    if (do_ref) { for (int i = 0; i < reps; i++) v3dref_exec_job(&refm, job); }
    r.t_ref = (now() - t0) / reps;
    t0 = now();
    for (int i = 0; i < reps; i++) v3d_exec_job(&newm, job);
    r.t_new = (now() - t0) / reps;
    r.unsup_ref = refm.stats_unsupported / reps; r.unsup_new = newm.stats_unsupported / reps;
    if (do_ref) {
        int bpp = s->tgt_fmt == 1 ? 4 : 2;
        uint8_t *a = mr.fb, *b = mn.fb;
        for (uint32_t i = 0; i < FB_BYTES; i++) if (a[i] != b[i]) r.diff_bytes++;
        for (uint32_t i = 0; i + bpp <= FB_BYTES; i += bpp) { int d = chan_diff(a + i, b + i, bpp); if (d) { r.diff_px++; if (d > r.maxd) r.maxd = d; } }
        for (uint32_t i = 0; i < POOL_SZ; i++) if (mr.pool[i] != mn.pool[i]) { r.diff_bytes++; }
    }
    free(mr.pool); free(mr.fb); free(mn.pool); free(mn.fb);
    return r;
}

/* ---- scenes ---- */
/* typical Gingerbread frame: opaque 565 launcher, translucent 8888 keyguard, 8888 status bar */
static void build_compose(struct scene *s, int partial) {
    scene_init(s);
    uint32_t t_launcher = make_tex565(s, 512, 800, 1), t_key = make_tex8888(s, 512, 768, 1), t_bar = make_tex8888(s, 512, 64, 0);
    struct qi p1[32], p2[32], p3[32]; int n1 = prog_texcopy(p1, 0), n2 = prog_texcopy(p2, 1), n3 = prog_texcopy(p3, 0);
    uint32_t u1[2] = { tex_p0(t_launcher, 4), tex_p1(512, 800, 1, 1, 1) };
    uint32_t u2[3] = { tex_p0(t_key, 0), tex_p1(512, 768, 1, 1, 1), 0xc0c0c0c0u };
    uint32_t u3[2] = { tex_p0(t_bar, 0), tex_p1(512, 64, 1, 1, 0) };
    struct vtx v1[4], v2[4], v3[4];
    quad(v1, 0, 0, 480, 800, 0.0f, 0.0f, 480.0f / 512.0f, 1.0f);
    quad(v2, 0, 38, 480, 800, 0.0f, 0.0f, 480.0f / 512.0f, 762.0f / 768.0f);
    quad(v3, 0, 0, 480, 38, 0.0f, 0.0f, 480.0f / 512.0f, 38.0f / 64.0f);
    struct draw_desc d1 = { p1, n1, u1, 2, 2, 5, v1, 4, { 0, 0, 480, 800 }, { 0, 0 } };
    struct draw_desc d2 = { p2, n2, u2, 3, 2, 5, v2, 4, { 0, 0, 480, 800 }, { 0, 0 } };
    struct draw_desc d3 = { p3, n3, u3, 2, 2, 5, v3, 4, { 0, 0, 480, 38 }, { 0, 0 } };
    if (partial) { d1.clip[1] = 100; d1.clip[3] = 60; d2.clip[1] = 100; d2.clip[3] = 60; d3.clip[2] = 0; }
    scene_add_draw(s, &d1); scene_add_draw(s, &d2);
    if (!partial) scene_add_draw(s, &d3);
    scene_end_bin(s);
    scene_render_list(s, 0, 480, 800, 2, 0, partial ? 1 : 0, 0, 0, 15, 25);
    if (partial) { /* previous frame is on screen */
        for (int i = 0; i < FB_W * FB_H; i++) wr16(s->m.fb + 2 * i, rnd());
    }
}

/* scaled / wrapped lookups with random geometry */
static void build_scale(struct scene *s, int nearest, int wrap, int fmt8888, int perspective) {
    scene_init(s);
    int tw = 37 + rndn(100), th = 21 + rndn(90);
    uint32_t tex = fmt8888 ? make_tex8888(s, tw, th, 0) : make_tex565(s, tw, th, 0);
    struct qi p[32]; int n = prog_texcopy(p, 0);
    uint32_t u[2] = { tex_p0(tex, fmt8888 ? 0 : 4), tex_p1(tw, th, wrap, wrap, !nearest) };
    struct vtx v[4];
    int x0 = rndn(100), y0 = rndn(100), x1 = x0 + 100 + rndn(300), y1 = y0 + 100 + rndn(500);
    float s1 = 0.3f + rndf() * 2.0f, t1 = 0.3f + rndf() * 2.0f;
    quad(v, x0, y0, x1, y1, -0.2f * rndf(), -0.2f * rndf(), s1, t1);
    if (perspective) for (int i = 0; i < 4; i++) { v[i].invw = 0.6f + rndf(); for (int k = 0; k < 2; k++) v[i].vary[k] *= v[i].invw; }
    struct draw_desc d = { p, n, u, 2, 2, 5, v, 4, { 0, 0, 0, 0 }, { 0, 0 } };
    scene_add_draw(s, &d); scene_end_bin(s);
    scene_render_list(s, 0, 480, 800, 2, 0x11223344u, 0, 0, 0, 15, 25);
}

static void report(const char *name, struct result r, int tol_px_ok) {
    printf("%-34s ref %8.2f ms  new %8.2f ms  x%-6.1f diffbytes %-8ld diffpx %-7ld maxd %d unsup %u/%u %s\n", name, r.t_ref * 1e3, r.t_new * 1e3,
           r.t_new > 0 ? r.t_ref / r.t_new : 0.0, r.diff_bytes, r.diff_px, r.maxd, r.unsup_ref, r.unsup_new,
           (r.diff_bytes == 0) ? "EXACT" : (tol_px_ok ? "close" : "DIFF"));
}

int main(int argc, char **argv) {
    int reps = argc > 1 ? atoi(argv[1]) : 1;
    struct scene s; struct result r;
    build_compose(&s, 0); r = run_both(&s, reps, 1); report("compose full frame", r, 1); scene_free(&s);
    build_compose(&s, 1); r = run_both(&s, reps, 1); report("compose partial (load/clip)", r, 1); scene_free(&s);
    for (int i = 0; i < 12; i++) {
        char nm[64]; int nearest = i & 1, wrap = (i >> 1) % 4, f8 = (i >> 2) & 1, persp = i >= 8;
        build_scale(&s, nearest, wrap, f8, persp);
        snprintf(nm, sizeof nm, "scale n%d wrap%d f%d p%d", nearest, wrap, f8, persp);
        r = run_both(&s, 1, 1); report(nm, r, 1); scene_free(&s);
    }
    return 0;
}
