/* Test scaffolding: QPU assembler, control-list builder, flat "physical" memory.  Host only. */
#ifndef SCENE_H
#define SCENE_H
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#define POOL_P 0x10000000u
#ifndef POOL_SZ
#define POOL_SZ (24u << 20)
#endif
#define FB_W 480
#define FB_H 800
#define FB_BYTES (FB_W * FB_H * 4)          /* room for an 8888 target */
#define FB_PHYS_LIMIT 0x00400000u

/* ---- deterministic rng */
static uint64_t rng_s = 88172645463325252ull;
static inline uint32_t rnd(void) { rng_s ^= rng_s << 13; rng_s ^= rng_s >> 7; rng_s ^= rng_s << 17; return (uint32_t)(rng_s >> 11); }
static inline uint32_t rndn(uint32_t n) { return n ? rnd() % n : 0; }
static inline float rndf(void) { return (float)(rnd() & 0xffffff) / 16777216.0f; }
static inline uint32_t fbits(float f) { uint32_t u; memcpy(&u, &f, 4); return u; }

/* ---- memory */
struct mem_img { uint8_t *pool, *fb; uint32_t pool_used; };
static uint8_t *test_map(void *ctx, uint32_t phys, uint32_t *avail) {
    struct mem_img *m = (struct mem_img *)ctx;
    if (phys >= POOL_P && phys < POOL_P + POOL_SZ) { *avail = POOL_P + POOL_SZ - phys; return m->pool + (phys - POOL_P); }
    if (phys < FB_BYTES) { *avail = FB_BYTES - phys; return m->fb + phys; }
    return 0;
}
static uint32_t pool_alloc(struct mem_img *m, uint32_t size) {
    uint32_t a = (m->pool_used + 0xfff) & ~0xfffu; m->pool_used = a + size;
    if (m->pool_used > POOL_SZ) { fprintf(stderr, "pool full\n"); exit(2); }
    return POOL_P + a;
}
static void wr32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
static void wr16(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; }

/* ---- QPU assembler */
struct qi { uint32_t lo, hi; };
#define MX_R0 0
#define MX_R1 1
#define MX_R2 2
#define MX_R3 3
#define MX_R4 4
#define MX_R5 5
#define MX_A 6
#define MX_B 7
#define W_NOP 39
#define W_TLB 45
#define W_TMU_S 56
#define W_TMU_T 57
#define R_UNIF 32
#define R_VARY 35
#define SIG_NONE 1
#define SIG_END 3
#define SIG_TILE 8
#define SIG_ENDTILE 9
#define SIG_LDTMU 10
#define SIG_SMALL 13
#define SIG_IMM 14
static struct qi qalu(int sig, int unpack, int pm, int pack, int cadd, int cmul, int ws, int wadd, int wmul,
                      int opm, int opa, int ra, int rb, int am, int bm, int mam, int mbm) {
    struct qi q;
    q.lo = ((uint32_t)opm << 29) | ((uint32_t)opa << 24) | ((uint32_t)ra << 18) | ((uint32_t)rb << 12) | (am << 9) | (bm << 6) | (mam << 3) | mbm;
    q.hi = ((uint32_t)sig << 28) | ((uint32_t)unpack << 25) | ((uint32_t)pm << 24) | ((uint32_t)pack << 20) | ((uint32_t)cadd << 17) |
           ((uint32_t)cmul << 14) | ((uint32_t)ws << 12) | ((uint32_t)wadd << 6) | (uint32_t)wmul;
    return q;
}
static struct qi qnop(int sig) { return qalu(sig, 0, 0, 0, 1, 1, 0, W_NOP, W_NOP, 0, 0, 0, 0, 0, 0, 0, 0); }
static struct qi qimm(uint32_t v, int ws, int wa, int wm, int cadd, int cmul) {
    struct qi q; q.lo = v; q.hi = (14u << 28) | ((uint32_t)cadd << 17) | ((uint32_t)cmul << 14) | ((uint32_t)ws << 12) | ((uint32_t)wa << 6) | (uint32_t)wm; return q;
}

