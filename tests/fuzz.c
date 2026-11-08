/* Random QPU programs + random triangles + random render lists: original core vs new core must agree bit for bit
 * (build with -DV3D_FAST_VARY=0). */
#define POOL_SZ (6u << 20)
#include "scene.h"
#include "v3demu_ref.h"
#include "../native/bcmgpu/v3demu_core.h"

struct texd { uint32_t base; int w, h, type; };
static struct texd texs[6]; static int ntex;

static struct qi last_prog[8][512]; static int last_np[8];
static struct qi *g_over; static int g_over_draw = -1, g_over_np;
static int rnd_in(const int *list, int n) { return list[rndn(n)]; }

/* generate one program; returns instruction count, fills unif[] */
static int gen_prog(struct qi *p, uint32_t *unif, int nvary, int allow_bad) {
    int n = 0, nuni = 0, nvr = 0, have_t = 0;
    for (int i = 0; i < 400; i++) unif[i] = rnd();
    /* prologue: define the registers the body may read */
    for (int r = 0; r < 8; r++) { uint32_t v = rnd(); if (rnd() & 1) v = fbits(rndf() * 4.0f - 1.0f); p[n++] = qimm(v, 0, r, 39, 1, 0); }
    for (int r = 0; r < 8; r++) { uint32_t v = rnd(); if (rnd() & 1) v = fbits(rndf() * 4.0f - 1.0f); p[n++] = qimm(v, 1, r, 39, 1, 0); }
    p[n++] = qimm(fbits(0.5f + rndf()), 0, 52, 39, 1, 0);                     /* r4 := 1/x */
    int body = 4 + rndn(28);
    static const int opas[] = { 0,1,2,3,4,5,6,7,8,12,13,14,15,16,17,18,19,20,21,22,23,24,30,31 };
    static const int wspec[] = { 0,1,2,3,4,5,6,7,15,32,33,34,35,37,39,45,46,52,56,57,58,36,38,53,60 };
    static const int rspec[] = { 0,1,2,3,4,5,6,7,15,32,35,38,41,0,1,2,3 };
    int nfifo = 0;
    for (int i = 0; i < body + 3; i++) {
        int ending = (i >= body);                     /* thrend at 'body', then 2 delay slots */
        int sig = 1;
        if (i == body) sig = (rnd() % 4 == 0) ? SIG_ENDTILE : SIG_END;
        else if (!ending) {
            int c = rndn(20);
            if (c == 0) sig = SIG_TILE; else if (c == 1) sig = SIG_LDTMU; else if (c == 2) sig = SIG_SMALL;
            else if (c == 3) sig = rnd_in((const int[]){ 0, 2, 4, 5, 6, 7, 11, 12 }, 8);
            else if (c == 4 && allow_bad) sig = 15;
        } else if (allow_bad && rndn(10) == 0) sig = 15;
        int small = (sig == SIG_SMALL);
        int ra = rnd_in(rspec, sizeof rspec / sizeof *rspec), rb = rnd_in(rspec, sizeof rspec / sizeof *rspec);
        if (rndn(12) == 0) { ra = rndn(64); if (ra >= 8 && ra < 32 && ra != 15) ra = 3; }   /* never read a register nothing wrote: its old value is a leftover of the previous span */
        if (rndn(12) == 0) { rb = rndn(64); if (rb >= 8 && rb < 32 && rb != 15) rb = 3; }
        if (small) rb = rndn(64);
        if ((ra == 35 || (!small && rb == 35)) && nvr >= nvary) { if (ra == 35) ra = 1; if (rb == 35) rb = 2; }
        int opa = rndn(10) ? rnd_in(opas, sizeof opas / sizeof *opas) : (int)rndn(32);
        int opm = rndn(8);
        int am = rndn(8), bm = rndn(8), mam = rndn(8), mbm = rndn(8);
        int wadd = rndn(8) ? rnd_in(wspec, sizeof wspec / sizeof *wspec) : (int)rndn(64);
        int wmul = rndn(8) ? rnd_in(wspec, sizeof wspec / sizeof *wspec) : (int)rndn(64);
        if (rndn(3) == 0) { wadd = 39; opa = 0; }
        if (rndn(3) == 0) { wmul = 39; opm = 0; }
        /* texture sequences are likely */
        if (rndn(6) == 0) { wadd = W_TMU_T; }
        if (rndn(6) == 0) { wmul = W_TMU_S; }
        int ws = rnd() & 1;
        int cadd = rndn(10) ? 1 : (int)rndn(8), cmul = rndn(10) ? 1 : (int)rndn(8);
        int unpack = rndn(3) == 0 ? (int)rndn(8) : 0, pm = rnd() & 1, pack = rndn(3) == 0 ? (int)rndn(16) : 0;
        p[n++] = qalu(sig, unpack, pm, pack, cadd, cmul, ws, wadd, wmul, opm, opa, ra, rb, am, bm, mam, mbm);
        /* simulate to place texture parameter words */
        if (ra == 32 || (!small && rb == 32)) nuni++;
        if (ra == 35 || (!small && rb == 35)) nvr++;
        int add_nop = (opa == 0 && wadd == 39), mul_nop = (opm == 0 && wmul == 39);
        int do_add = !add_nop && cadd != 0, do_mul = !mul_nop && cmul != 0;
        for (int k = 0; k < 2; k++) {
            if (!(k == 0 ? do_add : do_mul)) continue;
            int w = k == 0 ? wadd : wmul;
            if (w == 57) have_t = 1;
            else if (w == 56) {
                if (have_t) {
                    struct texd *t = &texs[rndn(ntex)];
                    int tw = rndn(8) ? t->w : (int)rndn(70), th = rndn(8) ? t->h : (int)rndn(70);
                    uint32_t base = rndn(10) ? t->base : (rndn(2) ? POOL_P + rndn(POOL_SZ) : rnd());
                    int type = rndn(10) ? t->type : (int)rndn(16);
                    unif[nuni] = tex_p0(base, type) | (rndn(4) == 0 ? rndn(16) : 0);
                    unif[nuni + 1] = ((uint32_t)(tw & 2047) << 8) | ((uint32_t)(th & 2047) << 20) | rndn(4) | (rndn(4) << 2) | (rndn(3) == 0 ? (rnd() & 0xf0) : (rndn(2) ? 0x80 : 0));
                    nuni += 2;
                }
                have_t = 0; if (nfifo < 4) nfifo++;
            }
        }
        if (sig == SIG_LDTMU && nfifo) nfifo--;
        if (nuni > 380) nuni = 380;
    }
    return n;
}

