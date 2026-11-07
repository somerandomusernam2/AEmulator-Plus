/*
 * android.hardware.power-service.aemu of the native arm64 engine (Android 15/16/17 images): the AIDL
 * android.hardware.power.IPower/default (interface version 6) that system_server's HintManagerService asks for.
 *
 * Why: HintManagerService's constructor waits for the declared service IPower/default, and when the interface version it
 * reports is 6 or more it calls getSupportInfo() and keeps the SupportInfo in mSupportInfo. Without a service the field stays
 * null and the constructor dies with "Attempt to read from field 'android.hardware.power.SupportInfo$HeadroomSupportInfo
 * android.hardware.power.SupportInfo.headroom' on a null object reference" ("Failed to create service
 * com.android.server.power.hint.HintManagerService"). The guest has no CPU governor, GPU or hint sessions to manage, so
 * every optional part is reported off (usesSessions=false, no modes/boosts/session hints, no composition data, no
 * CPU/GPU headroom) and the calls that depend on those parts answer EX_UNSUPPORTED_OPERATION, which is what the interface
 * documents for a HAL that does not support something. The HIDL android.hardware.power@1.0 service stays what it was.
 *
 * Same method as native/gralloc5/allocator.c: no AOSP headers and no generated AIDL code, the calls are read and written with
 * libbinder_ndk's parcel functions, laid out as the NDK backend lays them out. Transaction codes and the SupportInfo layout
 * are those of android.hardware.power-V6-ndk (BpPower::* transact with FIRST_CALL_TRANSACTION + the method's index in
 * IPower.aidl, SupportInfo::writeToParcel as disassembled):
 *
 *   code  method                          reply after the status header
 *    1    setMode(Mode, bool)             -
 *    2    isModeSupported(Mode)           bool
 *    3    setBoost(Boost, int)            -
 *    4    isBoostSupported(Boost)         bool
 *    5    createHintSession(...)          unsupported
 *    6    getHintSessionPreferredRate()   unsupported
 *    7    createHintSessionWithConfig()   unsupported
 *    8    getSessionChannel(int, int)     unsupported
 *    9    closeSessionChannel(int, int)   unsupported
 *   10    getSupportInfo()                SupportInfo (below)
 *   11    getCpuHeadroom(params)          unsupported
 *   12    getGpuHeadroom(params)          unsupported
 *   13    sendCompositionData(...)        - (oneway)
 *   14    sendCompositionUpdate(...)      - (oneway)
 *   0xffffff getInterfaceVersion()        int32 6
 *   0xfffffe getInterfaceHash()           string
 *
 * The numbers above are those of the frozen V6/V7 interface. A pre-release image (e.g. the Android 15/16 "BP11" builds) can carry an
 * IPower whose getSupportInfo has another number, and then system_server's proxy got UNKNOWN_TRANSACTION back
 * ("Method getSupportInfo is unimplemented.", "Could not contact PowerHAL!", system_server dies, zygote with it). So only
 * codes 1..9 (the same in every interface version that has them) are matched exactly; every other code from 10 on is told apart by
 * its arguments instead: a call without arguments is getSupportInfo (nothing else of the interface from 10 on takes none) and
 * is answered with the SupportInfo, a call with arguments is a headroom or composition call and is answered
 * EX_UNSUPPORTED_OPERATION (the composition ones are oneway, their reply is dropped). Each such call is logged (first 300, every call, with its result)
 * to stderr, which is run/aemu-power.log, so the number the guest really uses can be read from the next log.
 *
 * SupportInfo as a return value: int32 1 (not null), then the parcelable: int32 its size in bytes (counted from this
 * field), bool usesSessions, int64 x5 (boosts, modes, sessionHints, sessionModes, sessionTags), then the two nested
 * parcelables each as int32 1 + {int32 size, ...}:
 *   CompositionDataSupportInfo { bool, bool, int32, bool }
 *   HeadroomSupportInfo        { bool isCpuSupported, bool isGpuSupported, int32 cpuMinIntervalMillis, int32 gpuMinIntervalMillis }
 * A bool is one int32 in a parcel. The reader (generated code, Java or NDK) stops at the size, so a field that a newer
 * interface appends is simply absent here and reads as its default.
 *
 * Built with zig (no NDK, no bionic headers): native/powerhal/build-service-zig.sh. The program declares what it uses
 * itself, brings its own _start (what crtbegin_dynamic.o would) and is linked against stand-ins of libc.so /
 * libbinder_ndk.so that only give the dynamic section its DT_NEEDED entries; the guest's own libraries are used at run time.
 * AEMU_HOST_TEST builds the transaction handler against a fake parcel for native/powerhal/tests.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct AIBinder AIBinder;
typedef struct AIBinder_Class AIBinder_Class;
typedef struct AParcel AParcel;
typedef struct AStatus AStatus;

extern AIBinder_Class *AIBinder_Class_define(const char *descriptor, void *(*on_create)(void *), void (*on_destroy)(void *),
                                             int32_t (*on_transact)(AIBinder *, uint32_t, const AParcel *, AParcel *));
extern AIBinder *AIBinder_new(const AIBinder_Class *c, void *args);
extern void AIBinder_markVintfStability(AIBinder *b);
extern int32_t AServiceManager_addService(AIBinder *b, const char *instance);
extern bool ABinderProcess_setThreadPoolMaxThreadCount(uint32_t n);
extern void ABinderProcess_joinThreadPool(void);
extern int32_t AParcel_writeInt32(AParcel *p, int32_t v);
extern int32_t AParcel_writeInt64(AParcel *p, int64_t v);
extern int32_t AParcel_writeBool(AParcel *p, bool v);
extern int32_t AParcel_writeString(AParcel *p, const char *s, int32_t len);
extern int32_t AParcel_getDataPosition(const AParcel *p);
extern int32_t AParcel_getDataSize(const AParcel *p);
extern int32_t AParcel_setDataPosition(const AParcel *p, int32_t pos);
extern int32_t AParcel_writeStatusHeader(AParcel *p, const AStatus *s);
extern AStatus *AStatus_newOk(void);
extern AStatus *AStatus_fromExceptionCode(int32_t code);
extern void AStatus_delete(AStatus *s);

#define DESCRIPTOR "android.hardware.power.IPower"
/* 5 = the Android 15/16 interface (methods 1..9 only). HintManagerService calls getSupportInfo() only when the HAL reports 6 or more, and
 * this image's IPower proxy throws "Method getSupportInfo is unimplemented" right after getInterfaceVersion without sending the
 * transaction (aemu-power.log of builds 2..4: no call after the version query, reporting 6 and 7 alike). Reporting 5 keeps system_server
 * on the legacy path (isModeSupported / getHintSessionPreferredRate), which every real V5 HAL without hint support goes through. */
