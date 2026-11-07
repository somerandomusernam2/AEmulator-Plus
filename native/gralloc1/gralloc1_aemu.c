/*
 * gralloc1_aemu.c -- minimal, self-contained Gralloc1 module for the 32-bit MT8696 guest.
 *
 * Built as vendor/lib/hw/gralloc.default.so (ARMv7).  Replaces the stock AOSP gralloc0 module, which only
 * accepts the device names "gpu0"/"fb0" -- so android.hardware.graphics.allocator-service's
 *     hw_get_module("gralloc"); module->methods->open(module, "gralloc", &dev)
 * failed ("IMGAidlAllocator: init:27 Failed to open gralloc") and the service exited, leaving SurfaceFlinger
 * waiting forever for IAllocator/default.
 *
 * What the clients actually call (verified by disassembling the shipped binaries, not guessed):
 *
 *   allocator-service   2 createDescriptor   3 destroyDescriptor   4 setConsumerUsage   5 setDimensions
 *                       6 setFormat          7 setProducerUsage    13 getStride        14 allocate
 *                       16 release           0x45f IMG: (dev, desc64, const char *name, uint64 reservedSize)
 *   mapper@4.0-impl     15 retain  16 release  18 lock  20 unlock  23 validateBufferSize  24 getTransportSize
 *                       2-7,13,14 (isSupported: allocate(dev, 1, &desc, NULL) == test allocate)
 *                       0x454 getReservedRegion  0x455 free metadata  0x45a set metadata  0x45b get metadata
 *                       0x45c flush  0x45d reread  0x45e dump
 *
 * Neither client NULL-checks what getFunction() returns, so getFunction() NEVER returns NULL for anything in the
 * ranges they use: unimplemented ids get a stub that returns GRALLOC1_ERROR_UNSUPPORTED instead of a crash.
 *
 * Buffers are memfd-backed.  A buffer handle is a native_handle_t with ONE fd (the memfd) and AEMU_NUM_INTS ints;
 * the 64-bit buffer id sits at byte offset 0x18 of the handle, where the mapper reads it.  All process-local state
 * (reference counts, mappings) lives in a table keyed by the handle pointer; the handle itself carries only
 * immutable data, so it can be cloned across processes (the mapper's importBuffer clones it with dup()+memcpy()).
 *
 * The device advertises GRALLOC1_CAPABILITY_RELEASE_IMPLY_DELETE: the last release() closes the fd and frees the
 * handle.  The mapper depends on that -- it never closes or frees the handle it cloned.
 *
 * Plain C, no libstdc++, no libgralloc/PowerVR kernel driver.
 */
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <poll.h>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/syscall.h>

#define EXPORT __attribute__((visibility("default")))

/* hw_get_module() does dlsym(handle, "HMI"): hardware.h defines HAL_MODULE_INFO_SYM as HMI (and
 * HAL_MODULE_INFO_SYM_AS_STR as "HMI").  This file is built without the AOSP headers, so the macro has to be
 * defined here; without it the module was exported under the literal name "HAL_MODULE_INFO_SYM", the loader logged
 *     HAL: load: couldn't find symbol HMI
 * hw_get_module("gralloc") failed, and the allocator service exited with status 1. */
#ifndef HAL_MODULE_INFO_SYM
#define HAL_MODULE_INFO_SYM HMI
#endif
#define HAL_MODULE_INFO_SYM_AS_STR "HMI"

#define LOG_TAG "gralloc1_aemu"
extern int __android_log_print(int prio, const char *tag, const char *fmt, ...) __attribute__((weak));
#define LOGE(...) do { if (__android_log_print) __android_log_print(6, LOG_TAG, __VA_ARGS__); } while (0)

#ifndef __NR_memfd_create
#define __NR_memfd_create 385 /* ARM EABI */
#endif
#define AEMU_MFD_CLOEXEC 1u

/* ARM EABI raw syscalls for the two calls that take an off_t.  This module has been built with a 64-bit off_t but
 * linked against bionic's 32-bit-off_t ftruncate()/mmap(): the compiler passed the length in r2:r3 (and the mmap
 * offset at [sp+8]) while bionic read r1 (the leftover memfd_create flags, i.e. a 1-byte memfd) and [sp+4]; any write
 * past the first page of a buffer then died with SIGBUS (BUS_ADRERR) under qemu-user.  The kernel ABI does not depend
 * on _FILE_OFFSET_BITS, so go to it directly. */
#ifndef __NR_ftruncate64
#define __NR_ftruncate64 194
#endif
#ifndef __NR_mmap2
#define __NR_mmap2 192
#endif
static int aemu_ftruncate(int fd, uint64_t len) {
    /* ftruncate64(fd, pad, len_lo, len_hi): the 64-bit argument starts on an even register */
    return (int)syscall(__NR_ftruncate64, fd, 0, (uint32_t)len, (uint32_t)(len >> 32));
}
static void *aemu_map_shared(size_t len, int fd) {
    long r = syscall(__NR_mmap2, 0, len, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    return (r < 0 && r > -4096) ? MAP_FAILED : (void *)r;
}

/* ------------------------------------------------------------------ ABI (hardware.h / gralloc1.h / native_handle.h)
 * Declared here so the file builds with nothing but the NDK; the test build static-asserts every one of these
 * against the real AOSP headers. */
struct hw_module_t;
struct hw_device_t;

typedef struct hw_module_methods_t {
    int (*open)(const struct hw_module_t *module, const char *id, struct hw_device_t **device);
} hw_module_methods_t;

typedef struct hw_module_t {
    uint32_t tag;
    uint16_t module_api_version;
    uint16_t hal_api_version;
    const char *id;
    const char *name;
    const char *author;
    hw_module_methods_t *methods;
    void *dso;
    uint32_t reserved[32 - 7];
} hw_module_t;

typedef struct hw_device_t {
    uint32_t tag;
    uint32_t version;
    struct hw_module_t *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device_t *device);
} hw_device_t;

