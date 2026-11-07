#!/bin/bash
# qemu-arm / qemu-i386 for Android: STATIC build directly on the host.
# No proot, no chroot, no Alpine rootfs.
#
#   ./qemu/build-android.sh          (as a normal user; sudo is used only to install packages)
#
# Host: aarch64 Linux. Alpine (apk) or Debian/Ubuntu (apt).
#  * Alpine: glib-static comes from apk -> same musl static binary as before.
#  * Debian/Ubuntu: musl-tools (musl-gcc) is used, and zlib, libffi, pcre2 and
#    glib are ALL built statically against musl into qemu/work/musl-prefix.
#    Result: a fully static musl binary (no PT_INTERP, no glibc). glibc static
#    binaries tend to die on Android with exit 159 (SIGSYS, seccomp) because
#    glibc uses newer syscalls (clone3, rseq, faccessat2, ...); musl does not.
#    The output can be renamed to libfoo.so and exec'd from the app's native
#    lib dir (it is an executable, NOT dlopen-able).
set -euo pipefail
VER="${VER:-8.2.4}"
QEMU_SHA256_PIN=""
[ "$VER" = 8.2.4 ] && QEMU_SHA256_PIN=ecf5537feab92641b99d7482f551f2195d3a5bd34acef9d52bfbff353a607397
QEMU_SHA256="${QEMU_SHA256:-$QEMU_SHA256_PIN}"
[ -n "$QEMU_SHA256" ] || { echo "no QEMU_SHA256 for VER=$VER"; exit 1; }
GLIB_VER="${GLIB_VER:-2.80.5}"
GLIB_SHA256="${GLIB_SHA256:-}"     # optional pin; if empty the computed sum is printed
CHANGED_DATES='2026-08-29 ... 2026-10-12'
BR=host; ALPINE_ROOTFS=none; ALPINE_SHA256=none
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
W="${W:-$PROJ/qemu/work}"
case "$(uname -m)" in aarch64|arm64) ;; *) echo "need an aarch64 host, this is $(uname -m)"; exit 1;; esac
mkdir -p "$W"
SUDO=""; [ "$(id -u)" = 0 ] || SUDO="sudo"

echo "== 1/4 build dependencies"
if command -v apk >/dev/null 2>&1; then
  $SUDO apk add build-base meson samurai pkgconf python3 py3-pip py3-setuptools \
      py3-distlib bison flex linux-headers glib-dev glib-static zlib-static \
      curl tar xz patch file
  GLIB_FROM_SRC=0
elif command -v apt-get >/dev/null 2>&1; then
  $SUDO apt-get update
  # NB: no libpcre2-dev / libffi-dev / zlib1g-dev on purpose: those are glibc
  # builds and must never leak into a musl link. We build our own below.
  $SUDO apt-get install -y build-essential musl-tools linux-libc-dev meson ninja-build \
      pkg-config python3 python3-pip python3-venv python3-setuptools python3-distlib \
      bison flex curl tar xz-utils bzip2 patch file
  # glib 2.80 needs meson >= 1.2; old distro meson -> newer one from pip
  if [ "$(printf '1.2.0\n%s\n' "$(meson --version)" | sort -V | head -1)" != 1.2.0 ]; then
    pip3 install --user --break-system-packages 'meson>=1.2.0'
    export PATH="$HOME/.local/bin:$PATH"; hash -r
    echo "   meson now: $(meson --version)"
  fi
  GLIB_FROM_SRC=1
else
  echo "unsupported distro: need apk or apt-get"; exit 1
fi

