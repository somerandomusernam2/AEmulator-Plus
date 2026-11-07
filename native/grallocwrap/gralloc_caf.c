/*
 * gralloc.default.so of the 4.4 engine (32-bit firmware, 4.x-7.x) for a libui built for Qualcomm's gralloc: CAF and
 * CyanogenMod builds of the msm8960 generation (QCOM_BSP_LEGACY), e.g. MIUI 9 for the Xiaomi Mi 2 (5.0.2).
 *
 * Their alloc_device_t has one more call in the first of its reserved slots:
 *   int (*allocSize)(alloc_device_t *dev, int w, int h, int format, int usage, buffer_handle_t *handle, int *stride,
 *                    int bufferSize);
 * and GraphicBuffer uses it for a buffer of a size of its own. AOSP's module (the engine's) leaves the slot empty, so
 * SurfaceFlinger jumped to address 0 in a binder thread (pc 0, lr in libui.so, r1-r3 = width, height, format): the
 * screen stayed black and the boot never ended.
 *
 * This module hands everything to the module that was there, kept as /system/lib/libgralloc_aosp.so, and gives the
 * allocator that call: the real one when the module has it, otherwise an ordinary alloc with rows enough for
 * bufferSize bytes. TreeFixer installs it only where the firmware's libui.so names allocSize.
 */
#include <stdint.h>

extern void *dlopen(const char *path, int flags);
extern void *dlsym(void *h, const char *name);
extern void *malloc(unsigned long n);
extern void free(void *p);

#define EXPORT __attribute__((visibility("default")))
#ifndef REAL_PATH
#define REAL_PATH "/system/lib/libgralloc_aosp.so"
#endif

struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {
    uint32_t tag;
    uint16_t module_api_version, hal_api_version;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
    uint32_t reserved[25];
};
struct hw_device {
    uint32_t tag, version;
    struct hw_module *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device *dev);
};
struct gralloc_module {
    struct hw_module common;
    int (*registerBuffer)(const struct gralloc_module *m, const void *handle);
    int (*unregisterBuffer)(const struct gralloc_module *m, const void *handle);
    int (*lock)(const struct gralloc_module *m, const void *handle, int usage, int l, int t, int w, int h, void **vaddr);
    int (*unlock)(const struct gralloc_module *m, const void *handle);
    int (*perform)(const struct gralloc_module *m, int operation, ...);
    void *more[7];      /* lock_ycbcr, the async calls, reserved: none in the AOSP module */
};
struct alloc_device {
    struct hw_device common;
    int (*alloc)(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride);
    int (*free)(struct alloc_device *d, const void *handle);
    void (*dump)(struct alloc_device *d, char *buf, int len);
    int (*allocSize)(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride, int size);
    void *reserved_proc[6];
};
struct wrap_device {
    struct alloc_device dev;
    struct alloc_device *real;
};

#define HARDWARE_MODULE_TAG 0x484d4f44

static const struct gralloc_module *real;

static int w_register(const struct gralloc_module *m, const void *h) { (void)m; return real ? real->registerBuffer(real, h) : -22; }
static int w_unregister(const struct gralloc_module *m, const void *h) { (void)m; return real ? real->unregisterBuffer(real, h) : -22; }
static int w_lock(const struct gralloc_module *m, const void *h, int usage, int l, int t, int w, int hh, void **va) {
    (void)m; return real ? real->lock(real, h, usage, l, t, w, hh, va) : -22;
}
static int w_unlock(const struct gralloc_module *m, const void *h) { (void)m; return real ? real->unlock(real, h) : -22; }

static struct alloc_device *real_of(struct alloc_device *d) { return ((struct wrap_device *)d)->real; }

static int d_alloc(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride) {
    struct alloc_device *r = real_of(d);
    return r->alloc(r, w, h, format, usage, handle, stride);
}
static int d_free(struct alloc_device *d, const void *handle) { struct alloc_device *r = real_of(d); return r->free(r, handle); }
static void d_dump(struct alloc_device *d, char *buf, int len) { struct alloc_device *r = real_of(d); if (r->dump) r->dump(r, buf, len); }

/* bytes per pixel of the RGB formats the AOSP module allocates (RGBA, RGBX, RGB, RGB_565, BGRA) */
static unsigned bpp(int f) { return f == 4 ? 2u : f == 3 ? 3u : 4u; }

static int d_alloc_size(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride, int size) {
    struct alloc_device *r = real_of(d);
    if (r->allocSize) return r->allocSize(r, w, h, format, usage, handle, stride, size);
    if (size > 0 && w > 0 && h > 0 && w <= 16384 && format >= 1 && format <= 5) {
        /* (the module sizes a buffer by its rows: as many as the asked size needs; no division helper on ARMv7) */
        uint64_t row = (uint64_t)(unsigned)w * bpp(format), have = row * (unsigned)h;
        while (have < (uint64_t)(unsigned)size && h < 65536) { h++; have += row; }
    }
    return r->alloc(r, w, h, format, usage, handle, stride);
}

static int d_close(struct hw_device *dev) {
    struct wrap_device *wd = (struct wrap_device *)dev;
    int e = wd->real->common.close(&wd->real->common);
    free(wd);
    return e;
}

static int w_open(const struct hw_module *m, const char *id, struct hw_device **out) {
    if (!real) return -22;
    struct hw_device *dev = 0;
    int e = real->common.methods->open(&real->common, id, &dev);
    if (e || !dev) return e ? e : -22;
    /* "gpu0" is the allocator; the framebuffer device ("fb0") stays the module's own */
    if (!(id[0] == 'g' && id[1] == 'p' && id[2] == 'u' && id[3] == '0' && !id[4])) { *out = dev; return 0; }
    struct wrap_device *wd = malloc(sizeof *wd);
    if (!wd) { *out = dev; return 0; }
    /* (word by word: a struct copy becomes __aeabi_memcpy4, which the libc of 4.x does not have) */
    const volatile uint32_t *from = (const volatile uint32_t *)dev;
    uint32_t *to = (uint32_t *)&wd->dev;
    for (unsigned i = 0; i < sizeof(struct alloc_device) / sizeof(uint32_t); i++) to[i] = from[i];
    wd->real = (struct alloc_device *)dev;
    wd->dev.common.module = (struct hw_module *)m;
    wd->dev.common.close = d_close;
    wd->dev.alloc = d_alloc;
    wd->dev.free = d_free;
    wd->dev.dump = wd->real->dump ? d_dump : 0;
    wd->dev.allocSize = d_alloc_size;
    *out = &wd->dev.common;
    return 0;
}

static struct hw_module_methods methods = { w_open };

EXPORT struct gralloc_module HMI = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = 1,
        .hal_api_version = 0,
        .id = "gralloc",
        .name = "AEmulator gralloc (AOSP default + allocSize)",
        .author = "AEmulator",
        .methods = &methods,
    },
    .registerBuffer = w_register,
    .unregisterBuffer = w_unregister,
    .lock = w_lock,
    .unlock = w_unlock,
};

__attribute__((constructor)) static void init(void) {
    void *h = dlopen(REAL_PATH, 2 /* RTLD_NOW */);
    real = h ? dlsym(h, "HMI") : 0;
    if (real) {
        HMI.common.module_api_version = real->common.module_api_version;
        HMI.common.hal_api_version = real->common.hal_api_version;
    }
}