#define INTERFACE_VERSION 5
#define EX_UNSUPPORTED_OPERATION (-7)
#define UNKNOWN_TRANSACTION (-74)

enum {
    T_SET_MODE = 1, T_IS_MODE_SUPPORTED, T_SET_BOOST, T_IS_BOOST_SUPPORTED, T_CREATE_HINT_SESSION,
    T_GET_HINT_SESSION_PREFERRED_RATE, T_CREATE_HINT_SESSION_WITH_CONFIG, T_GET_SESSION_CHANNEL, T_CLOSE_SESSION_CHANNEL,
    T_GET_SUPPORT_INFO, T_GET_CPU_HEADROOM, T_GET_GPU_HEADROOM, T_SEND_COMPOSITION_DATA, T_SEND_COMPOSITION_UPDATE,
    T_VERSION = 0xffffff, T_HASH = 0xfffffe
};

static void ok(AParcel *out) { AStatus *s = AStatus_newOk(); AParcel_writeStatusHeader(out, s); AStatus_delete(s); }
static void unsupported(AParcel *out) {
    AStatus *s = AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION);
    AParcel_writeStatusHeader(out, s);
    AStatus_delete(s);
}

/* closes the parcelable opened at [start]: its first int32 becomes its size */
static void end_parcelable(AParcel *out, int32_t start) {
    int32_t end = AParcel_getDataPosition(out);
    AParcel_setDataPosition(out, start);
    AParcel_writeInt32(out, end - start);
    AParcel_setDataPosition(out, end);
}

