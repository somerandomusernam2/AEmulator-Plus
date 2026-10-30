/*
 * Software stand-in for Broadcom's V3D_Compose (Samsung GT-S5302 / BCM21553-class 2.3 firmware).
 *
 * That firmware's SurfaceFlinger has no OpenGL path left: every layer is drawn by V3D_Compose_Impl::TextureCompose, which
 * only queues a command list for the V3D GPU (/dev/v3d). Without the device V3D_Compose::Create() returns NULL, the
 * first frame dereferences it (SIGSEGV at libsurfaceflinger.so+0x183d8, zygote exits with 137) and, had it survived,
 * nothing would ever reach fb0. tools/patch_sf_v3d.py turns the functions below into the bodies of the V3D calls.
 *
 * Freestanding, position independent, Thumb, no libc, no floating point (the guest's libgcc is not around).
 * Layouts used (all read from the firmware's own libsurfaceflinger.so / libui.so, see docs/sf-v3d-software-composer.md):
 *   DisplayHardware : +0x0c current back buffer (NativeBuffer*), +0x34 width, +0x38 height, +0x3c PixelFormat
 *   NativeBuffer    : +0x28 stride (pixels), +0x40 handle           (GraphicBuffer: +0x2c stride, +0x44 handle)
 *   gralloc handle  : +0x20 base (private_handle_t of the engine's gralloc, what gralloc_lock() hands out)
 *   V3D object      : 0x120 bytes allocated by Create(); +0x120 lies past the original 0x120 bytes (Create() is patched to allocate 0x130) and keeps the DisplayHardware
 */
typedef unsigned int u32;
typedef int i32;
typedef unsigned short u16;
typedef unsigned char u8;

#include "corner_table.h"

#define SELF_HW 0x120
#define MAXAXIS 1024

static inline u32 rd(const void *p, u32 off) { return *(const u32 *)((const u8 *)p + off); }

/* IEEE-754 single (bits) in [0, 2^15) -> 16.16 fixed point */
static u32 fx16(u32 bits)
{
    if (!(bits & 0x7fffffffu) || (bits >> 31)) return 0;
    i32 e = (i32)((bits >> 23) & 0xff) - 127;
    u32 m = (bits & 0x7fffffu) | 0x800000u;     /* value = m * 2^(e-23) ; 16.16 = value * 2^16 */
    i32 s = 7 - e;
    if (s >= 32) return 0;
    if (s >= 0) return m >> s;
    if (s <= -16) return 0xffffffffu;
    return m << (-s);
}

/* ---- raw syscalls (ARM EABI) ---- */
static inline i32 sys_mmap2(u32 len)
{
    register u32 r0 __asm__("r0") = 0;
    register u32 r1 __asm__("r1") = len;
    register u32 r2 __asm__("r2") = 3;          /* PROT_READ|PROT_WRITE */
    register u32 r3 __asm__("r3") = 0x22;       /* MAP_PRIVATE|MAP_ANONYMOUS */
    register i32 r4 __asm__("r4") = -1;
    register u32 r5 __asm__("r5") = 0;
    register u32 r7 __asm__("r7") = 192;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5), "r"(r7) : "memory");
    return (i32)r0;
}
static inline void sys_munmap(void *p, u32 len)
{
    register u32 r0 __asm__("r0") = (u32)p;
    register u32 r1 __asm__("r1") = len;
    register u32 r7 __asm__("r7") = 91;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r7) : "memory");
}

