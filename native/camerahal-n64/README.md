# camerahal-n64 (from AEmulator 0.0.0.3)

Upstream's camera HAL sources. `../camerahal/` is the fork's own HAL1 bridge (`camera.aemu_host.so`, HostCameraBridge).
This directory builds the other set of binaries, which ship next to it and do not share file names:
`camera.default.so` / `camera.default64.so` / `libcamera.none.so` (2.3 "no camera" stub) and the N64 per-kernel HALs.
They are used by the native arm64 engine (`CameraFeed`). `build_all.py` points here.
