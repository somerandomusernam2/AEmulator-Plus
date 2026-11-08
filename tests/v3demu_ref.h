/*
 * v3demu_core.h - software execution of Broadcom V3D (VideoCore IV style) jobs.
 *
 * The firmware of Broadcom BCM2153x phones (GT-S5300/S5302/S5360 ...) composes the screen from SurfaceFlinger
 * through V3DREF_Compose_Impl: it builds a tile-binning control list, a tile-rendering control list and a few QPU
 * fragment programs in "physical" memory and hands them to /dev/v3d. There is no GPU here, so the same lists are
 * executed in software:
 *
 *   bin list     -> draw calls (shader record + primitive), pre-transformed vertices, no vertex shader
 *   render list  -> per 32x32 tile: clear / load / run the draws of the tile / store
 *   QPU programs -> interpreted, span-at-a-time (one instruction over up to 32 pixels), TMU + TLB included
 *
 * The code is pure computation: no libc, no system calls, all memory goes through struct v3dref_mem. That keeps it
 * usable inside the freestanding guest shim and in a host unit test.  Everything the firmware's own programs
 * need is implemented (textured copy, constant-alpha blend, dither, YUV->RGB), not recognised by checksum, so
 * other firmware builds with different shader blobs work as long as they use the same QPU features.
 *
 * Deliberate simplifications (none changes what SurfaceFlinger can observe):
 *   - memory formats "T" and "LT" of render targets / textures are stored raster; only the emulator reads them
 *   - no depth/stencil, no multisampling, no culling; scissor (clip window) and viewport offset are honoured
 *   - the dithered 565 target format is stored un-dithered (the firmware runs an explicit dither program anyway)
 *   - QPU condition flags are not modelled (the firmware never sets them); 'never' is honoured
 */
#ifndef V3DEMU_REF_H
#define V3DEMU_REF_H

#include <stdint.h>

#define V3DREF_TILE 32
#define V3DREF_LANES 32            /* pixels per interpreter span */
#define V3DREF_MAX_DRAWS 192
#define V3DREF_MAX_QPU_INSNS 2048

struct v3dref_mem {
    /* Map a physical range. Returns a host pointer and, in *avail, how many bytes are valid from there on;
     * NULL when the address does not belong to anything. */
    uint8_t *(*map)(void *ctx, uint32_t phys, uint32_t *avail);
    void *ctx;
    uint32_t stats_draws, stats_tiles, stats_unsupported;
    uint32_t debug_max_draws;     /* tests only: run just the first N draws of a job (0 = all) */
};

/* ------------------------------------------------------------------------------------------ small helpers */

static inline uint32_t v3dref_ld32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
static inline uint32_t v3dref_ld16(const uint8_t *p) { return p[0] | (p[1] << 8); }
static inline void v3dref_st16(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static inline void v3dref_st32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }
union v3dref_fu { float f; uint32_t u; };
static inline float v3dref_u2f(uint32_t u) { union v3dref_fu x; x.u = u; return x.f; }
static inline uint32_t v3dref_f2u(float f) { union v3dref_fu x; x.f = f; return x.u; }

static inline uint32_t v3dref_rd32(struct v3dref_mem *m, uint32_t phys) {
    uint32_t av = 0; const uint8_t *p = m->map(m->ctx, phys, &av);
    return (p && av >= 4) ? v3dref_ld32(p) : 0;
}

/* ------------------------------------------------------------------------------------------ pixel formats */

/* RGBA8888 as seen by the QPU: R in the low byte, A in the high byte */
static inline uint32_t v3dref_rgb565_to_rgba(uint32_t c) {
    uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    return ((r << 3) | (r >> 2)) | ((((g << 2) | (g >> 4))) << 8) | ((((b << 3) | (b >> 2))) << 16) | 0xff000000u;
}
static inline uint32_t v3dref_rgba_to_rgb565(uint32_t c) {
    return (((c & 0xff) >> 3) << 11) | ((((c >> 8) & 0xff) >> 2) << 5) | (((c >> 16) & 0xff) >> 3);
}

/* texel by TMU type; returns RGBA8888 (R low). 'p' points at the texel. */
static inline uint32_t v3dref_texel(int type, const uint8_t *p) {
    uint32_t c, r, g, b, a;
    switch (type) {
    case 0: return v3dref_ld32(p);                                   /* RGBA8888 */
    case 1: return v3dref_ld32(p) | 0xff000000u;                     /* RGBX8888 */
    case 2: c = v3dref_ld16(p);                                      /* RGBA4444, R on top */
        r = (c >> 12) & 15; g = (c >> 8) & 15; b = (c >> 4) & 15; a = c & 15;
        return (r * 17) | ((g * 17) << 8) | ((b * 17) << 16) | ((a * 17) << 24);
    case 3: c = v3dref_ld16(p);                                      /* RGBA5551 */
        r = (c >> 11) & 31; g = (c >> 6) & 31; b = (c >> 1) & 31; a = (c & 1) ? 255 : 0;
        return ((r << 3) | (r >> 2)) | (((g << 3) | (g >> 2)) << 8) | (((b << 3) | (b >> 2)) << 16) | (a << 24);
    case 4: return v3dref_rgb565_to_rgba(v3dref_ld16(p));               /* RGB565 */
    case 5: c = p[0]; return c | (c << 8) | (c << 16) | 0xff000000u;                    /* LUMINANCE */
    case 6: return (uint32_t)p[0] << 24;                                                /* ALPHA */
    case 7: c = v3dref_ld16(p); return (c & 255) | ((c & 255) << 8) | ((c & 255) << 16) | ((c >> 8) << 24); /* LUMALPHA */
    default: return 0;
    }
}
static inline int v3dref_texel_bytes(int type) {
    switch (type) { case 0: case 1: return 4; case 2: case 3: case 4: case 7: return 2; default: return 1; }
}

/* ------------------------------------------------------------------------------------------ job state */

struct v3dref_draw {
    uint32_t rec;                 /* NV shader record */
    uint32_t mode, len, start;
    int32_t clip_x, clip_y, clip_w, clip_h;   /* scissor, pixels */
    int32_t vp_x, vp_y;                       /* viewport offset, 1/16 pixel */
};