typedef struct native_handle {
    int version;  /* sizeof(native_handle_t) */
    int numFds;
    int numInts;
    int data[0];
} native_handle_t;
typedef const native_handle_t *buffer_handle_t;

typedef uint64_t gralloc1_buffer_descriptor_t;
typedef uint64_t gralloc1_backing_store_t;

typedef struct gralloc1_rect { int32_t left, top, width, height; } gralloc1_rect_t;

typedef struct gralloc1_buffer_descriptor_info {
    uint32_t width;
    uint32_t height;
    uint32_t layerCount;
    int32_t format;
    uint64_t producerUsage;
    uint64_t consumerUsage;
} gralloc1_buffer_descriptor_info_t;

typedef void (*gralloc1_function_pointer_t)(void);

typedef struct gralloc1_device {
    struct hw_device_t common;
    void (*getCapabilities)(struct gralloc1_device *device, uint32_t *outCount, int32_t *outCapabilities);
    gralloc1_function_pointer_t (*getFunction)(struct gralloc1_device *device, int32_t descriptor);
} gralloc1_device_t;

#define HW_TAG(a, b, c, d) (((uint32_t)(a) << 24) | ((uint32_t)(b) << 16) | ((uint32_t)(c) << 8) | (uint32_t)(d))
#define HARDWARE_MODULE_TAG HW_TAG('H', 'W', 'M', 'T')
#define HARDWARE_DEVICE_TAG HW_TAG('H', 'W', 'D', 'T')
#define API_VERSION_1_0 0x0100u

enum {
    G1_CAP_TEST_ALLOCATE = 1,
    G1_CAP_RELEASE_IMPLY_DELETE = 3
};

enum {
    G1_FN_CREATE_DESCRIPTOR = 2, G1_FN_DESTROY_DESCRIPTOR = 3, G1_FN_SET_CONSUMER_USAGE = 4,
    G1_FN_SET_DIMENSIONS = 5, G1_FN_SET_FORMAT = 6, G1_FN_SET_PRODUCER_USAGE = 7,
    G1_FN_GET_BACKING_STORE = 8, G1_FN_GET_CONSUMER_USAGE = 9, G1_FN_GET_DIMENSIONS = 10,
    G1_FN_GET_FORMAT = 11, G1_FN_GET_PRODUCER_USAGE = 12, G1_FN_GET_STRIDE = 13, G1_FN_ALLOCATE = 14,
    G1_FN_RETAIN = 15, G1_FN_RELEASE = 16, G1_FN_LOCK = 18, G1_FN_UNLOCK = 20,
    G1_FN_SET_LAYER_COUNT = 21, G1_FN_GET_LAYER_COUNT = 22, G1_FN_VALIDATE_BUFFER_SIZE = 23,
    G1_FN_GET_TRANSPORT_SIZE = 24, G1_FN_IMPORT_BUFFER = 25
};

/* IMG (PowerVR) vendor function ids, as requested by the allocator service and the IMG mapper. */
enum {
    IMG_FN_GET_RESERVED_REGION = 0x454, IMG_FN_FREE_METADATA = 0x455, IMG_FN_SET_METADATA = 0x45a,
    IMG_FN_GET_METADATA = 0x45b, IMG_FN_FLUSH = 0x45c, IMG_FN_REREAD = 0x45d, IMG_FN_DUMP = 0x45e,
    IMG_FN_SET_NAME_RESERVED = 0x45f
};

enum {
    G1_OK = 0, G1_BAD_DESCRIPTOR = 1, G1_BAD_HANDLE = 2, G1_BAD_VALUE = 3, G1_NOT_SHARED = 4,
    G1_NO_RESOURCES = 5, G1_UNDEFINED = 6, G1_UNSUPPORTED = 7
};

/* android_pixel_format_t / graphics-base */
enum {
    PF_RGBA_8888 = 1, PF_RGBX_8888 = 2, PF_RGB_888 = 3, PF_RGB_565 = 4, PF_BGRA_8888 = 5,
    PF_YCBCR_422_SP = 0x10, PF_YCRCB_420_SP = 0x11, PF_YCBCR_422_I = 0x14, PF_RGBA_FP16 = 0x16,
    PF_RAW16 = 0x20, PF_BLOB = 0x21, PF_IMPLEMENTATION_DEFINED = 0x22, PF_YCBCR_420_888 = 0x23,
    PF_RGBA_1010102 = 0x2B, PF_YV12 = 0x32315659, PF_Y8 = 0x20203859, PF_Y16 = 0x20363159,
    PF_YCBCR_P010 = 0x36, PF_R_8 = 0x38
};

/* ------------------------------------------------------------------ the buffer handle */
#define AEMU_MAGIC 0x55454d41u /* "AMEU" little-endian */
#define AEMU_LAYOUT 1u

