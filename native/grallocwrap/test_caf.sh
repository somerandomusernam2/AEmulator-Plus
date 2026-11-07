#!/bin/sh
# gralloc_caf.c on the build machine: sh test_caf.sh (needs a C compiler)
cd "$(dirname "$0")" || exit 1
T=$(mktemp -d) || exit 1
CC=${CC:-cc}
$CC -shared -fPIC -DFAKE -o "$T/libfake.so" test_caf.c &&
$CC -shared -fPIC "-DREAL_PATH=\"$T/libfake.so\"" -o "$T/wrap.so" gralloc_caf.c -ldl &&
$CC -o "$T/test" test_caf.c -ldl &&
"$T/test" "$T/libfake.so" "$T/wrap.so"
r=$?; rm -rf "$T"; exit $r
