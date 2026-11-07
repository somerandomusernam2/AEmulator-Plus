/* Added for AEmulator Plus, 2026-10-31 (fixed 2026-10-31: undeclared strstr_, unchecked null object in req_string, unterminated crt arrays). GPL-3.0; see LICENSE and NOTICE.md. */
// aemu-hwservicemanager: the HIDL service manager of the native arm64 engine, for kernels 35-37 (Android 15-17); the same binary serves all three.
//
// Android 17 ships no /system/bin/hwservicemanager. Its libhidl then reports "hwservicemanager is not supported on the
// device" / "HwBinder is not supported on this device", answers every HIDL lookup from the VINTF manifest alone (passthrough:
// the -impl library is loaded into the client's own process), and every vendor program built against the older libhidl waits
// for ever for the property hwservicemanager.ready ("Waited for hwservicemanager.ready for a second, waiting another...").
// SurfaceFlinger asks for android.hardware.graphics.composer@2.1::IComposer/default that way and aborts ("failed to get
// hwcomposer service") although the engine's composer@2.1 service is running.
//
// This program is what AOSP's hwservicemanager is to those programs, in about 900 lines and no libraries beyond libc:
//   * it becomes the context manager of /dev/hwbinder (binderd64 serves that device on its own socket, see bclient.c),
//   * it implements android.hidl.manager@1.0::IServiceManager and the unregisterForNotifications of @1.1 (get, add,
//     getTransport, list, listByInterface, registerForNotifications, debugDump, registerPassthroughClient), with the wire
//     format of the generated BnHwServiceManager / BpHwServiceNotification (hwbinder parcels: an interface token as a C
//     string, hidl_string / hidl_vec as BINDER_TYPE_PTR buffers with parents, bool and uint8 padded to four bytes),
//   * add() asks the service for IBase::interfaceChain, as the real one does, so that a service is found under every
//     interface it extends (composer@2.1 is found for a composer@2.1 client even when 2.1 is the service's own),
//   * getTransport() reads the VINTF manifests of /vendor, /odm, /system, /system_ext and /product (only <hal format="hidl">),
//     so a HAL declared "hwbinder" is reached through this manager and one declared "passthrough" (the mapper) still is not,
//   * services and notification callbacks are tracked by death notification and dropped when their process dies,
//   * it sets hwservicemanager.ready once it is the context manager.
// Not done: lazy-HAL start (ctl.interface_start), the SELinux checks, client callbacks of @1.2, getDebugInfo.
//
// No bionic headers (like native/composer32/composer_service.cpp): the libc functions it uses are declared below and the
// program is linked against empty stand-ins of libc.so / liblog.so, so build-hwsm.sh needs only zig, no NDK sysroot.
// -DAEMU_HOST_TEST builds it against the host's libc and a test double of the binder front end (tests/hwsm_host_test.c, a fake binder driver).

#ifdef AEMU_HOST_TEST
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <dirent.h>
#include <stdarg.h>
extern int tb_open(const char *dev);
extern int tb_ioctl(int fd, unsigned long cmd, void *arg);
extern void *tb_mmap(int fd, size_t len);
extern int tb_setprop(const char *name, const char *value);
extern const char *tb_root(void);
#define BN_OPEN(dev) tb_open(dev)
#define BN_IOCTL(fd, cmd, arg) tb_ioctl(fd, cmd, arg)
#define BN_MMAP(fd, len) tb_mmap(fd, len)
#define SETPROP(n, v) tb_setprop(n, v)
#define ROOT() tb_root()
#define LOG(prio, ...) do { fprintf(stderr, "hwsm[%d]: ", prio); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#else
typedef __SIZE_TYPE__ size_t;
typedef long ssize_t;
#define NULL ((void *)0)
extern void *malloc(size_t);
extern void *realloc(void *, size_t);
extern void free(void *);
extern void *memcpy(void *, const void *, size_t);
extern void *memset(void *, int, size_t);
extern int memcmp(const void *, const void *, size_t);
extern size_t strlen(const char *);
extern int strcmp(const char *, const char *);
extern int strncmp(const char *, const char *, size_t);
extern char *strchr(const char *, int);
extern int open(const char *, int, ...);
extern int close(int);
extern ssize_t read(int, void *, size_t);
extern int ioctl(int, int, ...);
extern void *mmap(void *, size_t, int, int, int, long);
extern int usleep(unsigned);
typedef struct DIR DIR;
struct dirent { unsigned long long d_ino; long long d_off; unsigned short d_reclen; unsigned char d_type; char d_name[256]; };
extern DIR *opendir(const char *);
extern struct dirent *readdir(DIR *);
extern int closedir(DIR *);
extern int __system_property_set(const char *, const char *);
extern int __android_log_print(int, const char *, const char *, ...);
__attribute__((noreturn)) extern void _exit(int);
__attribute__((noreturn)) extern void __libc_init(void *raw_args, void (*onexit)(void), int (*slingshot)(int, char **, char **), const void *structors);
#define O_RDWR 2
#define O_RDONLY 0
#define O_CLOEXEC 02000000
#define PROT_READ 1
#define MAP_PRIVATE 2
#define MAP_NORESERVE 0x4000
#define BN_OPEN(dev) open(dev, O_RDWR | O_CLOEXEC)
#define BN_IOCTL(fd, cmd, arg) ioctl(fd, (int)(cmd), arg)
static void *bn_mmap(int fd, size_t len) { void *m = mmap(NULL, len, PROT_READ, MAP_PRIVATE | MAP_NORESERVE, fd, 0); return m == (void *)-1 ? NULL : m; }
#define BN_MMAP(fd, len) bn_mmap(fd, len)
#define SETPROP(n, v) __system_property_set(n, v)
#define ROOT() ""
#define LOG(prio, ...) __android_log_print(prio, "hwservicemanager", __VA_ARGS__)
#endif

#define L_INFO 4
#define L_WARN 5
#define L_ERR 6

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef int i32;
typedef unsigned long long u64;
typedef __UINTPTR_TYPE__ uptr;

/* ---------------------------------------------------------------------------------------------- binder uapi (64-bit) */

#define BINDER_WRITE_READ 0xc0306201u
#define BINDER_SET_MAX_THREADS 0x40046205u
#define BINDER_SET_CONTEXT_MGR 0x40046207u
#define BINDER_VERSION 0xc0046209u
#define BINDER_SET_CONTEXT_MGR_EXT 0x4018620du

#define BC_TRANSACTION 0x40406300u
#define BC_REPLY 0x40406301u
#define BC_TRANSACTION_SG 0x40486311u
#define BC_REPLY_SG 0x40486312u
#define BC_FREE_BUFFER 0x40086303u
#define BC_INCREFS 0x40046304u
#define BC_ACQUIRE 0x40046305u
#define BC_RELEASE 0x40046306u
#define BC_DECREFS 0x40046307u
#define BC_INCREFS_DONE 0x40106308u
#define BC_ACQUIRE_DONE 0x40106309u
#define BC_ENTER_LOOPER 0x0000630cu
#define BC_REQUEST_DEATH_NOTIFICATION 0x400c630eu
#define BC_CLEAR_DEATH_NOTIFICATION 0x400c630fu
#define BC_DEAD_BINDER_DONE 0x40086310u

#define BR_TRANSACTION 0x80407202u
#define BR_TRANSACTION_SEC_CTX 0x80487202u
#define BR_REPLY 0x80407203u
#define BR_DEAD_REPLY 0x00007205u
#define BR_INCREFS 0x80107207u
#define BR_ACQUIRE 0x80107208u
#define BR_DEAD_BINDER 0x8008720fu
#define BR_FAILED_REPLY 0x00007211u

#define T_BINDER 0x73622a85u
#define T_HANDLE 0x73682a85u
#define T_WEAK_HANDLE 0x77682a85u
#define T_PTR 0x70742a85u
#define BUF_HAS_PARENT 1u
#define TF_ONE_WAY 1u
#define TF_STATUS_CODE 8u