struct aemu_handle {         /* native_handle_t, 1 fd, AEMU_NUM_INTS ints; byte offsets in comments */
    int32_t version;         /* 0x00  sizeof(native_handle_t) == 12 */
    int32_t numFds;          /* 0x04  1 */
    int32_t numInts;         /* 0x08  AEMU_NUM_INTS */
    int32_t fd;              /* 0x0c  the memfd */
    uint32_t magic;          /* 0x10 */
    uint32_t layout;         /* 0x14 */
    uint32_t id_lo, id_hi;   /* 0x18  64-bit buffer id, read by the IMG mapper at handle+0x18 */
    uint32_t width, height;  /* 0x20 */
    int32_t format;          /* 0x28 */
    uint32_t layers;         /* 0x2c */
    uint32_t stride;         /* 0x30  pixels */
    uint32_t size;           /* 0x34  bytes mapped (whole memfd) */
    uint32_t img_size;       /* 0x38  bytes of pixel data */
    uint32_t rsv_off;        /* 0x3c  reserved region (offset, bytes) */
    uint32_t rsv_size;       /* 0x40 */
    uint32_t prod_lo, prod_hi; /* 0x44 producer usage */
    uint32_t cons_lo, cons_hi; /* 0x4c consumer usage */
};
#define AEMU_NUM_INTS ((int)((sizeof(struct aemu_handle) - 12) / 4) - 1)

_Static_assert(offsetof(struct aemu_handle, fd) == 0x0c, "fd is data[0]");
_Static_assert(offsetof(struct aemu_handle, id_lo) == 0x18, "the IMG mapper reads the 64-bit id at handle+0x18");
_Static_assert(sizeof(struct aemu_handle) == 12 + 4 * 18, "1 fd + 17 ints");

static inline uint64_t h_id(const struct aemu_handle *h) { return ((uint64_t)h->id_hi << 32) | h->id_lo; }

/* ------------------------------------------------------------------ process-local state */
#define HASH_BUCKETS 256u /* power of two */

struct buf {
    struct buf *next;
    const struct aemu_handle *h;
    uint32_t ref;
    uint32_t locks;
    void *map;
    size_t maplen;
};

struct desc {
    struct desc *next;
    uint64_t id;
    uint32_t width, height, layers;
    int32_t format;
    uint64_t prod, cons;
    uint64_t rsv;
    char name[40];
};

static pthread_mutex_t g_mu; /* zero-initialised == PTHREAD_MUTEX_INITIALIZER on bionic and glibc */
static struct buf *g_bufs[HASH_BUCKETS];
static struct desc *g_descs[HASH_BUCKETS];
static uint32_t g_buf_counter;
static uint64_t g_desc_counter;

static inline uint32_t hash_ptr(const void *p) {
    uintptr_t v = (uintptr_t)p;
    return (uint32_t)((v >> 4) ^ (v >> 12)) & (HASH_BUCKETS - 1);
}
static inline uint32_t hash_id(uint64_t id) { return (uint32_t)(id ^ (id >> 32)) & (HASH_BUCKETS - 1); }

/* all of these: g_mu held */
static struct buf *buf_find(const void *p) {
    struct buf *b;
    for (b = g_bufs[hash_ptr(p)]; b; b = b->next) if ((const void *)b->h == p) return b;
    return NULL;
}
static void buf_insert(struct buf *b) {
    uint32_t i = hash_ptr(b->h);
    b->next = g_bufs[i];
    g_bufs[i] = b;
}
static void buf_remove(struct buf *b) {
    struct buf **pp;
    for (pp = &g_bufs[hash_ptr(b->h)]; *pp; pp = &(*pp)->next)
        if (*pp == b) { *pp = b->next; return; }
}
static struct desc *desc_find(uint64_t id) {
    struct desc *d;
    for (d = g_descs[hash_id(id)]; d; d = d->next) if (d->id == id) return d;
    return NULL;
}

#define LOCK() pthread_mutex_lock(&g_mu)
#define UNLOCK() pthread_mutex_unlock(&g_mu)

/* ------------------------------------------------------------------ pixel layout */
#define ALIGN(x, a) (((x) + ((a) - 1)) & ~(uint64_t)((a) - 1))
#define MAX_DIM 16384u
#define MAX_BYTES (256ull << 20)

/* Fills *stride (pixels) and *size (bytes of pixel data).  Returns G1_OK, G1_UNSUPPORTED or G1_BAD_VALUE. */
static int32_t compute_layout(int32_t fmt, uint32_t w, uint32_t h, uint32_t *stride, uint64_t *size) {
    uint64_t s, sz;
    uint32_t bpp = 0;
    if (!w || !h || w > MAX_DIM || h > MAX_DIM) return G1_BAD_VALUE;
    switch (fmt) {
    case PF_RGBA_8888: case PF_RGBX_8888: case PF_BGRA_8888: case PF_RGBA_1010102:
    case PF_IMPLEMENTATION_DEFINED: bpp = 4; break;
    case PF_RGB_888: bpp = 3; break;
    case PF_RGB_565: case PF_RAW16: case PF_Y16: case PF_YCBCR_422_I: bpp = 2; break;
    case PF_RGBA_FP16: bpp = 8; break;
    case PF_Y8: case PF_R_8: bpp = 1; break;
    default: break;
    }
    if (bpp) {
        s = ALIGN(w, 16);
        sz = s * h * bpp;
    } else switch (fmt) {
    case PF_BLOB:
        s = w; sz = (uint64_t)w * h; break;
    case PF_YV12: case PF_YCBCR_420_888: {
        uint64_t c = ALIGN(s = ALIGN(w, 16), 32) >> 1; /* chroma stride: align(ystride/2, 16) */
        c = ALIGN(s >> 1, 16);
        sz = s * h + 2 * c * (((uint64_t)h + 1) >> 1);
        break;
    }
    case PF_YCRCB_420_SP: s = ALIGN(w, 16); sz = s * h + s * (((uint64_t)h + 1) >> 1); break;
    case PF_YCBCR_422_SP: s = ALIGN(w, 16); sz = s * h * 2; break;
    case PF_YCBCR_P010: s = ALIGN(w, 16); sz = s * h * 2 + s * 2 * (((uint64_t)h + 1) >> 1); break;
    default: return G1_UNSUPPORTED;
    }
    if (sz > MAX_BYTES) return G1_NO_RESOURCES;
    *stride = (uint32_t)s;
    *size = sz;
    return G1_OK;
}

