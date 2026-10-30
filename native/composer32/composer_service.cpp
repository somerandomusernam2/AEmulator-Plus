/*
 * android.hardware.graphics.composer@2.1-service of the native arm64 engine, 32-bit build (Android 13, kernel 33).
 *
 * A 32-bit-only Qualcomm Wear OS 4 firmware has no standard HIDL composer: its only one is
 * vendor.qti.hardware.display.composer-service (SDM, needs the display hardware, dies at start here), and the engine's
 * own composer service is a 64-bit program, which a firmware with no 64-bit side cannot run. SurfaceFlinger then logs
 * "failed to get hwcomposer service" and aborts.
 *
 * This is what AOSP's service main() does, registerPassthroughServiceImplementation<IComposer>(), without AOSP's
 * headers (the same way as native/allocsvc): the interface library (android.hardware.graphics.composer@2.1.so) is
 * dlopen()ed and its own getService() and registerAsService() are called through their mangled names. getService(name,
 * true) loads ...composer@2.1-impl.so (native/composer32/composer_impl.cpp) and calls its HIDL_FETCH_IComposer, which
 * opens the engine's hwcomposer.default.so (native/hwchal) behind the HWC2-on-1 adapter.
 *
 * Only the first word of an sp<> and the three words of a libc++ std::string are touched, both the same in every
 * layout of libc++ (an empty string is all zeros; the text is put in with the library's own assign()).
 *
 * Fix (2026-10-28): "dlopen failed: cannot locate symbol _ZTIN7android8hardware8graphics8composer4V2_19IComposerE referenced by
 * /vendor/lib/hw/android.hardware.graphics.composer@2.1-impl.so", after which the service exited with code 1, SurfaceFlinger
 * found no composer and aborted. The impl is built with RTTI, so its classes' type_info point at the type_info of
 * IComposer, IComposerClient and android.hidl.base@1.0::IBase. The firmware's own HIDL libraries are built with -fno-rtti
 * and do not export them. This program now defines the three (exported from the executable: the main program is in the
 * linker's global group of every namespace, so the impl's relocations find them), filled in with libc++'s own
 * class_type_info vtables before the impl is loaded. Nothing uses them for casts (the HAL code has no dynamic_cast), they
 * only have to exist and be real objects.
 *
 * Build: sh build.sh (zig, no NDK needed) or the NDK's clang as before; see build.sh.
 */
#include <stddef.h>
#include <stdint.h>
#ifdef AEMU_NO_BIONIC_HEADERS
/* built with zig, which has no bionic headers: the few functions used, as the NDK's headers declare them */
extern "C" {
void *dlopen(const char *file, int flags);
void *dlsym(void *handle, const char *name);
char *dlerror(void);
int __android_log_print(int prio, const char *tag, const char *fmt, ...);
__attribute__((noreturn)) void abort(void);
__attribute__((noreturn)) void _exit(int status);
__attribute__((noreturn)) void __libc_init(void *raw_args, void (*onexit)(void), int (*slingshot)(int, char **, char **), const void *structors);
}
/* bionic's dlfcn.h: the 32-bit values differ from the 64-bit ones (0x102 = "invalid flags to dlopen" on 32-bit) */
#if defined(__LP64__)
#define RTLD_NOW 0x2
#define RTLD_GLOBAL 0x100
#else
#define RTLD_NOW 0x0
#define RTLD_GLOBAL 0x2
#endif
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_ERROR 6
#else
#include <dlfcn.h>
#include <stdlib.h>
#include <unistd.h>
#include <android/log.h>
#endif

#define TAG "composer@2.1-service"
#define ERR(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define INFO(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* a class with a destructor is returned through a hidden pointer, as android::sp<T> is */
struct Sp {
    void *p;
    Sp() : p(0) {}
    ~Sp() {}
};
struct CxxString { uintptr_t w[3]; };       /* std::string of libc++: three words in either word size */