/* ---- pixel helpers ---- */
static inline u32 px_get(const u8 *row, u32 x, u32 fmt)   /* -> 0xAARRGGBB */
{
    if (fmt == 4) {
        u32 v = ((const u16 *)row)[x];
        u32 r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
        return 0xff000000u | (((r << 3) | (r >> 2)) << 16) | (((g << 2) | (g >> 4)) << 8) | ((b << 3) | (b >> 2));
    }
    if (fmt == 1 || fmt == 2) {                                    /* R G B A / R G B X */
        const u8 *p = row + x * 4;
        return ((fmt == 1 ? (u32)p[3] : 0xffu) << 24) | ((u32)p[0] << 16) | ((u32)p[1] << 8) | p[2];
    }
    if (fmt == 5) {                                                /* B G R A */
        const u8 *p = row + x * 4;
        return ((u32)p[3] << 24) | ((u32)p[2] << 16) | ((u32)p[1] << 8) | p[0];
    }
    if (fmt == 6) {                                                /* RGBA 5551 */
        u32 v = ((const u16 *)row)[x];
        u32 r = (v >> 11) & 31, g = (v >> 6) & 31, b = (v >> 1) & 31;
        return ((v & 1) ? 0xff000000u : 0) | (((r << 3) | (r >> 2)) << 16) | (((g << 3) | (g >> 2)) << 8) | ((b << 3) | (b >> 2));
    }
    if (fmt == 7) {                                                /* RGBA 4444 */
        u32 v = ((const u16 *)row)[x];
        u32 r = (v >> 12) & 15, g = (v >> 8) & 15, b = (v >> 4) & 15, a = v & 15;
        return ((a * 17) << 24) | ((r * 17) << 16) | ((g * 17) << 8) | (b * 17);
    }
    return 0;
}

static inline u32 div255(u32 v) { v += 128; return (v + (v >> 8)) >> 8; }

/* the guest has no libgcc: unsigned division by shift-and-subtract (only called a few times per layer, never per pixel) */
static u32 udiv(u32 n, u32 d)
{
    u32 q = 0, r = 0;
    if (!d) return 0xffffffffu;
    for (i32 i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1);
        if (r >= d) { r -= d; q |= 1u << i; }
    }
    return q;
}

/* ---- the composer ---- */

/* V3D_Compose_Impl::init(const framebuffer_device_t*): nothing to open, "success" keeps Create() from returning NULL */
i32 v3d_soft_init(void *self, const void *fbdev) { (void)self; (void)fbdev; return 1; }

/* tileConvertRequest(W, H, dstPhys, srcStride, fmt, srcHandle, dstVirt, W, H): the hardware turned a layer's gralloc buffer into
 * its tiled texture format inside a "GE buffer". Here the GE buffer stays linear (W pixels per row, what TextureCompose reads),
 * so this is a row copy. Layer::reloadTexture is patched to hand over the buffer's stride and its gralloc handle in the two
 * fields that used to carry the Broadcom physical/virtual addresses. */
i32 v3d_soft_tile(void *self, u32 W, u32 H, u32 dstPhys, u32 d0, u32 d1,
                  u32 srcStride, u32 fmt, u32 handle, u32 dstVirt, u32 W2, u32 H2)
{
    (void)self; (void)dstPhys; (void)d0; (void)d1; (void)W2; (void)H2;
    if (!handle || !dstVirt || !srcStride || !W || !H) return 0;
    if (!(fmt == 1 || fmt == 2 || fmt == 4 || fmt == 5 || fmt == 6 || fmt == 7)) return 0;
    const u8 *src = (const u8 *)rd((const void *)handle, 0x20);
    u32 size = rd((const void *)handle, 0x18);
    if (!src || !size) return 0;
    u32 bpp = (fmt == 4 || fmt == 6 || fmt == 7) ? 2 : 4;
    u32 rowb = srcStride * bpp;
    u32 rows = H, cols = W < srcStride ? W : srcStride;
    u32 avail = udiv(size, rowb);                       /* rows the buffer really has */
    if (rows > avail) rows = avail;
    u8 *dst = (u8 *)dstVirt;
    for (u32 y = 0; y < rows; y++) {
        const u8 *s = src + y * rowb;
        u8 *d = dst + y * W * bpp;
        for (u32 i = 0; i < cols * bpp; i++) d[i] = s[i];
    }
    return 0;
}

/* compositionRequest(int), ditherFB(): the GPU kick-offs, nothing is queued here */
i32 v3d_soft_nop(void *self) { (void)self; return 0; }

