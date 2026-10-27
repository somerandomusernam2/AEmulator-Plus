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
 */
#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>
#include <android/log.h>

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

static void *need(void *lib, const char *sym) {
    void *f = lib ? dlsym(lib, sym) : 0;
    if (!f) ERR("symbol %s not found: %s", sym, dlerror());
    return f;
}

int main() {
    void *iface = dlopen("android.hardware.graphics.composer@2.1.so", RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { ERR("android.hardware.graphics.composer@2.1.so: %s", dlerror()); return 1; }
    void *cxx = dlopen("libc++.so", RTLD_NOW | RTLD_GLOBAL);
    void *ht = dlopen("libhidltransport.so", RTLD_NOW | RTLD_GLOBAL);
    if (!cxx || !ht) { ERR("libc++.so / libhidltransport.so: %s", dlerror()); return 1; }

    /* android::hardware::graphics::composer::V2_1::IComposer: same shape as allocator::V2_0::IAllocator, so the same substitutions */
    get_service_fn get_service = (get_service_fn)need(iface,
        "_ZN7android8hardware8graphics8composer4V2_19IComposer10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEEb");
    register_fn register_as_service = (register_fn)need(iface,
        "_ZN7android8hardware8graphics8composer4V2_19IComposer17registerAsServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEE");
    assign_fn assign = (assign_fn)need(cxx, "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKc");
    configure_fn configure = (configure_fn)need(ht, "_ZN7android8hardware20configureRpcThreadpoolEjb");
    join_fn join = (join_fn)need(ht, "_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!get_service || !register_as_service || !assign || !configure || !join) return 1;

    CxxString name = {{0, 0, 0}};
    assign(&name, "default");

    configure(1, true);
    Sp svc = get_service(&name, true);      /* true: the passthrough implementation, ...composer@2.1-impl.so */
    if (!svc.p) {
        ERR("Could not get passthrough implementation for android.hardware.graphics.composer@2.1::IComposer/default.");
        return 1;
    }
    int st = register_as_service(svc.p, &name);
    if (st != 0) { ERR("Could not register android.hardware.graphics.composer@2.1::IComposer/default (status %d)", st); return 1; }
    INFO("Registration complete for android.hardware.graphics.composer@2.1::IComposer/default.");
    join();
    return 1;       /* the thread pool does not return */
}