typedef Sp (*get_service_fn)(const CxxString *name, bool get_stub);
typedef int (*register_fn)(void *self, const CxxString *name);
typedef void *(*assign_fn)(CxxString *self, const char *s);
typedef void (*configure_fn)(size_t max_threads, bool caller_will_join);
typedef void (*join_fn)(void);

/*
 * The type_info objects the impl needs (see the Fix note above). Layout of the Itanium C++ ABI, 32-bit and 64-bit alike:
 * __class_type_info = { vptr, name }, __si_class_type_info = { vptr, name, base }. vptr is the "address point" of the
 * vtable, two words after the symbol libc++ exports.
 */
struct TypeInfo { const void *vptr; const char *name; const void *base; };

#define EXPORT_TI(var, sym, text, base_ti) \
    __attribute__((visibility("default"), used)) TypeInfo var __asm__(sym) = { 0, text, base_ti }

EXPORT_TI(ti_ibase, "_ZTIN7android4hidl4base4V1_05IBaseE",
          "N7android4hidl4base4V1_05IBaseE", 0);
EXPORT_TI(ti_icomposer, "_ZTIN7android8hardware8graphics8composer4V2_19IComposerE",
          "N7android8hardware8graphics8composer4V2_19IComposerE", &ti_ibase);
EXPORT_TI(ti_icomposerclient, "_ZTIN7android8hardware8graphics8composer4V2_115IComposerClientE",
          "N7android8hardware8graphics8composer4V2_115IComposerClientE", &ti_ibase);

static void fill_type_infos(void *cxx) {
    const char *cti = "_ZTVN10__cxxabiv117__class_type_infoE";
    const char *sti = "_ZTVN10__cxxabiv120__si_class_type_infoE";
    char *v_cti = (char *)dlsym(cxx, cti);
    char *v_sti = (char *)dlsym(cxx, sti);
    if (!v_cti || !v_sti) { ERR("type_info vtables not found in libc++.so: %s", dlerror()); return; }
    ti_ibase.vptr = v_cti + 2 * sizeof(void *);
    ti_icomposer.vptr = v_sti + 2 * sizeof(void *);
    ti_icomposerclient.vptr = v_sti + 2 * sizeof(void *);
}

/*
 * Fix (2026-10-28, second): the next load error was "cannot locate symbol _ZN7android8hardware7details5checkEbPKc". The impl
 * was built against a newer libhidlbase than this firmware's (Android 13): android::hardware::details::check(bool, const
 * char *) (used by hidl_vec's bounds check) does not exist there, and the same goes for logError() / errorWriteLog() in some
 * releases. The three are exported from this executable as well. Each one first looks for the firmware's own function in
 * libhidlbase.so (dlsym on that library's handle does not see this executable) and calls it if it exists, so a firmware that
 * has it is not changed; only when it is missing does the small fallback below run.
 */
static void *g_hidlbase;
static void *real_fn(void **cache, const char *sym) {
    if (!*cache && g_hidlbase) *cache = dlsym(g_hidlbase, sym);
    return *cache;
}
#define SYM_CHECK "_ZN7android8hardware7details5checkEbPKc"
#define SYM_LOGERR "_ZN7android8hardware7details8logErrorERKNSt3__112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE"
#define SYM_ERRLOG "_ZN7android8hardware7details13errorWriteLogEiPKc"

__attribute__((visibility("default"), used)) void hidl_check(bool cond, const char *msg) __asm__(SYM_CHECK);
void hidl_check(bool cond, const char *msg) {
    static void *cache; static bool looked;
    void *f = looked ? cache : (looked = true, real_fn(&cache, SYM_CHECK));
    if (f) { ((void (*)(bool, const char *))f)(cond, msg); return; }
    if (!cond) { ERR("hidl check failed: %s", msg ? msg : "(null)"); abort(); }
}

__attribute__((visibility("default"), used)) void hidl_log_error(const void *message) __asm__(SYM_LOGERR);
void hidl_log_error(const void *message) {
    static void *cache; static bool looked;
    void *f = looked ? cache : (looked = true, real_fn(&cache, SYM_LOGERR));
    if (f) { ((void (*)(const void *))f)(message); return; }
    ERR("hidl error (std::string message not decoded)");     /* libc++'s string layout is not touched here */
}