/* AllocGEBuf(uint size, uint*& virt, uint*& phys): "graphics-engine" memory is plain anonymous memory, phys == virt */
i32 v3d_soft_alloc(void *self, u32 size, u32 *virt, u32 *phys)
{
    (void)self;
    size = (size + 4095u) & ~4095u;
    i32 p = sys_mmap2(size);
    if ((u32)p > 0xfffff000u) { *virt = 0; *phys = 0; return -1; }
    *virt = (u32)p; *phys = (u32)p;
    return 0;
}

/* FreeGEBuf(uint size, uint*& virt, uint*& phys) */
i32 v3d_soft_free(void *self, u32 size, u32 *virt, u32 *phys)
{
    (void)self;
    if (*virt) sys_munmap((void *)*virt, (size + 4095u) & ~4095u);
    *virt = 0; *phys = 0;
    return 0;
}

/* TextureCompose(int fmt, uint addr, uint stride, uint alignedH, float uMax, float vMax, uint orient,
 *                const Rect& dst, int flip, uint alpha)   (the hardware code builds a 4-vertex quad from these)
 * The stack arguments follow two dummy words: the entry trampoline has pushed {r4, lr} in front of them. */
i32 v3d_soft_compose(void *self, u32 fmt, u32 addr, u32 stride, u32 d0, u32 d1,
                     u32 alignedH, u32 uBits, u32 vBits, u32 orient, const i32 *rect, u32 flip, u32 alpha)
{
    (void)d0; (void)d1;
    const void *hw = (const void *)rd(self, SELF_HW);
    if (!hw || addr < 0x10000u || !stride || !alignedH || !rect) return 0;

    const u8 *buf = (const u8 *)rd(hw, 0x0c);                    /* back buffer of the framebuffer window */
    if (!buf) return 0;
    const u8 *handle = (const u8 *)rd(buf, 0x40);
    if (!handle) return 0;
    u8 *dbase = (u8 *)rd(handle, 0x20);
    u32 dstride = rd(buf, 0x28);
    i32 dw = (i32)rd(hw, 0x34), dh = (i32)rd(hw, 0x38);
    u32 dfmt = rd(hw, 0x3c);
    if (!dbase || !dstride || dw <= 0 || dh <= 0) return 0;
    u32 dbpp = (dfmt == 4) ? 2 : 4;

    if (!(fmt == 1 || fmt == 2 || fmt == 4 || fmt == 5 || fmt == 6 || fmt == 7)) return 0;
    u32 sbpp = (fmt == 4 || fmt == 6 || fmt == 7) ? 2 : 4;

    i32 L = rect[0], T = rect[1], R = rect[2], B = rect[3];
    if (R <= L || B <= T) return 0;
    u32 um = fx16(uBits), vm = fx16(vBits);                      /* visible fraction of the buffer */
    u32 sw = (um * stride + 0x8000u) >> 16;                      /* visible width/height in texels */
    u32 sh = (vm * alignedH + 0x8000u) >> 16;
    if (!sw || !sh) return 0;

    u32 o = (orient == 1) ? 1 : (orient == 3) ? 3 : 0;
    const u8 *cn = CORNER[o][flip ? 1 : 0];
    /* corner texcoords as 0/1 fractions: v0 top-left, v1 top-right, v3 bottom-left (the quad is a parallelogram) */
    i32 s0 = cn[0] >> 1, t0 = cn[0] & 1, s1 = cn[1] >> 1, t1 = cn[1] & 1, s3 = cn[3] >> 1, t3 = cn[3] & 1;

    u32 pa = alpha & 0xff;
    i32 cx0 = L < 0 ? 0 : L, cx1 = R > dw ? dw : R, cy0 = T < 0 ? 0 : T, cy1 = B > dh ? dh : B;
    if (cx1 <= cx0 || cy1 <= cy0 || dw > MAXAXIS || dh > MAXAXIS) return 0;
    u32 rw = (u32)(R - L), rh = (u32)(B - T);
    const u8 *sbase = (const u8 *)addr;
    u32 sstride_b = stride * sbpp;
    int hasAlpha = (fmt == 1 || fmt == 5 || fmt == 6 || fmt == 7);

    /* which screen axis drives which texel axis, and whether it runs backwards (the quad is axis-aligned, see CORNER) */
    int colOnX = (t1 != t0);
    int colRev = colOnX ? (t1 < t0) : (t3 < t0);
    int rowOnX = (s1 != s0);
    int rowRev = (rowOnX ? (s1 < s0) : (s3 < s0)) ^ (flip ? 1 : 0);
    /* texel index of every destination column / row: floor((2k+1)*n / (2*extent)), by running remainder (no division) */
    u16 tabX[MAXAXIS], tabY[MAXAXIS];
    for (int ax = 0; ax < 2; ax++) {
        u32 ext = ax ? rh : rw;
        int onThis = ax ? !colOnX : colOnX;                         /* does the column index run along this axis? */
        u32 n = onThis ? sw : sh;
        int rev = onThis ? colRev : rowRev;
        i32 lo = ax ? cy0 - T : cx0 - L, hi = ax ? cy1 - T : cx1 - L;
        u16 *tab = ax ? tabY : tabX;
        u32 idx = 0, rem = n;
        for (i32 k = 0; k < hi; k++) {
            if (k >= lo) { u32 v = rev ? n - 1 - idx : idx; tab[k - lo] = (u16)(v >= n ? n - 1 : v); }
            rem += 2 * n;
            while (rem >= 2 * ext) { rem -= 2 * ext; idx++; }
        }
    }

    for (i32 y = cy0; y < cy1; y++) {
        u8 *drow = dbase + (u32)y * dstride * dbpp;
        for (i32 x = cx0; x < cx1; x++) {
            u32 ia = tabX[x - cx0], ib = tabY[y - cy0];
            u32 col = colOnX ? ia : ib;
            u32 row = rowOnX ? ia : ib;
            u32 p = px_get(sbase + row * sstride_b, col, fmt);
            u32 a = hasAlpha ? (p >> 24) : 0xff;
            u32 sr = (p >> 16) & 0xff, sg = (p >> 8) & 0xff, sb = p & 0xff;
            if (pa != 0xff) { a = div255(a * pa); sr = div255(sr * pa); sg = div255(sg * pa); sb = div255(sb * pa); }
            if (a == 0 && hasAlpha) continue;
            if (a != 0xff) {                                                            /* premultiplied source over the screen */
                u32 d, dr, dg, db;
                if (dbpp == 2) { d = ((u16 *)drow)[x]; dr = (d >> 11) & 31; dg = (d >> 5) & 63; db = d & 31;
                                 dr = (dr << 3) | (dr >> 2); dg = (dg << 2) | (dg >> 4); db = (db << 3) | (db >> 2); }
                else { const u8 *q = drow + x * 4; if (dfmt == 5) { db = q[0]; dg = q[1]; dr = q[2]; } else { dr = q[0]; dg = q[1]; db = q[2]; } }
                u32 ia = 255 - a;
                sr = sr + div255(dr * ia); sg = sg + div255(dg * ia); sb = sb + div255(db * ia);
                if (sr > 255) { sr = 255; }
                if (sg > 255) { sg = 255; }
                if (sb > 255) { sb = 255; }
            }
            if (dbpp == 2) ((u16 *)drow)[x] = (u16)(((sr >> 3) << 11) | ((sg >> 2) << 5) | (sb >> 3));
            else { u8 *q = drow + x * 4; if (dfmt == 5) { q[0] = sb; q[1] = sg; q[2] = sr; } else { q[0] = sr; q[1] = sg; q[2] = sb; } q[3] = 0xff; }
        }
    }
    return 0;
}

/* Create() wrapper helper: DisplayHardware::init's `bl V3D_Compose::Create` is redirected to a small stub (tools/patch_sf_v3d.py)
 * that keeps r4, still the DisplayHardware there (callee-saved, untouched by init up to that call), and passes it on here:
 * the composer needs it to find the back buffer of the framebuffer window. */
void v3d_soft_remember_hw(void *composer, void *hw)
{
    if (composer) *(void **)((u8 *)composer + SELF_HW) = hw;
}