static int32_t begin_parcelable(AParcel *out) {
    int32_t start = AParcel_getDataPosition(out);
    AParcel_writeInt32(out, 0);
    return start;
}

/* the SupportInfo of a HAL that supports nothing optional (see the layout above) */
static void write_support_info(AParcel *out) {
    AParcel_writeInt32(out, 1);                       /* not null */
    int32_t start = begin_parcelable(out);
    AParcel_writeBool(out, false);                    /* usesSessions */
    for (int i = 0; i < 5; i++) AParcel_writeInt64(out, 0);   /* boosts, modes, sessionHints, sessionModes, sessionTags */
    AParcel_writeInt32(out, 1);                       /* compositionData */
    int32_t comp = begin_parcelable(out);
    AParcel_writeBool(out, false);
    AParcel_writeBool(out, false);
    AParcel_writeInt32(out, 0);
    AParcel_writeBool(out, false);
    end_parcelable(out, comp);
    AParcel_writeInt32(out, 1);                       /* headroom */
    int32_t head = begin_parcelable(out);
    AParcel_writeBool(out, false);                    /* isCpuSupported */
    AParcel_writeBool(out, false);                    /* isGpuSupported */
    AParcel_writeInt32(out, 0);                       /* cpuMinIntervalMillis */
    AParcel_writeInt32(out, 0);                       /* gpuMinIntervalMillis */
    end_parcelable(out, head);
    end_parcelable(out, start);
}

/* bytes of arguments left in a transaction's data parcel (the interface token is already consumed by libbinder_ndk) */
static int32_t args_left(const AParcel *in) {
    if (!in) return 0;
    int32_t size = AParcel_getDataSize(in), pos = AParcel_getDataPosition(in);
    return size > pos ? size - pos : 0;
}

#ifdef AEMU_HOST_TEST
#define log_call(code, left, what) ((void)0)
#else
static void log_call(uint32_t code, int32_t left, const char *what);
#endif

/* The arguments of codes 1..9 are never read: a transaction's data parcel is dropped with the call, and nothing here depends on it. */
static int32_t handle(uint32_t code, const AParcel *in, AParcel *out) {
    switch (code) {
    case T_SET_MODE:
    case T_SET_BOOST:
        ok(out);
        return 0;
    case T_IS_MODE_SUPPORTED:
    case T_IS_BOOST_SUPPORTED:
        ok(out); AParcel_writeBool(out, false);
        return 0;
    case T_CREATE_HINT_SESSION:
    case T_GET_HINT_SESSION_PREFERRED_RATE:
    case T_CREATE_HINT_SESSION_WITH_CONFIG:
    case T_GET_SESSION_CHANNEL:
    case T_CLOSE_SESSION_CHANNEL:
        unsupported(out);
        return 0;
    case T_VERSION:
        ok(out); AParcel_writeInt32(out, INTERFACE_VERSION);
        return 0;
    case T_HASH:
        ok(out); AParcel_writeString(out, "notfrozen", 9);
        return 0;
    }
    if (code >= T_GET_SUPPORT_INFO && code < T_HASH) {
        /* getSupportInfo, getCpuHeadroom, getGpuHeadroom, sendCompositionData, sendCompositionUpdate, in whatever order and
         * numbering this image has them: told apart by the arguments (see the top of the file) */
        int32_t left = args_left(in);
        if (left <= 0) { ok(out); write_support_info(out); }
        else unsupported(out);
        return 0;
    }
    return UNKNOWN_TRANSACTION;
}