struct bwr { u64 write_size, write_consumed, write_buffer, read_size, read_consumed, read_buffer; };
struct btd { u64 target, cookie; u32 code, flags; i32 sender_pid; u32 sender_euid; u64 data_size, offsets_size, buffer, offsets; };
struct btd_sg { struct btd tr; u64 buffers_size; };
struct fbo { u32 type, flags; u64 binder, cookie; };                           /* flat_binder_object */
struct bbo { u32 type, flags; u64 buffer, length, parent, parent_offset; };    /* binder_buffer_object */
struct hstr { u64 ptr; u32 size; u8 owns, pad[3]; };                           /* hidl_string / hidl_vec: 16 bytes */

#define A8(x) (((x) + 7) & ~7ULL)

/* android.hidl.base@1.0::IBase, and the call numbers the generated stubs use (checked against BnHwServiceManager::onTransact) */
#define SM_GET 1
#define SM_ADD 2
#define SM_GET_TRANSPORT 3
#define SM_LIST 4
#define SM_LIST_BY_INTERFACE 5
#define SM_REGISTER_FOR_NOTIFICATIONS 6
#define SM_DEBUG_DUMP 7
#define SM_REGISTER_PASSTHROUGH_CLIENT 8
#define SM_UNREGISTER_FOR_NOTIFICATIONS 9       /* @1.1 */
/* @1.2: registerClientCallback 10, unregisterClientCallback 11, addWithChain 12, listManifestByInterface 13, tryUnregister 14.
 * A client built with libhidl of Android 11+ registers its service with addWithChain (the chain comes with the call, the
 * manager does not ask the service for it) as soon as the manager says it is @1.2. Added 2026-10-31: without it every
 * such HAL (the audio@7.0 service) got UNKNOWN_ERROR from registerAsService and aborted ("Could not register Audio Core
 * API"), and audioserver then died without a HAL. */
#define SM_ADD_WITH_CHAIN 12
/* Added 2026-10-31: @1.2 listManifestByInterface(fqName) -> vec<string>. keystore2 of Android 17 asks it for the HIDL keymaster
 * instances (get_hidl_instances) at start; the UNKNOWN_TRANSACTION answer was an unchecked hardware::Return in the client, which
 * aborts ("Failed HIDL return status not checked", exit 134): keystore2 never ran, system_server died in LockSettingsService
 * ("Could not connect to Keystore service"). The instances of the interface that are registered are answered, as listByInterface
 * does (the engine's HALs are the ones the device manifest declares). */
#define SM_LIST_MANIFEST_BY_INTERFACE 13
#define SM_FIRST_1_2 10
#define SM_LAST_1_2 16
#define HIDL_PACK(a, b, c) ((0x0fu << 24) | ((u32)(a) << 16) | ((u32)(b) << 8) | (u32)(c))
#define IB_PING HIDL_PACK('P', 'N', 'G')
#define IB_CHAIN HIDL_PACK('C', 'H', 'N')
#define IB_DESCRIPTOR HIDL_PACK('D', 'S', 'C')
#define IB_DEBUG HIDL_PACK('D', 'B', 'G')
#define IB_HASH_CHAIN HIDL_PACK('H', 'S', 'H')
#define IB_SYSPROPS HIDL_PACK('S', 'Y', 'S')
#define IB_INSTRUMENTATION HIDL_PACK('I', 'N', 'T')
#define NOTIFY_ON_REGISTRATION 1

#define TR_EMPTY 0
#define TR_HWBINDER 1
#define TR_PASSTHROUGH 2

/* Fixed 2026-10-31: the manager answers as @1.2 (what Android 17's WifiService asks for) and, like AOSP's hwservicemanager,
 * registers itself: get("android.hidl.manager@1.0/1.1/1.2::IServiceManager", "default") returns the manager. Before,
 * that get said "not registered", HwBinder.getService threw NoSuchElementException out of WifiHalHidlImpl.serviceDeclared
 * and system_server died ("Failed to create service com.android.server.wifi.WifiService"). */
#define MANAGER_DESC "android.hidl.manager@1.2::IServiceManager"
static const char *const g_manager_chain[] = { MANAGER_DESC, "android.hidl.manager@1.1::IServiceManager", "android.hidl.manager@1.0::IServiceManager", "android.hidl.base@1.0::IBase" };
#define MANAGER_NCHAIN 4
static int streq(const char *a, const char *b);
static int is_manager_iface(const char *fq, const char *name) {
    if (!streq(name, "default")) return 0;
    for (int i = 0; i < MANAGER_NCHAIN - 1; i++) if (streq(fq, g_manager_chain[i])) return 1;
    return 0;
}

/* ---------------------------------------------------------------------------------------------- small helpers */

static int g_fd = -1;

static char *xstrndup(const char *s, size_t n) {
    char *d = malloc(n + 1);
    if (!d) { LOG(L_ERR, "out of memory"); _exit(1); }
    memcpy(d, s, n); d[n] = 0;
    return d;
}
static char *xstrdup(const char *s) { return xstrndup(s, strlen(s)); }
static void *xzalloc(size_t n) {
    void *p = malloc(n);
    if (!p) { LOG(L_ERR, "out of memory"); _exit(1); }
    memset(p, 0, n);
    return p;
}
static int streq(const char *a, const char *b) { return strcmp(a, b) == 0; }

/* what was already logged once (a vendor program asks for the same missing HAL again and again) */
static char *g_seen[256]; static int g_nseen;
static int first_time(const char *kind, const char *a, const char *b) {
    char key[400]; size_t i = 0;
    for (const char *s = kind; *s && i < 300; s++) key[i++] = *s;
    key[i++] = ' ';
    for (const char *s = a; *s && i < 380; s++) key[i++] = *s;
    key[i++] = '/';
    for (const char *s = b; *s && i < 398; s++) key[i++] = *s;
    key[i] = 0;
    for (int k = 0; k < g_nseen; k++) if (streq(g_seen[k], key)) return 0;
    if (g_nseen < 256) g_seen[g_nseen++] = xstrdup(key);
    return 1;
}

/* ---------------------------------------------------------------------------------------------- arena, write buffer */

// Everything a reply points to lives in the arena until the next BINDER_WRITE_READ at the top level has sent it (the
// binder front end copies buffers while it sends); nested waits (add() asks the service for its interface chain) only flush.
#define ARENA (1u << 20)
static u8 g_arena[ARENA] __attribute__((aligned(16)));
static size_t g_ap;
static void *aalloc(size_t n) {
    n = (n + 15) & ~(size_t)15;
    if (g_ap + n > ARENA) { LOG(L_ERR, "reply arena exhausted"); _exit(1); }
    void *p = g_arena + g_ap; g_ap += n;
    memset(p, 0, n);
    return p;
}

#define WBUF 65536
static u8 g_w[WBUF] __attribute__((aligned(8)));
static size_t g_wl;
static int rw(u8 *rb, size_t rbsz, size_t *got);
static void w_cmd(u32 cmd, const void *payload, size_t n) {
    if (g_wl + 4 + n > WBUF) rw(0, 0, 0);
    memcpy(g_w + g_wl, &cmd, 4); g_wl += 4;
    if (n) { memcpy(g_w + g_wl, payload, n); g_wl += n; }
}
static void w_u32(u32 cmd, u32 v) { w_cmd(cmd, &v, 4); }
static void w_hc(u32 cmd, u32 handle, u64 cookie) { u8 b[12]; memcpy(b, &handle, 4); memcpy(b + 4, &cookie, 8); w_cmd(cmd, b, 12); }

