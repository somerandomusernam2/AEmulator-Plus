/*
 * v3demu_core.h - software execution of Broadcom V3D (VideoCore IV style) jobs.
 *
 * The firmware of Broadcom BCM2153x phones (GT-S5300/S5302/S5360 ...) composes the screen from SurfaceFlinger
 * through V3D_Compose_Impl: it builds a tile-binning control list, a tile-rendering control list and a few QPU
 * fragment programs in "physical" memory and hands them to /dev/v3d. There is no GPU here, so the same lists are
 * executed in software:
 *
 *   bin list     -> draw calls (shader record + primitive), pre-transformed vertices, no vertex shader
 *   render list  -> per 32x32 tile: clear / load / run the draws of the tile / store
 *   QPU programs -> decoded ONCE per draw into a flat list of vector micro-ops, then run span-at-a-time
 *                   (one micro-op over up to 32 pixels), TMU + TLB included
 *
 * The code is pure computation: no libc, no system calls, all memory goes through struct v3d_mem. That keeps it
 * usable inside the freestanding guest shim and in a host unit test.  Everything the firmware's own programs
 * need is implemented (textured copy, constant-alpha blend, dither, YUV->RGB), not recognised by checksum, so
 * other firmware builds with different shader blobs work as long as they use the same QPU features.
 *
 * Deliberate simplifications (none changes what SurfaceFlinger can observe):
 *   - memory formats "T" and "LT" of render targets / textures are stored raster; only the emulator reads them
 *   - no depth/stencil, no multisampling, no culling; scissor (clip window) and viewport offset are honoured
 *   - the dithered 565 target format is stored un-dithered (the firmware runs an explicit dither program anyway)
 *   - QPU condition flags are not modelled (the firmware never sets them); 'never' is honoured
 *
 * What makes this version fast (the previous one re-decoded every instruction for every 32-pixel span, ran both ALU
 * pipes over all 32 lanes through a per-lane switch, did the edge test of every triangle on its whole bounding box
 * pixel by pixel in int64 + float, and looked every texel up through the mapping callback):
 *   - a program is decoded once per draw: operand sources, unpack/pack, uniform values, TMU parameters, FIFO
 *     slots and the end-of-thread countdown are all resolved statically (a QPU program here is straight-line, no
 *     branches, so every uniform / varying read position is known ahead); unused pipes and dead writes vanish
 *   - micro-op kernels run only over the lanes of the span and write straight into the destination register
 *     whenever that cannot change what the second pipe reads
 *   - triangles are scanned row by row, only over the exact covered interval (exact integer edge functions, so the
 *     coverage is bit-identical to a per-pixel test); fully covered rows need no search at all
 *   - the TMU coordinate maths needs 4..8 float operations per lookup instead of ~20 (bit-identical results), the
 *     texture is mapped once per lookup instead of once per texel, RGB565 goes through a table
 *   - tile loads/stores are row loops; a tile that is loaded and stored back untouched costs nothing
 *   - stores to the framebuffer are collected and written when the job ends, so the screen never shows a frame
 *     that is half drawn
 * The only intentional numeric difference is the interpolation of varyings of non-perspective triangles
 * (V3D_FAST_VARY): they are evaluated exactly at the first pixel of a span and then stepped, instead of being
 * re-derived from the edge values for every pixel.  The error is a few 1e-7 relative; build with
 * -DV3D_FAST_VARY=0 to get the old per-pixel evaluation.
 *
 * Constraints of the guest build (freestanding, no libgcc): no integer division, no 64-bit division / variable
 * shifts / int64->float helpers, no memcpy/memset (the compiler may not even emit them), no struct returns.
 */
#ifndef V3DEMU_CORE_H
#define V3DEMU_CORE_H

#include <stdint.h>

#define V3D_TILE 32
#define V3D_LANES 32            /* pixels per interpreter span: exactly one tile row */
#define V3D_MAX_DRAWS 192
#define V3D_MAX_QPU_INSNS 2048

#ifndef V3D_FAST_VARY
#define V3D_FAST_VARY 1
#endif
#ifndef V3D_LAZY_LOAD
#define V3D_LAZY_LOAD 1        /* a tile that is loaded and stored back untouched is never read */
#endif
#ifndef V3D_STAGE
#define V3D_STAGE 1             /* collect framebuffer stores and write them when the job ends */
#endif

#define V3D_MAX_UOPS 49152
#define V3D_SCR_UOPS 4096       /* the last V3D_SCR_UOPS micro-ops are scratch for programs that do not fit the arena */
#define V3D_MAX_CONSTS 2048
#define V3D_SCR_CONSTS 128
#define V3D_MAX_TEX 1024
#define V3D_SCR_TEX 32
#define V3D_STAGE_BYTES (4u << 20)
#define V3D_STAGE_TILES 4096

struct v3d_mem {
    /* Map a physical range. Returns a host pointer and, in *avail, how many bytes are valid from there on;
     * NULL when the address does not belong to anything. */
    uint8_t *(*map)(void *ctx, uint32_t phys, uint32_t *avail);
    void *ctx;
    uint32_t stats_draws, stats_tiles, stats_unsupported;
    uint32_t debug_max_draws;     /* tests only: run just the first N draws of a job (0 = all) */
    uint32_t stage_limit;         /* physical addresses below this are host visible (the framebuffer): stores to the
                                   * render target are written when the job ends. 0 = write immediately */
    /* two-entry cache of map() results, reset at the start of every job */
    uint8_t *c_ptr[2]; uint32_t c_base[2], c_len[2]; int c_next;
    uint32_t last_fb_tgt;         /* physical address (= fb0 file offset) of the last render target that lay in the framebuffer */
};

/* ------------------------------------------------------------------------------------------ small helpers */