/* ------------------------------------------------------------------ descriptors */
static int32_t d_create(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t *out) {
    struct desc *d;
    uint32_t i;
    (void)dev;
    if (!out) return G1_BAD_VALUE;
    d = (struct desc *)calloc(1, sizeof(*d));
    if (!d) return G1_NO_RESOURCES;
    d->layers = 1;
    LOCK();
    d->id = ++g_desc_counter;
    i = hash_id(d->id);
    d->next = g_descs[i];
    g_descs[i] = d;
    UNLOCK();
    *out = d->id;
    return G1_OK;
}

static int32_t d_destroy(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id) {
    struct desc **pp, *d = NULL;
    (void)dev;
    LOCK();
    for (pp = &g_descs[hash_id(id)]; *pp; pp = &(*pp)->next)
        if ((*pp)->id == id) { d = *pp; *pp = d->next; break; }
    UNLOCK();
    if (!d) return G1_BAD_DESCRIPTOR;
    free(d);
    return G1_OK;
}

#define WITH_DESC(id, d) \
    struct desc *d; \
    (void)dev; \
    LOCK(); \
    d = desc_find(id); \
    if (!d) { UNLOCK(); return G1_BAD_DESCRIPTOR; }
#define END_DESC() UNLOCK(); return G1_OK

static int32_t d_set_consumer(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id, uint64_t u) {
    WITH_DESC(id, d); d->cons = u; END_DESC();
}
static int32_t d_set_producer(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id, uint64_t u) {
    WITH_DESC(id, d); d->prod = u; END_DESC();
}
static int32_t d_set_dimensions(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id, uint32_t w, uint32_t h) {
    WITH_DESC(id, d); d->width = w; d->height = h; END_DESC();
}
static int32_t d_set_format(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id, int32_t f) {
    WITH_DESC(id, d); d->format = f; END_DESC();
}
static int32_t d_set_layers(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id, uint32_t n) {
    WITH_DESC(id, d); d->layers = n; END_DESC();
}

/* IMG vendor 0x45f: (dev, desc64, const char *name, uint64 reservedSize) */
static int32_t d_set_name_reserved(gralloc1_device_t *dev, gralloc1_buffer_descriptor_t id, const char *name,
                                   uint64_t reserved) {
    WITH_DESC(id, d);
    if (name) {
        strncpy(d->name, name, sizeof(d->name) - 1);
        d->name[sizeof(d->name) - 1] = 0;
    } else {
        d->name[0] = 0;
    }
    d->rsv = reserved;
    END_DESC();
}

/* ------------------------------------------------------------------ allocation */
static int mk_memfd(const char *name, size_t size) {
    char nm[64] = "aemu-gralloc:";
    int fd;
    strncat(nm, name && *name ? name : "buf", sizeof(nm) - strlen(nm) - 1);
    fd = (int)syscall(__NR_memfd_create, nm, AEMU_MFD_CLOEXEC);
    if (fd < 0) { LOGE("memfd_create failed"); return -1; }
    if (aemu_ftruncate(fd, (uint64_t)size) != 0) { LOGE("ftruncate(%zu) failed", size); close(fd); return -1; }
    return fd;
}

/* Validates a descriptor and returns its pixel layout.  g_mu NOT held; takes a copy. */
static int32_t desc_snapshot(uint64_t id, struct desc *copy, uint32_t *stride, uint64_t *img) {
    struct desc *d;
    int32_t err;
    LOCK();
    d = desc_find(id);
    if (d) *copy = *d;
    UNLOCK();
    if (!d) return G1_BAD_DESCRIPTOR;
    if (copy->layers > 1) return G1_UNSUPPORTED; /* no LAYERED_BUFFERS capability */
    err = compute_layout(copy->format, copy->width, copy->height, stride, img);
    if (err == G1_BAD_VALUE) return G1_BAD_VALUE;
    return err;
}