struct v3dref_target {
    uint32_t addr; int w, h, fmt;  /* fmt: 0/2 = 565, 1 = 8888 */
    int bpp;
};

struct v3dref_job {
    struct v3dref_mem *mem;
    struct v3dref_draw draws[V3DREF_MAX_DRAWS];
    int ndraws;
    struct v3dref_target tgt;
    uint32_t clear_color;
    uint32_t tile[V3DREF_TILE * V3DREF_TILE];
    int tx, ty;
    int pend_load; uint32_t pend_addr; int pend_fmt;   /* a LOAD is performed by the next TILE_COORDINATES */
};

/* per-draw data cached for the tile loops */
struct v3dref_prim {
    const struct v3dref_draw *d;
    const uint8_t *vtx; uint32_t vtx_avail;
    uint32_t stride, nvary;
    const uint8_t *code; uint32_t code_avail;
    const uint8_t *unif; uint32_t unif_avail;
};

/* ------------------------------------------------------------------------------------------ QPU interpreter */

struct v3dref_qpu {
    const struct v3dref_job *job;
    const struct v3dref_prim *prim;
    uint32_t ra[32][V3DREF_LANES], rb[32][V3DREF_LANES], acc[6][V3DREF_LANES], r4[V3DREF_LANES];
    uint32_t px[V3DREF_LANES], py[V3DREF_LANES];       /* absolute pixel coordinate */
    float vary[8][V3DREF_LANES];                     /* perspective-correct, already divided by W */
    uint32_t active[V3DREF_LANES];
    uint32_t colout[V3DREF_LANES];
    uint32_t tmu_t[V3DREF_LANES]; int have_t;
    uint32_t fifo[4][V3DREF_LANES]; int nfifo;
    uint32_t nuni, nvary_read;
    int tile_x0, tile_y0;
};

static inline uint32_t v3dref_sat8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : (uint32_t)v); }

static inline uint32_t v3dref_v8mul(uint32_t a, uint32_t b) {
    uint32_t r = 0;
    for (int i = 0; i < 4; i++) {
        uint32_t x = ((a >> (8 * i)) & 255) * ((b >> (8 * i)) & 255) + 128;
        r |= (((x + (x >> 8)) >> 8) & 255) << (8 * i);
    }
    return r;
}
static inline uint32_t v3dref_v8bin(int op, uint32_t a, uint32_t b) {   /* 0 adds 1 subs 2 min 3 max */
    uint32_t r = 0;
    for (int i = 0; i < 4; i++) {
        int x = (a >> (8 * i)) & 255, y = (b >> (8 * i)) & 255, v;
        switch (op) { case 0: v = x + y; break; case 1: v = x - y; break; case 2: v = x < y ? x : y; break; default: v = x > y ? x : y; }
        r |= v3dref_sat8(v) << (8 * i);
    }
    return r;
}

static float v3dref_f16(uint32_t h) {
    uint32_t s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
    float v;
    if (e == 0) v = (float)m * (1.0f / 16777216.0f);
    else if (e == 31) v = 65504.0f;
    else { union v3dref_fu x; x.u = ((e + 112) << 23) | (m << 13); v = x.f; }
    return s ? -v : v;
}

/* operand fetch with unpack; 'isfloat' tells how 8a..8d / 16a / 16b are widened */
static void v3dref_unpack(uint32_t *dst, const uint32_t *src, int mode, int isfloat) {
    for (int l = 0; l < V3DREF_LANES; l++) {
        uint32_t v = src[l];
        switch (mode) {
        case 1: dst[l] = isfloat ? v3dref_f2u(v3dref_f16(v & 0xffff)) : (uint32_t)(int32_t)(int16_t)v; break;
        case 2: dst[l] = isfloat ? v3dref_f2u(v3dref_f16(v >> 16)) : (uint32_t)(int32_t)(int16_t)(v >> 16); break;
        case 3: dst[l] = (v >> 24) * 0x01010101u; break;
        case 4: case 5: case 6: case 7: {
            uint32_t b = (v >> (8 * (mode - 4))) & 255;
            dst[l] = isfloat ? v3dref_f2u((float)b * (1.0f / 255.0f)) : b;
            break; }
        default: dst[l] = v;
        }
    }
}

static int v3dref_op_is_float_add(int op) { return op >= 1 && op <= 8; }

static uint32_t v3dref_pack_byte_f(float f) {
    if (!(f > 0.0f)) return 0;
    if (f >= 1.0f) return 255;
    return (uint32_t)(f * 255.0f + 0.5f);
}

/* wrap a texel coordinate */
static inline int v3dref_wrap(int x, int n, int mode) {
    if (x >= 0 && x < n) return x;
    switch (mode) {
    case 0: { int g = 0; while (x < 0 && g++ < 8) x += n; g = 0; while (x >= n && g++ < 8) x -= n;
              return x < 0 ? 0 : (x >= n ? n - 1 : x); }
    case 2: { int p = 2 * n; int g = 0; while (x < 0 && g++ < 8) x += p; g = 0; while (x >= p && g++ < 8) x -= p;
              if (x < 0) x = 0;
              return x >= n ? (p - 1 - x < 0 ? 0 : p - 1 - x) : x; }
    default: return x < 0 ? 0 : n - 1;
    }
}

static uint32_t v3dref_fetch_texel(struct v3dref_mem *m, uint32_t base, int type, int w, int h, int x, int y, int ws, int wt) {
    if (ws == 3 && (x < 0 || x >= w)) return 0;
    if (wt == 3 && (y < 0 || y >= h)) return 0;
    x = v3dref_wrap(x, w, ws); y = v3dref_wrap(y, h, wt);
    int bpp = v3dref_texel_bytes(type);
    uint32_t off = ((uint32_t)y * (uint32_t)w + (uint32_t)x) * bpp, av = 0;
    const uint8_t *p = m->map(m->ctx, base, &av);
    if (!p || av < off + bpp) return 0;
    return v3dref_texel(type, p + off);
}

static inline int v3dref_ifloor(float f) { int i = (int)f; return (f < (float)i) ? i - 1 : i; }