/* one BINDER_WRITE_READ: sends what was queued, then reads (read_size 0: only sends) */
static int rw(u8 *rb, size_t rbsz, size_t *got) {
    struct bwr b; memset(&b, 0, sizeof b);
    b.write_size = g_wl; b.write_buffer = (u64)(uptr)g_w;
    b.read_size = rb ? rbsz : 0; b.read_buffer = (u64)(uptr)rb;
    int r = BN_IOCTL(g_fd, BINDER_WRITE_READ, &b);
    g_wl = 0;
    if (r < 0) return -1;
    if (got) *got = (size_t)b.read_consumed;
    return 0;
}

/* ---------------------------------------------------------------------------------------------- parcels */

struct pb { u8 *d; size_t dl, cap; u64 *o; size_t no, ocap; u64 sg; };

static void pb_init(struct pb *p, size_t dcap, size_t ocap) {
    p->d = aalloc(dcap); p->cap = dcap; p->dl = 0;
    p->o = aalloc(ocap * 8 + 8); p->ocap = ocap; p->no = 0; p->sg = 0;
}
static void pb_raw(struct pb *p, const void *s, size_t n) {
    if (p->dl + n > p->cap) { LOG(L_ERR, "parcel overflow"); _exit(1); }
    memcpy(p->d + p->dl, s, n); p->dl += n;
}
static void pb_i32(struct pb *p, i32 v) { pb_raw(p, &v, 4); }
/* writeCString / writeInterfaceToken: the string with its NUL, padded to four bytes */
static void pb_cstr(struct pb *p, const char *s) {
    size_t n = strlen(s) + 1, padded = (n + 3) & ~(size_t)3;
    static const u8 zero[4];
    pb_raw(p, s, n);
    if (padded > n) pb_raw(p, zero, padded - n);
}
static int pb_object(struct pb *p, const void *obj, size_t n) {
    if (p->no >= p->ocap) { LOG(L_ERR, "parcel: too many objects"); _exit(1); }
    p->o[p->no] = p->dl;
    pb_raw(p, obj, n);
    return (int)p->no++;
}
/* a buffer the driver copies to the other side (writeBuffer / writeEmbeddedBuffer); the pointers inside its parent are re-set by the receiver */
static int pb_ptr(struct pb *p, const void *buf, u64 len, int parent, u64 parent_offset) {
    struct bbo o; memset(&o, 0, sizeof o);
    o.type = T_PTR; o.buffer = (u64)(uptr)buf; o.length = len;
    if (parent >= 0) { o.flags = BUF_HAS_PARENT; o.parent = (u64)parent; o.parent_offset = parent_offset; }
    p->sg += A8(len);
    return pb_object(p, &o, sizeof o);
}
static void pb_handle(struct pb *p, u32 handle) {
    struct fbo f; memset(&f, 0, sizeof f);
    f.type = T_HANDLE; f.flags = 0x7f | 0x100; f.binder = handle;
    pb_object(p, &f, sizeof f);
}
/* the manager's own node: it is the context manager, whose node the driver keeps at binder 0 / cookie 0 (see become_context_manager),
 * so a local binder object with those values, in the list of objects, reaches the receiver as a handle to this manager */
static void pb_self_binder(struct pb *p) {
    struct fbo f; memset(&f, 0, sizeof f);
    f.type = T_BINDER; f.flags = 0x7f | 0x100;
    pb_object(p, &f, sizeof f);
}
/* writeStrongBinder(nullptr): the flat object is in the data but not in the list of objects, so the driver passes it as it is */
static void pb_null_binder(struct pb *p) {
    struct fbo f; memset(&f, 0, sizeof f);
    f.type = T_BINDER; f.flags = 0x7f | 0x100;
    pb_raw(p, &f, sizeof f);
}
/* a hidl_string argument: the 16-byte struct and its characters as its child */
static void pb_string(struct pb *p, const char *s) {
    size_t n = strlen(s);
    char *chars = aalloc(n + 1); memcpy(chars, s, n);
    struct hstr *h = aalloc(sizeof *h); h->ptr = (u64)(uptr)chars; h->size = (u32)n;
    int parent = pb_ptr(p, h, sizeof *h, -1, 0);
    pb_ptr(p, chars, n + 1, parent, 0);
}
/* a hidl_vec<hidl_string> return value (the elements follow their array in order, as writeEmbeddedToParcel writes them) */
static void pb_string_vec(struct pb *p, char *const *v, u32 n) {
    struct hstr *vec = aalloc(sizeof *vec);
    struct hstr *arr = aalloc((size_t)n * sizeof *arr + 16);
    vec->ptr = (u64)(uptr)arr; vec->size = n;
    int pv = pb_ptr(p, vec, sizeof *vec, -1, 0);
    int pa = pb_ptr(p, arr, (u64)n * sizeof *arr, pv, 0);
    for (u32 i = 0; i < n; i++) {
        size_t l = strlen(v[i]);
        char *chars = aalloc(l + 1); memcpy(chars, v[i], l);
        arr[i].ptr = (u64)(uptr)chars; arr[i].size = (u32)l;
        pb_ptr(p, chars, l + 1, pa, (u64)i * sizeof *arr);
    }
}
static void pb_empty_vec(struct pb *p) { pb_string_vec(p, NULL, 0); }

/* a transaction: a command carrying a binder_transaction_data (_sg when the parcel has buffers) */
static void send_txn(u32 cmd, u32 handle, u32 code, u32 flags, struct pb *p) {
    struct btd_sg t; memset(&t, 0, sizeof t);
    t.tr.target = handle; t.tr.code = code; t.tr.flags = flags;
    t.tr.data_size = p->dl; t.tr.offsets_size = p->no * 8;
    t.tr.buffer = (u64)(uptr)p->d; t.tr.offsets = (u64)(uptr)p->o;
    if (p->sg) { t.buffers_size = p->sg; w_cmd(cmd == BC_REPLY ? BC_REPLY_SG : BC_TRANSACTION_SG, &t, sizeof t); }
    else w_cmd(cmd, &t, sizeof t.tr);
}
static void reply_status(int status) {             /* a failed call: the status goes back as the one int32 of the reply */
    i32 *v = aalloc(8); *v = status;
    struct btd_sg t; memset(&t, 0, sizeof t);
    t.tr.flags = TF_STATUS_CODE; t.tr.data_size = 4; t.tr.buffer = (u64)(uptr)v; t.tr.offsets = (u64)(uptr)v;
    w_cmd(BC_REPLY, &t, sizeof t.tr);
}
#define EBADMSG_STATUS (-74)                        /* UNKNOWN_TRANSACTION */

/* ---------------------------------------------------------------------------------------------- binder handles */

// One entry per handle: the manager holds a strong and a weak reference and one death notification for it, however many
// services / callbacks it names. (A ref can carry only one death notification.)
struct hnd { struct hnd *next; u32 handle; int refs, dead; };
static struct hnd *g_hnds;

static struct hnd *hnd_get(u32 handle) {
    for (struct hnd *h = g_hnds; h; h = h->next) if (h->handle == handle) { h->refs++; return h; }
    struct hnd *h = xzalloc(sizeof *h);
    h->handle = handle; h->refs = 1; h->next = g_hnds; g_hnds = h;
    w_u32(BC_INCREFS, handle);
    w_u32(BC_ACQUIRE, handle);
    w_hc(BC_REQUEST_DEATH_NOTIFICATION, handle, (u64)(uptr)h);
    return h;
}
static void hnd_put(struct hnd *h) {
    if (--h->refs > 0) return;
    struct hnd **pp = &g_hnds; while (*pp && *pp != h) pp = &(*pp)->next;
    if (*pp) *pp = h->next;
    if (!h->dead) w_hc(BC_CLEAR_DEATH_NOTIFICATION, h->handle, (u64)(uptr)h);
    w_u32(BC_RELEASE, h->handle);
    w_u32(BC_DECREFS, h->handle);
    free(h);
}

/* ---------------------------------------------------------------------------------------------- registry */

