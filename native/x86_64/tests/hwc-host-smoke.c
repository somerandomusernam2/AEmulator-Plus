/* Host smoke test of native/hwcomposer/hwcomposer.c (glibc build): sh tests/hwc-host-smoke.sh
 * Opens the module the way the composer@2.4 passthrough does and checks what that HAL needs at start:
 * getFunction() answers every descriptor 1..70 (the HAL logs "failed to get hwcomposer2 function N" and exits otherwise,
 * 54 = GET_DISPLAY_CAPABILITIES in the guest log), a physical display exists, config 1 (the only one) has the framebuffer size. */
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <hardware/hwcomposer2.h>
int main(int argc, char **argv) {
    void *so = dlopen(argv[1], RTLD_NOW);
    if (!so) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }
    hw_module_t *m = dlsym(so, "HMI");
    if (!m) { fprintf(stderr, "no HMI\n"); return 1; }
    if (strcmp(m->id, HWC_HARDWARE_MODULE_ID)) { fprintf(stderr, "bad id %s\n", m->id); return 1; }
    hw_device_t *d = NULL;
    if (m->methods->open(m, HWC_HARDWARE_COMPOSER, &d) || !d) { fprintf(stderr, "open failed\n"); return 1; }
    hwc2_device_t *dev = (hwc2_device_t *)d;
    if (d->version != HWC_DEVICE_API_VERSION_2_0) { fprintf(stderr, "bad version %x\n", d->version); return 1; }
    int bad = 0;
    for (int i = 1; i <= 70; i++) if (!dev->getFunction(dev, i)) { fprintf(stderr, "descriptor %d: NULL\n", i); bad++; }
    typedef int32_t (*caps_fn)(hwc2_device_t *, hwc2_display_t, uint32_t *, uint32_t *);
    caps_fn caps = (caps_fn)dev->getFunction(dev, HWC2_FUNCTION_GET_DISPLAY_CAPABILITIES);
    uint32_t n = 99; int32_t r = caps(dev, 0, &n, NULL);
    printf("getDisplayCapabilities(54): r=%d n=%u\n", r, n);
    typedef int32_t (*attr_fn)(hwc2_device_t *, hwc2_display_t, hwc2_config_t, int32_t, int32_t *);
    attr_fn attr = (attr_fn)dev->getFunction(dev, HWC2_FUNCTION_GET_DISPLAY_ATTRIBUTE);
    int32_t w = 0, h = 0; attr(dev, 0, 1, HWC2_ATTRIBUTE_WIDTH, &w); attr(dev, 0, 1, HWC2_ATTRIBUTE_HEIGHT, &h);
    printf("config 1: %dx%d\n", w, h);
    printf("%s\n", bad || r != 0 || w <= 0 || h <= 0 ? "FAIL" : "PASS");
    return bad || r != 0 || w <= 0 || h <= 0;
}