static void v3dref_tmu_2d(struct v3dref_qpu *q, const uint32_t *s, uint32_t p0, uint32_t p1, uint32_t *out) {
    struct v3dref_mem *m = q->job->mem;
    uint32_t base = p0 & 0xfffff000u; int type = (p0 >> 4) & 15;
    int w = (p1 >> 8) & 2047, h = (p1 >> 20) & 2047; if (!w) w = 2048; if (!h) h = 2048;
    int ws = p1 & 3, wt = (p1 >> 2) & 3;
    int lin = !((p1 >> 7) & 1) && !((p1 >> 4) & 7);
    for (int l = 0; l < V3DREF_LANES; l++) {
        float u = v3dref_u2f(s[l]) * (float)w, v = v3dref_u2f(q->tmu_t[l]) * (float)h;
        if (!lin) {
            out[l] = v3dref_fetch_texel(m, base, type, w, h, v3dref_ifloor(u), v3dref_ifloor(v), ws, wt);
            continue;
        }
        u -= 0.5f; v -= 0.5f;
        int x0 = v3dref_ifloor(u), y0 = v3dref_ifloor(v);
        uint32_t fx = (uint32_t)((u - (float)x0) * 256.0f), fy = (uint32_t)((v - (float)y0) * 256.0f);
        if (fx > 255) fx = 255;
        if (fy > 255) fy = 255;
        if (fx == 0 && fy == 0) { out[l] = v3dref_fetch_texel(m, base, type, w, h, x0, y0, ws, wt); continue; }
        uint32_t c00 = v3dref_fetch_texel(m, base, type, w, h, x0, y0, ws, wt);
        uint32_t c10 = v3dref_fetch_texel(m, base, type, w, h, x0 + 1, y0, ws, wt);
        uint32_t c01 = v3dref_fetch_texel(m, base, type, w, h, x0, y0 + 1, ws, wt);
        uint32_t c11 = v3dref_fetch_texel(m, base, type, w, h, x0 + 1, y0 + 1, ws, wt);
        uint32_t r = 0;
        for (int i = 0; i < 4; i++) {
            uint32_t a = (c00 >> (8 * i)) & 255, b = (c10 >> (8 * i)) & 255, c = (c01 >> (8 * i)) & 255, d = (c11 >> (8 * i)) & 255;
            uint32_t top = a * (256 - fx) + b * fx, bot = c * (256 - fx) + d * fx;
            r |= (((top * (256 - fy) + bot * fy) + 32768) >> 16 & 255) << (8 * i);
        }
        out[l] = r;
    }
}

static void v3dref_tmu_raw(struct v3dref_qpu *q, const uint32_t *s, uint32_t *out) {
    struct v3dref_mem *m = q->job->mem;
    for (int l = 0; l < V3DREF_LANES; l++) out[l] = v3dref_rd32(m, s[l] & ~3u);
}

static inline uint32_t v3dref_uni(struct v3dref_qpu *q) {
    uint32_t i = q->nuni++;
    return (q->prim->unif && i * 4 + 4 <= q->prim->unif_avail) ? v3dref_ld32(q->prim->unif + i * 4) : 0;
}

/* sources: returns pointer to a lane array */
static const uint32_t *v3dref_src(struct v3dref_qpu *q, int mux, int raddr_a, int raddr_b, int smallimm,
                               uint32_t unif_val, uint32_t *tmp, int pm, int unpack, int isfloat, int rf_a_uni_read) {
    (void)rf_a_uni_read;
    const uint32_t *p = tmp;
    int l;
    if (mux < 4) p = q->acc[mux];
    else if (mux == 4) { p = q->r4; if (pm && unpack) { v3dref_unpack(tmp, q->r4, unpack, isfloat); p = tmp; } }
    else if (mux == 5) { for (l = 0; l < V3DREF_LANES; l++) tmp[l] = 0; p = tmp; }    /* r5: varying constant term, folded into VARY */
    else {
        int ra = (mux == 6);
        int addr = ra ? raddr_a : raddr_b;
        if (!ra && smallimm) {
            uint32_t v;
            if (addr < 16) v = (uint32_t)addr;
            else if (addr < 32) v = (uint32_t)(addr - 32);
            else if (addr < 40) v = v3dref_f2u((float)(1 << (addr - 32)));
            else if (addr < 48) v = v3dref_f2u(1.0f / (float)(1 << (48 - addr)));
            else v = 0;
            for (l = 0; l < V3DREF_LANES; l++) tmp[l] = v;
            return p;
        }
        if (addr < 32) p = ra ? q->ra[addr] : q->rb[addr];
        else switch (addr) {
        case 32: for (l = 0; l < V3DREF_LANES; l++) tmp[l] = unif_val; break;
        case 35: { int vi = q->nvary_read - 1; if (vi < 0) vi = 0; if (vi > 7) vi = 7;
                   for (l = 0; l < V3DREF_LANES; l++) tmp[l] = v3dref_f2u(q->vary[vi][l]);
                   break; }
        case 41: for (l = 0; l < V3DREF_LANES; l++) tmp[l] = ra ? q->px[l] : q->py[l]; break;
        case 38: for (l = 0; l < V3DREF_LANES; l++) tmp[l] = ra ? (uint32_t)(l & 15) : 0; break;
        default: for (l = 0; l < V3DREF_LANES; l++) tmp[l] = 0;
        }
        if (ra && unpack && !pm) { uint32_t t2[V3DREF_LANES]; for (l = 0; l < V3DREF_LANES; l++) t2[l] = p[l]; v3dref_unpack(tmp, t2, unpack, isfloat); p = tmp; }
    }
    return p;
}

