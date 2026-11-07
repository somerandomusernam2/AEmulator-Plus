#!/bin/sh
# Rebuilds src/main/assets/engines/kk/aemu-bt.jar (guest Bluetooth service for the Android 4.4 / API 19 image).
# Needs: JDK 8+ (javac), the `aidl` tool (apt: aidl), dalvik-exchange (apt) or a Build Tools `dx`, and an API 19 android.jar.
#   ANDROID_JAR=/path/to/platforms/android-19/android.jar ./build.sh
# The AIDL files are AOSP android-4.4w_r1 (frameworks/base/core/java/android/bluetooth), minus
# IBluetoothGattCallback.onAdvertiseStateChange, which the KKWT-1077298 framework does not have. Their transaction
# codes were checked against that image's IBluetooth*$Stub classes.
set -e
here=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_JAR:?set ANDROID_JAR to an android-19 android.jar}"
DX=${DX:-$(command -v dalvik-exchange || command -v dx)}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
mkdir -p "$work/gen" "$work/classes" "$work/dex"
for f in "$here"/aidl/android/bluetooth/I*.aidl; do aidl -I"$here/aidl" -o"$work/gen" "$f"; done
javac --release 8 -nowarn -Xlint:-options -cp "$ANDROID_JAR" -sourcepath "$work/gen" -d "$work/classes" \
    $(find "$here/java" -name '*.java')
# only our own classes go into the jar; android.bluetooth.* comes from the guest framework
find "$work/classes" -path '*/android' -prune -exec rm -rf {} + 2>/dev/null || true
"$DX" --dex --min-sdk-version=19 --output="$work/dex/classes.dex" "$work/classes"
out="$here/../src/main/assets/engines/kk/aemu-bt.jar"
(cd "$work/dex" && rm -f "$out" && zip -q -X "$out" classes.dex)
echo "wrote $out"