int32_t aemu_power_transact(AIBinder *b, uint32_t code, const AParcel *in, AParcel *out) {
    (void)b;
    int32_t left = args_left(in);
    log_call(code, left, "received");                 /* before the handler: a crash inside it still leaves this line */
    int32_t r = handle(code, in, out);
    log_call(code, left, r == 0 ? "answered" : "UNKNOWN_TRANSACTION");
    return r;
}

#ifndef AEMU_HOST_TEST
extern long write(int fd, const void *buf, unsigned long n);

static void say(const char *s) {
    unsigned long n = 0;
    while (s[n]) n++;
    write(2, s, n);
}

static void say_int(int32_t v) {
    char b[16]; int i = 16;
    uint32_t u = v < 0 ? (uint32_t)0 - (uint32_t)v : (uint32_t)v;
    do { b[--i] = (char)('0' + u % 10); u /= 10; } while (u);
    if (v < 0) b[--i] = '-';
    write(2, b + i, (unsigned long)(16 - i));
}

static void log_call(uint32_t code, int32_t left, const char *what) {
    static int logged;
    if (logged++ >= 600) return;
    say("power: transaction "); say_int((int32_t)code); say(", "); say_int(left); say(" byte(s) of arguments -> "); say(what); say("\n");
}

static void *on_create(void *args) { return args; }
static void on_destroy(void *data) { (void)data; }

int main(int argc, char **argv, char **envp) {
    (void)argc; (void)argv; (void)envp;
    say("power: build 5 (interface version 5, every transaction logged before and after)\n");
    ABinderProcess_setThreadPoolMaxThreadCount(2);
    AIBinder_Class *c = AIBinder_Class_define(DESCRIPTOR, on_create, on_destroy, aemu_power_transact);
    AIBinder *b = c ? AIBinder_new(c, 0) : 0;
    if (!b) { say("power: no binder object\n"); return 1; }
    AIBinder_markVintfStability(b);
    int32_t e = AServiceManager_addService(b, DESCRIPTOR "/default");
    if (e) { say("power: IPower/default registration failed, status "); say_int(e); say(" (-3 or EX_ILLEGAL_ARGUMENT: not declared in a VINTF manifest)\n"); }
    else say("power: IPower/default registered\n");
    if (e) return 1;
    ABinderProcess_joinThreadPool();
    return 0;
}

/* what the NDK's crtbegin_dynamic.o provides (bionic libc/arch-common/bionic/crtbegin.c), since there are no bionic crt files;
 * same as native/guest64/37/hwservicemanager.c */
extern __attribute__((noreturn)) void __libc_init(void *raw_args, void (*onexit)(void), int (*slingshot)(int, char **, char **),
                                                  const void *structors);
typedef void init_func_t(int, char *[], char *[]);
typedef void fini_func_t(void);
__attribute__((section(".preinit_array"), used)) init_func_t *__PREINIT_ARRAY__[2] = { (init_func_t *)-1, 0 };
__attribute__((section(".init_array.0"), used)) init_func_t *__INIT_ARRAY__[2] = { (init_func_t *)-1, 0 };
__attribute__((section(".fini_array.0"), used)) fini_func_t *__FINI_ARRAY__[2] = { (fini_func_t *)-1, 0 };
__attribute__((used)) void *__dso_handle = 0;
__attribute__((used)) static void _start_main(void *raw_args) {
    struct { init_func_t **preinit_array; init_func_t **init_array; fini_func_t **fini_array; } array;
    array.preinit_array = __PREINIT_ARRAY__;
    array.init_array = __INIT_ARRAY__;
    array.fini_array = __FINI_ARRAY__;
    __libc_init(raw_args, 0, main, &array);
}
__asm__(".text; .global _start; .type _start,%function; _start:"
        "bti j; mov x29,#0; mov x30,#0; mov x0,sp; b _start_main; .size _start, .-_start");
#endif