static void v3dref_dst_write(struct v3dref_qpu *q, int waddr, int to_file_b, const uint32_t *val, int pack, int mulpack_pm, int mulpipe,
                          uint8_t mask_hint) {
    (void)mask_hint;
    uint32_t tmp[V3DREF_LANES];
    int l;
    uint32_t *dst = 0;
    uint32_t bytemask = 0xffffffffu;
    int fl = 0;
    if (waddr < 32) dst = to_file_b ? q->rb[waddr] : q->ra[waddr];
    else if (waddr >= 32 && waddr <= 35) dst = q->acc[waddr - 32];
    else if (waddr == 37) dst = q->acc[5];
    /* byte / half packing: float result -> unorm8 into one byte lane, other bytes untouched */
    int pm = mulpack_pm;
    for (l = 0; l < V3DREF_LANES; l++) tmp[l] = val[l];
    if (pack) {
        if (!pm && (waddr >= 32 || to_file_b)) pack = 0;      /* regfile-A pack only affects regfile A writes */
        if (pm && mulpipe) {                                   /* MUL pack: float -> unorm8 */
            if (pack == 3) for (l = 0; l < V3DREF_LANES; l++) { uint32_t b = v3dref_pack_byte_f(v3dref_u2f(val[l])); tmp[l] = b * 0x01010101u; }
            else if (pack >= 4 && pack <= 7) { fl = pack - 4; for (l = 0; l < V3DREF_LANES; l++) tmp[l] = v3dref_pack_byte_f(v3dref_u2f(val[l])) << (8 * fl); bytemask = 0xffu << (8 * fl); }
        } else if (!pm) {                                      /* regfile-A pack (applies to either pipe) */
            if (pack == 1) { for (l = 0; l < V3DREF_LANES; l++) tmp[l] = val[l] & 0xffff; bytemask = 0xffffu; }
            else if (pack == 2) { for (l = 0; l < V3DREF_LANES; l++) tmp[l] = (val[l] & 0xffff) << 16; bytemask = 0xffff0000u; }
            else if (pack == 3) for (l = 0; l < V3DREF_LANES; l++) tmp[l] = (val[l] & 255) * 0x01010101u;
            else if (pack >= 4 && pack <= 7) { fl = pack - 4; for (l = 0; l < V3DREF_LANES; l++) tmp[l] = (val[l] & 255) << (8 * fl); bytemask = 0xffu << (8 * fl); }
        }
    }
    if (dst) { for (l = 0; l < V3DREF_LANES; l++) dst[l] = (dst[l] & ~bytemask) | (tmp[l] & bytemask); return; }
    switch (waddr) {
    case 45: case 46:                                          /* TLB colour: only active lanes land in the tile buffer */
        for (l = 0; l < V3DREF_LANES; l++) if (q->active[l]) q->colout[l] = tmp[l];
        break;
    case 52: for (l = 0; l < V3DREF_LANES; l++) q->r4[l] = v3dref_f2u(1.0f / v3dref_u2f(val[l])); break;   /* SFU recip */
    default: break;
    }
}

