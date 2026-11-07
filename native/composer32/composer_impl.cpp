/*
 * android.hardware.graphics.composer@2.1-impl.so, 32-bit: the passthrough implementation the service loads.
 *
 * It is AOSP's own (hardware/interfaces/graphics/composer/2.1/default/passthrough.cpp): HwcLoader opens the HWC module
 * (hw_get_module("hwcomposer") -> the engine's hwcomposer.default.so, native/hwchal/hwc.c, a hwcomposer 1.x device) and
 * wraps it in HWC2On1Adapter (libhwc2on1adapter.so) to get the HWC2 device the 2.1 HAL needs. Nothing is added here.
 *
 * Needs AOSP's headers and libraries, so it is built with Android.bp in an AOSP (android-13) tree; see README.md.
 */
#include <android/hardware/graphics/composer/2.1/IComposer.h>
#include <composer-passthrough/2.1/HwcLoader.h>

using android::hardware::graphics::composer::V2_1::IComposer;
using android::hardware::graphics::composer::V2_1::passthrough::HwcLoader;

extern "C" IComposer* HIDL_FETCH_IComposer(const char* /* name */) {
    return HwcLoader::load();
}