static int fuzz_case(int seed, int verbose) {
    rng_s = 0x9e3779b97f4a7c15ull ^ ((uint64_t)seed * 0x2545f4914f6cdd1dull); for (int i = 0; i < 5; i++) rnd();
    struct scene s; scene_init(&s);
    ntex = 0;
    uint32_t t;
    t = make_tex565(&s, 40, 30, 0); texs[ntex++] = (struct texd){ t, 40, 30, 4 };
    t = make_tex8888(&s, 33, 21, 0); texs[ntex++] = (struct texd){ t, 33, 21, 0 };
    t = make_tex565(&s, 16, 16, 1); texs[ntex++] = (struct texd){ t, 16, 16, 4 };
    t = make_tex8888(&s, 64, 64, 1); texs[ntex++] = (struct texd){ t, 64, 64, 1 };
    uint32_t pool_tgt = pool_alloc(&s.m, 100 * 100 * 4);
    uint32_t pool_buf2 = pool_alloc(&s.m, 100 * 100 * 4);
    for (uint32_t i = 0; i < 100 * 100 * 4; i += 4) { wr32(pp(&s, pool_tgt) + i, rnd()); wr32(pp(&s, pool_buf2) + i, rnd()); }
    for (int i = 0; i < FB_BYTES / 4; i++) wr32(s.m.fb + 4 * i, rnd());
    int tw = 33 + rndn(64), th = 33 + rndn(64);
    int fmt = rndn(3) == 0 ? 1 : (rnd() & 1 ? 0 : 2);
    int in_fb = rnd() & 1;
    uint32_t tgt = in_fb ? 0 : pool_tgt;
    int bpp = fmt == 1 ? 4 : 2;
    int allow_bad = rndn(8) == 0;
    int ndraw = 1 + rndn(4);
    for (int di = 0; di < ndraw; di++) {
        static struct qi prog[512]; static uint32_t unif[400];
        int nvary = rndn(9);
        int np = gen_prog(prog, unif, nvary, allow_bad);
        if (di < 8) { memcpy(last_prog[di], prog, np * sizeof(struct qi)); last_np[di] = np; }
        if (g_over && di == g_over_draw && g_over_np == np) memcpy(prog, g_over, np * sizeof(struct qi));
        int ntri = 1 + rndn(5), mode = 4 + rndn(3);
        int nv = mode == 4 ? 3 * ntri : 3 + ntri;
        struct vtx v[40];
        int affine = rnd() & 1;
        for (int i = 0; i < nv; i++) {
            v[i].x16 = (int)rndn(16 * 150) - 16 * 25 + (rndn(4) ? 0 : 7); v[i].y16 = (int)rndn(16 * 150) - 16 * 25 + (rndn(4) ? 0 : 3);
            v[i].invw = affine ? 1.0f : (rndn(10) ? 0.5f + rndf() * 1.5f : (rnd() & 1 ? 0.0f : -1.0f));
            for (int k = 0; k < 8; k++) v[i].vary[k] = rndn(20) ? rndf() * 5.0f - 1.0f : fbits((float)(int)rnd());
            if (rndn(30) == 0) v[i].vary[rndn(8)] = fbits(rnd() | 0x7f800000u);
        }
        if (rndn(3) == 0) { /* axis-aligned quad: the case that matters */
            int x0 = rndn(60), y0 = rndn(60), x1 = x0 + 5 + rndn(70), y1 = y0 + 5 + rndn(70);
            mode = 5; nv = 4; quad(v, x0, y0, x1, y1, rndf(), rndf(), 1 + rndf() * 3, 1 + rndf() * 3);
            if (rndn(3) == 0) for (int i = 0; i < 4; i++) v[i].invw = 0.7f + rndf() * 0.5f;
        }
        int clip_on = rndn(3) == 0;
        struct draw_desc d = { prog, np, unif, 400, nvary, mode, v, nv, { clip_on ? (int)rndn(40) : 0, clip_on ? (int)rndn(40) : 0, clip_on ? 20 + (int)rndn(80) : 0, clip_on ? 20 + (int)rndn(80) : 0 }, { rndn(3) == 0 ? (int)rndn(64) - 32 : 0, rndn(3) == 0 ? (int)rndn(64) - 32 : 0 } };
        scene_add_draw(&s, &d);
    }
    scene_end_bin(&s);
    /* render list */
    {
        uint8_t *rl = malloc(1 << 16); uint32_t n = 0;
#define R8(v) rl[n++] = (v)
#define R16(v) do { R8((v) & 255); R8(((v) >> 8) & 255); } while (0)
#define R32(v) do { R16((v) & 0xffff); R16((uint32_t)(v) >> 16); } while (0)
        uint32_t clear = rnd();
        R8(114); R32(clear); R32(rnd()); R32(0); R8(0);
        R8(113); R32(tgt); R16(tw); R16(th); R8(fmt << 2); R8(0);
        int ntx = (tw + 31) / 32, nty = (th + 31) / 32;
        int ntile = 3 + rndn(14);
        for (int i = 0; i < ntile; i++) {
            int tx = rndn(8) ? rndn(ntx) : rndn(ntx + 1), ty = rndn(8) ? rndn(nty) : rndn(nty + 1);
            int ld = rndn(3);
            uint32_t la = in_fb ? 0 : (rndn(4) ? tgt : pool_buf2); int lf = rndn(4);
            if (in_fb) lf = fmt == 1 ? 0 : 1;                      /* a load / store of the target itself, same pixel size */
            if (ld == 0) { R8(29); R8(1); R8(lf); R32(la); }
            else if (ld == 1 && rndn(3) == 0) { R8(29); R8(rndn(2) ? 1 : 2); R8(lf); R32(la); }
            R8(115); R8(tx); R8(ty);
            if (rndn(12) == 0) { R8(114); R32(rnd()); R32(rnd()); R32(0); R8(0); }
            int nd = rndn(4) ? 1 : (int)rndn(3);
            for (int k = 0; k < nd; k++) { R8(17); R32(0); }
            int st = rndn(5);
            uint32_t sa = in_fb ? 0 : (rndn(5) ? tgt : pool_buf2); int sf = in_fb ? (fmt == 1 ? 0 : 1) : (int)rndn(4);
            if (st == 0) { R8(28); R8(1); R8(sf); R32(sa); }
            else if (st == 1) R8(24);
            else if (st == 2) { R8(28); R8(0); R8(0); R32(0); }
            else if (st == 3 && i == ntile - 1) R8(25);
            if (rndn(20) == 0) { R8(1); }
        }
        R8(0);
        if (getenv("FZR")) { printf("tgt=%08x in_fb=%d fmt=%d %dx%d pool_tgt=%08x buf2=%08x\nrender list:", tgt, in_fb, fmt, tw, th, pool_tgt, pool_buf2);
            for (uint32_t o = 0; o < n;) { int op = rl[o]; int sz = op == 114 ? 14 : op == 113 ? 11 : op == 115 ? 3 : (op == 28 || op == 29) ? 7 : op == 17 ? 5 : 1;
                if (op == 115) printf("\n  T(%d,%d)", rl[o+1], rl[o+2]); else if (op == 29) printf(" LOAD(sel%d fmt%d @%08x)", rl[o+1]&7, rl[o+2], rl[o+3]|(rl[o+4]<<8)|(rl[o+5]<<16)|(rl[o+6]<<24));
                else if (op == 28) printf(" S28(sel%d fmt%d @%08x)", rl[o+1]&7, rl[o+2], rl[o+3]|(rl[o+4]<<8)|(rl[o+5]<<16)|(rl[o+6]<<24)); else if (op == 17) printf(" DRAW"); else if (op == 24) printf(" S24"); else if (op == 25) printf(" S25"); else if (op == 114) printf(" CLEARCOL"); else if (op==113) printf(" CFG");
                else if (op == 0) printf(" HALT"); else printf(" op%d", op); o += sz; } printf("\n"); }
        uint32_t a = pool_alloc(&s.m, n + 16); memcpy(pp(&s, a), rl, n);
        s.rl_s = a; s.rl_e = a + n; s.tgt_fmt = fmt; free(rl);
#undef R8
#undef R16
#undef R32
    }
    uint32_t job[7] = { 3, 0, s.bin_s, s.bin_e, s.rl_s, s.rl_e, 0 };
    if (getenv("FZV")) {
        for (int k = 1; k <= ndraw; k++) {
            struct mem_img a = s.m, b = s.m;
            a.pool = malloc(POOL_SZ); a.fb = malloc(FB_BYTES); b.pool = malloc(POOL_SZ); b.fb = malloc(FB_BYTES);
            memcpy(a.pool, s.m.pool, POOL_SZ); memcpy(b.pool, s.m.pool, POOL_SZ); memcpy(a.fb, s.m.fb, FB_BYTES); memcpy(b.fb, s.m.fb, FB_BYTES);
            struct v3dref_mem m1; memset(&m1, 0, sizeof m1); m1.map = test_map; m1.ctx = &a; m1.debug_max_draws = k;
            struct v3d_mem m2; memset(&m2, 0, sizeof m2); m2.map = test_map; m2.ctx = &b; m2.stage_limit = FB_PHYS_LIMIT; m2.debug_max_draws = k;
            v3dref_exec_job(&m1, job); v3d_exec_job(&m2, job);
            uint8_t *x = in_fb ? a.fb : a.pool + (tgt - POOL_P), *y = in_fb ? b.fb : b.pool + (tgt - POOL_P);
            int first = -1; for (int i = 0; i < 100 * 100 * 4; i++) if (x[i] != y[i]) { first = i; break; }
            printf("  with %d draw(s): %s", k, first < 0 ? "same\n" : "DIFF");
            if (first >= 0) { int px = first / bpp, ty = px / tw, tx = px % tw; printf(" first at byte %d = pixel (%d,%d) ref %02x new %02x\n", first, tx, ty, x[first], y[first]);
                printf("  draw %d program:\n", k - 1); for (int i = 0; i < last_np[k - 1]; i++) printf("   %08x %08x\n", last_prog[k - 1][i].hi, last_prog[k - 1][i].lo); }
            free(a.pool); free(a.fb); free(b.pool); free(b.fb);
            if (first >= 0) break;
        }
    }
    struct mem_img a = s.m, b = s.m;
    a.pool = malloc(POOL_SZ); a.fb = malloc(FB_BYTES); b.pool = malloc(POOL_SZ); b.fb = malloc(FB_BYTES);
    memcpy(a.pool, s.m.pool, POOL_SZ); memcpy(b.pool, s.m.pool, POOL_SZ); memcpy(a.fb, s.m.fb, FB_BYTES); memcpy(b.fb, s.m.fb, FB_BYTES);
    struct v3dref_mem m1; memset(&m1, 0, sizeof m1); m1.map = test_map; m1.ctx = &a;
    struct v3d_mem m2; memset(&m2, 0, sizeof m2); m2.map = test_map; m2.ctx = &b; m2.stage_limit = FB_PHYS_LIMIT;
    v3dref_exec_job(&m1, job); v3d_exec_job(&m2, job);
    int bad = memcmp(a.pool, b.pool, POOL_SZ) != 0 || memcmp(a.fb, b.fb, FB_BYTES) != 0;
    if (bad && verbose) {
        long df = 0, dp = 0; for (uint32_t i = 0; i < FB_BYTES; i++) df += a.fb[i] != b.fb[i]; for (uint32_t i = 0; i < POOL_SZ; i++) dp += a.pool[i] != b.pool[i];
        printf("seed %d: MISMATCH fb %ld bytes pool %ld bytes (tgt in_fb=%d fmt=%d %dx%d draws=%d bad=%d)\n", seed, df, dp, in_fb, fmt, tw, th, ndraw, allow_bad);
    }
    free(a.pool); free(a.fb); free(b.pool); free(b.fb); scene_free(&s);
    return bad;
}

