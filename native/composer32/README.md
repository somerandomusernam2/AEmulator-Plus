# composer32

32-bit `android.hardware.graphics.composer@2.1-service` + `-impl.so` for the native arm64 engine's kernel 33, for
32-bit-only Qualcomm Wear OS 4 firmware (its own composer, `vendor.qti.hardware.display.composer-service`, cannot run).
`hwcomposer.default.so` (native/hwchal) is the same 32-bit build as for kernels 30 and 34.

* `composer_service.cpp` - what AOSP's service `main()` does, without AOSP headers (like `native/allocsvc`).
* `composer_impl.cpp` - AOSP's passthrough `HIDL_FETCH_IComposer` (HwcLoader + HWC2On1Adapter).
* `Android.bp` - AOSP build, with the list of files to copy into `engines/n64-33/arm32/`.

Not compiled or run when written. TreeFixer.useEngineComposerOnPure32() installs nothing itself: it only sets the board's
composer aside and declares composer@2.1 once the service and impl above are in the tree.

Fix 2026-10-28: the impl's classes carry RTTI (type_info of IComposer, IComposerClient, IBase) that the firmware's -fno-rtti HIDL
libraries do not export, so the impl failed to load ("cannot locate symbol _ZTIN7android8hardware8graphics8composer4V2_19IComposerE").
`composer_service.cpp` now defines and exports those three type_info objects itself. `build-zig.sh` builds the service with zig
(no NDK): `sh build-zig.sh`.

Fix 2026-10-28 (2): the impl references `android::hardware::EventFlag::createEventFlag/deleteEventFlag` (libfmq.so) but has no
DT_NEEDED for libfmq, so loading it failed with "cannot locate symbol _ZN7android8hardware9EventFlag15createEventFlag..." and the
service then crashed (SIGSEGV in the linker, exit 139) in its own diagnostic dlopen. `composer_service.cpp` now preloads libfmq.so,
exports forwarding createEventFlag/deleteEventFlag, and leaves with `_exit(1)` without the second dlopen.
