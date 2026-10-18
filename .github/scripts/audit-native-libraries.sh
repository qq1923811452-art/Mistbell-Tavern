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

# A RUNPATH/RPATH is acceptable only when every colon-separated entry is
# relocatable relative to the library via $ORIGIN (or a subdirectory of it).
# Keep this check's self-tests independent of any actual APK libraries.
is_allowed_rpath_entry() {
  local entry="$1"
  [[ "$entry" == '$ORIGIN' ]] && return 0
  [[ "$entry" == '$ORIGIN/'* ]] || return 1
  # Reject traversal and ambiguous path components even when prefixed by $ORIGIN.
  local relative="${entry#'$ORIGIN/'}"
  [[ -n "$relative" && "$relative" != *"//"* ]] || return 1
  [[ "/$relative/" != *"/../"* && "/$relative/" != *"/./"* ]]
}
if ! grep -Eq '\((RPATH|RUNPATH)\)' <<< ' 0x0000001d (RUNPATH) Library runpath: [$ORIGIN/lib]' ||
   ! is_allowed_rpath_entry '$ORIGIN' ||
   ! is_allowed_rpath_entry '$ORIGIN/lib' ||
   is_allowed_rpath_entry '/tmp' ||
   is_allowed_rpath_entry '$ORIGINsuffix' ||
   is_allowed_rpath_entry '$ORIGIN/../vendor' ||
   is_allowed_rpath_entry '$ORIGIN/lib/../../tmp' ||
   is_allowed_rpath_entry '$ORIGIN/./lib'; then
  echo "RPATH/RUNPATH policy self-test failed." >&2
  exit 1
fi
echo "RPATH/RUNPATH policy self-test passed."

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
for name in libvndksupport.so libOpenCL.so libcdsprpc.so; do
  device_provided["$name"]=1
done

shopt -s nullglob
libraries=("$root"/*.so)
test "${#libraries[@]}" -gt 0 || { echo "No native libraries found under $root" >&2; exit 1; }

declare -A packaged=()
declare -A dynamic_info=()
rpath_violations=()
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

  # Collect every non-$ORIGIN RPATH/RUNPATH entry. Do not exit here: reporting
  # all offending libraries in one run makes the dependency fix complete.
  while IFS= read -r path_line; do
    [[ -n "$path_line" ]] || continue
    if [[ "$path_line" =~ \[(.*)\] ]]; then
      path_value="${BASH_REMATCH[1]}"
      IFS=: read -r -a path_entries <<< "$path_value"
      for path_entry in "${path_entries[@]}"; do
        if ! is_allowed_rpath_entry "$path_entry"; then
          rpath_violations+=("$lib: ${path_line#*] } (entry: $path_entry)")
        fi
      done
    else
      rpath_violations+=("$lib: unable to parse RPATH/RUNPATH entry: $path_line")
    fi
  done < <(grep -E '\((RPATH|RUNPATH)\)' <<< "$dynamic" || true)

  dynamic_info["$name"]="$dynamic"
done

if [[ "${#rpath_violations[@]}" -gt 0 ]]; then
  echo "Unexpected RPATH/RUNPATH entries (only \$ORIGIN and \$ORIGIN/... are allowed):" >&2
  printf '  - %s\n' "${rpath_violations[@]}" >&2
  echo "Native library audit failed: found ${#rpath_violations[@]} disallowed RPATH/RUNPATH entr(y/ies)." >&2
  exit 1
fi

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
