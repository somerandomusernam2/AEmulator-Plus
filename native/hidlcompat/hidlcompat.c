/*
 * Added for AEmulator Plus (2026-10-31). libaemu_hidlcompat.so: three functions of libhidlbase that the audio@7.0 HAL impl
 * (built for VNDK 34) links to and that the 15 firmware's libhidlbase no longer exports:
 *   android::hardware::details::check(bool, const char *)
 *   android::hardware::details::logError(const std::string &)
 *   android::hardware::details::errorWriteLog(int, const char *)
 * Without them dlopen of android.hardware.audio@7.0-impl.so fails ("cannot locate symbol _ZN7android8hardware7details5checkEbPKc")
 * and the service exits with "Could not register Audio Core API". The library is preloaded into the audio service only
 * (LD_PRELOAD, see GuestVm), has no dependencies at all (no libc: nothing it needs is in the vendor namespace) and keeps the
 * semantics: a failed check stops the process, the two log calls do nothing.
 */
__attribute__((visibility("default"), used)) void aemu_hidl_check(_Bool cond, const char *msg)
    __asm__("_ZN7android8hardware7details5checkEbPKc");
void aemu_hidl_check(_Bool cond, const char *msg) { (void)msg; if (!cond) __builtin_trap(); }

__attribute__((visibility("default"), used)) void aemu_hidl_log_error(const void *message)
    __asm__("_ZN7android8hardware7details8logErrorERKNSt3__112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE");
void aemu_hidl_log_error(const void *message) { (void)message; }

__attribute__((visibility("default"), used)) int aemu_hidl_error_write_log(int tag, const char *info)
    __asm__("_ZN7android8hardware7details13errorWriteLogEiPKc");
int aemu_hidl_error_write_log(int tag, const char *info) { (void)tag; (void)info; return 0; }
