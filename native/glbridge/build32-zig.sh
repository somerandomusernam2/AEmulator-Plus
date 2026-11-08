#!/bin/sh
# The GLES bridge for 32-bit firmware without the NDK: zig cc (LLVM), thumb-linux-musleabi (ARMv7-A, Thumb-2, NEON), the
# same idea as ../hwchal/build-zig.sh: the imports resolve against stand-in libc/liblog that carry the LIBC version tag
# the guest's bionic expects (they are not shipped), and errno is bionic's __errno().
#   sh build32-zig.sh [output]     (default: ../../app/src/main/assets/engines/kk/libGLES_aemu.so)
# (g_api.h / h_api.h are the generated ones that sit next to guest.c: gen.py is only needed to change them)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
cd "$(dirname "$0")"
OUT=${1:-../../app/src/main/assets/engines/kk/libGLES_aemu.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'EOT'
#define S(n) void n(void) {}
S(__cxa_finalize) S(__cxa_atexit) S(strlen) S(malloc) S(calloc) S(realloc) S(free) S(strstr)
S(strcmp) S(memcmp) S(memset) S(memcpy) S(send) S(__system_property_get) S(getprogname) S(open) S(read) S(close) S(prctl)
S(socket) S(connect) S(setsockopt) S(poll) S(abort) S(pthread_once) S(pthread_setspecific) S(pthread_getspecific)
S(pthread_key_create) S(pthread_key_delete) S(pthread_mutex_lock) S(pthread_mutex_unlock)
int *__errno(void) { return 0; }
EOT
echo 'LIBC { global: __cxa_finalize; __cxa_atexit; strlen; malloc; calloc; realloc; free; strstr; strcmp; memcmp; memset; memcpy; send; __system_property_get; getprogname; open; read; close; prctl; socket; connect; setsockopt; poll; abort; pthread_once; pthread_setspecific; pthread_getspecific; pthread_key_create; pthread_key_delete; pthread_mutex_lock; pthread_mutex_unlock; __errno; };' > "$STUB/libc.map"
echo 'void dlopen(void) {} void dlsym(void) {} void dlerror(void) {}' > "$STUB/dl.c"
echo 'LIBC { global: dlopen; dlsym; dlerror; };' > "$STUB/dl.map"
echo 'int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }' > "$STUB/log.c"
echo 'extern int *__errno(void); int *__errno_location(void) { return __errno(); }' > "$STUB/errno.c"
cat > "$STUB/emutls.c" <<'EOT'
/* emulated TLS on pthread keys, as the NDK's runtime does for API < 29 (zig's compiler-rt has no __emutls_get_address) */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
struct ctl { unsigned size, align, index; void *templ; };
struct arr { unsigned n; void *p[1]; };
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_key_t key;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static unsigned counter;
static void mk(void) { pthread_key_create(&key, 0); }
void *__emutls_get_address(struct ctl *c) {
    pthread_once(&once, mk);
    pthread_mutex_lock(&mu);
    if (!c->index) c->index = ++counter;
    unsigned idx = c->index;
    pthread_mutex_unlock(&mu);
    struct arr *a = pthread_getspecific(key);
    if (!a || a->n < idx) {
        unsigned n = idx + 8;
        struct arr *b = calloc(1, sizeof(struct arr) + n * sizeof(void *));
        if (!b) abort();
        b->n = n;
        if (a) { memcpy(b->p, a->p, a->n * sizeof(void *)); free(a); }
        pthread_setspecific(key, b);
        a = b;
    }
    if (!a->p[idx - 1]) {
        void *p = calloc(1, c->size ? c->size : 1);
        if (!p) abort();
        if (c->templ) memcpy(p, c->templ, c->size);
        a->p[idx - 1] = p;
    }
    return a->p[idx - 1];
}
EOT
T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -mfpu=neon -fPIC -shared -nostdlib"
$ZIG cc $T -Os -fno-builtin -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc_stub.so" "$STUB/libc.c"
$ZIG cc $T -Os -fno-builtin -Wl,--version-script,"$STUB/dl.map" -Wl,-soname,libdl.so -o "$STUB/libdl_stub.so" "$STUB/dl.c"
$ZIG cc $T -Os -fno-builtin -Wl,-soname,liblog.so -o "$STUB/liblog_stub.so" "$STUB/log.c"
$ZIG cc --target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=softfp -mfpu=neon -fPIC -shared -O2 -fvisibility=hidden \
  -Wall -Wno-unused-function -fno-stack-protector -femulated-tls -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,lazy -Wl,-z,norelro -Wl,-z,max-page-size=4096 -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s -Wl,-soname,libGLES_aemu.so \
  -L"$STUB" -o "$OUT" guest.c "$STUB/errno.c" "$STUB/emutls.c" -lc_stub -ldl_stub -llog_stub
ls -l "$OUT"