/* ---- scene builder: one job = bin list + render list + shader records + vertices */
struct vtx { int x16, y16; float invw; float vary[8]; };
struct draw_desc {
    const struct qi *prog; int nprog;
    const uint32_t *unif; int nunif;
    int nvary; int mode;                       /* 4 tri, 5 strip, 6 fan */
    struct vtx *v; int nv;
    int clip[4]; int vp[2];
};
struct scene {
    struct mem_img m;
    uint32_t bin_s, bin_e, rl_s, rl_e;
    uint32_t tgt_addr; int tgt_fmt;
    uint8_t *bin; uint32_t bin_n;
};
static uint8_t *pp(struct scene *s, uint32_t phys) { return s->m.pool + (phys - POOL_P); }

static uint32_t emit_draw_data(struct scene *s, const struct draw_desc *d) {
    int stride = 12 + 4 * d->nvary;
    uint32_t code = pool_alloc(&s->m, 8 * (d->nprog + 4)), unif = pool_alloc(&s->m, 4 * (d->nunif + 4)),
             vtx = pool_alloc(&s->m, stride * d->nv + 16), rec = pool_alloc(&s->m, 64);
    for (int i = 0; i < d->nprog; i++) { wr32(pp(s, code) + 8 * i, d->prog[i].lo); wr32(pp(s, code) + 8 * i + 4, d->prog[i].hi); }
    for (int i = 0; i < d->nunif; i++) wr32(pp(s, unif) + 4 * i, d->unif[i]);
    for (int i = 0; i < d->nv; i++) {
        uint8_t *p = pp(s, vtx) + stride * i;
        wr16(p, (uint16_t)d->v[i].x16); wr16(p + 2, (uint16_t)d->v[i].y16); wr32(p + 4, 0); wr32(p + 8, fbits(d->v[i].invw));
        for (int k = 0; k < d->nvary; k++) wr32(p + 12 + 4 * k, fbits(d->v[i].vary[k]));
    }
    uint32_t w0 = (uint32_t)(stride << 8) | ((uint32_t)d->nvary << 24);
    wr32(pp(s, rec), w0); wr32(pp(s, rec) + 4, code); wr32(pp(s, rec) + 8, unif); wr32(pp(s, rec) + 12, vtx);
    return rec;
}
static void bin_u8(struct scene *s, int v) { s->bin[s->bin_n++] = v; }
static void bin_u16(struct scene *s, int v) { bin_u8(s, v & 255); bin_u8(s, (v >> 8) & 255); }
static void bin_u32(struct scene *s, uint32_t v) { bin_u16(s, v & 0xffff); bin_u16(s, v >> 16); }