static inline uint32_t v3d_ld32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
static inline uint32_t v3d_ld16(const uint8_t *p) { return p[0] | (p[1] << 8); }
static inline void v3d_st16(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static inline void v3d_st32(uint8_t *p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }
union v3d_fu { float f; uint32_t u; };
static inline float v3d_u2f(uint32_t u) { union v3d_fu x; x.u = u; return x.f; }
static inline uint32_t v3d_f2u(float f) { union v3d_fu x; x.f = f; return x.u; }

/* aligned word access when the address allows it (ARMv7 and x86 would also cope with unaligned, a byte path is the safe fallback) */
static inline uint32_t v3d_ldw(const uint8_t *p) { return ((uintptr_t)p & 3) ? v3d_ld32(p) : *(const uint32_t *)p; }
static inline void v3d_stw(uint8_t *p, uint32_t v) { if ((uintptr_t)p & 3) v3d_st32(p, v); else *(uint32_t *)p = v; }

static inline uint8_t *v3d_map(struct v3d_mem *m, uint32_t phys, uint32_t *av) {
    uint32_t o = phys - m->c_base[0];
    if (o < m->c_len[0]) { *av = m->c_len[0] - o; return m->c_ptr[0] + o; }
    o = phys - m->c_base[1];
    if (o < m->c_len[1]) { *av = m->c_len[1] - o; return m->c_ptr[1] + o; }
    uint8_t *p = m->map(m->ctx, phys, av);
    if (p && *av) { int i = m->c_next; m->c_next = i ^ 1; m->c_ptr[i] = p; m->c_base[i] = phys; m->c_len[i] = *av; }
    return p;
}

static inline uint32_t v3d_rd32(struct v3d_mem *m, uint32_t phys) {
    uint32_t av = 0; const uint8_t *p = v3d_map(m, phys, &av);
    return (p && av >= 4) ? v3d_ld32(p) : 0;
}

/* ------------------------------------------------------------------------------------------ pixel formats */

/* RGBA8888 as seen by the QPU: R in the low byte, A in the high byte */
static inline uint32_t v3d_rgb565_to_rgba(uint32_t c) {
    uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    return ((r << 3) | (r >> 2)) | ((((g << 2) | (g >> 4))) << 8) | ((((b << 3) | (b >> 2))) << 16) | 0xff000000u;
}
static inline uint32_t v3d_rgba_to_rgb565(uint32_t c) {
    return (((c & 0xff) >> 3) << 11) | ((((c >> 8) & 0xff) >> 2) << 5) | (((c >> 16) & 0xff) >> 3);
}
static uint32_t v3d_lut565[65536];
static int v3d_lut_ready;
static void v3d_lut_init(void) {
    if (v3d_lut_ready) return;
    for (uint32_t i = 0; i < 65536; i++) v3d_lut565[i] = v3d_rgb565_to_rgba(i);
    v3d_lut_ready = 1;
}

/* texel by TMU type; returns RGBA8888 (R low). 'p' points at the texel. */
static inline uint32_t v3d_texel(int type, const uint8_t *p) {
    uint32_t c, r, g, b, a;
    switch (type) {
    case 0: return v3d_ldw(p);                                    /* RGBA8888 */
    case 1: return v3d_ldw(p) | 0xff000000u;                      /* RGBX8888 */
    case 2: c = v3d_ld16(p);                                      /* RGBA4444, R on top */
        r = (c >> 12) & 15; g = (c >> 8) & 15; b = (c >> 4) & 15; a = c & 15;
        return (r * 17) | ((g * 17) << 8) | ((b * 17) << 16) | ((a * 17) << 24);
    case 3: c = v3d_ld16(p);                                      /* RGBA5551 */
        r = (c >> 11) & 31; g = (c >> 6) & 31; b = (c >> 1) & 31; a = (c & 1) ? 255 : 0;
        return ((r << 3) | (r >> 2)) | (((g << 3) | (g >> 2)) << 8) | (((b << 3) | (b >> 2)) << 16) | (a << 24);
    case 4: return v3d_lut565[v3d_ld16(p)];                       /* RGB565 */
    case 5: c = p[0]; return c | (c << 8) | (c << 16) | 0xff000000u;                    /* LUMINANCE */
    case 6: return (uint32_t)p[0] << 24;                                                /* ALPHA */
    case 7: c = v3d_ld16(p); return (c & 255) | ((c & 255) << 8) | ((c & 255) << 16) | ((c >> 8) << 24); /* LUMALPHA */
    default: return 0;
    }
}
static inline int v3d_texel_bytes(int type) {
    switch (type) { case 0: case 1: return 4; case 2: case 3: case 4: case 7: return 2; default: return 1; }
}

/* ------------------------------------------------------------------------------------------ decoded programs */

struct v3d_tex {
    const uint8_t *p; uint32_t av;          /* mapped base and bytes valid from it (p == 0: every lookup returns 0) */
    int type, w, h, ws, wt, lin, bpp;
};

/* micro-op kinds */
#define UK_ADD 1        /* a = add-pipe opcode, d = dest row, s0 = a, s1 = b */
#define UK_MUL 2        /* a = mul-pipe opcode */
#define UK_UNPACK 3     /* d = temp row, s0 = source, a = mode, b = widen as float */
#define UK_WRITE 4      /* b = WK_*, a = pack mode, c = byte lane, d = register row (NULL for TLB/SFU), s0 = value */
#define UK_TLAT 5       /* tmu T coordinate := s0 */
#define UK_TMU2D 6      /* d = fifo row, s0 = S coordinate, x = texture index */
#define UK_TMURAW 7     /* d = fifo row, s0 = address */
#define UK_POP 8        /* r4 := s0 (a fifo row) */
#define UK_TREAD 9      /* r4 := the tile buffer colour of the span */

#define WK_ROW 0
#define WK_TLB 1
#define WK_SFU 2

#define PK_NONE 0
#define PK_A16LO 1
#define PK_A16HI 2
#define PK_A8REP 3
#define PK_A8LANE 4
#define PK_M8REP 5
#define PK_M8LANE 6

#define PF_PX 1
#define PF_PY 2
#define PF_RA15 4
#define PF_RB15 8
#define PF_TREAD 16     /* the program reads the tile buffer: TLB writes go to a separate colour buffer */
#define PF_LANE 32      /* the program reads the element number (ra38) */

struct v3d_uop {
    uint8_t k, a, b, c;
    uint32_t *d; const uint32_t *s0, *s1;
    uint32_t x;
};

struct v3d_prog {
    uint8_t state;        /* 0 = not decoded yet, 1 = ready, 2 = cannot run (a branch, runs off the code, no end ...), 3 = does not fit the arena: decode on every use */
    uint8_t nvary;        /* varying rows the program reads */
    uint8_t flags;        /* PF_* */
    uint16_t nuop;
    const struct v3d_uop *uops;
};

struct v3d_draw {
    uint32_t rec;                 /* NV shader record */
    uint32_t mode, len, start;
    int32_t clip_x, clip_y, clip_w, clip_h;   /* scissor, pixels */
    int32_t vp_x, vp_y;                       /* viewport offset, 1/16 pixel */
    struct v3d_prog prog[2];                  /* [0] perspective, [1] affine (1/w == 1 at every vertex) */
    int bb_state;                             /* 0 = not computed, 1 = bounding box valid, 2 = unknown */
    int bx0, by0, bx1, by1;                   /* bounding box, pixels (before the clip window) */
};

struct v3d_target {
    uint32_t addr; int w, h, fmt;  /* fmt: 0/2 = 565, 1 = 8888 */
    int bpp;
};

struct v3d_job {
    struct v3d_mem *mem;
    struct v3d_draw draws[V3D_MAX_DRAWS];
    int ndraws;
    struct v3d_target tgt;
    uint32_t clear_color;
    uint32_t tile[V3D_TILE * V3D_TILE];
    int tx, ty;
    int pend_load; uint32_t pend_addr; int pend_fmt;   /* a LOAD is performed by the next TILE_COORDINATES */
    /* lazy load: the tile buffer is not read yet, it equals the target's pixels of tile (lz_tx, lz_ty) */
    int lz, lz_tx, lz_ty, lz_bpp; uint32_t lz_addr;
    /* framebuffer store collection */
    int stg_on, stg_off, stg_bpp, stg_tw, stg_th, stg_tiles_x; uint32_t stg_addr;
    uint8_t stg_dirty[V3D_STAGE_TILES];
    /* decode arena */
    int nu, nc, ntex;
    struct v3d_uop uops[V3D_MAX_UOPS];
    uint32_t consts[V3D_MAX_CONSTS][V3D_LANES];
    struct v3d_tex tex[V3D_MAX_TEX];
    struct v3d_prog scratch;
};
static uint8_t v3d_stage_img[V3D_STAGE_BYTES];
static void v3d_stage_disable(struct v3d_job *job);

/* per-draw data cached for the tile loops */
struct v3d_prim {
    const struct v3d_draw *d;
    const uint8_t *vtx; uint32_t vtx_avail;
    uint32_t stride, nvary;
    const uint8_t *code; uint32_t code_avail;
    const uint8_t *unif; uint32_t unif_avail;
};

/* ------------------------------------------------------------------------------------------ QPU state */

struct v3d_qpu {
    uint32_t ra[32][V3D_LANES], rb[32][V3D_LANES], acc[6][V3D_LANES], r4[V3D_LANES];
    uint32_t px[V3D_LANES], py[V3D_LANES];       /* absolute pixel coordinate */
    uint32_t vary[8][V3D_LANES];                 /* float bits; perspective-correct, already divided by W */
    uint32_t colout[V3D_LANES];
    uint32_t tmu_t[V3D_LANES];
    uint32_t fifo[4][V3D_LANES];
    uint32_t tmp[8][V3D_LANES];
    uint32_t zero[V3D_LANES], lane15[V3D_LANES];
    uint32_t *tlb;                               /* where TLB writes land: the tile row, or colout */
    uint32_t *tilerow;                           /* the span's row of the tile buffer */
};
static struct v3d_qpu v3d_qs;       /* large; not re-entrant, which is fine (single threaded per job) */

static inline uint32_t v3d_sat8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : (uint32_t)v); }

static inline uint32_t v3d_v8mul(uint32_t a, uint32_t b) {
    uint32_t r = 0;
    for (int i = 0; i < 4; i++) {
        uint32_t x = ((a >> (8 * i)) & 255) * ((b >> (8 * i)) & 255) + 128;
        r |= (((x + (x >> 8)) >> 8) & 255) << (8 * i);
    }
    return r;
}
static inline uint32_t v3d_v8bin(int op, uint32_t a, uint32_t b) {   /* 0 adds 1 subs 2 min 3 max */
    uint32_t r = 0;
    for (int i = 0; i < 4; i++) {
        int x = (a >> (8 * i)) & 255, y = (b >> (8 * i)) & 255, v;
        switch (op) { case 0: v = x + y; break; case 1: v = x - y; break; case 2: v = x < y ? x : y; break; default: v = x > y ? x : y; }
        r |= v3d_sat8(v) << (8 * i);
    }
    return r;
}

static float v3d_f16(uint32_t h) {
    uint32_t s = (h >> 15) & 1, e = (h >> 10) & 31, m = h & 1023;
    float v;
    if (e == 0) v = (float)m * (1.0f / 16777216.0f);
    else if (e == 31) v = 65504.0f;
    else { union v3d_fu x; x.u = ((e + 112) << 23) | (m << 13); v = x.f; }
    return s ? -v : v;
}

/* operand unpack; 'isfloat' tells how 8a..8d / 16a / 16b are widened */
static void v3d_unpack(uint32_t *dst, const uint32_t *src, int mode, int isfloat, int n) {
    for (int l = 0; l < n; l++) {
        uint32_t v = src[l];
        switch (mode) {
        case 1: dst[l] = isfloat ? v3d_f2u(v3d_f16(v & 0xffff)) : (uint32_t)(int32_t)(int16_t)v; break;
        case 2: dst[l] = isfloat ? v3d_f2u(v3d_f16(v >> 16)) : (uint32_t)(int32_t)(int16_t)(v >> 16); break;
        case 3: dst[l] = (v >> 24) * 0x01010101u; break;
        case 4: case 5: case 6: case 7: {
            uint32_t b = (v >> (8 * (mode - 4))) & 255;
            dst[l] = isfloat ? v3d_f2u((float)b * (1.0f / 255.0f)) : b;
            break; }
        default: dst[l] = v;
        }
    }
}

static int v3d_op_is_float_add(int op) { return op >= 1 && op <= 8; }

static uint32_t v3d_pack_byte_f(float f) {
    if (!(f > 0.0f)) return 0;
    if (f >= 1.0f) return 255;
    return (uint32_t)(f * 255.0f + 0.5f);
}

/* ------------------------------------------------------------------------------------------ texture lookups */

