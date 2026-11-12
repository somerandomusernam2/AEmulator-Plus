#!/bin/sh
# The GLES bridge for the 64-bit engines (n64-*) without the NDK: zig cc (LLVM), aarch64-linux-musl, the same idea as
# build32-zig.sh: the imports resolve against stand-in libc/libdl/liblog that carry the LIBC version tag the guest's
# bionic expects (they are not shipped), errno is bionic's __errno(), and thread-locals are emulated on pthread keys
# (bionic has no ELF TLS before API 29, and the engines go down to API 21).
#   sh build64-zig.sh [output...]
# With no argument the library is built once and copied over every engines/n64-*/system#lib64#egl#libGLES_aemu.so
# that exists. (g_api.h / h_api.h are the generated ones that sit next to guest.c: gen.py is only needed to change them)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
cd "$(dirname "$0")"
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'EOT'
#define S(n) void n(void) {}
S(__cxa_finalize) S(__cxa_atexit) S(strlen) S(malloc) S(calloc) S(realloc) S(free) S(strstr)
S(strcmp) S(strncmp) S(memcmp) S(memset) S(memcpy) S(memmove) S(send) S(__system_property_get) S(getprogname) S(open) S(read)
S(close) S(prctl) S(socket) S(connect) S(setsockopt) S(poll) S(abort) S(pthread_once) S(pthread_setspecific)
S(pthread_getspecific) S(pthread_key_create) S(pthread_key_delete) S(pthread_mutex_lock) S(pthread_mutex_unlock)
S(getauxval) S(dladdr) S(memfd_create)
int *__errno(void) { return 0; }
EOT
echo 'LIBC { global: __cxa_finalize; __cxa_atexit; strlen; malloc; calloc; realloc; free; strstr; strcmp; strncmp; memcmp; memset; memcpy; memmove; send; __system_property_get; getprogname; open; read; close; prctl; socket; connect; setsockopt; poll; abort; pthread_once; pthread_setspecific; pthread_getspecific; pthread_key_create; pthread_key_delete; pthread_mutex_lock; pthread_mutex_unlock; getauxval; dladdr; memfd_create; __errno; };' > "$STUB/libc.map"
echo 'void dlopen(void) {} void dlsym(void) {} void dlerror(void) {}' > "$STUB/dl.c"
echo 'LIBC { global: dlopen; dlsym; dlerror; };' > "$STUB/dl.map"
echo 'int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }' > "$STUB/log.c"
echo 'extern int *__errno(void); int *__errno_location(void) { return __errno(); }' > "$STUB/errno.c"
cat > "$STUB/emutls.c" <<'EOT'
/* emulated TLS on pthread keys, as the NDK's runtime does for API < 29 (zig's compiler-rt has no __emutls_get_address) */
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
struct ctl { unsigned long size, align, index; void *templ; };
struct arr { unsigned long n; void *p[1]; };
static pthread_once_t once = PTHREAD_ONCE_INIT;
static pthread_key_t key;
static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static unsigned long counter;
static void mk(void) { pthread_key_create(&key, 0); }
void *__emutls_get_address(struct ctl *c) {
    pthread_once(&once, mk);
    pthread_mutex_lock(&mu);
    if (!c->index) c->index = ++counter;
    unsigned long idx = c->index;
    pthread_mutex_unlock(&mu);
    struct arr *a = pthread_getspecific(key);
    if (!a || a->n < idx) {
        unsigned long n = idx + 8;
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
T="--target=aarch64-linux-musl -fPIC -shared -nostdlib"
$ZIG cc $T -Os -fno-builtin -Wl,--version-script,"$STUB/libc.map" -Wl,-soname,libc.so -o "$STUB/libc_stub.so" "$STUB/libc.c"
$ZIG cc $T -Os -fno-builtin -Wl,--version-script,"$STUB/dl.map" -Wl,-soname,libdl.so -o "$STUB/libdl_stub.so" "$STUB/dl.c"
$ZIG cc $T -Os -fno-builtin -Wl,-soname,liblog.so -o "$STUB/liblog_stub.so" "$STUB/log.c"
$ZIG cc --target=aarch64-linux-musl -fPIC -shared -O2 -fvisibility=hidden -mno-outline-atomics \
  -Wall -Wno-unused-function -fno-stack-protector -femulated-tls -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,lazy -Wl,-z,norelro -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s -Wl,-soname,libGLES_aemu.so \
  -L"$STUB" -o "$STUB/out.so" guest.c "$STUB/errno.c" "$STUB/emutls.c" -lc_stub -ldl_stub -llog_stub
if [ $# -eq 0 ]; then
  set --
  for f in ../../app/src/main/assets/engines/n64-*/system#lib64#egl#libGLES_aemu.so; do [ -f "$f" ] && set -- "$@" "$f"; done
fi
for f in "$@"; do cp "$STUB/out.so" "$f"; ls -l "$f"; done