static void scene_init(struct scene *s) {
    memset(s, 0, sizeof *s);
    s->m.pool = calloc(1, POOL_SZ); s->m.fb = calloc(1, FB_BYTES);
    s->bin = malloc(1 << 20);
}
static void scene_free(struct scene *s) { free(s->m.pool); free(s->m.fb); free(s->bin); }
static void scene_add_draw(struct scene *s, const struct draw_desc *d) {
    uint32_t rec = emit_draw_data(s, d);
    if (d->clip[2] > 0) { bin_u8(s, 102); bin_u16(s, d->clip[0]); bin_u16(s, d->clip[1]); bin_u16(s, d->clip[2]); bin_u16(s, d->clip[3]); }
    bin_u8(s, 103); bin_u16(s, d->vp[0]); bin_u16(s, d->vp[1]);
    bin_u8(s, 65); bin_u32(s, rec);
    bin_u8(s, 33); bin_u8(s, d->mode); bin_u32(s, d->nv); bin_u32(s, 0);
}
static void scene_end_bin(struct scene *s) {
    bin_u8(s, 0);
    uint32_t a = pool_alloc(&s->m, s->bin_n + 16);
    memcpy(pp(s, a), s->bin, s->bin_n);
    s->bin_s = a; s->bin_e = a + s->bin_n;
}
/* render list: kind 0 = every tile clear+draw+store(25/24); 1 = load before each tile, draw, store(28) */
static void scene_render_list(struct scene *s, uint32_t tgt, int w, int h, int fmt, uint32_t clear, int mode,
                              int tx0, int ty0, int tx1, int ty1) {
    uint8_t *rl = malloc(1 << 20); uint32_t n = 0;
#define R8(v) rl[n++] = (v)
#define R16(v) do { R8((v) & 255); R8(((v) >> 8) & 255); } while (0)
#define R32(v) do { R16((v) & 0xffff); R16((uint32_t)(v) >> 16); } while (0)
    R8(114); R32(clear); R32(clear); R32(0); R8(0);                          /* 14 bytes */
    R8(113); R32(tgt); R16(w); R16(h); R8(fmt << 2); R8(0);                 /* 11 bytes */
    int tw = (w + 31) / 32, th = (h + 31) / 32; (void)tw; (void)th;
    int first = 1;
    for (int ty = ty0; ty < ty1; ty++) for (int tx = tx0; tx < tx1; tx++) {
        int last = (ty == ty1 - 1 && tx == tx1 - 1);
        if (mode == 1) { R8(29); R8(1); R8(fmt == 1 ? 0 : 1); R32(tgt); }   /* load before the tile */
        else if (first) { R8(28); R8(0); R8(0); R32(0); }                    /* 'store nothing': clear */
        R8(115); R8(tx); R8(ty);
        R8(17); R32(0);
        if (mode == 1) { if (last) { R8(25); } else { R8(28); R8(1); R8(fmt == 1 ? 0 : 1); R32(tgt); } }
        else { if (last) R8(25); else R8(24); }
        first = 0;
    }
    R8(0);
    uint32_t a = pool_alloc(&s->m, n + 16); memcpy(pp(s, a), rl, n);
    s->rl_s = a; s->rl_e = a + n; s->tgt_addr = tgt; s->tgt_fmt = fmt;
    free(rl);
#undef R8
#undef R16
#undef R32
}

/* ---- textures */
static uint32_t make_tex565(struct scene *s, int w, int h, int seed_kind) {
    uint32_t a = pool_alloc(&s->m, w * h * 2);
    uint8_t *p = pp(s, a);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        uint32_t v;
        if (seed_kind == 0) v = rnd() & 0xffff;
        else { int r = (x * 31 / (w > 1 ? w - 1 : 1)), g = (y * 63 / (h > 1 ? h - 1 : 1)), b = ((x ^ y) & 31); v = (r << 11) | (g << 5) | b; }
        wr16(p + (y * w + x) * 2, v);
    }
    return a;
}
static uint32_t make_tex8888(struct scene *s, int w, int h, int seed_kind) {
    uint32_t a = pool_alloc(&s->m, w * h * 4);
    uint8_t *p = pp(s, a);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        uint32_t v;
        if (seed_kind == 0) v = rnd();
        else v = (x * 255 / (w > 1 ? w - 1 : 1)) | ((y * 255 / (h > 1 ? h - 1 : 1)) << 8) | (((x ^ y) & 255) << 16) | (((x + y) & 255) << 24);
        wr32(p + (y * w + x) * 4, v);
    }
    return a;
}

