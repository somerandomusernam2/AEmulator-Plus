# composer32

32-bit `android.hardware.graphics.composer@2.1-service` + `-impl.so` for the native arm64 engine's kernel 33, for
32-bit-only Qualcomm Wear OS 4 firmware (its own composer, `vendor.qti.hardware.display.composer-service`, cannot run).
`hwcomposer.default.so` (native/hwchal) is the same 32-bit build as for kernels 30 and 34.

* `composer_service.cpp` - what AOSP's service `main()` does, without AOSP headers (like `native/allocsvc`).
* `composer_impl.cpp` - AOSP's passthrough `HIDL_FETCH_IComposer` (HwcLoader + HWC2On1Adapter).
* `Android.bp` - AOSP build, with the list of files to copy into `engines/n64-33/arm32/`.

Not compiled or run when written. TreeFixer.useEngineComposerOnPure32() installs nothing itself: it only sets the board's
composer aside and declares composer@2.1 once the service and impl above are in the tree.