static int32_t alloc_one(const struct desc *d, uint32_t stride, uint64_t img, buffer_handle_t *out) {
    struct aemu_handle *h;
    struct buf *b;
    uint64_t rsv = d->rsv, rsv_off, total;
    uint32_t i;
    int fd;
    if (rsv > MAX_BYTES) return G1_NO_RESOURCES;
    rsv_off = ALIGN(img, 64);
    total = ALIGN(rsv_off + rsv, 4096);
    if (total > MAX_BYTES) return G1_NO_RESOURCES;
    fd = mk_memfd(d->name, (size_t)total);
    if (fd < 0) return G1_NO_RESOURCES;
    h = (struct aemu_handle *)calloc(1, sizeof(*h));
    b = (struct buf *)calloc(1, sizeof(*b));
    if (!h || !b) { free(h); free(b); close(fd); return G1_NO_RESOURCES; }
    h->version = (int32_t)sizeof(native_handle_t);
    h->numFds = 1;
    h->numInts = AEMU_NUM_INTS;
    h->fd = fd;
    h->magic = AEMU_MAGIC;
    h->layout = AEMU_LAYOUT;
    h->width = d->width; h->height = d->height; h->format = d->format; h->layers = 1;
    h->stride = stride;
    h->size = (uint32_t)total;
    h->img_size = (uint32_t)img;
    h->rsv_off = (uint32_t)rsv_off;
    h->rsv_size = (uint32_t)rsv;
    h->prod_lo = (uint32_t)d->prod; h->prod_hi = (uint32_t)(d->prod >> 32);
    h->cons_lo = (uint32_t)d->cons; h->cons_hi = (uint32_t)(d->cons >> 32);
    b->h = h;
    b->ref = 1;
    LOCK();
    {   /* same scheme as android::GraphicBuffer ids: unique per process, and across processes by pid */
        uint64_t id = ((uint64_t)(uint32_t)getpid() << 32) | (uint64_t)(++g_buf_counter);
        h->id_lo = (uint32_t)id; h->id_hi = (uint32_t)(id >> 32);
    }
    i = hash_ptr(h);
    b->next = g_bufs[i];
    g_bufs[i] = b;
    UNLOCK();
    *out = (buffer_handle_t)h;
    return G1_OK;
}

static void buf_free(struct buf *b) { /* b already unlinked */
    if (b->map) munmap(b->map, b->maplen);
    close(b->h->fd);
    free((void *)b->h);
    free(b);
}

static int32_t g_release(gralloc1_device_t *dev, buffer_handle_t buffer);

static int32_t g_allocate(gralloc1_device_t *dev, uint32_t n, const gralloc1_buffer_descriptor_t *descs,
                          buffer_handle_t *out) {
    uint32_t k;
    int32_t err = G1_OK;
    (void)dev;
    if (!n || !descs) return G1_BAD_VALUE;
    for (k = 0; k < n; k++) {
        struct desc d;
        uint32_t stride;
        uint64_t img;
        err = desc_snapshot(descs[k], &d, &stride, &img);
        if (err == G1_OK && !out) continue; /* test allocate: allocate(dev, n, descs, NULL) */
        if (err == G1_OK) err = alloc_one(&d, stride, img, &out[k]);
        if (err != G1_OK) {
            while (k--) { g_release(dev, out[k]); out[k] = NULL; }
            return err;
        }
    }
    return (out && n > 1) ? G1_NOT_SHARED : G1_OK; /* every buffer has its own backing store */
}

/* ------------------------------------------------------------------ handle validation */
static int handle_ok(const struct aemu_handle *h) {
    return h && h->version == (int32_t)sizeof(native_handle_t) && h->numFds == 1 && h->numInts == AEMU_NUM_INTS &&
           h->fd >= 0 && h->magic == AEMU_MAGIC && h->layout == AEMU_LAYOUT && h->size >= 4096 &&
           h->img_size <= h->size;
}

/* Returns the process-local record of a handle (or NULL); g_mu held. */
#define FIND(buffer, b) \
    struct buf *b; \
    (void)dev; \
    LOCK(); \
    b = (buffer) ? buf_find(buffer) : NULL; \
    if (!b) { UNLOCK(); return G1_BAD_HANDLE; }

static int32_t g_retain(gralloc1_device_t *dev, buffer_handle_t buffer) {
    struct buf *b;
    (void)dev;
    if (!buffer) return G1_BAD_HANDLE;
    LOCK();
    b = buf_find(buffer);
    if (b) {                            /* ours, or already retained in this process */
        b->ref++;
        UNLOCK();
        return G1_OK;
    }
    UNLOCK();
    if (!handle_ok((const struct aemu_handle *)buffer)) {
        LOGE("retain: foreign handle %p rejected", (const void *)buffer);
        return G1_BAD_HANDLE;
    }
    b = (struct buf *)calloc(1, sizeof(*b));    /* first reference in this process: a cloned handle */
    if (!b) return G1_NO_RESOURCES;
    b->h = (const struct aemu_handle *)buffer;
    b->ref = 1;
    LOCK();
    if (buf_find(buffer)) {                      /* raced with another retain of the same pointer */
        buf_find(buffer)->ref++;
        UNLOCK();
        free(b);
        return G1_OK;
    }
    buf_insert(b);
    UNLOCK();
    return G1_OK;
}

static int32_t g_release(gralloc1_device_t *dev, buffer_handle_t buffer) {
    struct buf *b;
    (void)dev;
    if (!buffer) return G1_BAD_HANDLE;
    LOCK();
    b = buf_find(buffer);
    if (!b) { UNLOCK(); return G1_BAD_HANDLE; }
    if (--b->ref) { UNLOCK(); return G1_OK; }
    buf_remove(b);
    UNLOCK();
    buf_free(b);                        /* RELEASE_IMPLY_DELETE: close the fd, delete the handle */
    return G1_OK;
}