/* ---- the programs a compositor would use */
/* textured copy / constant alpha: varyings s,t -> TMU -> (v8muld alpha) -> TLB.  unif: tex0 tex1 [alpha] */
static int prog_texcopy(struct qi *p, int with_alpha) {
    int n = 0;
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, 32, W_NOP, 0, 1, R_VARY, 0, MX_A, MX_R5, 0, 0);       /* fadd r0, vary, r5 */
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, 33, W_NOP, 0, 1, R_VARY, 0, MX_A, MX_R5, 0, 0);       /* fadd r1, vary, r5 */
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, W_NOP, W_TMU_T, 1, 0, 15, 0, 0, 0, MX_R1, MX_A);      /* fmul t0t, r1, ra15 */
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, W_NOP, W_TMU_S, 1, 0, 15, 0, 0, 0, MX_R0, MX_A);      /* fmul t0s, r0, ra15 */
    p[n++] = qnop(SIG_NONE);
    p[n++] = qnop(SIG_LDTMU);
    if (with_alpha) {
        p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, W_NOP, 32, 3, 0, R_UNIF, 0, 0, 0, MX_R4, MX_A);   /* v8muld r0, r4, unif */
        p[n++] = qalu(SIG_END, 0, 0, 0, 1, 1, 0, W_TLB, W_NOP, 0, 21, 0, 0, MX_R0, MX_R0, 0, 0);    /* or tlb, r0, r0 ; thrend */
    } else {
        p[n++] = qalu(SIG_END, 0, 0, 0, 1, 1, 0, W_TLB, W_NOP, 0, 21, 0, 0, MX_R4, MX_R4, 0, 0);    /* or tlb, r4, r4 ; thrend */
    }
    p[n++] = qnop(SIG_NONE); p[n++] = qnop(SIG_NONE);
    return n;
}
/* blend: dst = src*a + dst*(1-a) using tile read; unif: tex0 tex1 alpha1 alpha2  (bytes replicated) */
static int prog_blend(struct qi *p) {
    int n = 0;
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, 32, W_NOP, 0, 1, R_VARY, 0, MX_A, MX_R5, 0, 0);
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, 33, W_NOP, 0, 1, R_VARY, 0, MX_A, MX_R5, 0, 0);
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, W_NOP, W_TMU_T, 1, 0, 15, 0, 0, 0, MX_R1, MX_A);
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, W_NOP, W_TMU_S, 1, 0, 15, 0, 0, 0, MX_R0, MX_A);
    p[n++] = qnop(SIG_TILE);                                                                         /* r4 = dest colour */
    p[n++] = qalu(SIG_LDTMU, 0, 0, 0, 1, 1, 0, 34, 35, 3, 0, R_UNIF, R_UNIF, 0, 0, MX_R4, MX_A);     /* r2 = dst * ua (r4 is tile) -> uses ra=unif */
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, W_NOP, W_NOP, 0, 0, 0, 0, 0, 0, 0, 0);
    p[n++] = qalu(SIG_NONE, 0, 0, 0, 1, 1, 0, 32, W_NOP, 0, 30, 0, 0, MX_R2, MX_R3, 0, 0);          /* v8adds r0, r2, r3 */
    p[n++] = qalu(SIG_END, 0, 0, 0, 1, 1, 0, W_TLB, W_NOP, 0, 21, 0, 0, MX_R0, MX_R0, 0, 0);
    p[n++] = qnop(SIG_NONE); p[n++] = qnop(SIG_NONE);
    return n;
}
/* unif words for a 2D texture lookup */
static uint32_t tex_p0(uint32_t base, int type) { return (base & 0xfffff000u) | ((uint32_t)type << 4); }
static uint32_t tex_p1(int w, int h, int ws, int wt, int bilinear) {
    uint32_t v = ((uint32_t)(w & 2047) << 8) | ((uint32_t)(h & 2047) << 20) | ws | (wt << 2);
    if (!bilinear) v |= 1u << 7;
    return v;
}
static void quad(struct vtx *v, int x0, int y0, int x1, int y1, float s0, float t0, float s1, float t1) {
    /* triangle strip order: (0,0) (1,0) (0,1) (1,1) ; coordinates in pixels -> 1/16 */
    int xs[4] = { x0, x1, x0, x1 }, ys[4] = { y0, y0, y1, y1 };
    float ss[4] = { s0, s1, s0, s1 }, ts[4] = { t0, t0, t1, t1 };
    for (int i = 0; i < 4; i++) { v[i].x16 = xs[i] * 16; v[i].y16 = ys[i] * 16; v[i].invw = 1.0f; memset(v[i].vary, 0, sizeof v[i].vary); v[i].vary[0] = ss[i]; v[i].vary[1] = ts[i]; }
}
#endif