#define MAXCHAIN 32
struct svc { struct svc *next; struct hnd *h; char *name; int nchain; char *chain[MAXCHAIN]; };
struct reg { struct reg *next; struct hnd *h; char *fq, *name; };      /* fq / name "" = any */
static struct svc *g_svcs;
static struct reg *g_regs;

static void svc_free(struct svc *s) {
    for (int i = 0; i < s->nchain; i++) free(s->chain[i]);
    free(s->name); hnd_put(s->h); free(s);
}
static void reg_free(struct reg *r) { free(r->fq); free(r->name); hnd_put(r->h); free(r); }

static struct svc *svc_find(const char *fq, const char *name) {
    for (struct svc *s = g_svcs; s; s = s->next) {
        if (!streq(s->name, name) || s->h->dead) continue;
        for (int i = 0; i < s->nchain; i++) if (streq(s->chain[i], fq)) return s;
    }
    return NULL;
}

/* the callback's onRegistration(fqName, name, preexisting): one-way */
static void notify(struct reg *r, const char *fq, const char *name, int preexisting) {
    if (r->h->dead) return;
    struct pb p; pb_init(&p, 512 + 2 * (strlen(fq) + strlen(name)), 8);
    pb_cstr(&p, "android.hidl.manager@1.0::IServiceNotification");
    pb_string(&p, fq);
    pb_string(&p, name);
    pb_i32(&p, preexisting ? 1 : 0);
    send_txn(BC_TRANSACTION, r->h->handle, NOTIFY_ON_REGISTRATION, TF_ONE_WAY, &p);
}
static int reg_matches(const struct reg *r, const char *fq, const char *name) {
    return (!r->fq[0] || streq(r->fq, fq)) && (!r->name[0] || streq(r->name, name));
}

/* a process died: everything it registered or asked to be told goes with it */
static void hnd_died(u64 cookie) {
    struct hnd *h = g_hnds;
    while (h && (u64)(uptr)h != cookie) h = h->next;
    if (!h) { w_cmd(BC_DEAD_BINDER_DONE, &cookie, 8); return; }
    h->dead = 1;
    h->refs++;                                  /* the loops below must not free it under their feet */
    struct svc **sp = &g_svcs;
    while (*sp) {
        struct svc *s = *sp;
        if (s->h == h) { *sp = s->next; LOG(L_INFO, "%s died, %s dropped", s->nchain ? s->chain[0] : "service", s->name); svc_free(s); }
        else sp = &s->next;
    }
    struct reg **rp = &g_regs;
    while (*rp) { struct reg *r = *rp; if (r->h == h) { *rp = r->next; reg_free(r); } else rp = &r->next; }
    w_cmd(BC_DEAD_BINDER_DONE, &cookie, 8);
    w_hc(BC_CLEAR_DEATH_NOTIFICATION, h->handle, cookie);
    hnd_put(h);
}

/* ---------------------------------------------------------------------------------------------- requests */

#define MAXOBJ 24
struct obj { u32 type; const struct bbo *b; const struct fbo *f; };
struct req { u32 n; struct obj o[MAXOBJ]; };

// The objects of a transaction in the order of its offsets, checked against the buffer. The binder front end has already put
// every BINDER_TYPE_PTR buffer in the transaction's memory and pointed the parent's field at it.
static int req_parse(const struct btd *tr, struct req *r) {
    r->n = 0;
    const u8 *data = (const u8 *)(uptr)tr->buffer;
    const u64 *offs = (const u64 *)(uptr)tr->offsets;
    u32 no = (u32)(tr->offsets_size / 8);
    if (no > MAXOBJ) return -1;
    u64 sg = 0;
    for (u32 i = 0; i < no; i++) {
        u64 off = offs[i];
        if (off + 4 > tr->data_size) return -1;
        u32 type; memcpy(&type, data + off, 4);
        r->o[i].type = type; r->o[i].b = NULL; r->o[i].f = NULL;
        if (type == T_PTR) {
            if (off + sizeof(struct bbo) > tr->data_size) return -1;
            r->o[i].b = (const struct bbo *)(data + off);
            sg += A8(r->o[i].b->length);
        } else if (type == T_HANDLE || type == T_WEAK_HANDLE || type == T_BINDER) {
            if (off + sizeof(struct fbo) > tr->data_size) return -1;
            r->o[i].f = (const struct fbo *)(data + off);
        } else return -1;
    }
    r->n = no;
    /* every buffer lies inside the transaction's memory */
    u64 lo = tr->buffer, hi = tr->buffer + A8(tr->data_size) + tr->offsets_size + sg;
    for (u32 i = 0; i < no; i++) {
        const struct bbo *b = r->o[i].b;
        if (b && b->length && (b->buffer < lo || b->buffer + b->length > hi || b->buffer + b->length < b->buffer)) return -1;
    }
    return 0;
}
/* the next argument is a hidl_string: its struct (a buffer of 16 bytes) and its characters (a child of it) */
static int req_string(const struct req *r, u32 *i, const char **out) {
    u32 k = *i;
    if (k + 1 >= r->n) return -1;
    const struct bbo *s = r->o[k].b, *c = r->o[k + 1].b;
    if (!s || !c || s->length != sizeof(struct hstr) || (s->flags & BUF_HAS_PARENT)) return -1;
    if (!(c->flags & BUF_HAS_PARENT) || c->parent != k || c->parent_offset != 0 || c->length == 0) return -1;
    u32 size; memcpy(&size, (const u8 *)(uptr)s->buffer + 8, 4);
    if ((u64)size + 1 != c->length || size > 4096) return -1;
    const char *chars = (const char *)(uptr)c->buffer;
    if (chars[size] != 0) return -1;
    *out = chars; *i = k + 2;
    return 0;
}
/* the next argument is a nullable binder: a handle of this process, or nothing (a null object is not in the list) */
static int req_handle(const struct req *r, u32 i, u32 *handle) {
    if (i >= r->n) return 0;
    const struct obj *o = &r->o[i];
    if (!o->f || (o->type != T_HANDLE && o->type != T_WEAK_HANDLE)) return -1;
    *handle = (u32)o->f->binder;
    return 1;
}

/* ---------------------------------------------------------------------------------------------- nested calls */

struct sync { int state; struct btd tr; };      /* 0 waiting, 1 reply, 2 failed */
static void process_br(const u8 *buf, size_t len, struct sync *s);
static int g_depth;

static int wait_reply(struct sync *s) {
    u8 rb[2048] __attribute__((aligned(8)));
    memset(s, 0, sizeof *s);
    g_depth++;
    while (!s->state) {
        size_t n = 0;
        if (rw(rb, sizeof rb, &n) < 0) { s->state = 2; break; }
        process_br(rb, n, s);
    }
    g_depth--;
    return s->state == 1 ? 0 : -1;
}

/* IBase::interfaceChain of a service: its own interface and every one it extends */
static int query_chain(u32 handle, char **chain, int *nchain) {
    struct pb p; pb_init(&p, 128, 1);
    pb_cstr(&p, "android.hidl.base@1.0::IBase");
    send_txn(BC_TRANSACTION, handle, IB_CHAIN, 0, &p);
    struct sync s;
    if (wait_reply(&s) < 0) return -1;
    int ok = -1, n = 0;
    struct req r;
    const struct btd *tr = &s.tr;
    i32 status = -1;
    if (!(tr->flags & TF_STATUS_CODE) && tr->data_size >= 4) memcpy(&status, (const void *)(uptr)tr->buffer, 4);
    if (status == 0 && req_parse(tr, &r) == 0 && r.n >= 2 && r.o[0].b && r.o[1].b) {
        u32 vsize = 0;
        memcpy(&vsize, (const u8 *)(uptr)r.o[0].b->buffer + 8, 4);
        if (r.o[0].b->length == sizeof(struct hstr) && vsize >= 1 && vsize <= MAXCHAIN && r.n == 2 + vsize &&
            r.o[1].b->length == (u64)vsize * sizeof(struct hstr) && r.o[1].b->parent == 0) {
            ok = 0;
            for (u32 i = 0; i < vsize && ok == 0; i++) {
                const struct bbo *c = r.o[2 + i].b;
                if (!c || c->parent != 1 || c->parent_offset != (u64)i * sizeof(struct hstr) || c->length < 1 || c->length > 512) { ok = -1; break; }
                const char *chars = (const char *)(uptr)c->buffer;
                chain[n++] = xstrndup(chars, (size_t)c->length - 1);
            }
            if (ok < 0) for (int i = 0; i < n; i++) free(chain[i]);
        }
    }
    u64 buf = tr->buffer;
    w_cmd(BC_FREE_BUFFER, &buf, 8);
    if (ok < 0) return -1;
    *nchain = n;
    return 0;
}