/* IMPORT_BUFFER: clone a handle received from another process and retain the clone. */
static int32_t g_import(gralloc1_device_t *dev, buffer_handle_t raw, buffer_handle_t *out) {
    struct aemu_handle *c;
    int32_t err;
    if (!raw || !out) return G1_BAD_VALUE;
    if (!handle_ok((const struct aemu_handle *)raw)) return G1_BAD_HANDLE;
    c = (struct aemu_handle *)malloc(sizeof(*c));
    if (!c) return G1_NO_RESOURCES;
    memcpy(c, raw, sizeof(*c));
    c->fd = dup(((const struct aemu_handle *)raw)->fd);
    if (c->fd < 0) { free(c); return G1_NO_RESOURCES; }
    err = g_retain(dev, (buffer_handle_t)c);
    if (err != G1_OK) { close(c->fd); free(c); return err; }
    *out = (buffer_handle_t)c;
    return G1_OK;
}

/* ------------------------------------------------------------------ queries */
static int32_t g_get_backing_store(gralloc1_device_t *dev, buffer_handle_t buffer, gralloc1_backing_store_t *o) {
    FIND(buffer, b);
    if (o) *o = h_id(b->h);
    UNLOCK();
    return o ? G1_OK : G1_BAD_VALUE;
}
static int32_t g_get_consumer(gralloc1_device_t *dev, buffer_handle_t buffer, uint64_t *o) {
    FIND(buffer, b);
    if (o) *o = ((uint64_t)b->h->cons_hi << 32) | b->h->cons_lo;
    UNLOCK();
    return o ? G1_OK : G1_BAD_VALUE;
}
static int32_t g_get_producer(gralloc1_device_t *dev, buffer_handle_t buffer, uint64_t *o) {
    FIND(buffer, b);
    if (o) *o = ((uint64_t)b->h->prod_hi << 32) | b->h->prod_lo;
    UNLOCK();
    return o ? G1_OK : G1_BAD_VALUE;
}
static int32_t g_get_dimensions(gralloc1_device_t *dev, buffer_handle_t buffer, uint32_t *w, uint32_t *h) {
    FIND(buffer, b);
    if (w) *w = b->h->width;
    if (h) *h = b->h->height;
    UNLOCK();
    return (w && h) ? G1_OK : G1_BAD_VALUE;
}
static int32_t g_get_format(gralloc1_device_t *dev, buffer_handle_t buffer, int32_t *o) {
    FIND(buffer, b);
    if (o) *o = b->h->format;
    UNLOCK();
    return o ? G1_OK : G1_BAD_VALUE;
}
static int32_t g_get_layers(gralloc1_device_t *dev, buffer_handle_t buffer, uint32_t *o) {
    FIND(buffer, b);
    if (o) *o = b->h->layers;
    UNLOCK();
    return o ? G1_OK : G1_BAD_VALUE;
}
static int32_t g_get_stride(gralloc1_device_t *dev, buffer_handle_t buffer, uint32_t *o) {
    FIND(buffer, b);
    if (o) *o = b->h->stride;
    UNLOCK();
    return o ? G1_OK : G1_BAD_VALUE;
}

/* getTransportSize(dev, buffer, &numFds, &numInts): what a Binder transfer of the handle needs. */
static int32_t g_get_transport_size(gralloc1_device_t *dev, buffer_handle_t buffer, uint32_t *fds, uint32_t *ints) {
    FIND(buffer, b);
    if (fds) *fds = (uint32_t)b->h->numFds;
    if (ints) *ints = (uint32_t)b->h->numInts;
    UNLOCK();
    return (fds && ints) ? G1_OK : G1_BAD_VALUE;
}

/* validateBufferSize: the mapper calls this on every importBuffer.  Only fail when the caller could read or
 * write outside the backing store; don't second-guess attributes. */
static int32_t g_validate_size(gralloc1_device_t *dev, buffer_handle_t buffer,
                               const gralloc1_buffer_descriptor_info_t *info, uint32_t stride) {
    uint32_t cs;
    uint64_t need;
    int32_t err;
    uint64_t size;
    FIND(buffer, b);
    size = b->h->size;
    UNLOCK();
    if (!info) return G1_BAD_VALUE;
    err = compute_layout(info->format, info->width, info->height, &cs, &need);
    if (err != G1_OK) return err;
    switch (info->format) { /* packed formats: the caller's own stride decides how far it reads */
    case PF_RGBA_8888: case PF_RGBX_8888: case PF_BGRA_8888: case PF_RGBA_1010102: case PF_IMPLEMENTATION_DEFINED:
        need = (uint64_t)stride * info->height * 4; break;
    case PF_RGB_888: need = (uint64_t)stride * info->height * 3; break;
    case PF_RGB_565: case PF_RAW16: case PF_Y16: case PF_YCBCR_422_I:
        need = (uint64_t)stride * info->height * 2; break;
    case PF_RGBA_FP16: need = (uint64_t)stride * info->height * 8; break;
    case PF_Y8: case PF_R_8: need = (uint64_t)stride * info->height; break;
    default: break;
    }
    if (stride < info->width) return G1_BAD_VALUE;
    return need <= size ? G1_OK : G1_BAD_VALUE;
}

/* ------------------------------------------------------------------ CPU access */
static void wait_fence(int fd) {
    struct pollfd p;
    if (fd < 0) return;
    p.fd = fd; p.events = POLLIN; p.revents = 0;
    poll(&p, 1, 3000);
    close(fd); /* lock() takes ownership of the acquire fence */
}