/* Runs one program over the lanes of q. Returns 0 on success. */
static int v3dref_run_qpu(struct v3dref_qpu *q, uint32_t first_unif_word) {
    const struct v3dref_prim *pr = q->prim;
    const struct v3dref_job *job = q->job;
    int pc = 0, end_countdown = -1;
    q->nuni = first_unif_word; q->nvary_read = 0; q->have_t = 0; q->nfifo = 0;
    for (int n = 0; n < V3DREF_MAX_QPU_INSNS; n++, pc++) {
        if ((uint32_t)(pc * 8 + 8) > pr->code_avail) return -1;
        uint32_t lo = v3dref_ld32(pr->code + pc * 8), hi = v3dref_ld32(pr->code + pc * 8 + 4);
        int sig = hi >> 28;
        if (sig == 15 || sig == 14) { if (sig == 15) return -2; }   /* branches are not used by compositor programs */
        int sf_unused = (hi >> 13) & 1; (void)sf_unused;
        uint32_t tmpA[V3DREF_LANES], tmpB[V3DREF_LANES], tmpC[V3DREF_LANES], tmpD[V3DREF_LANES];
        uint32_t addres[V3DREF_LANES], mulres[V3DREF_LANES];
        int l;
        if (sig == 14) {                                         /* load immediate */
            int ws = (hi >> 12) & 1, wa = (hi >> 6) & 63, wm = hi & 63;
            uint32_t imm[V3DREF_LANES]; for (l = 0; l < V3DREF_LANES; l++) imm[l] = lo;
            if (((hi >> 17) & 7) != 0) v3dref_dst_write(q, wa, ws, imm, 0, 0, 0, 0);
            if (((hi >> 14) & 7) != 0) v3dref_dst_write(q, wm, !ws, imm, 0, 0, 1, 0);
        } else {
            int unpack = (hi >> 25) & 7, pm = (hi >> 24) & 1, pack = (hi >> 20) & 15;
            int cadd = (hi >> 17) & 7, cmul = (hi >> 14) & 7, ws = (hi >> 12) & 1;
            int wadd = (hi >> 6) & 63, wmul = hi & 63;
            int opm = (lo >> 29) & 7, opa = (lo >> 24) & 31;
            int raddr_a = (lo >> 18) & 63, raddr_b = (lo >> 12) & 63;
            int am = (lo >> 9) & 7, bm = (lo >> 6) & 7, mam = (lo >> 3) & 7, mbm = lo & 7;
            int smallimm = (sig == 13);
            /* reading address 32 / 35 is a side effect of the instruction, whether or not a mux selects it */
            uint32_t unif_val = 0;
            if (raddr_a == 32 || (!smallimm && raddr_b == 32)) unif_val = v3dref_uni(q);
            if (raddr_a == 35 || (!smallimm && raddr_b == 35)) q->nvary_read++;
            int isfa = v3dref_op_is_float_add(opa), isfm = (opm == 1);
            const uint32_t *a = v3dref_src(q, am, raddr_a, raddr_b, smallimm, unif_val, tmpA, pm, unpack, isfa, 0);
            const uint32_t *b = v3dref_src(q, bm, raddr_a, raddr_b, smallimm, unif_val, tmpB, pm, unpack, isfa, 0);
            const uint32_t *ma = v3dref_src(q, mam, raddr_a, raddr_b, smallimm, unif_val, tmpC, pm, unpack, isfm, 0);
            const uint32_t *mb = v3dref_src(q, mbm, raddr_a, raddr_b, smallimm, unif_val, tmpD, pm, unpack, isfm, 0);
            /* ---- ADD pipe */
            for (l = 0; l < V3DREF_LANES; l++) {
                uint32_t x = a[l], y = b[l], r = 0; float fx = v3dref_u2f(x), fy = v3dref_u2f(y);
                switch (opa) {
                case 0: r = 0; break;
                case 1: r = v3dref_f2u(fx + fy); break;
                case 2: r = v3dref_f2u(fx - fy); break;
                case 3: r = v3dref_f2u(fx < fy ? fx : fy); break;
                case 4: r = v3dref_f2u(fx > fy ? fx : fy); break;
                case 5: { float ax = fx < 0 ? -fx : fx, ay = fy < 0 ? -fy : fy; r = v3dref_f2u(ax < ay ? ax : ay); break; }
                case 6: { float ax = fx < 0 ? -fx : fx, ay = fy < 0 ? -fy : fy; r = v3dref_f2u(ax > ay ? ax : ay); break; }
                case 7: r = (uint32_t)(int32_t)fx; break;
                case 8: r = v3dref_f2u((float)(int32_t)x); break;
                case 12: r = x + y; break;
                case 13: r = x - y; break;
                case 14: r = x >> (y & 31); break;
                case 15: r = (uint32_t)((int32_t)x >> (y & 31)); break;
                case 16: r = (y & 31) ? ((x >> (y & 31)) | (x << (32 - (y & 31)))) : x; break;
                case 17: r = x << (y & 31); break;
                case 18: r = (int32_t)x < (int32_t)y ? x : y; break;
                case 19: r = (int32_t)x > (int32_t)y ? x : y; break;
                case 20: r = x & y; break;
                case 21: r = x | y; break;
                case 22: r = x ^ y; break;
                case 23: r = ~x; break;
                case 24: { uint32_t t = x; r = 0; if (!t) r = 32; else while (!(t & 0x80000000u)) { t <<= 1; r++; } break; }
                case 30: r = v3dref_v8bin(0, x, y); break;
                case 31: r = v3dref_v8bin(1, x, y); break;
                default: r = 0;
                }
                addres[l] = r;
            }
            /* ---- MUL pipe */
            for (l = 0; l < V3DREF_LANES; l++) {
                uint32_t x = ma[l], y = mb[l], r = 0;
                switch (opm) {
                case 0: r = 0; break;
                case 1: r = v3dref_f2u(v3dref_u2f(x) * v3dref_u2f(y)); break;
                case 2: r = (x & 0xffffff) * (y & 0xffffff); break;
                case 3: r = v3dref_v8mul(x, y); break;
                case 4: r = v3dref_v8bin(2, x, y); break;
                case 5: r = v3dref_v8bin(3, x, y); break;
                case 6: r = v3dref_v8bin(0, x, y); break;
                case 7: r = v3dref_v8bin(1, x, y); break;
                }
                mulres[l] = r;
            }
            /* ---- writes (after both pipes have read their operands). Each pipe writes the A file iff
             * (add: ws==0, mul: ws==1 means mul->A). */
            int add_nop = (opa == 0 && wadd == 39), mul_nop = (opm == 0 && wmul == 39);
            int do_add = !add_nop && cadd != 0, do_mul = !mul_nop && cmul != 0;
            /* TMU: a write of S triggers the lookup; T just latches the coordinate */
            struct { int w; const uint32_t *v; int pipe; } wr[2] = { { wadd, addres, 0 }, { wmul, mulres, 1 } };
            for (int k = 0; k < 2; k++) {
                if (!(k == 0 ? do_add : do_mul)) continue;
                int w = wr[k].w;
                if (w >= 56 && w <= 59) {
                    if (w == 57) { for (l = 0; l < V3DREF_LANES; l++) q->tmu_t[l] = wr[k].v[l]; q->have_t = 1; }
                    else if (w == 56) {
                        uint32_t res[V3DREF_LANES];
                        if (q->have_t) { uint32_t p0 = v3dref_uni(q), p1 = v3dref_uni(q); v3dref_tmu_2d(q, wr[k].v, p0, p1, res); }
                        else v3dref_tmu_raw(q, wr[k].v, res);
                        q->have_t = 0;
                        if (q->nfifo < 4) { for (l = 0; l < V3DREF_LANES; l++) q->fifo[q->nfifo][l] = res[l]; q->nfifo++; }
                    }
                    continue;
                }
                int to_b = (k == 0) ? (ws == 1) : (ws == 0);
                v3dref_dst_write(q, w, to_b, wr[k].v, pack, pm, k, 0);
            }
        }
        /* signals complete after the instruction: results appear in r4 for the next one */
        if (sig == 10 && q->nfifo > 0) {
            for (int l = 0; l < V3DREF_LANES; l++) q->r4[l] = q->fifo[0][l];
            for (int i = 1; i < q->nfifo; i++) for (int l = 0; l < V3DREF_LANES; l++) q->fifo[i - 1][l] = q->fifo[i][l];
            q->nfifo--;
        }
        if (sig == 8 || sig == 9) {
            for (int l = 0; l < V3DREF_LANES; l++) {
                int lx = q->px[l] - q->tile_x0, ly = q->py[l] - q->tile_y0;
                q->r4[l] = (lx >= 0 && lx < V3DREF_TILE && ly >= 0 && ly < V3DREF_TILE) ? job->tile[ly * V3DREF_TILE + lx] : 0;
            }
        }
        if ((sig == 3 || sig == 9) && end_countdown < 0) end_countdown = 2;
        if (end_countdown >= 0) { if (end_countdown-- == 0) return 0; }
    }
    return -3;
}

/* ------------------------------------------------------------------------------------------ rasteriser */

struct v3dref_vert { int32_t x, y; float invw; float vary[8]; };   /* x,y in 1/16 pixel */

/* int64 -> float without the libgcc helper (the shim links without it) */
static inline float v3dref_i64f(int64_t v) {
    int neg = v < 0; uint64_t u = neg ? (uint64_t)0 - (uint64_t)v : (uint64_t)v;
    float f = (float)(uint32_t)(u >> 32) * 4294967296.0f + (float)(uint32_t)u;
    return neg ? -f : f;
}