__attribute__((visibility("default"), used)) int hidl_error_write_log(int tag, const char *info) __asm__(SYM_ERRLOG);
int hidl_error_write_log(int tag, const char *info) {
    static void *cache; static bool looked;
    void *f = looked ? cache : (looked = true, real_fn(&cache, SYM_ERRLOG));
    if (f) return ((int (*)(int, const char *))f)(tag, info);
    ERR("errorWriteLog %d: %s", tag, info ? info : "");
    return 0;
}

/*
 * Fix (2026-10-28, third): the next load error was "cannot locate symbol
 * _ZN7android8hardware9EventFlag15createEventFlagEPNSt3__16atomicIjEEPPS1_ referenced by .../composer@2.1-impl.so". The impl
 * (HWC command queue, MessageQueue<>) calls android::hardware::EventFlag::createEventFlag() and deleteEventFlag(), which live in
 * the firmware's libfmq.so, but the impl has no DT_NEEDED entry for libfmq.so, so nothing in its lookup scope defines them (and
 * the service then crashed in the linker while giving up). The two are exported from this executable as well, the same way as
 * the libhidlbase ones above: each forwards to the firmware's libfmq.so (dlopen'ed on first use; dlsym on its handle does not
 * see this executable, so there is no recursion). Both return android::status_t (int).
 */
#define SYM_EF_CREATE "_ZN7android8hardware9EventFlag15createEventFlagEPNSt3__16atomicIjEEPPS1_"
#define SYM_EF_DELETE "_ZN7android8hardware9EventFlag15deleteEventFlagEPPS1_"
#define ENOSYS_ 38
static void *g_fmq;
static void *fmq_fn(void **cache, const char *sym) {
    if (!*cache) {
        if (!g_fmq) g_fmq = dlopen("libfmq.so", RTLD_NOW | RTLD_GLOBAL);
        if (!g_fmq) { ERR("libfmq.so: %s", dlerror()); return 0; }
        *cache = dlsym(g_fmq, sym);
        if (!*cache) ERR("%s not found in libfmq.so: %s", sym, dlerror());
    }
    return *cache;
}

__attribute__((visibility("default"), used)) int ef_create(void *fw_addr, void **flag) __asm__(SYM_EF_CREATE);
int ef_create(void *fw_addr, void **flag) {
    static void *cache;
    void *f = fmq_fn(&cache, SYM_EF_CREATE);
    return f ? ((int (*)(void *, void **))f)(fw_addr, flag) : -ENOSYS_;
}

__attribute__((visibility("default"), used)) int ef_delete(void **flag) __asm__(SYM_EF_DELETE);
int ef_delete(void **flag) {
    static void *cache;
    void *f = fmq_fn(&cache, SYM_EF_DELETE);
    return f ? ((int (*)(void **))f)(flag) : -ENOSYS_;
}

static void *need(void *lib, const char *sym) {
    void *f = lib ? dlsym(lib, sym) : 0;
    if (!f) ERR("symbol %s not found: %s", sym, dlerror());
    return f;
}