/* ---------------------------------------------------------------------------------------------- VINTF manifests */

struct decl { char pkg[96]; u32 major, minor; char iface[64]; char inst[64]; int transport; };
static struct decl *g_decls; static u32 g_ndecls, g_cdecls;

static void decl_add(const char *pkg, u32 major, u32 minor, const char *iface, const char *inst, int transport) {
    if (g_ndecls == g_cdecls) {
        g_cdecls = g_cdecls ? g_cdecls * 2 : 128;
        g_decls = realloc(g_decls, g_cdecls * sizeof *g_decls);
        if (!g_decls) { LOG(L_ERR, "out of memory"); _exit(1); }
    }
    struct decl *d = &g_decls[g_ndecls++];
    memset(d, 0, sizeof *d);
    size_t n;
    n = strlen(pkg); if (n >= sizeof d->pkg) n = sizeof d->pkg - 1; memcpy(d->pkg, pkg, n);
    n = strlen(iface); if (n >= sizeof d->iface) n = sizeof d->iface - 1; memcpy(d->iface, iface, n);
    n = strlen(inst); if (n >= sizeof d->inst) n = sizeof d->inst - 1; memcpy(d->inst, inst, n);
    d->major = major; d->minor = minor; d->transport = transport;
}

static char *find_str(char *hay, const char *needle) {
    size_t n = strlen(needle);
    for (; *hay; hay++) if (*hay == *needle && strncmp(hay, needle, n) == 0) return hay;
    return NULL;
}

static u32 parse_uint(const char **s) { u32 v = 0; while (**s >= '0' && **s <= '9') { v = v * 10 + (u32)(**s - '0'); (*s)++; } return v; }
/* "2.1" */
static int parse_version(const char *s, u32 *major, u32 *minor) {
    if (*s < '0' || *s > '9') return -1;
    *major = parse_uint(&s);
    if (*s != '.') return -1;
    s++;
    if (*s < '0' || *s > '9') return -1;
    *minor = parse_uint(&s);
    return 0;
}
/* "android.hardware.graphics.composer@2.1::IComposer" -> package, version, interface */
static int parse_fq(const char *fq, char *pkg, size_t pkgsz, u32 *major, u32 *minor, char *iface, size_t ifsz) {
    const char *at = strchr(fq, '@');
    if (!at || (size_t)(at - fq) >= pkgsz) return -1;
    memcpy(pkg, fq, (size_t)(at - fq)); pkg[at - fq] = 0;
    const char *p = at + 1;
    if (*p < '0' || *p > '9') return -1;
    *major = parse_uint(&p);
    if (*p != '.') return -1;
    p++;
    *minor = parse_uint(&p);
    if (p[0] != ':' || p[1] != ':') return -1;
    p += 2;
    size_t n = strlen(p);
    if (n == 0 || n >= ifsz) return -1;
    memcpy(iface, p, n + 1);
    return 0;
}

struct hal {
    char pkg[96]; int hidl, transport;
    u32 nver, vmaj[8], vmin[8];
    u32 nif; char ifname[16][64]; u32 ninst[16]; char inst[16][16][64];
    u32 nfq; u32 fmaj[32], fmin[32]; char fiface[32][64]; char finst[32][64];
};
static void cpy(char *dst, size_t cap, const char *s, size_t n) { if (n >= cap) n = cap - 1; memcpy(dst, s, n); dst[n] = 0; }

static void hal_flush(const struct hal *h) {
    if (!h->hidl || !h->pkg[0] || h->transport == TR_EMPTY) return;
    for (u32 i = 0; i < h->nif; i++) for (u32 v = 0; v < h->nver; v++) for (u32 k = 0; k < h->ninst[i]; k++)
        decl_add(h->pkg, h->vmaj[v], h->vmin[v], h->ifname[i], h->inst[i][k], h->transport);
    for (u32 i = 0; i < h->nfq; i++) decl_add(h->pkg, h->fmaj[i], h->fmin[i], h->fiface[i], h->finst[i], h->transport);
}

// A tolerant scan of one manifest: <hal format="hidl"> blocks with <name>, <transport>, <version>, <fqname> and
// <interface><name>..</name><instance>..</instance></interface>. Anything else is ignored.
static void scan_manifest(char *t) {
    static struct hal h;
    int in_hal = 0, in_if = 0;
    char *p = t;
    while ((p = strchr(p, '<'))) {
        if (p[1] == '!') { char *e = p[2] == '-' && p[3] == '-' ? NULL : strchr(p, '>'); if (!e) { e = p; while (*e && !(e[0] == '-' && e[1] == '-' && e[2] == '>')) e++; if (!*e) break; e += 2; } p = e + 1; continue; }
        if (p[1] == '?') { char *e = strchr(p, '>'); if (!e) break; p = e + 1; continue; }
        int closing = p[1] == '/';
        char *nm = p + 1 + closing, *ne = nm;
        while (*ne && *ne != '>' && *ne != ' ' && *ne != '\t' && *ne != '\n' && *ne != '\r' && *ne != '/') ne++;
        char *gt = strchr(ne, '>');
        if (!gt) break;
        size_t nl = (size_t)(ne - nm);
        int selfclose = gt > p && gt[-1] == '/';
        #define IS(s) (nl == sizeof(s) - 1 && memcmp(nm, s, nl) == 0)
        if (IS("hal")) {
            if (closing) { if (in_hal) hal_flush(&h); in_hal = 0; in_if = 0; }
            else if (!selfclose) {
                memset(&h, 0, sizeof h); in_hal = 1; in_if = 0; h.hidl = 1;
                char save = *gt; *gt = 0;
                char *f = find_str(ne, "format=");
                if (f) { f += 7; char q = *f; if (q == '"' || q == '\'') { f++; h.hidl = strncmp(f, "hidl", 4) == 0 && f[4] == q; } }
                *gt = save;
            }
            p = gt + 1; continue;
        }
        if (!in_hal || closing || selfclose) { if (in_hal && closing && IS("interface")) in_if = 0; p = gt + 1; continue; }
        if (IS("interface")) { in_if = 1; if (h.nif < 16) { h.ifname[h.nif][0] = 0; h.ninst[h.nif] = 0; h.nif++; } p = gt + 1; continue; }
        if (IS("name") || IS("transport") || IS("version") || IS("fqname") || IS("instance")) {
            char *ts = gt + 1, *te = strchr(ts, '<');
            if (!te) break;
            while (ts < te && (*ts == ' ' || *ts == '\n' || *ts == '\t' || *ts == '\r')) ts++;
            char *tz = te; while (tz > ts && (tz[-1] == ' ' || tz[-1] == '\n' || tz[-1] == '\t' || tz[-1] == '\r')) tz--;
            size_t tl = (size_t)(tz - ts);
            if (IS("name")) { if (in_if) { if (h.nif) cpy(h.ifname[h.nif - 1], sizeof h.ifname[0], ts, tl); } else cpy(h.pkg, sizeof h.pkg, ts, tl); }
            else if (IS("transport")) {
                if (tl == 8 && memcmp(ts, "hwbinder", 8) == 0) h.transport = TR_HWBINDER;
                else if (tl == 11 && memcmp(ts, "passthrough", 11) == 0) { if (h.transport != TR_HWBINDER) h.transport = TR_PASSTHROUGH; }
            } else if (IS("version")) {
                char v[24]; cpy(v, sizeof v, ts, tl);
                u32 a, b; if (h.nver < 8 && parse_version(v, &a, &b) == 0) { h.vmaj[h.nver] = a; h.vmin[h.nver] = b; h.nver++; }
            } else if (IS("fqname")) {
                char v[160]; cpy(v, sizeof v, ts, tl);
                const char *s = v;
                if (*s == '@' && h.nfq < 32) {
                    s++;
                    u32 a = 0, b = 0;
                    if (*s >= '0' && *s <= '9') { a = parse_uint(&s); if (*s == '.') { s++; b = parse_uint(&s); } }
                    if (s[0] == ':' && s[1] == ':') {
                        s += 2;
                        const char *sl = strchr(s, '/');
                        if (sl) { cpy(h.fiface[h.nfq], sizeof h.fiface[0], s, (size_t)(sl - s)); cpy(h.finst[h.nfq], sizeof h.finst[0], sl + 1, strlen(sl + 1)); h.fmaj[h.nfq] = a; h.fmin[h.nfq] = b; h.nfq++; }
                    }
                }
            } else if (IS("instance")) {
                if (in_if && h.nif && h.ninst[h.nif - 1] < 16) { cpy(h.inst[h.nif - 1][h.ninst[h.nif - 1]], sizeof h.inst[0][0], ts, tl); h.ninst[h.nif - 1]++; }
            }
            p = te; continue;
        }
        #undef IS
        p = gt + 1;
    }
}

