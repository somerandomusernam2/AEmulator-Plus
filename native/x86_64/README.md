# native/x86_64 - guest-side engine libraries for x86-64 firmware (zig, no NDK)

`zig-bionic.sh <out.so> <soname> <src.c>` builds one plain-C guest library for an x86-64 Android guest. zig has no
bionic, so the code is compiled against zig's musl headers (-ffreestanding -fno-builtin) and linked -nostdlib against
empty stand-in libc/liblog/libdl/libm/libhardware (DT_NEEDED only); the guest's bionic provides the real ones. The script
refuses musl-only imports. `inc/` holds minimal stand-ins for AOSP's hardware/hardware.h and hardware/hwcomposer2.h.

| Script | Output (assets/engines/n64-x86_64/) | Why |
|---|---|---|
| `build-hwcomposer.sh` | `vendor#lib64#hw#hwcomposer.default.so` | composer@2.4 log: `failed to get hwcomposer2 function 54`, exit 1. No hwcomposer HAL in the tree, so the HAL fell back to the Android 9 framebuffer adapter, which has no GET_DISPLAY_CAPABILITIES. The engine's HWC2 module (native/hwcomposer) answers descriptors 1..70. |

`sh tests/hwc-host-smoke.sh` builds the same source for glibc and checks the module the way the composer HAL does
(every descriptor 1..70 answered, function 54 works, display config 1 is the framebuffer size). Not run inside a guest.

`TreeFixer` installs the file for an x86-64 firmware that has no hwcomposer HAL of its own.

## Not covered (needs more than a rebuild)

* `android.hardware.graphics.allocator@4.0-service.minigbm` exits 234 (`cros_gralloc_driver: Failed to initialize driver`):
  minigbm wants a DRM render node (/dev/dri) that the phone does not give the guest. A replacement IAllocator/IMapper 4.0
  has to be built against AOSP's generated HIDL headers; none of the engine's sources do that (native/gralloc1, grallocwrap
  are gralloc0/1 modules, native/gralloc5 is the AIDL one of Android 17).
* SurfaceFlinger `couldn't find an OpenGL ES implementation`: the tree has only ANGLE (needs Vulkan) and the goldfish
  `emulation` driver (needs the emulator's host). The engine's GL bridge (native/glbridge/guest.c) has not been built for
  x86-64 and needs the allocator above to hand it buffers.