int main() {
    void *iface = dlopen("android.hardware.graphics.composer@2.1.so", RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { ERR("android.hardware.graphics.composer@2.1.so: %s", dlerror()); _exit(1); }   /* not return: bionic's fini array crashes (exit 139) in this crt-less build */
    void *cxx = dlopen("libc++.so", RTLD_NOW | RTLD_GLOBAL);
    /* configureRpcThreadpool() / joinRpcThreadpool() are in libhidlbase.so since Android 10; libhidltransport.so is only an
     * empty stub there (looking in it fails with "undefined symbol", and the service exits with code 1) */
    void *ht = dlopen("libhidlbase.so", RTLD_NOW | RTLD_GLOBAL);
    if (!cxx || !ht) { ERR("libc++.so / libhidlbase.so: %s", dlerror()); _exit(1); }

    /* android::hardware::graphics::composer::V2_1::IComposer: same shape as allocator::V2_0::IAllocator, so the same substitutions */
    get_service_fn get_service = (get_service_fn)need(iface,
        "_ZN7android8hardware8graphics8composer4V2_19IComposer10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb");
    register_fn register_as_service = (register_fn)need(iface,
        "_ZN7android8hardware8graphics8composer4V2_19IComposer17registerAsServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEE");
    assign_fn assign = (assign_fn)need(cxx, "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKc");
    configure_fn configure = (configure_fn)need(ht, "_ZN7android8hardware22configureRpcThreadpoolEjb");
    join_fn join = (join_fn)need(ht, "_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!get_service || !register_as_service || !assign || !configure || !join) _exit(1);

    g_hidlbase = ht;
    g_fmq = dlopen("libfmq.so", RTLD_NOW | RTLD_GLOBAL);      /* EventFlag, see ef_create() */
    if (!g_fmq) ERR("libfmq.so: %s", dlerror());
    fill_type_infos(cxx);       /* before the impl is loaded: its relocations bind to these */

    CxxString name = {{0, 0, 0}};
    assign(&name, "default");

    configure(1, true);
    Sp svc = get_service(&name, true);      /* true: the passthrough implementation, ...composer@2.1-impl.so */
    if (!svc.p) {
        ERR("Could not get passthrough implementation for android.hardware.graphics.composer@2.1::IComposer/default.");
        /* (the linker's own reason is logged by vndksupport just before this; a second dlopen of the impl is not done here: the
         * service used to die with SIGSEGV in the linker right after it) */
        _exit(1);
    }
    int st = register_as_service(svc.p, &name);
    if (st != 0) { ERR("Could not register android.hardware.graphics.composer@2.1::IComposer/default (status %d)", st); _exit(1); }
    INFO("Registration complete for android.hardware.graphics.composer@2.1::IComposer/default.");
    join();
    _exit(1);       /* the thread pool does not return */
}

#ifdef AEMU_NO_BIONIC_HEADERS
/* what the NDK's crtbegin_dynamic.o provides (bionic libc/arch-common/bionic/crtbegin.c), since zig has no bionic crt */
typedef void init_func_t(int, char *[], char *[]);
typedef void fini_func_t(void);
extern "C" {
__attribute__((section(".preinit_array"), used)) init_func_t *__PREINIT_ARRAY__ = (init_func_t *)-1;
__attribute__((section(".init_array.0"), used)) init_func_t *__INIT_ARRAY__ = (init_func_t *)-1;
__attribute__((section(".fini_array.0"), used)) fini_func_t *__FINI_ARRAY__ = (fini_func_t *)-1;
__attribute__((used)) void *__dso_handle = 0;

__attribute__((used)) static void _start_main(void *raw_args) {
    struct { init_func_t **preinit_array; init_func_t **init_array; fini_func_t **fini_array; } array;
    array.preinit_array = &__PREINIT_ARRAY__;
    array.init_array = &__INIT_ARRAY__;
    array.fini_array = &__FINI_ARRAY__;
    __libc_init(raw_args, 0, (int (*)(int, char **, char **))&main, &array);
}
}
/* _start is ARM code (even entry address), as in the NDK's crtbegin: the linker jumps to the entry with "mov pc, r0", which
 * does not switch to Thumb. build-zig.sh compiles everything as ARM (-marm), so a plain "bl" reaches _start_main. */
#if defined(__arm__)
__asm__(".text; .arm; .global _start; .type _start,%function; .p2align 2; _start:"
        "mov fp,#0; mov lr,#0; mov r0,sp; bl _start_main; .size _start, .-_start");
#elif defined(__aarch64__)
__asm__(".text; .global _start; .type _start,%function; _start:"
        "bti j; mov x29,#0; mov x30,#0; mov x0,sp; b _start_main; .size _start, .-_start");
#endif
#endif
