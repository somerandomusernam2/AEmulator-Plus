/*
 * android.hardware.graphics.allocator@2.0-service of the native arm64 engine (Android 8.x, kernels 26 and 27).
 *
 * Android 8 keeps the graphics allocator in a HAL service of its own (/vendor/bin/hw/...allocator@2.0-service, init
 * service gralloc-2-0). A firmware whose tree has the passthrough implementation (android.hardware.graphics.allocator@2.0-impl.so)
 * and the rc file but no such program (a 32-bit-only MediaTek tree is one) has nothing that registers
 * android.hardware.graphics.allocator@2.0::IAllocator/default: SurfaceFlinger logs "Gralloc2: gralloc-alloc is missing"
 * and aborts, and TreeFixer takes the HAL out of the manifest, which makes the abort immediate (a client waits for a
 * hwbinder entry the manifest promises, it asks once for one it does not).
 *
 * This is what AOSP's service main() does, registerPassthroughServiceImplementation<IAllocator>(), without AOSP's
 * headers: the interface library (android.hardware.graphics.allocator@2.0.so) is dlopen()ed and its own getService()
 * and registerAsService() are called through their mangled names. getService(name, true) loads the firmware's
 * ...@2.0-impl.so and calls its HIDL_FETCH_IAllocator, which opens the gralloc module (the engine's, native/grallocwrap).
 *
 * Only the first word of an sp<> and the three words of a libc++ std::string are touched, both the same in every
 * layout of libc++ (an empty string is all zeros; the text is put in with the library's own assign()).
 */
#include <dlfcn.h>
#include <stddef.h>
#include <stdint.h>
#include <android/log.h>

#define TAG "allocator@2.0-service"
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
    void *iface = dlopen("android.hardware.graphics.allocator@2.0.so", RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { ERR("android.hardware.graphics.allocator@2.0.so: %s", dlerror()); return 1; }
    void *cxx = dlopen("libc++.so", RTLD_NOW | RTLD_GLOBAL);
    void *ht = dlopen("libhidltransport.so", RTLD_NOW | RTLD_GLOBAL);
    if (!cxx || !ht) { ERR("libc++.so / libhidltransport.so: %s", dlerror()); return 1; }

    get_service_fn get_service = (get_service_fn)need(iface,
        "_ZN7android8hardware8graphics9allocator4V2_010IAllocator10getServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcENS5_9allocatorIcEEEEb");
    register_fn register_as_service = (register_fn)need(iface,
        "_ZN7android8hardware8graphics9allocator4V2_010IAllocator17registerAsServiceERKNSt3__112basic_stringIcNS5_11char_traitsIcENS5_9allocatorIcEEEE");
    assign_fn assign = (assign_fn)need(cxx, "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKc");
    configure_fn configure = (configure_fn)need(ht, "_ZN7android8hardware20configureRpcThreadpoolEjb");
    join_fn join = (join_fn)need(ht, "_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!get_service || !register_as_service || !assign || !configure || !join) return 1;

    CxxString name = {{0, 0, 0}};
    assign(&name, "default");

    configure(1, true);
    Sp svc = get_service(&name, true);      /* true: the passthrough implementation of the firmware's ...-impl.so */
    if (!svc.p) {
        ERR("Could not get passthrough implementation for android.hardware.graphics.allocator@2.0::IAllocator/default.");
        return 1;
    }
    int st = register_as_service(svc.p, &name);
    if (st != 0) { ERR("Could not register android.hardware.graphics.allocator@2.0::IAllocator/default (status %d)", st); return 1; }
    INFO("Registration complete for android.hardware.graphics.allocator@2.0::IAllocator/default.");
    join();
    return 1;       /* the thread pool does not return */
}