/* wrap a texel coordinate */
static inline int v3d_wrap(int x, int n, int mode) {
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

/* texel with wrap modes / border; the texture is already mapped */
static uint32_t v3d_tex_fetch(const struct v3d_tex *t, int x, int y) {
    if (t->ws == 3 && (x < 0 || x >= t->w)) return 0;
    if (t->wt == 3 && (y < 0 || y >= t->h)) return 0;
    x = v3d_wrap(x, t->w, t->ws); y = v3d_wrap(y, t->h, t->wt);
    uint32_t off = ((uint32_t)y * (uint32_t)t->w + (uint32_t)x) * t->bpp;
    if (t->av < off + t->bpp) return 0;
    return v3d_texel(t->type, t->p + off);
}
/* same for coordinates already known to be inside the texture */
static inline uint32_t v3d_tex_fetch_in(const struct v3d_tex *t, int x, int y) {
    uint32_t off = ((uint32_t)y * (uint32_t)t->w + (uint32_t)x) * t->bpp;
    if (t->av < off + t->bpp) return 0;
    return v3d_texel(t->type, t->p + off);
}

static inline int v3d_ifloor(float f) { int i = (int)f; return (f < (float)i) ? i - 1 : i; }

static void v3d_tmu_2d_run(const struct v3d_tex *t, const uint32_t *s, const uint32_t *tt, uint32_t *out, int n) {
    int l;
    if (!t->p) { for (l = 0; l < n; l++) out[l] = 0; return; }
    const float fw = (float)t->w, fh = (float)t->h;
    const int w = t->w, h = t->h;
    if (!t->lin) {
        for (l = 0; l < n; l++) {
            float u = v3d_u2f(s[l]) * fw, v = v3d_u2f(tt[l]) * fh;
            int x = (u >= 0.0f && u < 2147483520.0f) ? (int)u : v3d_ifloor(u);
            int y = (v >= 0.0f && v < 2147483520.0f) ? (int)v : v3d_ifloor(v);
            out[l] = ((unsigned)x < (unsigned)w && (unsigned)y < (unsigned)h) ? v3d_tex_fetch_in(t, x, y) : v3d_tex_fetch(t, x, y);
        }
        return;
    }
    for (l = 0; l < n; l++) {
        float u = v3d_u2f(s[l]) * fw, v = v3d_u2f(tt[l]) * fh;
        int x0, y0; uint32_t fx, fy;
        u -= 0.5f; v -= 0.5f;
        if (u >= 0.0f && u < 8388607.0f && v >= 0.0f && v < 8388607.0f) {
            /* floor(u*256) == 256*floor(u) + floor(256*(u-floor(u))): u*256 is exact, so this is the old result bit for bit */
            int iu = (int)(u * 256.0f), iv = (int)(v * 256.0f);
            x0 = iu >> 8; fx = (uint32_t)(iu & 255); y0 = iv >> 8; fy = (uint32_t)(iv & 255);
        } else {
            x0 = v3d_ifloor(u); y0 = v3d_ifloor(v);
            fx = (uint32_t)((u - (float)x0) * 256.0f); fy = (uint32_t)((v - (float)y0) * 256.0f);
            if (fx > 255) fx = 255;
            if (fy > 255) fy = 255;
        }
        if (fx == 0 && fy == 0) {
            out[l] = ((unsigned)x0 < (unsigned)w && (unsigned)y0 < (unsigned)h) ? v3d_tex_fetch_in(t, x0, y0) : v3d_tex_fetch(t, x0, y0);
            continue;
        }
        uint32_t c00, c10, c01, c11;
        if ((unsigned)x0 < (unsigned)(w - 1) && (unsigned)y0 < (unsigned)(h - 1)) {
            c00 = v3d_tex_fetch_in(t, x0, y0); c10 = v3d_tex_fetch_in(t, x0 + 1, y0);
            c01 = v3d_tex_fetch_in(t, x0, y0 + 1); c11 = v3d_tex_fetch_in(t, x0 + 1, y0 + 1);
        } else {
            c00 = v3d_tex_fetch(t, x0, y0); c10 = v3d_tex_fetch(t, x0 + 1, y0);
            c01 = v3d_tex_fetch(t, x0, y0 + 1); c11 = v3d_tex_fetch(t, x0 + 1, y0 + 1);
        }
        uint32_t r = 0;
        for (int i = 0; i < 4; i++) {
            uint32_t a = (c00 >> (8 * i)) & 255, b = (c10 >> (8 * i)) & 255, c = (c01 >> (8 * i)) & 255, d = (c11 >> (8 * i)) & 255;
            uint32_t top = a * (256 - fx) + b * fx, bot = c * (256 - fx) + d * fx;
            r |= (((top * (256 - fy) + bot * fy) + 32768) >> 16 & 255) << (8 * i);
        }
        out[l] = r;
    }
}

static void v3d_tmu_raw_run(struct v3d_job *job, const uint32_t *s, uint32_t *out, int n) {
    struct v3d_mem *m = job->mem;
    if (m->stage_limit && job->stg_on)                       /* a shader reads the framebuffer: show it what was stored so far */
        for (int l = 0; l < n; l++) if ((s[l] & ~3u) < m->stage_limit) { v3d_stage_disable(job); break; }
    for (int l = 0; l < n; l++) out[l] = v3d_rd32(m, s[l] & ~3u);
}

/* ------------------------------------------------------------------------------------------ program decoder */

struct v3d_dec {
    struct v3d_uop *u; int nu, maxu;
    uint32_t (*c)[V3D_LANES]; int nc, maxc, c0;
    struct v3d_tex *tex; int nt, maxt, t0;     /* t0: index of tex[0] in the job's array */
};

static const uint32_t *v3d_dec_const(struct v3d_dec *d, uint32_t v) {
    for (int i = d->c0; i < d->nc; i++) {                  /* rows made by unpack folding are not uniform: compare every lane */
        int same = 1;
        for (int l = 0; l < V3D_LANES; l++) if (d->c[i][l] != v) { same = 0; break; }
        if (same) return d->c[i];
    }
    uint32_t *r = d->c[d->nc++];
    for (int l = 0; l < V3D_LANES; l++) r[l] = v;
    return r;
}

struct v3d_dstate {
    struct v3d_qpu *q;
    const struct v3d_prim *pr;
    struct v3d_mem *mem;
    struct v3d_job *job;
    int affine, ntmp, flags;
    int w_ra15, w_rb15;           /* the program has written ra15 / rb15 */
};

static inline uint32_t v3d_uniw(const struct v3d_prim *pr, int i) {
    return (pr->unif && (uint32_t)i * 4 + 4 <= pr->unif_avail) ? v3d_ld32(pr->unif + i * 4) : 0;
}

static struct v3d_uop *v3d_emit(struct v3d_dec *d, int k) {
    struct v3d_uop *u = &d->u[d->nu++];
    u->k = (uint8_t)k; u->a = u->b = u->c = 0; u->d = 0; u->s0 = u->s1 = 0; u->x = 0;
    return u;
}

/* source operand: returns a row (never NULL). *isc tells it is a constant row (can be folded). */
static const uint32_t *v3d_dec_src(struct v3d_dec *d, struct v3d_dstate *st, int mux, int ra_a, int ra_b, int smallimm,
                                   uint32_t unif_val, int vi, int pm, int unpack, int isfloat) {
    struct v3d_qpu *q = st->q;
    const uint32_t *p; int isc = 0;
    int l;
    if (mux < 4) p = q->acc[mux];
    else if (mux == 4) {
        p = q->r4;
        if (pm && unpack) { uint32_t *t = q->tmp[st->ntmp++]; struct v3d_uop *u = v3d_emit(d, UK_UNPACK); u->d = t; u->s0 = p; u->a = (uint8_t)unpack; u->b = (uint8_t)isfloat; p = t; }
        return p;
    }
    else if (mux == 5) return q->zero;
    else {
        int ra = (mux == 6);
        int addr = ra ? ra_a : ra_b;
        if (!ra && smallimm) {
            uint32_t v;
            if (addr < 16) v = (uint32_t)addr;
            else if (addr < 32) v = (uint32_t)(addr - 32);
            else if (addr < 40) v = v3d_f2u((float)(1 << (addr - 32)));
            else if (addr < 48) v = v3d_f2u(1.0f / (float)(1 << (48 - addr)));
            else v = 0;
            return v3d_dec_const(d, v);
        }
        if (addr < 32) {
            if (addr == 15 && ra) {
                if (!st->w_ra15 && st->affine) { p = v3d_dec_const(d, v3d_f2u(1.0f)); isc = 1; }
                else { p = q->ra[15]; if (!st->w_ra15) st->flags |= PF_RA15; }
            } else if (addr == 15 && !ra) {
                if (!st->w_rb15) { p = v3d_dec_const(d, v3d_f2u(1.0f)); isc = 1; }
                else p = q->rb[15];
            } else p = ra ? q->ra[addr] : q->rb[addr];
        } else switch (addr) {
            case 32: p = v3d_dec_const(d, unif_val); isc = 1; break;
            case 35: { int v = vi; if (v < 0) v = 0; if (v > 7) v = 7; p = q->vary[v]; break; }
            case 41: if (ra) { p = q->px; st->flags |= PF_PX; } else { p = q->py; st->flags |= PF_PY; } break;
            case 38: if (ra) { p = q->lane15; st->flags |= PF_LANE; } else { p = q->zero; } break;
            default: p = q->zero; break;
        }
        if (ra && unpack && !pm) {
            if (isc || p == q->zero) {
                const uint32_t *src = p;
                uint32_t *r = d->c[d->nc++];
                v3d_unpack(r, src, unpack, isfloat, V3D_LANES);
                p = r;
            } else {
                uint32_t *t = q->tmp[st->ntmp++]; struct v3d_uop *u = v3d_emit(d, UK_UNPACK);
                u->d = t; u->s0 = p; u->a = (uint8_t)unpack; u->b = (uint8_t)isfloat; p = t;
            }
        }
    }
    (void)l;
    return p;
}

/* the register row a write to waddr lands in, else NULL (no side effects) */
static uint32_t *v3d_row_of(struct v3d_qpu *q, int waddr, int to_b) {
    if (waddr < 32) return to_b ? q->rb[waddr] : q->ra[waddr];
    if (waddr <= 35) return q->acc[waddr - 32];
    return 0;
}
/* same, and remembers that the program changes ra15 / rb15 (they hold W / 1.0 at the start of every span) */
static uint32_t *v3d_plain_row(struct v3d_dstate *st, int waddr, int to_b) {
    if (waddr == 15) { if (to_b) { st->w_rb15 = 1; st->flags |= PF_RB15; } else { st->w_ra15 = 1; st->flags |= PF_RA15; } }
    return v3d_row_of(st->q, waddr, to_b);
}

/* does a write to waddr change anything? (TMU S/T count; 58/59, acc 5 and the unused addresses do not) */
static int v3d_write_has_effect(int waddr) { return waddr <= 35 || waddr == 45 || waddr == 46 || waddr == 52 || waddr == 56 || waddr == 57; }

static void v3d_pack_mode(int pack, int pm, int mulpipe, int waddr, int to_b, int *pk, int *fl) {
    *pk = PK_NONE; *fl = 0;
    if (!pack) return;
    if (!pm && (waddr >= 32 || to_b)) pack = 0;
    if (!pack) return;
    if (pm && mulpipe) {
        if (pack == 3) *pk = PK_M8REP; else if (pack >= 4 && pack <= 7) { *pk = PK_M8LANE; *fl = pack - 4; }
    } else if (!pm) {
        if (pack == 1) *pk = PK_A16LO; else if (pack == 2) *pk = PK_A16HI; else if (pack == 3) *pk = PK_A8REP;
        else if (pack >= 4 && pack <= 7) { *pk = PK_A8LANE; *fl = pack - 4; }
    }
}

/* generic write of 'val' (a row) to waddr */
static void v3d_dec_write(struct v3d_dec *d, struct v3d_dstate *st, int waddr, int to_b, const uint32_t *val, int pack, int pm, int mulpipe) {
    int pk, fl;
    v3d_pack_mode(pack, pm, mulpipe, waddr, to_b, &pk, &fl);
    if (waddr == 37) return;                       /* acc 5: nothing ever reads it */
    if (waddr <= 35) {
        struct v3d_uop *u = v3d_emit(d, UK_WRITE);
        u->b = WK_ROW; u->a = (uint8_t)pk; u->c = (uint8_t)fl; u->d = v3d_plain_row(st, waddr, to_b); u->s0 = val;
    } else if (waddr == 45 || waddr == 46) {
        struct v3d_uop *u = v3d_emit(d, UK_WRITE); u->b = WK_TLB; u->a = (uint8_t)pk; u->c = (uint8_t)fl; u->s0 = val;
    } else if (waddr == 52) {
        struct v3d_uop *u = v3d_emit(d, UK_WRITE); u->b = WK_SFU; u->s0 = val;
    }
    }

static int v3d_row_in(const uint32_t *r, const uint32_t *a, const uint32_t *b) { return r == a || r == b; }

/* Decodes the program of a draw. Returns 0 or a negative error:
 * -1 runs off the code, -2 branch, -3 never ends, -4 does not fit the decode buffers */
static int v3d_decode(struct v3d_dec *d, struct v3d_dstate *st, struct v3d_prog *pg) {
    const struct v3d_prim *pr = st->pr;
    struct v3d_qpu *q = st->q;
    int pc = 0, end_cd = -1, nuni = 0, nvr = 0, have_t = 0, fq[4], nfq = 0, nvary = 0;
    st->flags = 0; st->w_ra15 = st->w_rb15 = 0;
    for (int n = 0; n < V3D_MAX_QPU_INSNS; n++, pc++) {
        if ((uint32_t)(pc * 8 + 8) > pr->code_avail) return -1;
        if (d->nu + 24 > d->maxu || d->nc + 12 > d->maxc || d->nt + 2 > d->maxt) return -4;
        uint32_t lo = v3d_ld32(pr->code + pc * 8), hi = v3d_ld32(pr->code + pc * 8 + 4);
        int sig = hi >> 28;
        if (sig == 15) return -2;
        st->ntmp = 0;
        if (sig == 14) {                                         /* load immediate */
            int ws = (hi >> 12) & 1, wa = (hi >> 6) & 63, wm = hi & 63;
            const uint32_t *imm = v3d_dec_const(d, lo);
            if (((hi >> 17) & 7) != 0) v3d_dec_write(d, st, wa, ws, imm, 0, 0, 0);
            if (((hi >> 14) & 7) != 0) v3d_dec_write(d, st, wm, !ws, imm, 0, 0, 1);
        } else {
            int unpack = (hi >> 25) & 7, pm = (hi >> 24) & 1, pack = (hi >> 20) & 15;
            int cadd = (hi >> 17) & 7, cmul = (hi >> 14) & 7, ws = (hi >> 12) & 1;
            int wadd = (hi >> 6) & 63, wmul = hi & 63;
            int opm = (lo >> 29) & 7, opa = (lo >> 24) & 31;
            int raddr_a = (lo >> 18) & 63, raddr_b = (lo >> 12) & 63;
            int am = (lo >> 9) & 7, bm = (lo >> 6) & 7, mam = (lo >> 3) & 7, mbm = lo & 7;
            int smallimm = (sig == 13);
            uint32_t unif_val = 0;
            if (raddr_a == 32 || (!smallimm && raddr_b == 32)) unif_val = v3d_uniw(pr, nuni++);
            if (raddr_a == 35 || (!smallimm && raddr_b == 35)) nvr++;
            int vi = nvr - 1;
            int isfa = v3d_op_is_float_add(opa), isfm = (opm == 1);
            int add_nop = (opa == 0 && wadd == 39), mul_nop = (opm == 0 && wmul == 39);
            int do_add = !add_nop && cadd != 0, do_mul = !mul_nop && cmul != 0;
            /* a pipe whose write goes nowhere has no effect at all */
            int eff_add = do_add && v3d_write_has_effect(wadd);
            int eff_mul = do_mul && v3d_write_has_effect(wmul);
            const uint32_t *a = 0, *b = 0, *ma = 0, *mb = 0;
            if (eff_add) {
                a = v3d_dec_src(d, st, am, raddr_a, raddr_b, smallimm, unif_val, vi, pm, unpack, isfa);
                b = v3d_dec_src(d, st, bm, raddr_a, raddr_b, smallimm, unif_val, vi, pm, unpack, isfa);
            }
            if (eff_mul) {
                ma = v3d_dec_src(d, st, mam, raddr_a, raddr_b, smallimm, unif_val, vi, pm, unpack, isfm);
                mb = v3d_dec_src(d, st, mbm, raddr_a, raddr_b, smallimm, unif_val, vi, pm, unpack, isfm);
            }
            if (vi >= 0 && (raddr_a == 35 || (!smallimm && raddr_b == 35))) { int v = vi > 7 ? 7 : vi; if (v + 1 > nvary) nvary = v + 1; }
            /* targets */
            int to_b_add = (ws == 1), to_b_mul = (ws == 0);
            uint32_t *rowA = 0, *rowM = 0; int pkA = 0, pkM = 0, flA, flM;
            if (eff_add) rowA = v3d_row_of(q, wadd, to_b_add);
            if (eff_mul) rowM = v3d_row_of(q, wmul, to_b_mul);
            if (eff_add) v3d_pack_mode(pack, pm, 0, wadd, to_b_add, &pkA, &flA);
            if (eff_mul) v3d_pack_mode(pack, pm, 1, wmul, to_b_mul, &pkM, &flM);
            int dirA = eff_add && rowA && pkA == PK_NONE, dirM = eff_mul && rowM && pkM == PK_NONE;
#ifdef V3D_NO_DIRECT
            dirA = dirM = 0;
#endif
            if (dirA && eff_mul && (v3d_row_in(rowA, ma, mb) || rowA == rowM)) dirA = 0;
            if (dirM && eff_add && rowA == rowM) dirM = 0;
            uint32_t *tA = 0, *tM = 0;
            if (eff_add) {
                if (dirA) { tA = rowA; (void)v3d_plain_row(st, wadd, to_b_add); }
                else tA = q->tmp[st->ntmp++];
                struct v3d_uop *u = v3d_emit(d, UK_ADD); u->a = (uint8_t)opa; u->d = tA; u->s0 = a; u->s1 = b;
            }
            if (eff_mul) {
                if (dirM) { tM = rowM; (void)v3d_plain_row(st, wmul, to_b_mul); }
                else tM = q->tmp[st->ntmp++];
                struct v3d_uop *u = v3d_emit(d, UK_MUL); u->a = (uint8_t)opm; u->d = tM; u->s0 = ma; u->s1 = mb;
            }
            for (int k = 0; k < 2; k++) {
                int eff = k == 0 ? eff_add : eff_mul, w = k == 0 ? wadd : wmul, dir = k == 0 ? dirA : dirM;
                const uint32_t *val = k == 0 ? tA : tM;
                if (!eff || dir) continue;
                if (w >= 56 && w <= 59) {
                    if (w == 57) { struct v3d_uop *u = v3d_emit(d, UK_TLAT); u->s0 = val; have_t = 1; }
                    else if (w == 56) {
                        uint32_t p0 = 0, p1 = 0; int twod = have_t;
                        if (have_t) { p0 = v3d_uniw(pr, nuni++); p1 = v3d_uniw(pr, nuni++); }
                        have_t = 0;
                        if (nfq < 4) {
                            int slot = 0; for (;;) { int used = 0; for (int i = 0; i < nfq; i++) if (fq[i] == slot) used = 1; if (!used) break; slot++; }
                            fq[nfq++] = slot;
                            if (twod) {
                                struct v3d_tex *t = &d->tex[d->nt]; 
                                uint32_t base = p0 & 0xfffff000u; uint32_t av = 0;
                                t->type = (p0 >> 4) & 15; t->w = (p1 >> 8) & 2047; t->h = (p1 >> 20) & 2047; if (!t->w) t->w = 2048; if (!t->h) t->h = 2048;
                                t->ws = p1 & 3; t->wt = (p1 >> 2) & 3; t->lin = !((p1 >> 7) & 1) && !((p1 >> 4) & 7);
                                t->bpp = v3d_texel_bytes(t->type);
                                if (st->mem->stage_limit && base < st->mem->stage_limit) v3d_stage_disable(st->job);
                                t->p = v3d_map(st->mem, base, &av); t->av = av; if (!t->p) t->av = 0;
                                struct v3d_uop *u = v3d_emit(d, UK_TMU2D); u->d = q->fifo[slot]; u->s0 = val; u->x = (uint32_t)(d->t0 + d->nt); d->nt++;
                            } else {
                                struct v3d_uop *u = v3d_emit(d, UK_TMURAW); u->d = q->fifo[slot]; u->s0 = val;
                            }
                        } else if (twod) { /* result dropped, uniforms consumed */ }
                    }
                    continue;
                }
                v3d_dec_write(d, st, w, k == 0 ? to_b_add : to_b_mul, val, pack, pm, k);
            }
        }
        if (sig == 10 && nfq > 0) {
            struct v3d_uop *u = v3d_emit(d, UK_POP); u->d = q->r4; u->s0 = q->fifo[fq[0]];
            for (int i = 1; i < nfq; i++) fq[i - 1] = fq[i];
            nfq--;
        }
        if (sig == 8 || sig == 9) { v3d_emit(d, UK_TREAD); st->flags |= PF_TREAD; }
        if ((sig == 3 || sig == 9) && end_cd < 0) end_cd = 2;
        if (end_cd >= 0) {
            if (end_cd-- == 0) {
                pg->nvary = (uint8_t)nvary; pg->flags = (uint8_t)st->flags;
                return 0;
            }
        }
    }
    return -3;
}

/* ------------------------------------------------------------------------------------------ micro-op kernels */

static void v3d_k_add(int op, uint32_t *d, const uint32_t *a, const uint32_t *b, int n) {
    int l;
    switch (op) {
    case 1: for (l = 0; l < n; l++) d[l] = v3d_f2u(v3d_u2f(a[l]) + v3d_u2f(b[l])); break;
    case 2: for (l = 0; l < n; l++) d[l] = v3d_f2u(v3d_u2f(a[l]) - v3d_u2f(b[l])); break;
    case 3: for (l = 0; l < n; l++) { float fx = v3d_u2f(a[l]), fy = v3d_u2f(b[l]); d[l] = v3d_f2u(fx < fy ? fx : fy); } break;
    case 4: for (l = 0; l < n; l++) { float fx = v3d_u2f(a[l]), fy = v3d_u2f(b[l]); d[l] = v3d_f2u(fx > fy ? fx : fy); } break;
    case 5: for (l = 0; l < n; l++) { float fx = v3d_u2f(a[l]), fy = v3d_u2f(b[l]); float ax = fx < 0 ? -fx : fx, ay = fy < 0 ? -fy : fy; d[l] = v3d_f2u(ax < ay ? ax : ay); } break;
    case 6: for (l = 0; l < n; l++) { float fx = v3d_u2f(a[l]), fy = v3d_u2f(b[l]); float ax = fx < 0 ? -fx : fx, ay = fy < 0 ? -fy : fy; d[l] = v3d_f2u(ax > ay ? ax : ay); } break;
    case 7: for (l = 0; l < n; l++) d[l] = (uint32_t)(int32_t)v3d_u2f(a[l]); break;
    case 8: for (l = 0; l < n; l++) d[l] = v3d_f2u((float)(int32_t)a[l]); break;
    case 12: for (l = 0; l < n; l++) d[l] = a[l] + b[l]; break;
    case 13: for (l = 0; l < n; l++) d[l] = a[l] - b[l]; break;
    case 14: for (l = 0; l < n; l++) d[l] = a[l] >> (b[l] & 31); break;
    case 15: for (l = 0; l < n; l++) d[l] = (uint32_t)((int32_t)a[l] >> (b[l] & 31)); break;
    case 16: for (l = 0; l < n; l++) { uint32_t x = a[l], y = b[l]; d[l] = (y & 31) ? ((x >> (y & 31)) | (x << (32 - (y & 31)))) : x; } break;
    case 17: for (l = 0; l < n; l++) d[l] = a[l] << (b[l] & 31); break;
    case 18: for (l = 0; l < n; l++) { uint32_t x = a[l], y = b[l]; d[l] = (int32_t)x < (int32_t)y ? x : y; } break;
    case 19: for (l = 0; l < n; l++) { uint32_t x = a[l], y = b[l]; d[l] = (int32_t)x > (int32_t)y ? x : y; } break;
    case 20: for (l = 0; l < n; l++) d[l] = a[l] & b[l]; break;
    case 21: for (l = 0; l < n; l++) d[l] = a[l] | b[l]; break;
    case 22: for (l = 0; l < n; l++) d[l] = a[l] ^ b[l]; break;
    case 23: for (l = 0; l < n; l++) d[l] = ~a[l]; break;
    case 24: for (l = 0; l < n; l++) { uint32_t t = a[l], r = 0; if (!t) r = 32; else while (!(t & 0x80000000u)) { t <<= 1; r++; } d[l] = r; } break;
    case 30: for (l = 0; l < n; l++) d[l] = v3d_v8bin(0, a[l], b[l]); break;
    case 31: for (l = 0; l < n; l++) d[l] = v3d_v8bin(1, a[l], b[l]); break;
    default: for (l = 0; l < n; l++) d[l] = 0; break;
    }
}

static void v3d_k_mul(int op, uint32_t *d, const uint32_t *a, const uint32_t *b, int n) {
    int l;
    switch (op) {
    case 1: for (l = 0; l < n; l++) d[l] = v3d_f2u(v3d_u2f(a[l]) * v3d_u2f(b[l])); break;
    case 2: for (l = 0; l < n; l++) d[l] = (a[l] & 0xffffff) * (b[l] & 0xffffff); break;
    case 3: for (l = 0; l < n; l++) d[l] = v3d_v8mul(a[l], b[l]); break;
    case 4: for (l = 0; l < n; l++) d[l] = v3d_v8bin(2, a[l], b[l]); break;
    case 5: for (l = 0; l < n; l++) d[l] = v3d_v8bin(3, a[l], b[l]); break;
    case 6: for (l = 0; l < n; l++) d[l] = v3d_v8bin(0, a[l], b[l]); break;
    case 7: for (l = 0; l < n; l++) d[l] = v3d_v8bin(1, a[l], b[l]); break;
    default: for (l = 0; l < n; l++) d[l] = 0; break;
    }
}

static inline uint32_t v3d_packv(int pk, int fl, uint32_t val) {
    switch (pk) {
    case PK_A16LO: return val & 0xffff;
    case PK_A16HI: return (val & 0xffff) << 16;
    case PK_A8REP: return (val & 255) * 0x01010101u;
    case PK_A8LANE: return (val & 255) << (8 * fl);
    case PK_M8REP: return v3d_pack_byte_f(v3d_u2f(val)) * 0x01010101u;
    case PK_M8LANE: return v3d_pack_byte_f(v3d_u2f(val)) << (8 * fl);
    default: return val;
    }
}

static void v3d_exec_prog(struct v3d_job *job, struct v3d_qpu *q, const struct v3d_prog *pg, int n) {
    const struct v3d_uop *u = pg->uops, *ue = u + pg->nuop;
    int l;
    for (; u != ue; u++) {
        uint32_t *d = u->d; const uint32_t *a = u->s0, *b = u->s1;
        switch (u->k) {
        case UK_ADD: v3d_k_add(u->a, d, a, b, n); break;
        case UK_MUL: v3d_k_mul(u->a, d, a, b, n); break;
        case UK_UNPACK: v3d_unpack(d, a, u->a, u->b, n); break;
        case UK_TLAT: for (l = 0; l < n; l++) q->tmu_t[l] = a[l]; break;
        case UK_TMU2D: v3d_tmu_2d_run(&job->tex[u->x], a, q->tmu_t, d, n); break;
        case UK_TMURAW: v3d_tmu_raw_run(job, a, d, n); break;
        case UK_POP: for (l = 0; l < n; l++) d[l] = a[l]; break;
        case UK_TREAD: for (l = 0; l < n; l++) q->r4[l] = q->tilerow[l]; break;
        case UK_WRITE: {
            int pk = u->a, fl = u->c;
            if (u->b == WK_SFU) { for (l = 0; l < n; l++) q->r4[l] = v3d_f2u(1.0f / v3d_u2f(a[l])); break; }
            if (u->b == WK_TLB) {
                uint32_t *t = q->tlb;
                if (pk == PK_NONE) for (l = 0; l < n; l++) t[l] = a[l];
                else for (l = 0; l < n; l++) t[l] = v3d_packv(pk, fl, a[l]);
                break;
            }
            if (pk == PK_NONE) { for (l = 0; l < n; l++) d[l] = a[l]; break; }
            uint32_t mask = 0xffffffffu;
            if (pk == PK_A16LO) mask = 0xffffu; else if (pk == PK_A16HI) mask = 0xffff0000u;
            else if (pk == PK_A8LANE || pk == PK_M8LANE) mask = 0xffu << (8 * fl);
            for (l = 0; l < n; l++) d[l] = (d[l] & ~mask) | (v3d_packv(pk, fl, a[l]) & mask);
            break; }
        default: break;
        }
    }
}

/* ------------------------------------------------------------------------------------------ tile buffer: load, store, framebuffer staging */

static void v3d_tile_clear(struct v3d_job *job) {
    uint32_t c = job->clear_color;
    for (int i = 0; i < V3D_TILE * V3D_TILE; i++) job->tile[i] = c;
}

static void v3d_copy_bytes(uint8_t *dst, const uint8_t *src, uint32_t n) {
    if ((((uintptr_t)dst | (uintptr_t)src | n) & 3) == 0) { for (uint32_t i = 0; i < n; i += 4) *(uint32_t *)(dst + i) = *(const uint32_t *)(src + i); }
    else for (uint32_t i = 0; i < n; i++) dst[i] = src[i];
}

/* writes the collected tiles to the real target and stops collecting */
static void v3d_stage_flush(struct v3d_job *job) {
    struct v3d_mem *m = job->mem;
    if (!job->stg_on) return;
    job->stg_on = 0;
    uint32_t av = 0; uint8_t *base = v3d_map(m, job->stg_addr, &av);
    int tw = job->stg_tw, th = job->stg_th, bpp = job->stg_bpp, txn = job->stg_tiles_x, tyn = (th + V3D_TILE - 1) / V3D_TILE;
    for (int ty = 0; ty < tyn; ty++) for (int tx = 0; tx < txn; tx++) {
        int idx = ty * txn + tx;
        if (!job->stg_dirty[idx]) continue;
        job->stg_dirty[idx] = 0;
        if (!base) { m->stats_unsupported++; continue; }
        int x0 = tx * V3D_TILE, y0 = ty * V3D_TILE;
        int cols = tw - x0 < V3D_TILE ? tw - x0 : V3D_TILE;
        for (int y = 0; y < V3D_TILE && y0 + y < th; y++) {
            uint32_t off = ((uint32_t)(y0 + y) * (uint32_t)tw + (uint32_t)x0) * bpp, nb = (uint32_t)cols * bpp;
            if (off + nb <= av) v3d_copy_bytes(base + off, v3d_stage_img + off, nb);
            else for (int x = 0; x < cols; x++) {
                uint32_t o = off + (uint32_t)x * bpp;
                if (o + bpp > av) continue;
                v3d_copy_bytes(base + o, v3d_stage_img + o, (uint32_t)bpp);
            }
        }
    }
}
/* a stage-limit address is read by a lookup: from now on write directly */
static void v3d_stage_disable(struct v3d_job *job) { v3d_stage_flush(job); job->stg_off = 1; }

/* tile buffer <- target pixels of tile (tx,ty) */
static void v3d_tile_load(struct v3d_job *job, uint32_t addr, int bpp, int tx, int ty) {
    struct v3d_mem *m = job->mem;
    uint32_t av = 0; const uint8_t *base;
    int tw = job->tgt.w, th = job->tgt.h;
    if (job->stg_on && addr == job->stg_addr && bpp == job->stg_bpp && tx < job->stg_tiles_x && job->stg_dirty[ty * job->stg_tiles_x + tx]) {
        base = v3d_stage_img; av = V3D_STAGE_BYTES;
    } else {
        base = v3d_map(m, addr, &av);
        if (!base) { m->stats_unsupported++; return; }
    }
    int x0 = tx * V3D_TILE, y0 = ty * V3D_TILE;
    int cols = tw - x0 < V3D_TILE ? tw - x0 : V3D_TILE;
    for (int y = 0; y < V3D_TILE && y0 + y < th; y++) {
        uint32_t off = ((uint32_t)(y0 + y) * (uint32_t)tw + (uint32_t)x0) * bpp;
        uint32_t *t = &job->tile[y * V3D_TILE];
        const uint8_t *s = base + off;
        if (off + (uint32_t)cols * bpp <= av) {
            if (bpp == 2) { for (int x = 0; x < cols; x++) t[x] = v3d_lut565[v3d_ld16(s + 2 * x)]; }
            else { for (int x = 0; x < cols; x++) t[x] = v3d_ldw(s + 4 * x); }
        } else {
            for (int x = 0; x < cols; x++) {
                uint32_t o = off + (uint32_t)x * bpp;
                if (o + bpp > av) continue;
                t[x] = (bpp == 2) ? v3d_lut565[v3d_ld16(base + o)] : v3d_ldw(base + o);
            }
        }
    }
}

/* target <- tile buffer. Returns 0 when the target is not mapped. */
static int v3d_tile_store(struct v3d_job *job, uint32_t addr, int bpp) {
    struct v3d_mem *m = job->mem;
    uint32_t av = 0; uint8_t *base;
    int tw = job->tgt.w, th = job->tgt.h, tx = job->tx, ty = job->ty;
    int staged = 0;
#if V3D_STAGE
    if (m->stage_limit && !job->stg_off && addr == job->tgt.addr && addr < m->stage_limit && bpp == job->tgt.bpp) {
        if (job->stg_on && (job->stg_addr != addr || job->stg_bpp != bpp || job->stg_tw != tw || job->stg_th != th)) v3d_stage_flush(job);
        if (!job->stg_on) {
            int txn = (tw + V3D_TILE - 1) / V3D_TILE, tyn = (th + V3D_TILE - 1) / V3D_TILE;
            uint32_t a2 = 0;
            if (tw > 0 && th > 0 && (uint32_t)tw * (uint32_t)th * (uint32_t)bpp <= V3D_STAGE_BYTES && txn * tyn <= V3D_STAGE_TILES && v3d_map(m, addr, &a2)) {
                job->stg_on = 1; job->stg_addr = addr; job->stg_bpp = bpp; job->stg_tw = tw; job->stg_th = th; job->stg_tiles_x = txn;
                for (int i = 0; i < txn * tyn; i++) job->stg_dirty[i] = 0;
            } else job->stg_off = 1;
        }
        if (job->stg_on && tx < job->stg_tiles_x && ty * job->stg_tiles_x + tx < V3D_STAGE_TILES) staged = 1;
    }
#endif
    if (staged) { base = v3d_stage_img; av = V3D_STAGE_BYTES; job->stg_dirty[ty * job->stg_tiles_x + tx] = 1; }
    else {
        base = v3d_map(m, addr, &av);
        if (!base) { m->stats_unsupported++; return 0; }
    }
    int x0 = tx * V3D_TILE, y0 = ty * V3D_TILE;
    int cols = tw - x0 < V3D_TILE ? tw - x0 : V3D_TILE;
    for (int y = 0; y < V3D_TILE && y0 + y < th; y++) {
        uint32_t off = ((uint32_t)(y0 + y) * (uint32_t)tw + (uint32_t)x0) * bpp;
        const uint32_t *t = &job->tile[y * V3D_TILE];
        uint8_t *d = base + off;
        if (off + (uint32_t)cols * bpp <= av) {
            if (bpp == 2) { for (int x = 0; x < cols; x++) v3d_st16(d + 2 * x, v3d_rgba_to_rgb565(t[x])); }
            else { for (int x = 0; x < cols; x++) v3d_stw(d + 4 * x, t[x]); }
        } else {
            for (int x = 0; x < cols; x++) {
                uint32_t o = off + (uint32_t)x * bpp;
                if (o + bpp > av) continue;
                if (bpp == 2) v3d_st16(base + o, v3d_rgba_to_rgb565(t[x])); else v3d_stw(base + o, t[x]);
            }
        }
    }
    return 1;
}

/* a tile that was loaded lazily is read now */
static void v3d_tile_materialize(struct v3d_job *job) {
    if (!job->lz) return;
    job->lz = 0;
    v3d_tile_load(job, job->lz_addr, job->lz_bpp, job->lz_tx, job->lz_ty);
}

/* ------------------------------------------------------------------------------------------ rasteriser */

struct v3d_vert { int32_t x, y; float invw; float vary[8]; };   /* x,y in 1/16 pixel */

/* int64 -> float without the libgcc helper (the shim links without it) */
static inline float v3d_i64f(int64_t v) {
    int neg = v < 0; uint64_t u = neg ? (uint64_t)0 - (uint64_t)v : (uint64_t)v;
    uint32_t hi = (uint32_t)(u >> 32);
    float f = hi ? (float)hi * 4294967296.0f + (float)(uint32_t)u : (float)(uint32_t)u;
    return neg ? -f : f;
}

static inline int64_t v3d_edge(const struct v3d_vert *a, const struct v3d_vert *b, int32_t px, int32_t py) {
    return (int64_t)(b->x - a->x) * (int64_t)(py - a->y) - (int64_t)(b->y - a->y) * (int64_t)(px - a->x);
}
static inline int v3d_topleft(const struct v3d_vert *a, const struct v3d_vert *b) {
    int32_t dx = b->x - a->x, dy = b->y - a->y;
    return dy < 0 || (dy == 0 && dx > 0);
}
static inline int v3d_inside(int64_t e, int tl) { return e > 0 || (e == 0 && tl); }

/* The pixels k = 0..cnt-1 of a row whose edge value e0 + s*k passes the edge test form an interval [*lo,*hi). */
static void v3d_edge_range(int64_t e0, int64_t s, int tl, int cnt, int *lo, int *hi) {
    int a0 = v3d_inside(e0, tl), a1 = v3d_inside(e0 + s * (int64_t)(cnt - 1), tl);
    *lo = 0; *hi = cnt;
    if (a0 && a1) return;
    if (!a0 && !a1) { *hi = 0; return; }
    int l = 0, h = cnt - 1;                    /* the answer lies between the ends: l and h differ in the test result */
    while (h - l > 1) {
        int mid = (l + h) >> 1;
        if (v3d_inside(e0 + s * (int64_t)mid, tl) == a0) l = mid; else h = mid;
    }
    if (a0) *hi = h; else *lo = h;
}

static void v3d_get_vert(const struct v3d_prim *pr, uint32_t idx, struct v3d_vert *v) {
    uint32_t off = idx * pr->stride;
    for (int i = 0; i < 8; i++) v->vary[i] = 0.0f;
    v->x = v->y = 0; v->invw = 1.0f;
    if (!pr->vtx || off + 12 > pr->vtx_avail) return;
    const uint8_t *p = pr->vtx + off;
    uint32_t xy = v3d_ld32(p);
    v->x = (int16_t)(xy & 0xffff) + pr->d->vp_x;
    v->y = (int16_t)(xy >> 16) + pr->d->vp_y;
    v->invw = v3d_u2f(v3d_ld32(p + 8));
    for (uint32_t i = 0; i < pr->nvary && i < 8; i++)
        if (off + 12 + 4 * i + 4 <= pr->vtx_avail) v->vary[i] = v3d_u2f(v3d_ld32(p + 12 + 4 * i));
}

/* the decoded program of a draw (decoded on first use) */
static const struct v3d_prog *v3d_prog_get(struct v3d_job *job, struct v3d_draw *d, const struct v3d_prim *pr, int affine) {
    struct v3d_prog *pg = &d->prog[affine ? 1 : 0];
    if (pg->state == 1 || pg->state == 2) return pg;
    struct v3d_dstate st; struct v3d_dec dec;
    st.q = &v3d_qs; st.pr = pr; st.mem = job->mem; st.job = job; st.affine = affine; st.ntmp = 0; st.flags = 0; st.w_ra15 = st.w_rb15 = 0;
    int pass;
    for (pass = 0; pass < 2; pass++) {
        int use_scratch = (pass == 1) || pg->state == 3;
        if (!use_scratch) {
            dec.u = job->uops + job->nu; dec.maxu = V3D_MAX_UOPS - V3D_SCR_UOPS - job->nu;
            dec.c = job->consts + job->nc; dec.maxc = V3D_MAX_CONSTS - V3D_SCR_CONSTS - job->nc;
            dec.tex = job->tex + job->ntex; dec.t0 = job->ntex; dec.maxt = V3D_MAX_TEX - V3D_SCR_TEX - job->ntex;
        } else {
            dec.u = job->uops + (V3D_MAX_UOPS - V3D_SCR_UOPS); dec.maxu = V3D_SCR_UOPS;
            dec.c = job->consts + (V3D_MAX_CONSTS - V3D_SCR_CONSTS); dec.maxc = V3D_SCR_CONSTS;
            dec.tex = job->tex + (V3D_MAX_TEX - V3D_SCR_TEX); dec.t0 = V3D_MAX_TEX - V3D_SCR_TEX; dec.maxt = V3D_SCR_TEX;
        }
        dec.nu = 0; dec.nc = 0; dec.c0 = 0; dec.nt = 0;
        struct v3d_prog tmp; tmp.state = 0; tmp.nvary = 0; tmp.flags = 0; tmp.nuop = 0; tmp.uops = 0;
        int r = v3d_decode(&dec, &st, &tmp);
        if (r == 0) {
            if (!use_scratch) { pg->state = 1; pg->uops = dec.u; pg->nuop = (uint16_t)dec.nu; pg->nvary = tmp.nvary; pg->flags = tmp.flags;
                job->nu += dec.nu; job->nc += dec.nc; job->ntex += dec.nt; return pg; }
            pg->state = 3;
            job->scratch.state = 3; job->scratch.uops = dec.u; job->scratch.nuop = (uint16_t)dec.nu; job->scratch.nvary = tmp.nvary; job->scratch.flags = tmp.flags;
            return &job->scratch;
        }
        if (r == -4 && !use_scratch) continue;            /* arena full: try the scratch area */
        pg->state = 2;
        return pg;
    }
    pg->state = 2;
    return pg;
}

/* rows of a span: pixel coordinates and interpolated values */
static void v3d_span_setup(struct v3d_qpu *q, const struct v3d_prog *pg, const struct v3d_prim *pr,
                           const struct v3d_vert *v0, const struct v3d_vert *v1, const struct v3d_vert *v2,
                           int affine, float inv_area, int64_t e12, int64_t e20, int64_t e01,
                           int64_t s12, int64_t s20, int64_t s01, int xa, int y, int lane0, int n) {
    int l, k;
    int nf = pg->nvary > 8 ? 8 : pg->nvary, np = pr->nvary > 8 ? 8 : (int)pr->nvary;
    if (pg->flags & PF_PX) for (l = 0; l < n; l++) q->px[l] = (uint32_t)(xa + l);
    if (pg->flags & PF_PY) for (l = 0; l < n; l++) q->py[l] = (uint32_t)y;
    /* element number: the position inside a run that starts at the left of the triangle's bounding box in this tile, as before */
    if (pg->flags & PF_LANE) for (l = 0; l < n; l++) q->lane15[l] = (uint32_t)((lane0 + l) & 15);
    if (pg->flags & PF_RB15) { uint32_t one = v3d_f2u(1.0f); for (l = 0; l < n; l++) q->rb[15][l] = one; }
    float w0 = v0->invw, w1 = v1->invw, w2 = v2->invw;
    for (k = np; k < nf; k++) for (l = 0; l < n; l++) q->vary[k][l] = 0;
    if (np < nf) nf = np;
    if (affine) {
        if (pg->flags & PF_RA15) { uint32_t one = v3d_f2u(1.0f); for (l = 0; l < n; l++) q->ra[15][l] = one; }
        if (!nf) return;
#if V3D_FAST_VARY
        {
            float l0 = v3d_i64f(e12) * inv_area, l1 = v3d_i64f(e20) * inv_area, l2 = v3d_i64f(e01) * inv_area;
            float d0 = v3d_i64f(s12) * inv_area, d1 = v3d_i64f(s20) * inv_area, d2 = v3d_i64f(s01) * inv_area;
            for (k = 0; k < nf; k++) {
                float b = l0 * v0->vary[k] + l1 * v1->vary[k] + l2 * v2->vary[k];
                float dk = d0 * v0->vary[k] + d1 * v1->vary[k] + d2 * v2->vary[k];
                uint32_t *row = q->vary[k];
                for (l = 0; l < n; l++) { row[l] = v3d_f2u(b); b += dk; }
            }
            return;
        }
#endif
    }
    for (l = 0; l < n; l++) {
        float l0 = v3d_i64f(e12) * inv_area, l1 = v3d_i64f(e20) * inv_area, l2 = v3d_i64f(e01) * inv_area;
        float iw, W;
        if (affine) { iw = 1.0f; W = 1.0f; }
        else { iw = l0 * w0 + l1 * w1 + l2 * w2; W = (iw != 0.0f) ? 1.0f / iw : 1.0f; }
        (void)iw;
        if (pg->flags & PF_RA15) q->ra[15][l] = v3d_f2u(W);
        for (k = 0; k < nf; k++) {
            float num = affine ? (l0 * v0->vary[k] + l1 * v1->vary[k] + l2 * v2->vary[k])
                               : (l0 * v0->vary[k] * w0 + l1 * v1->vary[k] * w1 + l2 * v2->vary[k] * w2);
            q->vary[k][l] = v3d_f2u(num);
        }
        e12 += s12; e20 += s20; e01 += s01;
    }
}

static void v3d_raster_tri(struct v3d_job *job, const struct v3d_prim *pr, const struct v3d_vert *v0, const struct v3d_vert *v1, const struct v3d_vert *v2) {
    struct v3d_draw *d = (struct v3d_draw *)pr->d;
    int64_t area = v3d_edge(v0, v1, v2->x, v2->y);
    if (area == 0) return;
    if (area < 0) { const struct v3d_vert *t = v1; v1 = v2; v2 = t; area = -area; }
    int minx = v0->x, maxx = v0->x, miny = v0->y, maxy = v0->y;
    const struct v3d_vert *vs[3]; vs[0] = v0; vs[1] = v1; vs[2] = v2;
    for (int i = 1; i < 3; i++) {
        if (vs[i]->x < minx) minx = vs[i]->x;
        if (vs[i]->x > maxx) maxx = vs[i]->x;
        if (vs[i]->y < miny) miny = vs[i]->y;
        if (vs[i]->y > maxy) maxy = vs[i]->y;
    }
    int x0 = (minx >> 4), x1 = (maxx + 15) >> 4, y0 = (miny >> 4), y1 = (maxy + 15) >> 4;
    int tx0 = job->tx * V3D_TILE, ty0 = job->ty * V3D_TILE;
    int cx0 = tx0, cy0 = ty0, cx1 = tx0 + V3D_TILE, cy1 = ty0 + V3D_TILE;
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

    int tl12 = v3d_topleft(v1, v2), tl20 = v3d_topleft(v2, v0), tl01 = v3d_topleft(v0, v1);
    float inv_area = 1.0f / v3d_i64f(area);
    int affine = (v0->invw == 1.0f && v1->invw == 1.0f && v2->invw == 1.0f);

    const struct v3d_prog *pg = v3d_prog_get(job, d, pr, affine);
    if (pg->state == 2 || pg->state == 0) { job->mem->stats_unsupported++; return; }
    if (job->lz) v3d_tile_materialize(job);

    struct v3d_qpu *q = &v3d_qs;
    int32_t px0 = x0 * 16 + 8, py0 = y0 * 16 + 8;
    int64_t s12 = -(int64_t)(v2->y - v1->y) * 16, r12s = (int64_t)(v2->x - v1->x) * 16;
    int64_t s20 = -(int64_t)(v0->y - v2->y) * 16, r20s = (int64_t)(v0->x - v2->x) * 16;
    int64_t s01 = -(int64_t)(v1->y - v0->y) * 16, r01s = (int64_t)(v1->x - v0->x) * 16;
    int64_t r12 = v3d_edge(v1, v2, px0, py0), r20 = v3d_edge(v2, v0, px0, py0), r01 = v3d_edge(v0, v1, px0, py0);
    int cnt = x1 - x0;
    int use_colout = (pg->flags & PF_TREAD) != 0;
    for (int y = y0; y < y1; y++, r12 += r12s, r20 += r20s, r01 += r01s) {
        int lo, hi, a, b;
        v3d_edge_range(r12, s12, tl12, cnt, &lo, &hi);
        if (hi <= lo) continue;
        v3d_edge_range(r20, s20, tl20, cnt, &a, &b);
        if (a > lo) lo = a;
        if (b < hi) hi = b;
        if (hi <= lo) continue;
        v3d_edge_range(r01, s01, tl01, cnt, &a, &b);
        if (a > lo) lo = a;
        if (b < hi) hi = b;
        if (hi <= lo) continue;
        int n = hi - lo, xa = x0 + lo;
        uint32_t *trow = &job->tile[(y - ty0) * V3D_TILE + (xa - tx0)];
        q->tilerow = trow;
        if (use_colout) { for (int l = 0; l < n; l++) q->colout[l] = trow[l]; q->tlb = q->colout; }
        else q->tlb = trow;
        v3d_span_setup(q, pg, pr, v0, v1, v2, affine, inv_area, r12 + s12 * lo, r20 + s20 * lo, r01 + s01 * lo, s12, s20, s01, xa, y, lo, n);
        for (int l = 0; l < n; l++) { q->acc[0][l] = 0; q->acc[1][l] = 0; q->acc[2][l] = 0; q->acc[3][l] = 0; }
        v3d_exec_prog(job, q, pg, n);
        if (use_colout) for (int l = 0; l < n; l++) trow[l] = q->colout[l];
    }
}

static void v3d_run_draw_on_tile(struct v3d_job *job, struct v3d_draw *d) {
    struct v3d_mem *m = job->mem;
    int tx0 = job->tx * V3D_TILE, ty0 = job->ty * V3D_TILE;
    if (d->clip_w > 0 && (d->clip_x >= tx0 + V3D_TILE || d->clip_x + d->clip_w <= tx0 || d->clip_y >= ty0 + V3D_TILE || d->clip_y + d->clip_h <= ty0)) return;
    if (d->bb_state == 1 && (d->bx0 >= tx0 + V3D_TILE || d->bx1 <= tx0 || d->by0 >= ty0 + V3D_TILE || d->by1 <= ty0)) return;
    struct v3d_prim pr; uint32_t av;
    const uint8_t *rec = v3d_map(m, d->rec, &av);
    if (!rec || av < 16) { m->stats_unsupported++; return; }
    uint32_t w0 = v3d_ld32(rec);
    pr.d = d; pr.stride = (w0 >> 8) & 255; pr.nvary = (w0 >> 24) & 255;
    pr.code = v3d_map(m, v3d_ld32(rec + 4) & ~7u, &pr.code_avail);
    pr.unif = v3d_map(m, v3d_ld32(rec + 8), &pr.unif_avail);
    pr.vtx = v3d_map(m, v3d_ld32(rec + 12), &pr.vtx_avail);
    if (!pr.code || !pr.stride) { m->stats_unsupported++; return; }
    if (pr.unif_avail > 4096 * 4) pr.unif_avail = 4096 * 4;
    struct v3d_vert v[3];
    uint32_t n = d->len;
    if (d->bb_state == 0) {                                   /* bounding box of every vertex of the draw, once */
        d->bb_state = 2;
        if ((d->mode == 4 || d->mode == 5 || d->mode == 6) && n >= 3 && n <= 4096 && pr.vtx) {
            struct v3d_vert t; int mnx = 0, mxx = 0, mny = 0, mxy = 0;
            for (uint32_t i = 0; i < n; i++) {
                v3d_get_vert(&pr, d->start + i, &t);
                if (!i || t.x < mnx) mnx = t.x;
                if (!i || t.x > mxx) mxx = t.x;
                if (!i || t.y < mny) mny = t.y;
                if (!i || t.y > mxy) mxy = t.y;
            }
            d->bx0 = mnx >> 4; d->bx1 = (mxx + 15) >> 4; d->by0 = mny >> 4; d->by1 = (mxy + 15) >> 4; d->bb_state = 1;
            if (d->bx0 >= tx0 + V3D_TILE || d->bx1 <= tx0 || d->by0 >= ty0 + V3D_TILE || d->by1 <= ty0) return;
        }
    }
    switch (d->mode) {
    case 4:                                                    /* triangles */
        for (uint32_t i = 0; i + 2 < n; i += 3) {
            v3d_get_vert(&pr, d->start + i, &v[0]); v3d_get_vert(&pr, d->start + i + 1, &v[1]); v3d_get_vert(&pr, d->start + i + 2, &v[2]);
            v3d_raster_tri(job, &pr, &v[0], &v[1], &v[2]);
        }
        break;
    case 5:                                                    /* strip */
        for (uint32_t i = 0; i + 2 < n; i++) {
            v3d_get_vert(&pr, d->start + i, &v[0]); v3d_get_vert(&pr, d->start + i + 1, &v[1]); v3d_get_vert(&pr, d->start + i + 2, &v[2]);
            if (i & 1) v3d_raster_tri(job, &pr, &v[1], &v[0], &v[2]); else v3d_raster_tri(job, &pr, &v[0], &v[1], &v[2]);
        }
        break;
    case 6:                                                    /* fan */
        v3d_get_vert(&pr, d->start, &v[0]);
        for (uint32_t i = 1; i + 1 < n; i++) {
            v3d_get_vert(&pr, d->start + i, &v[1]); v3d_get_vert(&pr, d->start + i + 1, &v[2]);
            v3d_raster_tri(job, &pr, &v[0], &v[1], &v[2]);
        }
        break;
    default: m->stats_unsupported++; break;                    /* points / lines are not used for composition */
    }
    m->stats_draws++;
}

/* ------------------------------------------------------------------------------------------ job execution */

static const uint8_t v3d_cl_size[128] = {
    [0] = 1, [1] = 1, [4] = 1, [5] = 1, [6] = 1, [7] = 1, [8] = 1, [16] = 5, [17] = 5, [18] = 1, [24] = 1, [25] = 1,
    [26] = 5, [27] = 5, [28] = 7, [29] = 7, [32] = 14, [33] = 10, [48] = 1, [49] = 1, [56] = 2, [64] = 5, [65] = 5, [66] = 5,
    [96] = 4, [97] = 5, [98] = 5, [99] = 5, [100] = 3, [101] = 5, [102] = 9, [103] = 5, [104] = 9, [105] = 9, [106] = 9,
    [112] = 16, [113] = 11, [114] = 14, [115] = 3,
};

static void v3d_bin_walk(struct v3d_job *job, uint32_t start, uint32_t end) {
    struct v3d_mem *m = job->mem;
    uint32_t av = 0; const uint8_t *p = v3d_map(m, start, &av);
    if (!p) return;
    if (end - start > av) end = start + av;
    int32_t cx = 0, cy = 0, cw = 0, ch = 0, vx = 0, vy = 0; uint32_t rec = 0;
    for (uint32_t o = 0; o < end - start;) {
        uint32_t op = p[o], sz = op < 128 ? v3d_cl_size[op] : 0;
        if (!sz || o + sz > end - start) { if (op) m->stats_unsupported++; break; }
        const uint8_t *b = p + o;
        switch (op) {
        case 102: cx = v3d_ld16(b + 1); cy = v3d_ld16(b + 3); cw = v3d_ld16(b + 5); ch = v3d_ld16(b + 7); break;
        case 103: vx = (int16_t)v3d_ld16(b + 1); vy = (int16_t)v3d_ld16(b + 3); break;
        case 65: rec = v3d_ld32(b + 1) & ~0xfu; break;
        case 33:
            if (job->ndraws < V3D_MAX_DRAWS) {
                struct v3d_draw *d = &job->draws[job->ndraws++];
                d->rec = rec; d->mode = b[1] & 15; d->len = v3d_ld32(b + 2); d->start = v3d_ld32(b + 6);
                d->clip_x = cx; d->clip_y = cy; d->clip_w = cw; d->clip_h = ch; d->vp_x = vx; d->vp_y = vy;
                d->prog[0].state = 0; d->prog[1].state = 0; d->bb_state = 0;
            } else m->stats_unsupported++;
            break;
        case 32: m->stats_unsupported++; break;                /* indexed draws are not produced by the compositor */
        case 0: o = end - start; continue;
        default: break;
        }
        o += sz;
    }
}

static inline int v3d_fmt_bpp(int fmt) { return (fmt == 1 || fmt == 2) ? 2 : 4; }

/* store the tile to addr: skipped when the tile is a lazy copy of exactly that place */
static int v3d_store_op(struct v3d_job *job, uint32_t addr, int bpp) {
    if (job->lz && job->lz_addr == addr && job->lz_bpp == bpp && job->lz_tx == job->tx && job->lz_ty == job->ty) return 1;
    if (job->lz) v3d_tile_materialize(job);
    return v3d_tile_store(job, addr, bpp);
}

static void v3d_render_walk(struct v3d_job *job, uint32_t start, uint32_t end) {
    struct v3d_mem *m = job->mem;
    uint32_t av = 0; const uint8_t *p = v3d_map(m, start, &av);
    if (!p) return;
    if (end - start > av) end = start + av;
    for (uint32_t o = 0; o < end - start;) {
        uint32_t op = p[o], sz = op < 128 ? v3d_cl_size[op] : 0;
        if (!sz || o + sz > end - start) { if (op) m->stats_unsupported++; break; }
        const uint8_t *b = p + o;
        switch (op) {
        case 113: {
            v3d_stage_flush(job);
            job->tgt.addr = v3d_ld32(b + 1) & ~0xfu; job->tgt.w = v3d_ld16(b + 5); job->tgt.h = v3d_ld16(b + 7);
            job->tgt.fmt = (b[9] >> 2) & 3; job->tgt.bpp = (job->tgt.fmt == 1) ? 4 : 2;
            if (m->stage_limit && job->tgt.addr < m->stage_limit) m->last_fb_tgt = job->tgt.addr;
            break; }
        case 114: job->clear_color = v3d_ld32(b + 1); v3d_tile_clear(job); job->lz = 0; break;
        case 115:                                   /* selects the tile, and runs the loads queued before it */
            job->tx = b[1]; job->ty = b[2]; m->stats_tiles++;
            if (job->pend_load) {
                uint32_t a2 = 0;
                int bpp = v3d_fmt_bpp(job->pend_fmt);
                job->pend_load = 0;
                /* The old tile buffer may only be dropped when this load overwrites every pixel of it: a full tile whose
                 * source is mapped completely. Otherwise its leftover pixels stay visible (an edge tile), keep them. */
                int full = (job->tx + 1) * V3D_TILE <= job->tgt.w && (job->ty + 1) * V3D_TILE <= job->tgt.h;
                int mapped = v3d_map(m, job->pend_addr, &a2) != 0 && (uint64_t)a2 >= (uint64_t)job->tgt.w * (uint64_t)job->tgt.h * (uint64_t)bpp;
                if (job->lz && !(full && mapped)) v3d_tile_materialize(job);
                if (V3D_LAZY_LOAD && mapped) {                          /* lazy: read only if something needs the pixels */
                    job->lz = 1; job->lz_tx = job->tx; job->lz_ty = job->ty; job->lz_addr = job->pend_addr; job->lz_bpp = bpp;
                } else { job->lz = 0; v3d_tile_load(job, job->pend_addr, bpp, job->tx, job->ty); }
            } else if (job->lz) v3d_tile_materialize(job);    /* the old tile's pixels stay in the buffer */
            break;
        case 29:
            if ((b[1] & 7) == 1) { job->pend_load = 1; job->pend_addr = v3d_ld32(b + 3) & ~0xfu; job->pend_fmt = b[2] & 3; }
            break;
        case 28:
            if ((b[1] & 7) == 1) v3d_store_op(job, v3d_ld32(b + 3) & ~0xfu, v3d_fmt_bpp(b[2] & 3));
            else if ((b[1] & 7) == 0) { v3d_tile_clear(job); job->lz = 0; }   /* 'store nothing': how the first tile clears */
            break;
        case 17: {
            int n = job->ndraws;
            if (m->debug_max_draws && (int)m->debug_max_draws < n) n = (int)m->debug_max_draws;
            for (int i = 0; i < n; i++) v3d_run_draw_on_tile(job, &job->draws[i]);
            break; }
        case 24: case 25: {
            int ok = v3d_store_op(job, job->tgt.addr, job->tgt.bpp);
            if (!ok) { if (op == 25) return; break; }
            if (op == 25) return;
            v3d_tile_clear(job); job->lz = 0;                 /* the tile buffer is cleared after each store */
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
static int v3d_exec_job(struct v3d_mem *mem, const uint32_t req[7]) {
    static struct v3d_job job;
    job.mem = mem; job.ndraws = 0; job.pend_load = 0; job.clear_color = 0; job.tgt.w = job.tgt.h = 0; job.tgt.fmt = 0; job.tgt.bpp = 2;
    job.lz = 0; job.stg_on = 0; job.stg_off = 0; job.nu = job.nc = job.ntex = 0;
    mem->c_len[0] = mem->c_len[1] = 0; mem->c_next = 0;
    v3d_lut_init();
    for (int l = 0; l < V3D_LANES; l++) v3d_qs.zero[l] = 0;
    if ((req[0] & 1) && req[3] > req[2]) v3d_bin_walk(&job, req[2], req[3]);
    if ((req[0] & 2) && req[5] > req[4]) v3d_render_walk(&job, req[4], req[5]);
    v3d_stage_flush(&job);
    return 0;
}

#endif /* V3DEMU_CORE_H */
