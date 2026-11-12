/*
 * power.default.so — power HAL of AEmulator for the native arm64 engine (Android 8+): accepts every request and does
 * nothing. The guest has no CPU governor or display panel of its own to manage; the HIDL service
 * android.hardware.power@1.0 only needs a module to wrap (without one it exits, and the framework waits for a HAL the
 * manifest lists). Built for arm64 (build.sh) and armv7 (build32.sh). The emulator's own power.ranchu.so is not usable: it talks to the host through /dev/qemu_pipe.
 */
#include <stdint.h>

#define EXPORT __attribute__((visibility("default")))

struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {
    uint32_t tag;
    uint16_t module_api_version, hal_api_version;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
    unsigned long reserved[25]; /* uint32 on 32-bit, uint64 on 64-bit */
};
struct power_module {
    struct hw_module common;
    void (*init)(struct power_module *m);
    void (*setInteractive)(struct power_module *m, int on);
    void (*powerHint)(struct power_module *m, int hint, void *data);
    void (*setFeature)(struct power_module *m, int feature, int state);
    /* low power statistics: left NULL, the service answers "not supported" */
    void *get_platform_low_power_stats, *get_number_of_platform_modes, *get_voter_list;
};

#define HARDWARE_MODULE_TAG 0x484d4f44

static void p_init(struct power_module *m) { (void)m; }
static void p_interactive(struct power_module *m, int on) { (void)m; (void)on; }
static void p_hint(struct power_module *m, int hint, void *data) { (void)m; (void)hint; (void)data; }
static void p_feature(struct power_module *m, int feature, int state) { (void)m; (void)feature; (void)state; }

/*
 * open(): the Android 8.x android.hardware.power@1.0-impl (and MediaTek's build of it) only wraps a module whose
 * methods->open is non-NULL: with a NULL open() it logs "hw_get_module power failed: 0" (hw_get_module itself
 * succeeded, hence 0), returns no implementation, the service exits and the framework waits for the HAL forever.
 * Newer impls take the module itself when open() is NULL, but they also accept an open() that succeeds, so one
 * module serves all of them. The "device" handed back is the module itself: the impl casts it to a power_module.
 */
static int p_open(const struct hw_module *m, const char *id, struct hw_device **dev) {
    (void)id;
    if (!dev) return -22; /* -EINVAL */
    *dev = (struct hw_device *)m;
    return 0;
}

static struct hw_module_methods methods = { p_open };

EXPORT struct power_module HMI = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = 0x0002,   /* POWER_MODULE_API_VERSION_0_2 */
        .hal_api_version = 0,
        .id = "power",
        .name = "AEmulator power",
        .author = "AEmulator",
        .methods = &methods,
    },
    .init = p_init,
    .setInteractive = p_interactive,
    .powerHint = p_hint,
    .setFeature = p_feature,
};
