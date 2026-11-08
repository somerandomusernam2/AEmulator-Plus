# v3d-fwharness

Runs the firmware's own `V3D_Compose_Impl` (from its `libsurfaceflinger.so`, ARM, in Unicorn) against a fake /dev/gememalloc +
/dev/v3d, captures the jobs it submits and replays them through `native/bcmgpu/v3demu_core.h`.

    pip install unicorn pyelftools capstone
    gcc -O2 -o replay native/bcmgpu/tests/replay.c
    V3D_SF_LIB=<firmware>/system/lib/libsurfaceflinger.so V3D_REPLAY=./replay python3 tools/v3d-fwharness/tests_more.py

`qpudis.py` disassembles the QPU shader blobs, `armdis.py` the Thumb code. Allocator test: `native/bcmgpu/tests/test_gem.c`.