static int64_t v3dref_edge(const struct v3dref_vert *a, const struct v3dref_vert *b, int32_t px, int32_t py) {
    return (int64_t)(b->x - a->x) * (int64_t)(py - a->y) - (int64_t)(b->y - a->y) * (int64_t)(px - a->x);
}
static int v3dref_topleft(const struct v3dref_vert *a, const struct v3dref_vert *b) {
    int32_t dx = b->x - a->x, dy = b->y - a->y;
    return dy < 0 || (dy == 0 && dx > 0);
}

static void v3dref_get_vert(const struct v3dref_prim *pr, uint32_t idx, struct v3dref_vert *v) {
    uint32_t off = idx * pr->stride;
    for (int i = 0; i < 8; i++) v->vary[i] = 0.0f;
    v->x = v->y = 0; v->invw = 1.0f;
    if (!pr->vtx || off + 12 > pr->vtx_avail) return;
    const uint8_t *p = pr->vtx + off;
    uint32_t xy = v3dref_ld32(p);
    v->x = (int16_t)(xy & 0xffff) + pr->d->vp_x;
    v->y = (int16_t)(xy >> 16) + pr->d->vp_y;
    v->invw = v3dref_u2f(v3dref_ld32(p + 8));
    for (uint32_t i = 0; i < pr->nvary && i < 8; i++)
        if (off + 12 + 4 * i + 4 <= pr->vtx_avail) v->vary[i] = v3dref_u2f(v3dref_ld32(p + 12 + 4 * i));
}

static void v3dref_raster_tri(struct v3dref_job *job, const struct v3dref_prim *pr, struct v3dref_vert v0, struct v3dref_vert v1, struct v3dref_vert v2) {
    const struct v3dref_draw *d = pr->d;
    int64_t area = v3dref_edge(&v0, &v1, v2.x, v2.y);
    if (area == 0) return;
    if (area < 0) { struct v3dref_vert t = v1; v1 = v2; v2 = t; area = -area; }
    int minx = v0.x, maxx = v0.x, miny = v0.y, maxy = v0.y;
    const struct v3dref_vert *vs[3] = { &v0, &v1, &v2 };
    for (int i = 1; i < 3; i++) {
        if (vs[i]->x < minx) minx = vs[i]->x;
        if (vs[i]->x > maxx) maxx = vs[i]->x;
        if (vs[i]->y < miny) miny = vs[i]->y;
        if (vs[i]->y > maxy) maxy = vs[i]->y;
    }
    int x0 = (minx >> 4), x1 = (maxx + 15) >> 4, y0 = (miny >> 4), y1 = (maxy + 15) >> 4;
    int tx0 = job->tx * V3DREF_TILE, ty0 = job->ty * V3DREF_TILE;
    int cx0 = tx0, cy0 = ty0, cx1 = tx0 + V3DREF_TILE, cy1 = ty0 + V3DREF_TILE;
    if (d->clip_w > 0) {
        if (d->clip_x > cx0) cx0 = d->clip_x;
        if (d->clip_y > cy0) cy0 = d->clip_y;
        if (d->clip_x + d->clip_w < cx1) cx1 = d->clip_x + d->clip_w;
        if (d->clip_y + d->clip_h < cy1) cy1 = d->clip_y + d->clip_h;
    }
    if (job->tgt.w < cx1) cx1 = job->tgt.w;
    if (job->tgt.h < cy1) cy1 = job->tgt.h;
    if (x0 < cx0) x0 = cx0;
    if (y0 < cy0) y0 = cy0;
    if (x1 > cx1) x1 = cx1;
    if (y1 > cy1) y1 = cy1;
    if (x0 >= x1 || y0 >= y1) return;

    int tl01 = v3dref_topleft(&v0, &v1), tl12 = v3dref_topleft(&v1, &v2), tl20 = v3dref_topleft(&v2, &v0);
    float inv_area = 1.0f / v3dref_i64f(area);
    float w0 = v0.invw, w1 = v1.invw, w2 = v2.invw;
    int affine = (w0 == 1.0f && w1 == 1.0f && w2 == 1.0f);

    static struct v3dref_qpu q;      /* large; the interpreter is not re-entrant, which is fine (single threaded per job) */
    q.job = job; q.prim = pr; q.tile_x0 = tx0; q.tile_y0 = ty0;
    for (int y = y0; y < y1; y++) {
        int32_t py = y * 16 + 8;
        int x = x0;
        while (x < x1) {
            int n = 0;
            for (; n < V3DREF_LANES && x + n < x1; n++) {
                int32_t px = (x + n) * 16 + 8;
                int64_t e12 = v3dref_edge(&v1, &v2, px, py), e20 = v3dref_edge(&v2, &v0, px, py), e01 = v3dref_edge(&v0, &v1, px, py);
                int in = (e12 > 0 || (e12 == 0 && tl12)) && (e20 > 0 || (e20 == 0 && tl20)) && (e01 > 0 || (e01 == 0 && tl01));
                q.active[n] = in;
                float l0 = v3dref_i64f(e12) * inv_area, l1 = v3dref_i64f(e20) * inv_area, l2 = v3dref_i64f(e01) * inv_area;
                float iw, W;
                if (affine) { iw = 1.0f; W = 1.0f; }                 /* 1/w == 1 everywhere: no perspective division */
                else { iw = l0 * w0 + l1 * w1 + l2 * w2; W = (iw != 0.0f) ? 1.0f / iw : 1.0f; }
                q.px[n] = (uint32_t)(x + n); q.py[n] = (uint32_t)y;
                q.ra[15][n] = v3dref_f2u(W); q.rb[15][n] = v3dref_f2u(1.0f);
                for (uint32_t k = 0; k < pr->nvary && k < 8; k++) {
                    /* perspective-correct value, pre-divided by W because the program multiplies by ra15 */
                    float num = affine ? (l0 * v0.vary[k] + l1 * v1.vary[k] + l2 * v2.vary[k])
                                       : (l0 * v0.vary[k] * w0 + l1 * v1.vary[k] * w1 + l2 * v2.vary[k] * w2);
                    q.vary[k][n] = num;                             /* num / (W * iw) with W * iw == 1 */
                }
            }
            for (int l = n; l < V3DREF_LANES; l++) { q.active[l] = 0; q.px[l] = q.px[n ? n - 1 : 0]; q.py[l] = (uint32_t)y; }
            int any = 0; for (int l = 0; l < n; l++) any |= (int)q.active[l];
            if (any) {
                for (int l = 0; l < V3DREF_LANES; l++) q.colout[l] = 0;
                uint32_t wrote_tlb_marker = 0;
                for (int l = 0; l < V3DREF_LANES; l++) q.colout[l] = job->tile[((int)q.py[l] - ty0) * V3DREF_TILE + ((int)q.px[l] - tx0)];
                (void)wrote_tlb_marker;
                if (v3dref_run_qpu(&q, 0) == 0) {
                    for (int l = 0; l < n; l++) if (q.active[l])
                        job->tile[(y - ty0) * V3DREF_TILE + (x + l - tx0)] = q.colout[l];
                } else job->mem->stats_unsupported++;
                for (int r = 0; r < 6; r++) for (int l = 0; l < V3DREF_LANES; l++) q.acc[r][l] = 0;
            }
            x += n;
        }
    }
}

