/*
 * Minimal 32-bit host for the keymaster 3.0 passthrough implementation.
 * Stands in for android.hardware.keymaster@3.0-service, which the image only has as aarch64 (or not at all).
 *
 * It does what that service does (hardware/interfaces/keymaster/3.0/default/service.cpp):
 *   configureRpcThreadpool(1, true); IKeymasterDevice instance "default" -> registerAsService(); joinRpcThreadpool();
 * but without the generated headers: everything is looked up by its exported (mangled) name, and the one
 * std::string argument is built by hand (libc++ short-string layout, 32-bit, "default").
 *
 * Fixed against the previous version (keystore waited for the keymaster service for ever, "Waited one second for
 * android.hardware.keymaster@3.0::IKeymasterDevice/default"; the host logged "configureRpcThreadpoolEmb not found"
 * and exited with 1 before it had registered anything):
 *   - the mangled name had the wrong length prefix: "configureRpcThreadpool" is 22 characters, not 20;
 *   - configureRpcThreadpool/joinRpcThreadpool are in libhidltransport.so on 8.x and in libhidlbase.so on 9+, and
 *     dlsym() on one library's handle does not see the other: look in the global scope and in both;
 *   - registerAsService() returns a plain status_t (this = r0, name = r1 on ARM EABI), there is no result slot.
 *
 * Build (Android NDK, 32-bit ARM, no libc++ needed):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang -O2 -fPIE -pie \
 *       aemu-keymaster-host.c -o android.hardware.keymaster@3.0-service -ldl -llog
 * and put the result into assets/engines/n64-<kernel>/arm32/ as "vendor#bin#hw#android.hardware.keymaster@3.0-service"
 * (the name the keymaster-3-0 init service starts).
 */
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define TAG "aemu-keymaster-host"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define IMPL_LIB "android.hardware.keymaster@3.0-impl.so"
#define IFACE_LIB "android.hardware.keymaster@3.0.so"
#define FETCH_SYM "HIDL_FETCH_IKeymasterDevice"
/* android::hardware::keymaster::V3_0::IKeymasterDevice::registerAsService(std::string const&) */
#define REGISTER_SYM "_ZN7android8hardware9keymaster4V3_016IKeymasterDevice17registerAsServiceERKNSt3__112basic_stringIcNS4_11char_traitsIcEENS4_9allocatorIcEEEE"

/* registerAsService returns status_t: ARM EABI passes this in r0 and the name in r1 */
typedef void *(*fetch_fn)(const char *name);
typedef int (*register_fn)(void *self, const void *name);
typedef void (*configure_fn)(unsigned int maxThreads, int callerWillJoin);
typedef void (*join_fn)(void);

static void *need(void *lib, const char *what, const char *sym) {
    void *p = dlsym(lib, sym);
    if (!p) { LOG("%s: %s not found: %s", what, sym, dlerror()); exit(1); }
    return p;
}

/* configureRpcThreadpool/joinRpcThreadpool live in libhidltransport on 8.x and libhidlbase on 9+, and whether dlsym on
 * one library's handle sees the other depends on what was loaded before: try everywhere. */
static void *lookup(const char *sym) {
    static const char *libs[] = { "libhidltransport.so", "libhidlbase.so", "libhwbinder.so" };
    void *p = dlsym(RTLD_DEFAULT, sym);
    for (unsigned i = 0; !p && i < sizeof libs / sizeof libs[0]; i++) {
        void *h = dlopen(libs[i], RTLD_NOW);
        if (h) p = dlsym(h, sym);
    }
    return p;
}

int main(void) {
    void *iface = dlopen(IFACE_LIB, RTLD_NOW | RTLD_GLOBAL);
    if (!iface) { LOG("dlopen %s: %s", IFACE_LIB, dlerror()); return 1; }
    void *impl = dlopen(IMPL_LIB, RTLD_NOW);
    if (!impl) { LOG("dlopen %s: %s", IMPL_LIB, dlerror()); return 1; }

    fetch_fn fetch = (fetch_fn)need(impl, "impl", FETCH_SYM);
    register_fn reg = (register_fn)need(iface, "iface", REGISTER_SYM);
    /* size_t is "unsigned int" ('j') on 32-bit ARM; 'm' (unsigned long) kept as a fallback */
    configure_fn configure = (configure_fn)lookup("_ZN7android8hardware22configureRpcThreadpoolEjb");
    if (!configure) configure = (configure_fn)lookup("_ZN7android8hardware22configureRpcThreadpoolEmb");
    join_fn join = (join_fn)lookup("_ZN7android8hardware17joinRpcThreadpoolEv");
    if (!configure || !join) { LOG("configureRpcThreadpool/joinRpcThreadpool not found"); return 1; }

    configure(1, 1);

    void *dev = fetch("default");
    if (!dev) { LOG("%s(\"default\") returned null", FETCH_SYM); return 1; }

    /* std::string "default" as libc++ lays it out on 32-bit: short form, byte 0 = size << 1, then the characters */
    unsigned char name[12];
    memset(name, 0, sizeof name);
    name[0] = 7 << 1;
    memcpy(name + 1, "default", 7);

    int rc = reg(dev, name);
    if (rc != 0) { LOG("registerAsService failed: %d", rc); return 1; }
    LOG("registered android.hardware.keymaster@3.0::IKeymasterDevice/default");

    join();
    return 0;
}