static int32_t g_lock(gralloc1_device_t *dev, buffer_handle_t buffer, uint64_t prod, uint64_t cons,
                      const gralloc1_rect_t *region, void **out, int32_t fence) {
    (void)prod; (void)cons; (void)region; /* one flat shared mapping: no per-usage or per-region work */
    if (!out) { wait_fence(fence); return G1_BAD_VALUE; }
    *out = NULL;
    wait_fence(fence);
    {
        FIND(buffer, b);
        if (!b->map) {
            void *m = aemu_map_shared(b->h->size, b->h->fd);
            if (m == MAP_FAILED) { UNLOCK(); LOGE("lock: mmap(%u) failed", b->h->size); return G1_NO_RESOURCES; }
            b->map = m;
            b->maplen = b->h->size;
        }
        b->locks++;
        *out = b->map;
        UNLOCK();
    }
    return G1_OK;
}

static int32_t g_unlock(gralloc1_device_t *dev, buffer_handle_t buffer, int32_t *fence) {
    FIND(buffer, b);
    if (b->locks) b->locks--;
    UNLOCK();
    if (fence) *fence = -1; /* writes are visible immediately; nothing to wait for */
    return G1_OK;
}

/* ------------------------------------------------------------------ IMG vendor calls */
static int32_t img_free_metadata(gralloc1_device_t *dev, buffer_handle_t buffer) { (void)dev; (void)buffer; return G1_OK; }
static int32_t img_reread(gralloc1_device_t *dev, buffer_handle_t buffer) { (void)dev; (void)buffer; return G1_OK; }
static int32_t img_flush(gralloc1_device_t *dev, buffer_handle_t buffer, int32_t *fence) {
    (void)dev; (void)buffer;
    if (fence) *fence = -1; /* the mapper reads the out value as a release fence: never leave it uninitialised */
    return G1_OK;
}

/* Anything requested that we don't implement: an error code, never a NULL function pointer (neither client checks). */
static int32_t stub_unsupported(void) { return G1_UNSUPPORTED; }

/* ------------------------------------------------------------------ device */
static void g_get_capabilities(gralloc1_device_t *dev, uint32_t *count, int32_t *caps) {
    static const int32_t c[] = { G1_CAP_TEST_ALLOCATE, G1_CAP_RELEASE_IMPLY_DELETE };
    uint32_t i, n = sizeof(c) / sizeof(c[0]);
    (void)dev;
    if (!count) return;
    if (!caps) { *count = n; return; }
    for (i = 0; i < n && i < *count; i++) caps[i] = c[i];
    *count = i;
}

#define FN(fp) ((gralloc1_function_pointer_t)(fp))

static gralloc1_function_pointer_t g_get_function(gralloc1_device_t *dev, int32_t d) {
    (void)dev;
    switch (d) {
    case G1_FN_CREATE_DESCRIPTOR: return FN(d_create);
    case G1_FN_DESTROY_DESCRIPTOR: return FN(d_destroy);
    case G1_FN_SET_CONSUMER_USAGE: return FN(d_set_consumer);
    case G1_FN_SET_DIMENSIONS: return FN(d_set_dimensions);
    case G1_FN_SET_FORMAT: return FN(d_set_format);
    case G1_FN_SET_PRODUCER_USAGE: return FN(d_set_producer);
    case G1_FN_SET_LAYER_COUNT: return FN(d_set_layers);
    case G1_FN_GET_BACKING_STORE: return FN(g_get_backing_store);
    case G1_FN_GET_CONSUMER_USAGE: return FN(g_get_consumer);
    case G1_FN_GET_DIMENSIONS: return FN(g_get_dimensions);
    case G1_FN_GET_FORMAT: return FN(g_get_format);
    case G1_FN_GET_PRODUCER_USAGE: return FN(g_get_producer);
    case G1_FN_GET_LAYER_COUNT: return FN(g_get_layers);
    case G1_FN_GET_STRIDE: return FN(g_get_stride);
    case G1_FN_ALLOCATE: return FN(g_allocate);
    case G1_FN_RETAIN: return FN(g_retain);
    case G1_FN_RELEASE: return FN(g_release);
    case G1_FN_LOCK: return FN(g_lock);
    case G1_FN_UNLOCK: return FN(g_unlock);
    case G1_FN_VALIDATE_BUFFER_SIZE: return FN(g_validate_size);
    case G1_FN_GET_TRANSPORT_SIZE: return FN(g_get_transport_size);
    case G1_FN_IMPORT_BUFFER: return FN(g_import);

    case IMG_FN_SET_NAME_RESERVED: return FN(d_set_name_reserved);
    case IMG_FN_FREE_METADATA: return FN(img_free_metadata);
    case IMG_FN_REREAD: return FN(img_reread);
    case IMG_FN_FLUSH: return FN(img_flush);
    /* IMG_FN_GET_RESERVED_REGION, _SET_METADATA, _GET_METADATA, _DUMP: not decoded yet -> UNSUPPORTED (see stub) */
    default:
        /* The rest of the standard range (dump, getNumFlexPlanes, lockFlex, ...) and the whole IMG vendor range
         * 0x400-0x4ff get the stub; ids outside any range a client could plausibly use get NULL, as the spec says. */
        if ((d >= 1 && d <= 32) || (d >= 0x400 && d < 0x500)) return FN(stub_unsupported);
        return NULL;
    }
}

static int g_dev_close(struct hw_device_t *d) { (void)d; return 0; } /* the device is a static singleton */

static gralloc1_device_t g_device;
static int g_device_ready;

