#!/usr/bin/env bash
set -euo pipefail

root="${1:-mistbell-v3/app/src/main/jniLibs/arm64-v8a}"
test -d "$root"

# Public Android platform/NDK libraries that may legitimately be DT_NEEDED by
# app-bundled arm64 libraries. Do not treat vendor/device libraries as public NDK
# APIs merely because some phones expose them.
declare -A android_system=()
for name in \
  libc.so libm.so libdl.so liblog.so libandroid.so libz.so libjnigraphics.so \
  libEGL.so libGLESv1_CM.so libGLESv2.so libGLESv3.so libvulkan.so \
  libOpenSLES.so libaaudio.so libmediandk.so libnativewindow.so libsync.so \
  libcamera2ndk.so libamidi.so libneuralnetworks.so libbinder_ndk.so; do
  android_system["$name"]=1
done

# These are device/vendor-provided dependencies on relevant devices, not APK
# payloads. Manifest uses-native-library declarations do not mean to bundle them.
declare -A device_provided=()
for name in libvndksupport.so libOpenCL.so; do
  device_provided["$name"]=1
done

shopt -s nullglob
libraries=("$root"/*.so)
test "${#libraries[@]}" -gt 0 || { echo "No native libraries found under $root" >&2; exit 1; }

declare -A packaged=()
for lib in "${libraries[@]}"; do
  test -s "$lib" || { echo "Empty native library: $lib" >&2; exit 1; }
  name="$(basename "$lib")"
  if [[ -n "${device_provided[$name]+x}" ]]; then
    echo "Device/vendor-provided library must not be bundled in APK jniLibs: $lib" >&2
    exit 1
  fi
  packaged["$name"]=1
  header="$(readelf -h "$lib")"
  grep -q 'Machine:.*AArch64' <<< "$header" || {
    echo "Wrong ELF architecture (expected AArch64): $lib" >&2
    exit 1
  }
  if readelf -d "$lib" 2>/dev/null | grep -Eq '\((RPATH|RUNPATH)\)'; then
    echo "Unexpected RPATH/RUNPATH in $lib" >&2
    exit 1
  fi
done

missing=0
report="${TMPDIR:-/tmp}/native-needed-$$.txt"
: > "$report"
for lib in "${libraries[@]}"; do
  while IFS= read -r needed; do
    [[ -n "$needed" ]] || continue
    printf '%s -> %s\n' "$(basename "$lib")" "$needed" >> "$report"
    if [[ -n "${packaged[$needed]+x}" || -n "${android_system[$needed]+x}" ]]; then
      continue
    fi
    if [[ -n "${device_provided[$needed]+x}" ]]; then
      echo "Device/vendor-provided dependency (must not be bundled): $(basename "$lib") -> $needed"
      continue
    fi
    echo "Unresolved/unknown DT_NEEDED dependency: $(basename "$lib") -> $needed"
    missing=1
  done < <(readelf -d "$lib" 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p')
done

echo "ELF architecture and dependency report:"
cat "$report"
rm -f "$report"
if [[ "$missing" -ne 0 ]]; then
  echo "Native dependency audit failed. Add a dependency only after verifying source, ABI, version, license and device-vs-APK ownership." >&2
  exit 1
fi
echo "Audited ${#libraries[@]} AArch64 native libraries."