static void reduce(int seed) {
    fuzz_case(seed, 0);
    int dr = -1;
    for (int k = 0; k < 8; k++) { g_over = 0; }
    /* find the draw: try each */
    for (int d = 0; d < 4 && dr < 0; d++) {
        fuzz_case(seed, 0); int np = last_np[d]; if (!np) continue;
        static struct qi cur[512]; memcpy(cur, last_prog[d], np * sizeof(struct qi));
        g_over = cur; g_over_draw = d; g_over_np = np;
        if (!fuzz_case(seed, 0)) { g_over = 0; continue; }
        dr = d;
        for (int i = 0; i < np; i++) {
            struct qi save = cur[i];
            int sig = cur[i].hi >> 28; if (sig == 14 || sig == 15) sig = 1;
            cur[i] = qnop(sig == 3 || sig == 9 ? sig : (sig == 10 || sig == 8 ? sig : 1));
            if (!fuzz_case(seed, 0)) cur[i] = save;
        }
        printf("reduced draw %d (kept instructions):\n", d);
        for (int i = 0; i < np; i++) { struct qi n = qnop(1); if (cur[i].lo != n.lo || cur[i].hi != n.hi) printf("  [%2d] hi %08x lo %08x\n", i, cur[i].hi, cur[i].lo); }
    }
    g_over = 0;
}

int main(int argc, char **argv) {
    if (argc > 2 && !strcmp(argv[1], "reduce")) { reduce(atoi(argv[2])); return 0; }
    int from = argc > 1 ? atoi(argv[1]) : 0, count = argc > 2 ? atoi(argv[2]) : 500, bad = 0;
    for (int i = from; i < from + count; i++) bad += fuzz_case(i, 1);
    printf("fuzz: %d cases, %d mismatches\n", count, bad);
    return bad != 0;
}