static int g_open(const struct hw_module_t *module, const char *name, struct hw_device_t **device) {
    if (!name || !device || strcmp(name, "gralloc") != 0) return -22; /* -EINVAL: "gpu0"/"fb0" are gralloc0 names */
    LOCK();
    if (!g_device_ready) {
        memset(&g_device, 0, sizeof(g_device));
        g_device.common.tag = HARDWARE_DEVICE_TAG;
        g_device.common.version = API_VERSION_1_0;
        g_device.common.module = (struct hw_module_t *)module;
        g_device.common.close = g_dev_close;
        g_device.getCapabilities = g_get_capabilities;
        g_device.getFunction = g_get_function;
        g_device_ready = 1;
    }
    UNLOCK();
    *device = &g_device.common;
    return 0;
}

static hw_module_methods_t g_methods = { g_open };

/* ------------------------------------------------------------------ gralloc0 view of the module
 * SwiftShader's GrallocModule (the EGL/GLES libraries the app installs) is a gralloc0 client: it takes the
 * module's symbol as a gralloc_module_t and calls module->lock()/unlock() (offsets 0x88/0x8c on 32-bit), which
 * a bare hw_module_t does not have.  Its constructor also refuses a module whose API major version is not 0 and
 * then returns -1 from lock() WITHOUT touching *vaddr, which it never checks: the caller (sw::Blitter::clear)
 * got a NULL pointer and wrote through it, killing SurfaceFlinger in sw::clear.
 * The symbol is therefore a gralloc_module_t: the gralloc1 clients (allocator service, IMG mapper) only read the
 * hw_module_t part, so for them nothing changes.  The version check is dealt with in the SwiftShader library
 * (patch_swiftshader_gralloc.py), because the version here has to stay 1.0 for those gralloc1 clients. */
struct aemu_module {
    hw_module_t common;
    int (*registerBuffer)(const struct hw_module_t *m, buffer_handle_t h);                      /* 0x80 */
    int (*unregisterBuffer)(const struct hw_module_t *m, buffer_handle_t h);                    /* 0x84 */
    int (*lock)(const struct hw_module_t *m, buffer_handle_t h, int usage, int l, int t, int w, int hh, void **vaddr); /* 0x88 */
    int (*unlock)(const struct hw_module_t *m, buffer_handle_t h);                              /* 0x8c */
    void *unused[10];                                                                           /* perform, lock_ycbcr, async calls, reserved */
};

/* A handle this process never retained (the table is keyed by the pointer the mapper retained): mapped directly,
 * from the validated handle, for the length of the lock. */
#define M0_MAX 64
static struct { const void *h; void *va; size_t len; } g_m0[M0_MAX];

static int m0_register(const struct hw_module_t *m, buffer_handle_t h) { (void)m; return handle_ok((const struct aemu_handle *)h) ? 0 : -22; }
static int m0_unregister(const struct hw_module_t *m, buffer_handle_t h) { (void)m; (void)h; return 0; }

static int m0_lock(const struct hw_module_t *m, buffer_handle_t buffer, int usage, int l, int t, int w, int hh, void **out) {
    gralloc1_rect_t r;
    int32_t e;
    int i;
    (void)m;
    if (!out) return -22;
    r.left = l; r.top = t; r.width = w; r.height = hh;
    e = g_lock(NULL, buffer, (uint64_t)(uint32_t)usage, (uint64_t)(uint32_t)usage, &r, out, -1);
    if (e == G1_OK && *out) return 0;
    *out = NULL;                                  /* whatever happens, never hand back a stale value */
    if (e == G1_BAD_HANDLE && handle_ok((const struct aemu_handle *)buffer)) {
        const struct aemu_handle *h = (const struct aemu_handle *)buffer;
        void *va = aemu_map_shared(h->size, h->fd);
        if (va == MAP_FAILED) return -12;
        LOCK();
        for (i = 0; i < M0_MAX; i++) if (!g_m0[i].h) {
            g_m0[i].h = buffer; g_m0[i].va = va; g_m0[i].len = h->size;
            UNLOCK();
            *out = va;
            return 0;
        }
        UNLOCK();
        munmap(va, h->size);
        return -12;
    }
    return e == G1_BAD_HANDLE ? -22 : -12;
}

static int m0_unlock(const struct hw_module_t *m, buffer_handle_t buffer) {
    int32_t fence = -1, e;
    int i;
    (void)m;
    LOCK();
    for (i = 0; i < M0_MAX; i++) if (g_m0[i].h == buffer) {
        munmap(g_m0[i].va, g_m0[i].len);
        g_m0[i].h = NULL;
        UNLOCK();
        return 0;
    }
    UNLOCK();
    e = g_unlock(NULL, buffer, &fence);
    if (fence >= 0) close(fence);
    return e == G1_OK ? 0 : -22;
}

EXPORT struct aemu_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = API_VERSION_1_0,
        .hal_api_version = API_VERSION_1_0,
        .id = "gralloc",
        .name = "AEMU Gralloc1 (memfd)",
        .author = "aemu",
        .methods = &g_methods,
    },
    .registerBuffer = m0_register,
    .unregisterBuffer = m0_unregister,
    .lock = m0_lock,
    .unlock = m0_unlock,
};

_Static_assert(offsetof(struct aemu_module, lock) == 0x88 || sizeof(void *) != 4, "gralloc0 lock slot");
_Static_assert(offsetof(struct aemu_module, unlock) == 0x8c || sizeof(void *) != 4, "gralloc0 unlock slot");