static void scan_file(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    size_t cap = 1 << 16, len = 0;
    char *buf = malloc(cap + 1);
    while (buf) {
        if (len == cap) { if (cap >= (1u << 22)) break; cap *= 2; char *nb = realloc(buf, cap + 1); if (!nb) break; buf = nb; }
        ssize_t n = read(fd, buf + len, cap - len);
        if (n <= 0) break;
        len += (size_t)n;
    }
    close(fd);
    if (buf) { buf[len] = 0; scan_manifest(buf); free(buf); }
}
static void scan_dir(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        size_t n = strlen(e->d_name);
        if (n < 5 || memcmp(e->d_name + n - 4, ".xml", 4) != 0) continue;
        char path[512];
        size_t dl = strlen(dir);
        if (dl + 1 + n + 1 > sizeof path) continue;
        memcpy(path, dir, dl); path[dl] = '/'; memcpy(path + dl + 1, e->d_name, n + 1);
        scan_file(path);
    }
    closedir(d);
}
static void scan_all(void) {
    static const char *const files[] = {
        "/vendor/etc/vintf/manifest.xml", "/vendor/manifest.xml", "/odm/etc/vintf/manifest.xml", "/odm/etc/manifest.xml",
        "/system/etc/vintf/manifest.xml", "/system_ext/etc/vintf/manifest.xml", "/product/etc/vintf/manifest.xml",
    };
    static const char *const dirs[] = {
        "/vendor/etc/vintf/manifest", "/odm/etc/vintf/manifest", "/system/etc/vintf/manifest",
        "/system_ext/etc/vintf/manifest", "/product/etc/vintf/manifest",
    };
    char path[400];
    const char *root = ROOT();
    size_t rl = strlen(root);
    for (unsigned i = 0; i < sizeof files / sizeof *files; i++) {
        if (rl + strlen(files[i]) + 1 > sizeof path) continue;
        memcpy(path, root, rl); memcpy(path + rl, files[i], strlen(files[i]) + 1);
        scan_file(path);
    }
    for (unsigned i = 0; i < sizeof dirs / sizeof *dirs; i++) {
        if (rl + strlen(dirs[i]) + 1 > sizeof path) continue;
        memcpy(path, root, rl); memcpy(path + rl, dirs[i], strlen(dirs[i]) + 1);
        scan_dir(path);
    }
    LOG(L_INFO, "%u HIDL declarations in the VINTF manifests", g_ndecls);
}

/* the transport the manifests give an instance: a declared minor version covers the older ones of its major */
static int manifest_transport(const char *fq, const char *inst) {
    char pkg[96], iface[64]; u32 major, minor;
    if (parse_fq(fq, pkg, sizeof pkg, &major, &minor, iface, sizeof iface) < 0) return TR_EMPTY;
    int found = TR_EMPTY;
    for (u32 i = 0; i < g_ndecls; i++) {
        const struct decl *d = &g_decls[i];
        if (d->major != major || d->minor < minor || !streq(d->pkg, pkg) || !streq(d->iface, iface) || !streq(d->inst, inst)) continue;
        if (d->transport == TR_HWBINDER) return TR_HWBINDER;
        found = d->transport;
    }
    return found;
}

/* ---------------------------------------------------------------------------------------------- IServiceManager */

static void reply_ok_bool(int v) {
    struct pb p; pb_init(&p, 16, 1);
    pb_i32(&p, 0); pb_i32(&p, v ? 1 : 0);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}