if [ "$GLIB_FROM_SRC" = 1 ]; then
  echo "== 2/4 static musl deps: zlib, libffi, pcre2, glib $GLIB_VER (from source)"
  command -v musl-gcc >/dev/null || { echo "musl-gcc not found (apt: musl-tools)"; exit 1; }
  # musl-gcc only searches /usr/include/<arch>-linux-musl, which on Debian arm64
  # lacks the kernel headers (linux/limits.h etc). Link them in from linux-libc-dev.
  MI="/usr/include/$(uname -m)-linux-musl"
  $SUDO mkdir -p "$MI"
  for d in linux asm-generic mtd sound rdma misc scsi video xen drm; do
    [ -d "/usr/include/$d" ] && [ ! -e "$MI/$d" ] && $SUDO ln -s "/usr/include/$d" "$MI/$d"
  done
  if [ ! -e "$MI/asm" ]; then
    A=$(ls -d /usr/include/*-linux-gnu/asm 2>/dev/null | head -1)
    [ -n "$A" ] && $SUDO ln -s "$A" "$MI/asm"
  fi
  echo '#include <linux/limits.h>
#include <asm/unistd.h>' | musl-gcc -x c -E - >/dev/null \
    || { echo "!! musl-gcc still cannot see kernel headers in $MI"; exit 1; }
  MP="$W/musl-prefix"
  export CC=musl-gcc
  # Only OUR prefix: system .pc files point at glibc libs.
  export PKG_CONFIG_LIBDIR="$MP/lib/pkgconfig:$MP/lib64/pkgconfig:$MP/share/pkgconfig"
  export PKG_CONFIG_PATH=""
  unset LDFLAGS CPPFLAGS 2>/dev/null || true
  # ★ Debian's aarch64 gcc defaults to -moutline-atomics: calls into libgcc's
  # __aarch64_* helpers, whose lse-init.o needs glibc's __getauxval, which musl
  # does not have ("undefined reference to __getauxval"). It also makes glib's
  # atomics probe fail ("Can't use atomics ... racy"). Inline atomics fix both.
  export CFLAGS="-O2 -mno-outline-atomics"
  fetch() {  # fetch <url> <file> <expected-sha256-or-empty>
    cd "$W"
    [ -f "$2" ] || curl -fsSL "$1" -o "$2"
    local S; S=$(sha256sum "$2" | cut -d' ' -f1)
    if [ -n "$3" ] && [ "$S" != "$3" ]; then
      rm -f "$2"; echo "sha256 mismatch for $2: expected $3 got $S"; exit 1
    fi
    echo "   $2 sha256: $S"
  }
  ZLIB_VER="${ZLIB_VER:-1.3.1}";  FFI_VER="${FFI_VER:-3.4.6}";  PCRE_VER="${PCRE_VER:-10.44}"
  if [ ! -f "$MP/lib/libz.a" ]; then
    fetch "https://github.com/madler/zlib/releases/download/v$ZLIB_VER/zlib-$ZLIB_VER.tar.gz" "zlib-$ZLIB_VER.tar.gz" "${ZLIB_SHA256:-}"
    cd "$W"; rm -rf "zlib-$ZLIB_VER"; tar xf "zlib-$ZLIB_VER.tar.gz"; cd "zlib-$ZLIB_VER"
    ./configure --prefix="$MP" --static
    make -j"$(nproc)"
    make install
  fi
  if [ ! -f "$MP/lib/libffi.a" ]; then
    fetch "https://github.com/libffi/libffi/releases/download/v$FFI_VER/libffi-$FFI_VER.tar.gz" "libffi-$FFI_VER.tar.gz" "${FFI_SHA256:-}"
    cd "$W"; rm -rf "libffi-$FFI_VER"; tar xf "libffi-$FFI_VER.tar.gz"; cd "libffi-$FFI_VER"
    ./configure --prefix="$MP" --libdir="$MP/lib" --disable-shared --enable-static --disable-docs
    make -j"$(nproc)"
    make install
  fi
  if [ ! -f "$MP/lib/libpcre2-8.a" ]; then
    fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-$PCRE_VER/pcre2-$PCRE_VER.tar.bz2" "pcre2-$PCRE_VER.tar.bz2" "${PCRE_SHA256:-}"
    cd "$W"; rm -rf "pcre2-$PCRE_VER"; tar xf "pcre2-$PCRE_VER.tar.bz2"; cd "pcre2-$PCRE_VER"
    ./configure --prefix="$MP" --libdir="$MP/lib" --disable-shared --enable-static
    make -j"$(nproc)"
    make install
  fi
  for m in zlib libffi libpcre2-8; do
    pkg-config --exists $m || { echo "!! $m not visible to pkg-config; see:"; find "$MP" -name "$m*" ; exit 1; }
  done
  if [ ! -f "$MP/lib/libglib-2.0.a" ]; then
    fetch "https://download.gnome.org/sources/glib/${GLIB_VER%.*}/glib-$GLIB_VER.tar.xz" "glib-$GLIB_VER.tar.xz" "$GLIB_SHA256"
    cd "$W"; rm -rf "glib-$GLIB_VER"; tar xf "glib-$GLIB_VER.tar.xz"
    meson setup "glib-$GLIB_VER/b" "glib-$GLIB_VER" --prefix="$MP" --libdir=lib \
      --default-library=static --wrap-mode=nofallback -Dtests=false -Dnls=disabled \
      -Dselinux=disabled -Dlibmount=disabled -Dxattr=false -Dlibelf=disabled \
      -Dintrospection=disabled -Dsysprof=disabled -Dman-pages=disabled
    ninja -C "glib-$GLIB_VER/b" install
  else
    echo "   already built"
  fi
  QEMU_CC="--cc=musl-gcc --host-cc=musl-gcc"
else
  echo "== 2/4 glib: using Alpine glib-static"
  QEMU_CC=""
fi

echo "== 3/4 build QEMU $VER"
mkdir -p "$W/patches" "$W/src" "$W/src-target"
rm -rf "$W/patches" "$W/src-target"; mkdir -p "$W/patches" "$W/src-target"
for p in "$PROJ"/qemu/patches/*.patch; do [ -e "$p" ] && cp -f "$p" "$W/patches/"; done
cp -f "$PROJ"/qemu/src/* "$W/src/"
[ -d "$PROJ/qemu/src-target" ] && cp -rf "$PROJ"/qemu/src-target/. "$W/src-target/"
FRESH="${FRESH:-0}"
cd $W
# ⚠️ Архив НЕ удаляем: изменившийся набор патчей требует чистого дерева, и
# перекачивать 120 МБ ради этого незачем.
#
# ⚠️★ Имя архива — С НОМЕРОМ ВЕРСИИ. Раньше он звался просто `q.tar.xz`, и
# сборка с другим VER брала СТАРЫЙ архив: проверка «файл есть» проходила, и
# распаковывалась прежняя версия. Снаружи это выглядело как «обновление не
# помогло», хотя обновления и не было.
[ -f "q-$VER.tar.xz" ] || curl -fsSL "https://download.qemu.org/qemu-$VER.tar.xz" -o "q-$VER.tar.xz"
# ★★ Сумма архива сверяется при КАЖДОМ запуске, а не только после скачивания:
# файл мог остаться от прежнего запуска или от другого источника. Расхождение —
# отказ; битый файл стираем, чтобы следующий запуск скачал заново.
QSUM=$(sha256sum "q-$VER.tar.xz" | cut -d" " -f1)
if [ "$QSUM" != "$QEMU_SHA256" ]; then
  rm -f "q-$VER.tar.xz"
  echo "!! сумма архива QEMU $VER не сошлась: ждали $QEMU_SHA256, получили $QSUM"
  exit 1
fi
echo "   архив QEMU $VER: сумма сошлась"

# ★ Состояние дерева определяется ОТПЕЧАТКОМ всего набора патчей, а не попыткой
# применить каждый по отдельности.
#
# Прежняя проверка («уже наложен» = патч применяется обратно) неверна в принципе:
# патчи трогают одни и те же места, и следующий ломает контекст предыдущему.
# 0008 добавил строку вплотную к строке из 0006 — после чего 0006 перестал
# применяться и прямо, и обратно, и ЛЮБАЯ пересборка без FRESH падала на нём:
#
#   !! патч не лёг и не наложен: 0006-guest-binder.patch — соберите с FRESH=1
#
# Теперь набор либо тот же самый (дерево не трогаем), либо изменился — и тогда
# дерево пересоздаётся само, без ручного FRESH. Это единственный способ быть
# уверенным, что собран именно тот код, который лежит в patches/.
STAMP=$W/patches.stamp
SUM=$(cat $W/patches/*.patch | md5sum | cut -d" " -f1)
if [ "${FRESH:-0}" = 1 ] || [ ! -d "qemu-$VER" ] || \
   [ "$(cat $STAMP 2>/dev/null)" != "$SUM" ]; then
  echo "   набор патчей другой (или FRESH) — дерево заново"
  rm -rf "qemu-$VER" "$STAMP"
  tar xf "q-$VER.tar.xz"
  cd "qemu-$VER"
  for p in $W/patches/*.patch; do
    [ -e "$p" ] || continue
    patch -p1 -N < "$p" >/dev/null || { echo "!! патч не лёг: $(basename $p)"; exit 1; }
    echo "   наложен $(basename $p)"
  done

  # ★★★★★ GPLv2 §2a: ИЗМЕНЁННЫЙ ФАЙЛ ОБЯЗАН НЕСТИ ПОМЕТКУ.
  #
  # «You must cause the modified files to carry prominent notices stating that
  # you changed the files and the date of any change.» Мы распространяем
  # libqemu.so внутри APK, значит это требование на нас распространяется.
  #
  # ⚠️ Почему ЗДЕСЬ, а не в самих патчах: пометка в патче сдвинула бы начало
  # файла, и у следующего патча разъехался бы контекст. После наложения всего
  # набора — единственное безопасное место.
  #
  # ⚠️ Список берём из самих патчей: что они правят, то и помечаем. Руками
  # список вести нельзя — разойдётся с действительностью на первом же патче.
  echo "   ставлю пометки об изменении (GPLv2 §2a)"
  grep -h '^--- a/' $W/patches/*.patch | sed 's|^--- a/||' | awk '{print $1}' \
    | sort -u | while read -r f; do
      [ -f "$f" ] || continue
      grep -q 'ISTRATII_TECH SENSES' "$f" && continue
      case "$f" in
        *.build|*.mak|Makefile*) o='' ; pre='# ' ; c='' ;;
        *)                       o='/*' ; pre=' * ' ; c=' */' ;;
      esac
      { [ -n "$o" ] && echo "$o"
        echo "${pre}MODIFIED by the ISTRATII_TECH SENSES project: running an"
        echo "${pre}Android system under ARM32-to-ARM64 user-mode translation."
        echo "${pre}Dates of change: ${CHANGED_DATES}."
        echo "${pre}For what was changed and where to get the sources, see NOTICE."
        [ -n "$c" ] && echo "$c"
        cat "$f"
      } > "$f.noticetmp" && mv "$f.noticetmp" "$f"
    done
  echo "   помечено файлов: $(grep -hl 'ISTRATII_TECH SENSES' $(grep -h '^--- a/' $W/patches/*.patch | sed 's|^--- a/||' | awk '{print $1}' | sort -u) 2>/dev/null | wc -l | tr -d ' ')"

  cd $W
  echo "$SUM" > "$STAMP"
else
  echo "   набор патчей тот же — дерево не трогаем"
fi
cd "$W/qemu-$VER"
# Наши файлы — в дерево qemu, перед сборкой (после патчей: meson.build уже
# знает про guest_binder.c).
cp -f $W/src/* linux-user/
# ★ x86-32: QEMU 8.2 has no /proc/cpuinfo for i386, the guest would read the
# phone's ARM one. Our target_proc.h (src-target/i386) replaces the stock stub.
# Only the i386 directory is touched; the arm build does not look there.
[ -f $W/src-target/i386/target_proc.h ] && cp -f $W/src-target/i386/target_proc.h linux-user/i386/target_proc.h

mkdir -p build-static && cd build-static
# --without-default-features: для linux-user не нужно ничего, кроме glib и zlib.
# Всё прочее (pixman, sdl, capstone, fdt) статически тянуло бы полдистрибутива.
#
# ★★★ Оптимизация выше умолчания. qemu собирается с -O2; здесь -O3 и сборка со
# сквозной оптимизацией (LTO). Смысл ровно один: главный поток гостевого
# приложения при листании занят ТРАНСЛЯЦИЕЙ на 94 % пользовательского времени
# (замерено на живом телефоне), и единственный рычаг для него — скорость самого
# транслятора. Ожидание — проценты, не разы.
#
# ⚠️ Ключи задаются переменными OPT и LTO, чтобы вернуться к прежней сборке
# можно было одной командой: OPT=-O2 LTO=0 ./qemu/build-android.sh
#
# ⚠️ Каталог сборки пересоздаётся, КОГДА КЛЮЧИ ПОМЕНЯЛИСЬ. Прежняя проверка
# «есть build.ninja — значит настроено» пропускала configure совсем, и новые
# ключи молча не применялись бы: бинарь получился бы прежним, а замер — ложным.
# ★ Страница 16 КБ — то же требование, что и в native/build.sh: на аппаратах
# Android 15+ со страницей 16 КБ ядро не отобразит сегменты, выровненные на 4.
# Сегодня статический бинарь и так выходит с выравниванием 64 КБ, но полагаться
# на умолчание компоновщика нельзя — оно менялось от версии к версии.
CFG="$QEMU_CC --static --target-list=arm-linux-user,i386-linux-user --disable-system --disable-tools \
     --disable-docs --disable-guest-agent --disable-werror \
     --without-default-features --extra-cflags='${OPT:--O3} -mno-outline-atomics' \
     --extra-ldflags=-Wl,-z,max-page-size=16384"
[ "${LTO:-1}" = 1 ] && CFG="$CFG --enable-lto"
CSUM=$(echo "$CFG" | md5sum | cut -d" " -f1)
if [ "$(cat .cfgstamp 2>/dev/null)" != "$CSUM" ]; then
  echo "   ключи сборки другие — настраиваю заново"
  rm -rf ./* .cfgstamp 2>/dev/null || true
  eval ../configure $CFG   # eval: CFG holds a quoted multi-word --extra-cflags
  echo "$CSUM" > .cfgstamp
fi
ninja qemu-arm qemu-i386
mkdir -p $W/out
cp -f qemu-arm $W/out/qemu-arm
# ★ Копия С СИМВОЛАМИ. Нужна, когда qemu падает в СВОЁМ коде («QEMU internal
# SIGSEGV»): адрес сбоя переводится в имя функции только по ней.
cp -f qemu-arm $W/out/qemu-arm-dbg
strip $W/out/qemu-arm
ls -l $W/out/qemu-arm
# ★ x86-32 guest translator (same patches, same flags). The app runs it as
# libqemu_i386.so for firmware whose ELF files are EM_386.
cp -f qemu-i386 $W/out/qemu-i386
cp -f qemu-i386 $W/out/qemu-i386-dbg
strip $W/out/qemu-i386
ls -l $W/out/qemu-i386
file $W/out/qemu-arm 2>/dev/null || readelf -h $W/out/qemu-arm | head -12
# ★ Must be fully static and not glibc: no dynamic loader, no GLIBC symbol versions.
for b in qemu-arm qemu-i386; do
  if readelf -lW "$W/out/$b" | grep -q INTERP; then echo "!! $b has an ELF interpreter (not static)"; exit 1; fi
  if [ "$GLIB_FROM_SRC" = 1 ] && strings "$W/out/$b" | grep -qE 'GNU C Library|glibc 2\.'; then
    echo "!! $b contains glibc strings - not a musl build"; exit 1
  fi
done
echo "   static + musl check OK"

# ★★★ BUILD-MANIFEST.txt — что именно взято и что получилось.
#
# Это ответ на вопрос «из чего собран этот двоичный файл», который GPLv2 §3
# задаёт получателю: версии пакетов Alpine на день сборки не прибиты (ветка
# движется), поэтому они ЗАПИСЫВАЮТСЯ. Лежит рядом с двоичным файлом и уезжает
# с ним в выпуск. Текст — по-английски: файл читают получатели комплекта.
#
# ⚠️ Версии библиотек, названные в NOTICE (раздел 1.3), сверяются с фактическими:
# разошлись — это сказано и здесь, и в выводе сборки (NOTICE надо поправить).
if command -v apk >/dev/null 2>&1; then
  EXPECT="glib-2.80.5-r0 gettext-0.22.5-r0 musl-1.2.5-r3 zlib-1.3.2-r0 libgcc-13.2.1_git20240309-r1"
  APKV=$(apk info -v | sort)
else
  EXPECT=""
  APKV=$(dpkg-query -W -f='${Package}-${Version}\n' 2>/dev/null | sort; echo "glib (static, built from source): $GLIB_VER")
fi
LIBS=""
for e in $EXPECT; do
  echo "$APKV" | grep -qx "$e" || LIBS="$LIBS $e"
done
{
  echo "BUILD-MANIFEST for qemu-arm-android (libqemu.so) and qemu-i386-android (libqemu_i386.so)"
  echo "built (UTC):  $(date -u '+%Y-%m-%d %H:%M:%S')"
  echo
  echo "QEMU version:   $VER"
  echo "QEMU archive:   qemu-$VER.tar.xz  sha256 $QEMU_SHA256"
  echo "Host:           $(. /etc/os-release; echo "$PRETTY_NAME") $(uname -m)"
  echo "configure:      $CFG"
  echo "                OPT=${OPT:--O3} LTO=${LTO:-1}"
  echo
  echo "compiler:       $(gcc --version | head -1)"
  echo
  if [ -z "$LIBS" ]; then
    echo "libraries named in NOTICE 1.3: all present in the versions given there"
  else
    echo "!! libraries named in NOTICE 1.3 NOT found in the versions given there:$LIBS"
    echo "!! (the Alpine branch has moved on; NOTICE 1.3 must be corrected)"
    echo "!! libraries named in NOTICE 1.3 NOT found in the versions given there:$LIBS" >&2
  fi
  echo
  echo "== installed packages (build host)"
  echo "$APKV"
  echo
  NP=$(ls $W/patches/*.patch | wc -l | tr -d ' ')
  echo "== patches: $NP (applied in this order), sha256"
  (cd $W/patches && sha256sum *.patch)
  echo
  echo "== our source files copied into linux-user/: $(ls $W/src | wc -l | tr -d ' '), sha256"
  (cd $W/src && sha256sum *)
  echo
  echo "== target-directory files (src-target/, replace stock files of linux-user/<arch>/), sha256"
  (cd $W/src-target && find . -type f | sort | xargs sha256sum)
  echo
  echo "== result, sha256"
  (cd $W/out && sha256sum qemu-arm qemu-arm-dbg qemu-i386 qemu-i386-dbg)
} > $W/out/BUILD-MANIFEST.txt
echo "   BUILD-MANIFEST.txt: патчей $NP, результат $(sha256sum $W/out/qemu-arm | cut -d' ' -f1)"

echo "== 4/4 copy results"
mkdir -p "$PROJ/qemu/build"
cp -f "$W/out/qemu-arm"      "$PROJ/qemu/build/qemu-arm-android"
cp -f "$W/out/qemu-arm-dbg"  "$PROJ/qemu/build/qemu-arm-android-dbg"
cp -f "$W/out/qemu-i386"     "$PROJ/qemu/build/qemu-i386-android"
cp -f "$W/out/qemu-i386-dbg" "$PROJ/qemu/build/qemu-i386-android-dbg"
cp -f "$W/out/BUILD-MANIFEST.txt" "$PROJ/qemu/build/BUILD-MANIFEST.txt"
{ echo; echo "== build script, sha256"; echo "$(sha256sum "$PROJ/qemu/build-android.sh" | cut -d' ' -f1)  build-android.sh"; } >> "$PROJ/qemu/build/BUILD-MANIFEST.txt"
chmod 644 "$PROJ/qemu/build/BUILD-MANIFEST.txt"
chmod 755 "$PROJ"/qemu/build/qemu-*-android*
ls -l "$PROJ/qemu/build/qemu-arm-android"
"$PROJ/qemu/build/qemu-arm-android" --version | head -1
"$PROJ/qemu/build/qemu-i386-android" --version | head -1
echo "== done: qemu/build/qemu-arm-android, qemu-i386-android, BUILD-MANIFEST.txt"