static void v3dref_run_draw_on_tile(struct v3dref_job *job, const struct v3dref_draw *d) {
    struct v3dref_mem *m = job->mem;
    struct v3dref_prim pr; uint32_t av;
    const uint8_t *rec = m->map(m->ctx, d->rec, &av);
    if (!rec || av < 16) { m->stats_unsupported++; return; }
    uint32_t w0 = v3dref_ld32(rec);
    pr.d = d; pr.stride = (w0 >> 8) & 255; pr.nvary = (w0 >> 24) & 255;
    pr.code = m->map(m->ctx, v3dref_ld32(rec + 4) & ~7u, &pr.code_avail);
    pr.unif = m->map(m->ctx, v3dref_ld32(rec + 8), &pr.unif_avail);
    pr.vtx = m->map(m->ctx, v3dref_ld32(rec + 12), &pr.vtx_avail);
    if (!pr.code || !pr.stride) { m->stats_unsupported++; return; }
    if (pr.unif_avail > 4096 * 4) pr.unif_avail = 4096 * 4;
    struct v3dref_vert v[3];
    uint32_t n = d->len;
    /* quick reject on the tile: vertices bounding box is checked per triangle in the rasteriser */
    switch (d->mode) {
    case 4:                                                    /* triangles */
        for (uint32_t i = 0; i + 2 < n; i += 3) {
            v3dref_get_vert(&pr, d->start + i, &v[0]); v3dref_get_vert(&pr, d->start + i + 1, &v[1]); v3dref_get_vert(&pr, d->start + i + 2, &v[2]);
            v3dref_raster_tri(job, &pr, v[0], v[1], v[2]);
        }
        break;
    case 5:                                                    /* strip */
        for (uint32_t i = 0; i + 2 < n; i++) {
            v3dref_get_vert(&pr, d->start + i, &v[0]); v3dref_get_vert(&pr, d->start + i + 1, &v[1]); v3dref_get_vert(&pr, d->start + i + 2, &v[2]);
            if (i & 1) v3dref_raster_tri(job, &pr, v[1], v[0], v[2]); else v3dref_raster_tri(job, &pr, v[0], v[1], v[2]);
        }
        break;
    case 6:                                                    /* fan */
        v3dref_get_vert(&pr, d->start, &v[0]);
        for (uint32_t i = 1; i + 1 < n; i++) {
            v3dref_get_vert(&pr, d->start + i, &v[1]); v3dref_get_vert(&pr, d->start + i + 1, &v[2]);
            v3dref_raster_tri(job, &pr, v[0], v[1], v[2]);
        }
        break;
    default: m->stats_unsupported++; break;                    /* points / lines are not used for composition */
    }
    m->stats_draws++;
}

/* ------------------------------------------------------------------------------------------ job execution */

static const uint8_t v3dref_cl_size[128] = {
    [0] = 1, [1] = 1, [4] = 1, [5] = 1, [6] = 1, [7] = 1, [8] = 1, [16] = 5, [17] = 5, [18] = 1, [24] = 1, [25] = 1,
    [26] = 5, [27] = 5, [28] = 7, [29] = 7, [32] = 14, [33] = 10, [48] = 1, [49] = 1, [56] = 2, [64] = 5, [65] = 5, [66] = 5,
    [96] = 4, [97] = 5, [98] = 5, [99] = 5, [100] = 3, [101] = 5, [102] = 9, [103] = 5, [104] = 9, [105] = 9, [106] = 9,
    [112] = 16, [113] = 11, [114] = 14, [115] = 3,
};

static void v3dref_bin_walk(struct v3dref_job *job, uint32_t start, uint32_t end) {
    struct v3dref_mem *m = job->mem;
    uint32_t av = 0; const uint8_t *p = m->map(m->ctx, start, &av);
    if (!p) return;
    if (end - start > av) end = start + av;
    int32_t cx = 0, cy = 0, cw = 0, ch = 0, vx = 0, vy = 0; uint32_t rec = 0;
    for (uint32_t o = 0; o < end - start;) {
        uint32_t op = p[o], sz = op < 128 ? v3dref_cl_size[op] : 0;
        if (!sz || o + sz > end - start) { if (op) m->stats_unsupported++; break; }
        const uint8_t *b = p + o;
        switch (op) {
        case 102: cx = v3dref_ld16(b + 1); cy = v3dref_ld16(b + 3); cw = v3dref_ld16(b + 5); ch = v3dref_ld16(b + 7); break;
        case 103: vx = (int16_t)v3dref_ld16(b + 1); vy = (int16_t)v3dref_ld16(b + 3); break;
        case 65: rec = v3dref_ld32(b + 1) & ~0xfu; break;
        case 33:
            if (job->ndraws < V3DREF_MAX_DRAWS) {
                struct v3dref_draw *d = &job->draws[job->ndraws++];
                d->rec = rec; d->mode = b[1] & 15; d->len = v3dref_ld32(b + 2); d->start = v3dref_ld32(b + 6);
                d->clip_x = cx; d->clip_y = cy; d->clip_w = cw; d->clip_h = ch; d->vp_x = vx; d->vp_y = vy;
            } else m->stats_unsupported++;
            break;
        case 32: m->stats_unsupported++; break;                /* indexed draws are not produced by the compositor */
        case 0: o = end - start; continue;
        default: break;
        }
        o += sz;
    }
}

