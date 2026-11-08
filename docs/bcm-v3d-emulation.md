# Broadcom V3D / gememalloc emulation

Replaces the old binary patch of `libsurfaceflinger.so` / `libui.so` (GT-S5302 only). The firmware is no longer modified: the
three device nodes it uses are emulated in `libaemushim.so` (`native/bcmgpu`, hooked from `open/openat/ioctl/close`).

| node | emulation |
|---|---|
| `/dev/gememalloc` | ioctl `0xc0044201` alloc `{phys out, size in}`, `0x40044202` free, `mmap(fd, offset=phys)`. One sparse file `/dev/.aemu-gememalloc` shared by all processes (physical address = `0x10000000` + file offset); allocator state in its first page under a spin lock. |
| `/dev/v3d` | `0x401c5687` submit (28 bytes: flags, -, bin start/end, render start/end), executed synchronously; `0xc00c5688` wait returns status 3. |
| `/dev/cache-ops` | `0x6301` -> success. |

Physical map: `0..fb0 size` = `/dev/graphics/fb0` (qemu reports `smem_start = 0`), `0x10000000..+128 MB` = pool.

## What the V3D emulation executes (`v3demu_core.h`, no libc, no syscalls)
VideoCore-IV control lists exactly as SurfaceFlinger builds them: bin list (clip window, viewport offset, NV shader state,
array primitives: triangles/strip/fan) and render list (tile config, clear, load, store, branch-to-sublist, per 32x32 tile).
The QPU fragment programs are **interpreted**, not recognised: add/mul pipes, small immediates, unpack/pack, varyings, TMU 2D
lookups (RGBA8888/RGBX/4444/5551/565/L/A/LA, nearest/bilinear, wrap modes) and raw memory lookups, colour load, TLB write.
So the firmware's copy, constant-alpha blend, dither and YUV->RGB programs run as they are. Tiled ("T") formats are stored
raster (only the emulator reads them). No depth/stencil/MSAA/culling, no QPU condition flags.

Because the firmware gralloc is used again, the engine-gralloc replacement for `/dev/gememalloc` firmware is gone
(`TreeFixer`); trees prepared by older builds are repaired (`BroadcomGpu.kt`: parked gralloc put back, old patches undone).

## Verification
`tools/v3d-fwharness` runs the firmware's real `V3D_Compose_Impl` and replays the captured jobs: tile-convert + compose are
pixel-exact for RGB565 and RGBA8888 (compose draw alone), the dither pass stays within 2 channel LSB, constant alpha 200 and
a 2x bilinear upscale match an independent reference. `native/bcmgpu/tests/test_gem.c` covers the allocator (shared between
processes). **Not done here:** a full boot (no emulator/NDK in the build sandbox); the guest shim was only compile-checked for ARM.
Untested paths: YUV tile-convert jobs, `libGLES_hgl` (other `/dev/v3d` ioctls return ENOTTY), x86 shim (not hooked).
Build: `native/guestshim/build.sh` (adds `-I../bcmgpu`).
