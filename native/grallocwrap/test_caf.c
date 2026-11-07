/* Host test of gralloc_caf.c (sh test_caf.sh): a stand-in for the AOSP module that records its calls, the wrapper
 * built over it, and what libui of a Qualcomm build would call. */
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
struct hw_module; struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module { uint32_t tag; uint16_t module_api_version, hal_api_version; const char *id, *name, *author;
    struct hw_module_methods *methods; void *dso; uint32_t reserved[25]; };
struct hw_device { uint32_t tag, version; struct hw_module *module; uint32_t reserved[12]; int (*close)(struct hw_device *dev); };
struct gralloc_module { struct hw_module common;
    int (*registerBuffer)(const struct gralloc_module *m, const void *handle);
    int (*unregisterBuffer)(const struct gralloc_module *m, const void *handle);
    int (*lock)(const struct gralloc_module *m, const void *handle, int usage, int l, int t, int w, int h, void **vaddr);
    int (*unlock)(const struct gralloc_module *m, const void *handle);
    int (*perform)(const struct gralloc_module *m, int operation, ...); void *more[7]; };
struct alloc_device { struct hw_device common;
    int (*alloc)(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride);
    int (*free)(struct alloc_device *d, const void *handle);
    void (*dump)(struct alloc_device *d, char *buf, int len);
    int (*allocSize)(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride, int size);
    void *reserved_proc[6]; };

#ifdef FAKE
/* the AOSP module: allocSize stays empty, as in hardware/libhardware/modules/gralloc */
int last_w, last_h, last_f, closed, registered, locked;
static struct alloc_device dev;
static int a(struct alloc_device *d, int w, int h, int f, int u, const void **hd, int *st) {
    (void)u; if (d != &dev) return -99; last_w = w; last_h = h; last_f = f; *hd = (void *)0x1234; *st = w; return 0; }
static int fr(struct alloc_device *d, const void *h) { return d == &dev && h == (void *)0x1234 ? 0 : -99; }
static int cl(struct hw_device *d) { closed += d == &dev.common; return 0; }
static int op(const struct hw_module *m, const char *id, struct hw_device **out) {
    (void)m; memset(&dev, 0, sizeof dev); dev.common.close = cl; dev.alloc = a; dev.free = fr;
    if (strcmp(id, "gpu0")) { static struct hw_device fb; *out = &fb; return 0; }
    *out = &dev.common; return 0; }
static struct hw_module_methods ms = { op };
static int rg(const struct gralloc_module *m, const void *h); static int lk(const struct gralloc_module *m, const void *h, int u, int l, int t, int w, int hh, void **va);
struct gralloc_module HMI = { .common = { .tag = 0x484d4f44, .module_api_version = 1, .id = "gralloc", .methods = &ms }, .registerBuffer = rg, .lock = lk };
static int rg(const struct gralloc_module *m, const void *h) { registered += m == &HMI && h == (void *)0x1234; return 0; }
static int lk(const struct gralloc_module *m, const void *h, int u, int l, int t, int w, int hh, void **va) {
    (void)u; (void)l; (void)t; (void)w; (void)hh; locked += m == &HMI && h == (void *)0x1234; *va = (void *)0x5678; return 0; }
#else
static int fails;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); fails++; } } while (0)
int main(int argc, char **argv) {
    (void)argc;
    void *fake = dlopen(argv[1], RTLD_NOW | RTLD_GLOBAL);
    void *wrap = dlopen(argv[2], RTLD_NOW);
    CHECK(fake && wrap);
    if (!fake || !wrap) { printf("%s\n", dlerror()); return 1; }
    struct gralloc_module *m = dlsym(wrap, "HMI");
    int *lw = dlsym(fake, "last_w"), *lh = dlsym(fake, "last_h"), *lf = dlsym(fake, "last_f"), *cl = dlsym(fake, "closed");
    int *rg = dlsym(fake, "registered"), *lk = dlsym(fake, "locked");
    CHECK(m && m->common.tag == 0x484d4f44 && !strcmp(m->common.id, "gralloc") && m->common.module_api_version == 1);
    struct hw_device *d = 0;
    CHECK(m->common.methods->open(&m->common, "gpu0", &d) == 0 && d);
    struct alloc_device *ad = (struct alloc_device *)d;
    CHECK(ad->common.module == &m->common);
    CHECK(ad->allocSize != 0);              /* the slot a Qualcomm libui calls */
    CHECK(ad->dump == 0);                   /* the module has none: libui skips it */
    const void *h = 0; int stride = 0;
    CHECK(ad->alloc(ad, 1080, 1920, 1, 0x933, &h, &stride) == 0 && h == (void *)0x1234 && *lw == 1080 && *lh == 1920 && *lf == 1);
    /* allocSize of the size the buffer has: the same alloc */
    CHECK(ad->allocSize(ad, 1080, 1920, 1, 0x933, &h, &stride, 1080 * 1920 * 4) == 0 && *lh == 1920);
    /* a bigger one: rows enough for it */
    CHECK(ad->allocSize(ad, 100, 10, 1, 0, &h, &stride, 100 * 4 * 25 + 1) == 0 && *lw == 100 && *lh == 26);
    CHECK(ad->allocSize(ad, 100, 10, 4, 0, &h, &stride, 100 * 2 * 30) == 0 && *lh == 30);     /* RGB_565: 2 bytes */
    CHECK(ad->allocSize(ad, 64, 64, 0x32315659, 0, &h, &stride, 1 << 20) == 0 && *lh == 64);  /* YV12: as asked */
    CHECK(ad->free(ad, h) == 0);
    void *va = 0;
    CHECK(m->registerBuffer(m, (void *)0x1234) == 0 && *rg == 1);
    CHECK(m->lock(m, (void *)0x1234, 3, 0, 0, 1, 1, &va) == 0 && va == (void *)0x5678 && *lk == 1);
    CHECK(d->close(d) == 0 && *cl == 1);
    struct hw_device *fb = 0;
    CHECK(m->common.methods->open(&m->common, "fb0", &fb) == 0 && fb && fb->close == 0);     /* the module's own */
    printf(fails ? "%d failed\n" : "gralloc_caf: all checks passed\n", fails);
    return fails != 0;
}
#endif