static void v3dref_tile_load_store(struct v3dref_job *job, uint32_t addr, int fmt, int store) {
    struct v3dref_mem *m = job->mem;
    int bpp = (fmt == 1 || fmt == 2) ? 2 : 4;
    uint32_t av = 0; uint8_t *base = m->map(m->ctx, addr, &av);
    if (!base) { m->stats_unsupported++; return; }
    int tw = job->tgt.w, th = job->tgt.h;
    int x0 = job->tx * V3DREF_TILE, y0 = job->ty * V3DREF_TILE;
    for (int y = 0; y < V3DREF_TILE && y0 + y < th; y++) {
        for (int x = 0; x < V3DREF_TILE && x0 + x < tw; x++) {
            uint32_t off = ((uint32_t)(y0 + y) * (uint32_t)tw + (uint32_t)(x0 + x)) * bpp;
            if (off + bpp > av) continue;
            uint32_t *t = &job->tile[y * V3DREF_TILE + x];
            if (store) { if (bpp == 2) v3dref_st16(base + off, v3dref_rgba_to_rgb565(*t)); else v3dref_st32(base + off, *t); }
            else *t = (bpp == 2) ? v3dref_rgb565_to_rgba(v3dref_ld16(base + off)) : v3dref_ld32(base + off);
        }
    }
}

static void v3dref_tile_clear(struct v3dref_job *job) {
    for (int i = 0; i < V3DREF_TILE * V3DREF_TILE; i++) job->tile[i] = job->clear_color;
}

static void v3dref_render_walk(struct v3dref_job *job, uint32_t start, uint32_t end) {
    struct v3dref_mem *m = job->mem;
    uint32_t av = 0; const uint8_t *p = m->map(m->ctx, start, &av);
    if (!p) return;
    if (end - start > av) end = start + av;
    for (uint32_t o = 0; o < end - start;) {
        uint32_t op = p[o], sz = op < 128 ? v3dref_cl_size[op] : 0;
        if (!sz || o + sz > end - start) { if (op) m->stats_unsupported++; break; }
        const uint8_t *b = p + o;
        switch (op) {
        case 113: {
            job->tgt.addr = v3dref_ld32(b + 1) & ~0xfu; job->tgt.w = v3dref_ld16(b + 5); job->tgt.h = v3dref_ld16(b + 7);
            job->tgt.fmt = (b[9] >> 2) & 3; job->tgt.bpp = (job->tgt.fmt == 1) ? 4 : 2;
            break; }
        case 114: job->clear_color = v3dref_ld32(b + 1); v3dref_tile_clear(job); break;
        case 115:                                   /* selects the tile, and runs the loads queued before it */
            job->tx = b[1]; job->ty = b[2]; m->stats_tiles++;
            if (job->pend_load) { v3dref_tile_load_store(job, job->pend_addr, job->pend_fmt, 0); job->pend_load = 0; }
            break;
        case 29:
            if ((b[1] & 7) == 1) { job->pend_load = 1; job->pend_addr = v3dref_ld32(b + 3) & ~0xfu; job->pend_fmt = b[2] & 3; }
            break;
        case 28:
            if ((b[1] & 7) == 1) v3dref_tile_load_store(job, v3dref_ld32(b + 3) & ~0xfu, b[2] & 3, 1);
            else if ((b[1] & 7) == 0) v3dref_tile_clear(job);   /* 'store nothing': how the first tile clears */
            break;
        case 17: {
            int n = job->ndraws;
            if (m->debug_max_draws && (int)m->debug_max_draws < n) n = (int)m->debug_max_draws;
            for (int i = 0; i < n; i++) v3dref_run_draw_on_tile(job, &job->draws[i]);
            break; }
        case 24: case 25: {
            uint32_t a = 0; uint8_t *base = m->map(m->ctx, job->tgt.addr, &a);
            if (!base) { m->stats_unsupported++; if (op == 25) return; break; }
            int bpp = job->tgt.bpp, x0 = job->tx * V3DREF_TILE, y0 = job->ty * V3DREF_TILE;
            for (int y = 0; y < V3DREF_TILE && y0 + y < job->tgt.h; y++)
                for (int x = 0; x < V3DREF_TILE && x0 + x < job->tgt.w; x++) {
                    uint32_t off = ((uint32_t)(y0 + y) * (uint32_t)job->tgt.w + (uint32_t)(x0 + x)) * bpp;
                    if (off + bpp > a) continue;
                    uint32_t c = job->tile[y * V3DREF_TILE + x];
                    if (bpp == 2) v3dref_st16(base + off, v3dref_rgba_to_rgb565(c)); else v3dref_st32(base + off, c);
                }
            if (op == 25) return;
            v3dref_tile_clear(job);                              /* the tile buffer is cleared after each store */
            break; }
        case 0: return;
        default: break;
        }
        o += sz;
    }
}

/* One submit as the firmware issues it through ioctl 0x401c5687:
 *   req[0] bit0 = has bin list, bit1 = has render list; req[2..3] = bin list start/end,
 *   req[4..5] = render list start/end (physical addresses). Returns 0, or -1 on an unusable request. */
static int v3dref_exec_job(struct v3dref_mem *mem, const uint32_t req[7]) {
    static struct v3dref_job job;
    job.mem = mem; job.ndraws = 0; job.pend_load = 0; job.clear_color = 0; job.tgt.w = job.tgt.h = 0; job.tgt.fmt = 0; job.tgt.bpp = 2;
    if ((req[0] & 1) && req[3] > req[2]) v3dref_bin_walk(&job, req[2], req[3]);
    if ((req[0] & 2) && req[5] > req[4]) v3dref_render_walk(&job, req[4], req[5]);
    return 0;
}

#endif /* V3DEMU_REF_H */
