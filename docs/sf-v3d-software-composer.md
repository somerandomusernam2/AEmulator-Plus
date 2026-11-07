# Software composer for Broadcom V3D SurfaceFlinger (Samsung GT-S5302 and kin)

**Symptoms:** zygote exits with 137 on every boot; `fb0` is all zeros. Logcat: `Failed to open [/dev/v3d]`,
`V3D Composer creation failed`, then SIGSEGV in `SurfaceFlinger` at `libsurfaceflinger.so+0x183d8`.

**Cause:** this SurfaceFlinger has no GL fallback (`drawWithOpenGL`/`clearWithOpenGL` are `bx lr`). Every layer is drawn by
`V3D_Compose_Impl::TextureCompose`, which builds a V3D command list. `V3D_Compose::Create` returns NULL without the device and
`DisplayHardware::compositionRequest` calls through the NULL pointer on the first frame.

**Fix** (`tools/patch_sf_v3d.py`, code in `native/sfv3d/v3dsoft.c`, applied by `TreeFixer` via `V3dSoftComposer`):
`init` succeeds, `compositionRequest/tileConvertRequest/ditherFB` are no-ops, `AllocGEBuf/FreeGEBuf` use anonymous memory,
`TextureCompose` blits (nearest, premultiplied alpha, 4 orientations, RGB565/8888/5551/4444 sources) into the back buffer of the
framebuffer window. `Layer::onDraw` is pointed at the engine gralloc's CPU address (`handle+0x20`) and the buffer's real stride.

**Layers:** normal (linear) surfaces are first converted by `tileConvertRequest` into a GPU-tiled "GE buffer"; here that conversion is a row copy (`Layer::reloadTexture` hands over stride + gralloc handle), and `TextureCompose` reads the linear copy.

**Tested here:** blit logic (`native/sfv3d/test.sh`, ARM under qemu-arm); patched library entry points executed in an ARM emulator.
**Not tested:** a real boot. Byte offsets are exact-match for the supplied library only; other builds are skipped.
Regenerate the table with `tools/patch_sf_v3d.py --kotlin app/src/main/java/app/aemu/core/V3dSoftTable.kt <libsurfaceflinger.so>`.
