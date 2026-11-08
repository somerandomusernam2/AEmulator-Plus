Host-side checks (x86 Linux, gcc). Not needed to build the app.
  gcc -O2 -ffp-contract=off -DV3D_FAST_VARY=0 -o fuzz fuzz.c -lm && ./fuzz 0 4000   # exact: expects 0 mismatches vs the original core
  gcc -O2 -ffp-contract=off -o diff diff.c -lm && ./diff 1                           # compose scenes, original vs new
v3demu_ref.h is the original v3demu_core.h with v3d_ renamed to v3dref_.
