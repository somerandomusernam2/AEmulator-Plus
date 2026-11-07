/* Host-side check of the software composer: builds with libc, runs under qemu-arm (see test.sh). */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "v3dsoft.c"

static u8 composer[0x120];
static u32 hwmem[0x40];
static u8 nbuf[0x70];
static u32 handle[16];
static int fails;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); fails++; } } while (0)

static u32 fbits(float f) { u32 b; memcpy(&b, &f, 4); return b; }

int main(void)
{
    /* fixed point conversion */
    CHECK(fx16(fbits(1.0f)) == 65536, "fx16(1)=%u", fx16(fbits(1.0f)));
    CHECK(fx16(fbits(0.5f)) == 32768, "fx16(.5)=%u", fx16(fbits(0.5f)));
    CHECK(fx16(fbits(0.9375f)) == 61440, "fx16(.9375)");
    CHECK(fx16(0) == 0, "fx16(0)");
    CHECK(udiv(65536, 240) == 273, "udiv");

    /* screen: 240x320 RGB565, stride 240 */
    const int DW = 240, DH = 320;
    u16 *fb = calloc(DW * DH, 2);
    handle[8] = (u32)(size_t)fb;                       /* +0x20 base */
    *(u32 *)(nbuf + 0x40) = (u32)(size_t)handle;
    *(u32 *)(nbuf + 0x28) = DW;
    hwmem[3] = (u32)(size_t)nbuf;                      /* +0x0c */
    hwmem[0x34 / 4] = DW; hwmem[0x38 / 4] = DH; hwmem[0x3c / 4] = 4;
    *(u32 *)(composer + SELF_HW) = (u32)(size_t)hwmem;

    /* source: 100x60 RGB565 in a buffer with stride 128, aligned height 64; each pixel encodes its coordinates */
    const int SW = 100, SH = 60, SS = 128, AH = 64;
    u16 *src = calloc(SS * AH, 2);
    for (int y = 0; y < SH; y++) for (int x = 0; x < SW; x++) src[y * SS + x] = (u16)(((x * 31 / 99) << 11) | ((y * 63 / 59) << 5) | 7);
    i32 rect[4] = { 20, 30, 120, 90 };                 /* 100x60 on screen: 1:1 */
    u32 uM = fbits((float)SW / SS), vM = fbits((float)SH / AH);

    /* identity (orientation 0, flip=1 as Layer::onDraw passes for gralloc buffers) */
    v3d_soft_compose(composer, 4, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 0, rect, 1, 255);
    int bad = 0;
    for (int y = 0; y < SH; y++) for (int x = 0; x < SW; x++) if (fb[(30 + y) * DW + 20 + x] != src[y * SS + x]) bad++;
    CHECK(bad == 0, "identity copy: %d pixels differ", bad);
    CHECK(fb[29 * DW + 20] == 0 && fb[30 * DW + 19] == 0 && fb[90 * DW + 20] == 0 && fb[30 * DW + 120] == 0, "nothing drawn outside the rect");

    /* flip=0 (what linear layers use) is upright as well */
    memset(fb, 0, DW * DH * 2);
    v3d_soft_compose(composer, 4, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 0, rect, 0, 255);
    bad = 0;
    for (int y = 0; y < SH; y++) for (int x = 0; x < SW; x++) if (fb[(30 + y) * DW + 20 + x] != src[y * SS + x]) bad++;
    CHECK(bad == 0, "flip=0: %d pixels differ", bad);

    /* tile convert: 240x300 source (stride 240) into a 256-wide GE buffer, then composed at 1:1 */
    {
        const int W = 256, H = 320, RW = 240, RH = 300;
        u16 *sb = calloc(RW * RH, 2);
        for (int y = 0; y < RH; y++) for (int x = 0; x < RW; x++) sb[y * RW + x] = (u16)(x * 7 + y * 13 + 1);
        u32 hnd[16] = {0}; hnd[8] = (u32)(size_t)sb; hnd[6] = RW * RH * 2;      /* +0x20 base, +0x18 size */
        u16 *ge = calloc(W * H, 2);
        v3d_soft_tile(composer, W, H, 0, 0, 0, RW, 4, (u32)(size_t)hnd, (u32)(size_t)ge, W, H);
        int b2 = 0;
        for (int y = 0; y < RH; y++) for (int x = 0; x < RW; x++) if (ge[y * W + x] != sb[y * RW + x]) b2++;
        CHECK(b2 == 0, "tile convert: %d pixels differ", b2);
        CHECK(ge[RH * W] == 0, "tile convert read past the buffer");
        memset(fb, 0, DW * DH * 2);
        i32 rr[4] = { 0, 0, 240, 300 };
        v3d_soft_compose(composer, 4, (u32)(size_t)ge, W, 0, 0, H, fbits(240.0f / 256), fbits(300.0f / 320), 0, rr, 0, 255);
        b2 = 0;
        for (int y = 0; y < RH; y++) for (int x = 0; x < RW; x++) if (fb[y * DW + x] != sb[y * RW + x]) b2++;
        CHECK(b2 < 8, "tile+compose end to end: %d pixels differ", b2);
        free(sb); free(ge);
    }

    /* scaling 2x down: every dest pixel must come from the matching source area (spot check corners / middle) */
    memset(fb, 0, DW * DH * 2);
    i32 r2[4] = { 0, 0, 50, 30 };
    v3d_soft_compose(composer, 4, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 0, r2, 1, 255);
    CHECK(fb[0] == src[0] || fb[0] == src[1 * SS + 1], "scaled top-left");
    CHECK((fb[29 * DW + 49] >> 11) >= 29, "scaled bottom-right red=%d", fb[29 * DW + 49] >> 11);
    CHECK((fb[15 * DW + 25] >> 11) >= 13 && (fb[15 * DW + 25] >> 11) <= 18, "scaled middle");

    /* off-screen / clipped rectangles must not write outside the framebuffer (guard pages around it) */
    {
        size_t sz = (size_t)DW * DH * 2;
        u8 *raw = malloc(sz + 8192); memset(raw, 0xAA, sz + 8192);
        u16 *f2 = (u16 *)(raw + 4096);
        handle[8] = (u32)(size_t)f2;
        i32 r3[4] = { -50, -40, 60, 50 };
        v3d_soft_compose(composer, 4, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 0, r3, 1, 255);
        i32 r4[4] = { 200, 300, 400, 500 };
        v3d_soft_compose(composer, 4, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 0, r4, 1, 255);
        int clobber = 0;
        for (int i = 0; i < 4096; i++) if (raw[i] != 0xAA || raw[4096 + sz + i] != 0xAA) clobber++;
        CHECK(clobber == 0, "wrote outside the framebuffer (%d bytes)", clobber);
        handle[8] = (u32)(size_t)fb;
        free(raw);
    }

    /* 8888 premultiplied over 565 with plane alpha */
    memset(fb, 0, DW * DH * 2);
    for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) fb[y * DW + x] = 0xFFFF;          /* white background */
    u32 px[8 * 8]; for (int i = 0; i < 64; i++) px[i] = 0x80000080u;                           /* RGBA bytes: R=0x80? see below */
    /* memory bytes R,G,B,A = 0x80,0,0,0x80 : premultiplied half-transparent red */
    for (int i = 0; i < 64; i++) { u8 *p = (u8 *)&px[i]; p[0] = 0x80; p[1] = 0; p[2] = 0; p[3] = 0x80; }
    i32 r5[4] = { 0, 0, 8, 8 };
    v3d_soft_compose(composer, 1, (u32)(size_t)px, 8, 0, 0, 8, fbits(1.0f), fbits(1.0f), 0, r5, 1, 255);
    u16 got = fb[3 * DW + 3];
    int rr = (got >> 11) & 31, gg = (got >> 5) & 63, bb = got & 31;
    CHECK(rr >= 30 && gg >= 30 && gg <= 32 && bb >= 14 && bb <= 16, "premultiplied blend got r%d g%d b%d (want r~31 g~31 b~15)", rr, gg, bb);
    /* fully transparent leaves the destination alone */
    for (int i = 0; i < 64; i++) { u8 *p = (u8 *)&px[i]; p[0] = p[1] = p[2] = p[3] = 0; }
    fb[3 * DW + 3] = 0x1234;
    v3d_soft_compose(composer, 1, (u32)(size_t)px, 8, 0, 0, 8, fbits(1.0f), fbits(1.0f), 0, r5, 1, 255);
    CHECK(fb[3 * DW + 3] == 0x1234, "transparent pixel changed the screen");

    /* rotation 90 (orientation 1): the corner mapping must move texels the way the table says (top-left <- s=1,t=0 ...) */
    memset(fb, 0, DW * DH * 2);
    i32 r6[4] = { 0, 0, 60, 100 };
    v3d_soft_compose(composer, 4, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 1, r6, 1, 255);
    CHECK(fb[0] != 0 || fb[1] != 0, "rotated layer drew nothing");

    /* bad input is ignored, no crash */
    CHECK(v3d_soft_compose(composer, 99, (u32)(size_t)src, SS, 0, 0, AH, uM, vM, 0, rect, 1, 255) == 0, "bad format");
    CHECK(v3d_soft_compose(composer, 4, 0, SS, 0, 0, AH, uM, vM, 0, rect, 1, 255) == 0, "null addr");

    /* GE buffers */
    u32 v = 1, p = 1;
    CHECK(v3d_soft_alloc(composer, 5000, &v, &p) == 0 && v && v == p, "alloc");
    memset((void *)(size_t)v, 7, 5000);
    CHECK(v3d_soft_free(composer, 5000, &v, &p) == 0 && !v && !p, "free");

    printf(fails ? "FAILED (%d)\n" : "all tests passed\n", fails);
    return fails != 0;
}