static void reply_ok_empty(void) {
    struct pb p; pb_init(&p, 16, 1);
    pb_i32(&p, 0);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

static void sm_get(const struct req *r) {
    u32 i = 0; const char *fq, *name;
    if (req_string(r, &i, &fq) < 0 || req_string(r, &i, &name) < 0) { reply_status(EBADMSG_STATUS); return; }
    struct svc *s = svc_find(fq, name);
    struct pb p; pb_init(&p, 64, 2);
    pb_i32(&p, 0);
    if (!s && is_manager_iface(fq, name)) pb_self_binder(&p);
    else if (s) pb_handle(&p, s->h->handle);
    else {
        pb_null_binder(&p);
        if (first_time("get", fq, name)) LOG(L_INFO, "get %s/%s: not registered", fq, name);
    }
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

/* the service is known under every interface of its chain; takes over iname and the chain strings */
static void add_registered(char *iname, struct hnd *h, u32 handle, char **chain, int n) {
    struct svc *s = xzalloc(sizeof *s);
    s->h = h; s->name = iname; s->nchain = n;
    for (int k = 0; k < n; k++) s->chain[k] = chain[k];
    /* a service registered again under the same name replaces the earlier one (its process has not been seen dying yet) */
    struct svc **sp = &g_svcs;
    while (*sp) {
        struct svc *o = *sp;
        if (streq(o->name, s->name) && o->nchain && streq(o->chain[0], s->chain[0])) { *sp = o->next; svc_free(o); }
        else sp = &o->next;
    }
    s->next = g_svcs; g_svcs = s;
    LOG(L_INFO, "registered %s/%s (handle %u, %d interfaces)", s->chain[0], s->name, handle, n);
    for (int k = 0; k < n; k++) for (struct reg *rg = g_regs; rg; rg = rg->next) if (reg_matches(rg, s->chain[k], s->name)) notify(rg, s->chain[k], s->name, 0);
    reply_ok_bool(1);
}

static void sm_add(const struct req *r) {
    u32 i = 0; const char *name; u32 handle = 0;
    if (req_string(r, &i, &name) < 0) { reply_status(EBADMSG_STATUS); return; }
    int has = req_handle(r, i, &handle);
    if (has < 0) { reply_status(EBADMSG_STATUS); return; }
    if (!has) { LOG(L_WARN, "add %s: no service", name); reply_ok_bool(0); return; }
    char *iname = xstrdup(name);
    struct hnd *h = hnd_get(handle);
    char *chain[MAXCHAIN]; int n = 0;
    int ok = !h->dead && query_chain(handle, chain, &n) == 0 && !h->dead;
    if (!ok) {
        LOG(L_ERR, "add %s: the service did not tell its interface chain", iname);
        free(iname); hnd_put(h); reply_ok_bool(0);
        return;
    }
    add_registered(iname, h, handle, chain, n);
}

/* @1.2 addWithChain(string name, interface service, vec<string> chain): name (2 objects), the service (1), the vec struct
 * (1, 16 bytes), its array (1, a child of the struct) and one child per string. Returns -1 when the call does not have
 * that shape (another method of @1.2 or a damaged call): the caller answers with a transaction error then. */
static int sm_add_with_chain(const struct req *r) {
    u32 i = 0; const char *name; u32 handle = 0;
    if (req_string(r, &i, &name) < 0) return -1;
    if (req_handle(r, i, &handle) != 1) return -1;
    u32 v = i + 1;
    if (v + 1 >= r->n) return -1;
    const struct bbo *vs = r->o[v].b, *va = r->o[v + 1].b;
    if (!vs || !va || vs->length != 16 || (vs->flags & BUF_HAS_PARENT) ||
        !(va->flags & BUF_HAS_PARENT) || va->parent != v || va->parent_offset != 0) return -1;
    u32 vsize = 0;
    memcpy(&vsize, (const u8 *)(uptr)vs->buffer + 8, 4);
    if (vsize < 1 || vsize > MAXCHAIN || r->n != v + 2 + vsize || va->length != (u64)vsize * 16) return -1;
    char *chain[MAXCHAIN]; int n = 0;
    for (u32 k = 0; k < vsize; k++) {
        const struct bbo *c = r->o[v + 2 + k].b;
        if (!c || c->parent != v + 1 || c->parent_offset != (u64)k * 16 || c->length < 1 || c->length > 512) {
            for (int j = 0; j < n; j++) free(chain[j]);
            return -1;
        }
        chain[n++] = xstrndup((const char *)(uptr)c->buffer, (size_t)c->length - 1);
    }
    struct hnd *h = hnd_get(handle);
    if (h->dead) { for (int j = 0; j < n; j++) free(chain[j]); hnd_put(h); reply_ok_bool(0); return 0; }
    add_registered(xstrdup(name), h, handle, chain, n);
    return 0;
}

static void sm_get_transport(const struct req *r) {
    u32 i = 0; const char *fq, *name;
    if (req_string(r, &i, &fq) < 0 || req_string(r, &i, &name) < 0) { reply_status(EBADMSG_STATUS); return; }
    int t = manifest_transport(fq, name);
    if (t == TR_EMPTY && is_manager_iface(fq, name)) t = TR_HWBINDER;
    if (t == TR_EMPTY && svc_find(fq, name)) t = TR_HWBINDER;       /* running, so reachable by hwbinder, declared or not */
    if (t == TR_EMPTY && first_time("transport", fq, name)) LOG(L_INFO, "getTransport %s/%s: not in any manifest", fq, name);
    struct pb p; pb_init(&p, 16, 1);
    pb_i32(&p, 0); pb_i32(&p, t);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

static void sm_list(void) {
    u32 n = 0;
    for (struct svc *s = g_svcs; s; s = s->next) if (!s->h->dead) n += (u32)s->nchain;
    char **v = aalloc((n + 1) * sizeof *v);
    u32 k = 0;
    for (struct svc *s = g_svcs; s; s = s->next) {
        if (s->h->dead) continue;
        for (int c = 0; c < s->nchain; c++) {
            size_t l = strlen(s->chain[c]) + 1 + strlen(s->name);
            char *e = aalloc(l + 1);
            memcpy(e, s->chain[c], strlen(s->chain[c])); e[strlen(s->chain[c])] = '/'; memcpy(e + strlen(s->chain[c]) + 1, s->name, strlen(s->name));
            v[k++] = e;
        }
    }
    struct pb p; pb_init(&p, 64 + 48 * (n + 2), n + 3);
    pb_i32(&p, 0);
    pb_string_vec(&p, v, k);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

static void sm_list_by_interface(const struct req *r) {
    u32 i = 0; const char *fq;
    if (req_string(r, &i, &fq) < 0) { reply_status(EBADMSG_STATUS); return; }
    u32 n = 0;
    for (struct svc *s = g_svcs; s; s = s->next) n++;
    char **v = aalloc((n + 1) * sizeof *v);
    u32 k = 0;
    for (struct svc *s = g_svcs; s; s = s->next) {
        if (s->h->dead) continue;
        for (int c = 0; c < s->nchain; c++) if (streq(s->chain[c], fq)) { v[k++] = s->name; break; }
    }
    struct pb p; pb_init(&p, 64 + 48 * (n + 2), n + 3);
    pb_i32(&p, 0);
    pb_string_vec(&p, v, k);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

static void sm_register_for_notifications(const struct req *r) {
    u32 i = 0; const char *fq, *name; u32 handle = 0;
    if (req_string(r, &i, &fq) < 0 || req_string(r, &i, &name) < 0) { reply_status(EBADMSG_STATUS); return; }
    int has = req_handle(r, i, &handle);
    if (has < 0) { reply_status(EBADMSG_STATUS); return; }
    if (!has) { reply_ok_bool(0); return; }
    struct hnd *h = hnd_get(handle);
    if (h->dead) { hnd_put(h); reply_ok_bool(0); return; }
    struct reg *dup = NULL;
    for (struct reg *x = g_regs; x; x = x->next) if (x->h == h && streq(x->fq, fq) && streq(x->name, name)) dup = x;
    if (dup) { hnd_put(h); reply_ok_bool(1); return; }
    struct reg *rg = xzalloc(sizeof *rg);
    rg->h = h; rg->fq = xstrdup(fq); rg->name = xstrdup(name);
    rg->next = g_regs; g_regs = rg;
    /* what is registered already is announced at once, marked as preexisting */
    for (struct svc *s = g_svcs; s; s = s->next) {
        if (s->h->dead) continue;
        for (int c = 0; c < s->nchain; c++) if (reg_matches(rg, s->chain[c], s->name)) notify(rg, s->chain[c], s->name, 1);
    }
    reply_ok_bool(1);
}

static void sm_unregister_for_notifications(const struct req *r) {
    u32 i = 0; const char *fq, *name; u32 handle = 0;
    if (req_string(r, &i, &fq) < 0 || req_string(r, &i, &name) < 0) { reply_status(EBADMSG_STATUS); return; }
    int has = req_handle(r, i, &handle);
    if (has < 0) { reply_status(EBADMSG_STATUS); return; }
    int removed = 0;
    if (has) {
        struct reg **rp = &g_regs;
        while (*rp) {
            struct reg *x = *rp;
            if (x->h->handle == handle && streq(x->fq, fq) && streq(x->name, name)) { *rp = x->next; reg_free(x); removed = 1; break; }
            rp = &x->next;
        }
    }
    reply_ok_bool(removed);
}

static void sm_debug_dump(void) {
    struct pb p; pb_init(&p, 128, 4);
    pb_i32(&p, 0);
    pb_empty_vec(&p);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

static void ib_descriptor(void) {
    struct pb p; pb_init(&p, 256, 4);
    pb_i32(&p, 0);
    pb_string(&p, MANAGER_DESC);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}
static void ib_chain(void) {
    struct pb p; pb_init(&p, 512, 8);
    pb_i32(&p, 0);
    pb_string_vec(&p, (char *const *)g_manager_chain, MANAGER_NCHAIN);
    send_txn(BC_REPLY, 0, 0, 0, &p);
}

static void handle_txn(const struct btd *tr) {
    int oneway = (tr->flags & TF_ONE_WAY) != 0;
    struct req r;
    int bad = req_parse(tr, &r) < 0;
    int replied = 1;
    if (bad && !oneway) { reply_status(EBADMSG_STATUS); }
    else if (!bad) {
        replied = 0;
        switch (tr->code) {
        case SM_GET: sm_get(&r); break;
        case SM_ADD: sm_add(&r); break;
        case SM_ADD_WITH_CHAIN:
            if (sm_add_with_chain(&r) < 0) reply_status(EBADMSG_STATUS);
            break;
        case SM_GET_TRANSPORT: sm_get_transport(&r); break;
        case SM_LIST: sm_list(); break;
        case SM_LIST_BY_INTERFACE: case SM_LIST_MANIFEST_BY_INTERFACE: sm_list_by_interface(&r); break;
        case SM_REGISTER_FOR_NOTIFICATIONS: sm_register_for_notifications(&r); break;
        case SM_DEBUG_DUMP: sm_debug_dump(); break;
        case SM_REGISTER_PASSTHROUGH_CLIENT: reply_ok_empty(); break;
        case SM_UNREGISTER_FOR_NOTIFICATIONS: sm_unregister_for_notifications(&r); break;
        case IB_PING: case IB_DEBUG: reply_ok_empty(); break;
        case IB_CHAIN: ib_chain(); break;
        case IB_DESCRIPTOR: ib_descriptor(); break;
        case IB_HASH_CHAIN: sm_debug_dump(); break;           /* an empty vec: no hashes */
        case IB_SYSPROPS: case IB_INSTRUMENTATION: replied = 1; break;
        default:
            /* an @1.2 call this manager does not implement: addWithChain is told from the others by the shape of its
             * arguments, in case the call number differs from the one above */
            if (tr->code >= SM_FIRST_1_2 && tr->code <= SM_LAST_1_2 && !oneway && sm_add_with_chain(&r) == 0) break;
            if (!oneway) reply_status(EBADMSG_STATUS);
            replied = 1;
            break;
        }
        if (oneway && !replied) { /* a reply nobody waits for would be refused by the driver: take it back */ }
    }
    (void)replied;
    u64 buf = tr->buffer;
    w_cmd(BC_FREE_BUFFER, &buf, 8);
}

/* ---------------------------------------------------------------------------------------------- the loop */

static void process_br(const u8 *buf, size_t len, struct sync *s) {
    const u8 *p = buf, *end = buf + len;
    while (p + 4 <= end) {
        u32 cmd; memcpy(&cmd, p, 4);
        u32 sz = (cmd >> 16) & 0x3fff;
        p += 4;
        if (p + sz > end) break;
        switch (cmd) {
        case BR_TRANSACTION: case BR_TRANSACTION_SEC_CTX: {
            struct btd tr; memcpy(&tr, p, sizeof tr);
            if (tr.flags & TF_ONE_WAY) {
                handle_txn(&tr);
            } else {
                size_t before = g_wl;
                handle_txn(&tr);
                (void)before;
            }
            break;
        }
        case BR_REPLY: {
            struct btd tr; memcpy(&tr, p, sizeof tr);
            if (s && !s->state) { s->tr = tr; s->state = 1; }
            else { u64 b = tr.buffer; w_cmd(BC_FREE_BUFFER, &b, 8); }
            break;
        }
        case BR_DEAD_REPLY: case BR_FAILED_REPLY:
            if (s && !s->state) s->state = 2;
            break;
        case BR_INCREFS: case BR_ACQUIRE: {                 /* the context manager node, which nobody else may drop */
            u8 b[16]; memcpy(b, p, 16);
            w_cmd(cmd == BR_INCREFS ? BC_INCREFS_DONE : BC_ACQUIRE_DONE, b, 16);
            break;
        }
        case BR_DEAD_BINDER: { u64 cookie; memcpy(&cookie, p, 8); hnd_died(cookie); break; }
        default: break;                                      /* BR_NOOP, BR_TRANSACTION_COMPLETE, BR_RELEASE, BR_DECREFS, BR_SPAWN_LOOPER, ... */
        }
        p += sz;
    }
}

static int become_context_manager(void) {
    struct fbo o; memset(&o, 0, sizeof o);
    o.flags = 0x7f | 0x100;
    if (BN_IOCTL(g_fd, BINDER_SET_CONTEXT_MGR_EXT, &o) == 0) return 0;
    int zero = 0;
    return BN_IOCTL(g_fd, BINDER_SET_CONTEXT_MGR, &zero);
}

int main(int argc, char **argv, char **envp) {
    (void)argc; (void)argv; (void)envp;
    g_fd = BN_OPEN("/dev/hwbinder");
    if (g_fd < 0) { LOG(L_ERR, "cannot open /dev/hwbinder"); return 1; }
    i32 version[2] = { 0, 0 };
    if (BN_IOCTL(g_fd, BINDER_VERSION, version) < 0 || version[0] != 8) LOG(L_WARN, "binder protocol version %d (expected 8)", version[0]);
    if (!BN_MMAP(g_fd, 1024 * 1024 - 8192)) { LOG(L_ERR, "cannot map the transaction buffer"); return 1; }
    u32 zero = 0;
    BN_IOCTL(g_fd, BINDER_SET_MAX_THREADS, &zero);          /* one thread: a call is answered before the next is read */
    if (become_context_manager() < 0) { LOG(L_ERR, "cannot become the context manager of /dev/hwbinder (another one runs?)"); return 1; }
    scan_all();
    w_cmd(BC_ENTER_LOOPER, NULL, 0);
    rw(NULL, 0, NULL);
    /* Android 17's libhidl waits for hwservicemanager.disabled to be set before it decides whether HIDL goes through a
     * hwservicemanager or through passthrough ("Waited for hwservicemanager.disabled for a second, waiting another...":
     * SurfaceFlinger, audioserver, netd, cameraserver... sat in that wait while the composer was registered here, and
     * SurfaceFlinger then aborted with "failed to get hwcomposer service"). Nothing set it: a real device's init does.
     * "false" says there is a hwservicemanager; it is set before .ready so that nobody sees .ready with no answer. */
    SETPROP("hwservicemanager.disabled", "false");
    SETPROP("hwservicemanager.ready", "true");
    LOG(L_INFO, "context manager of /dev/hwbinder, hwservicemanager.disabled=false, hwservicemanager.ready set");
    for (;;) {
        u8 rb[4096] __attribute__((aligned(8)));
        size_t n = 0;
        if (rw(rb, sizeof rb, &n) < 0) { usleep(50000); continue; }
        g_ap = 0;                                           /* what the last round queued has been sent */
        process_br(rb, n, NULL);
    }
}

#ifndef AEMU_HOST_TEST
/* what the NDK's crtbegin_dynamic.o provides (bionic libc/arch-common/bionic/crtbegin.c), since there are no bionic crt files */
typedef void init_func_t(int, char *[], char *[]);
typedef void fini_func_t(void);
__attribute__((section(".preinit_array"), used)) init_func_t *__PREINIT_ARRAY__[2] = { (init_func_t *)-1, 0 };
__attribute__((section(".init_array.0"), used)) init_func_t *__INIT_ARRAY__[2] = { (init_func_t *)-1, 0 };
__attribute__((section(".fini_array.0"), used)) fini_func_t *__FINI_ARRAY__[2] = { (fini_func_t *)-1, 0 };
__attribute__((used)) void *__dso_handle = 0;
__attribute__((used)) static void _start_main(void *raw_args) {
    struct { init_func_t **preinit_array; init_func_t **init_array; fini_func_t **fini_array; } array;
    array.preinit_array = __PREINIT_ARRAY__;
    array.init_array = __INIT_ARRAY__;
    array.fini_array = __FINI_ARRAY__;
    __libc_init(raw_args, 0, main, &array);
}
__asm__(".text; .global _start; .type _start,%function; _start:"
        "bti j; mov x29,#0; mov x30,#0; mov x0,sp; b _start_main; .size _start, .-_start");
#endif
