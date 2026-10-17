#!/usr/bin/env bash
set -euo pipefail

# Keep ELF header parsing stable across runners with different locales.
export LC_ALL=C

root="${1:-mistbell-v3/app/src/main/jniLibs/arm64-v8a}"
test -d "$root"

for command in readelf sed grep; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Native library audit requires '$command' in PATH (install the Android/ELF binutils tools)." >&2
    exit 127
  fi
done

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
declare -A dynamic_info=()
for lib in "${libraries[@]}"; do
  test -s "$lib" || { echo "Empty native library: $lib" >&2; exit 1; }
  name="$(basename "$lib")"
  if [[ -n "${device_provided[$name]+x}" ]]; then
    echo "Device/vendor-provided library must not be bundled in APK jniLibs: $lib" >&2
    exit 1
  fi
  packaged["$name"]=1

  if ! header="$(readelf -h "$lib" 2>&1)"; then
    echo "Unable to read ELF header for $lib: $header" >&2
    exit 1
  fi
  grep -Eq '^[[:space:]]*Machine:[[:space:]]*AArch64([[:space:]]|$)' <<< "$header" || {
    echo "Wrong ELF architecture (expected AArch64): $lib" >&2
    exit 1
  }

  # Do not hide readelf errors: otherwise a malformed ELF could appear to have
  # no dependencies and incorrectly pass this audit.
  if ! dynamic="$(readelf -d "$lib" 2>&1)"; then
    echo "Unable to read dynamic section for $lib: $dynamic" >&2
    exit 1
  fi
  if grep -Eq '\\((RPATH|RUNPATH)\\)' <<< "$dynamic"; then
    echo "Unexpected RPATH/RUNPATH in $lib" >&2
    exit 1
  fi
  dynamic_info["$name"]="$dynamic"
done

missing=0
report="${TMPDIR:-/tmp}/native-needed-$$.txt"
: > "$report"
for lib in "${libraries[@]}"; do
  name="$(basename "$lib")"
  dynamic="${dynamic_info[$name]}"
  while IFS= read -r needed; do
    [[ -n "$needed" ]] || continue
    printf '%s -> %s\n' "$name" "$needed" >> "$report"
    if [[ -n "${packaged[$needed]+x}" || -n "${android_system[$needed]+x}" ]]; then
      continue
    fi
    if [[ -n "${device_provided[$needed]+x}" ]]; then
      echo "Device/vendor-provided dependency (must not be bundled): $name -> $needed"
      continue
    fi
    echo "Unresolved/unknown DT_NEEDED dependency: $name -> $needed"
    missing=1
  done < <(sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p' <<< "$dynamic")
done

echo "ELF architecture and dependency report:"
cat "$report"
rm -f "$report"
if [[ "$missing" -ne 0 ]]; then
  echo "Native dependency audit failed. Add a dependency only after verifying source, ABI, version, license and device-vs-APK ownership." >&2
  exit 1
fi
echo "Audited ${#libraries[@]} AArch64 native libraries."
