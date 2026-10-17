#!/usr/bin/env bash
set -euo pipefail

# Stage the Qualcomm HTP V75 runtime payload from a locally installed QAIRT SDK.
# This deliberately does not download or redistribute the proprietary QAIRT SDK.
# Required environment:
#   QUALCOMM_QAIRT_ROOT=/path/to/qairt/<version>
#   QUALCOMM_QAIRT_VERSION=<exact SDK version>
#   LITERT_QUALCOMM_DISPATCH_SO=/path/to/libLiteRtDispatch_Qualcomm.so
#
# The output manifest remains "packaged_not_device_verified". Do not change it to
# "verified" until the exact LiteRT-LM 0.18.0 + QAIRT combination passes real
# SM8650 inference and the native ELF/runtime logs have been reviewed.

: "${QUALCOMM_QAIRT_ROOT:?Set QUALCOMM_QAIRT_ROOT to the extracted QAIRT SDK root}"
: "${QUALCOMM_QAIRT_VERSION:?Set QUALCOMM_QAIRT_VERSION to the exact QAIRT SDK version}"
: "${LITERT_QUALCOMM_DISPATCH_SO:?Set LITERT_QUALCOMM_DISPATCH_SO to the matching built dispatch plugin}"

readonly qairt_root="${QUALCOMM_QAIRT_ROOT%/}"
readonly qairt_host_dir="$qairt_root/lib/aarch64-android"
readonly qairt_hexagon_dir="$qairt_root/lib/hexagon-v75/unsigned"
readonly dispatch_so="$LITERT_QUALCOMM_DISPATCH_SO"
readonly destination="mistbell-v3/app/src/main/jniLibs/arm64-v8a"
readonly asset_dir="mistbell-v3/app/src/main/assets/native-runtime"
readonly temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"

test -d "$qairt_host_dir" || { echo "QAIRT Android host library directory not found: $qairt_host_dir" >&2; exit 1; }
test -d "$qairt_hexagon_dir" || { echo "QAIRT Hexagon V75 library directory not found: $qairt_hexagon_dir" >&2; exit 1; }
test -s "$dispatch_so" || { echo "Qualcomm dispatch plugin missing or empty: $dispatch_so" >&2; exit 1; }
test "$(basename "$dispatch_so")" = "libLiteRtDispatch_Qualcomm.so" || {
  echo "Unexpected dispatch plugin filename: $dispatch_so" >&2
  exit 1
}

readonly host_libraries=(
  libQnnSystem.so
  libQnnHtp.so
  libQnnHtpPrepare.so
)
# The exact stub/skeleton set is QAIRT-version-specific. Stage every V75 HTP stub
# and DSP skeleton shipped by that same SDK instead of guessing optional filenames.
shopt -s nullglob
stub_paths=("$qairt_host_dir"/libQnnHtpV75*Stub.so)
dsp_paths=("$qairt_hexagon_dir"/libQnnHtpV75*Skel.so)
stub_libraries=()
dsp_libraries=()
for path in "${stub_paths[@]}"; do stub_libraries+=("$(basename "$path")"); done
for path in "${dsp_paths[@]}"; do dsp_libraries+=("$(basename "$path")"); done
test "${#stub_libraries[@]}" -gt 0 || { echo "No V75 HTP stub libraries found in $qairt_host_dir" >&2; exit 1; }
test "${#dsp_libraries[@]}" -gt 0 || { echo "No V75 HTP DSP skeletons found in $qairt_hexagon_dir" >&2; exit 1; }

mkdir -p "$destination" "$asset_dir"

for name in "${host_libraries[@]}"; do
  source="$qairt_host_dir/$name"
  test -s "$source" || {
    echo "Required SM8650/V75 QAIRT host library is missing: $source" >&2
    exit 1
  }
done
for name in "${stub_libraries[@]}"; do
  source="$qairt_host_dir/$name"
  test -s "$source" || { echo "Required V75 stub library is missing: $source" >&2; exit 1; }
done
for name in "${dsp_libraries[@]}"; do
  source="$qairt_hexagon_dir/$name"
  test -s "$source" || {
    echo "Required SM8650/V75 DSP library is missing: $source" >&2
    exit 1
  }
done

# Stage only the reviewed SM8650/V75 allowlist plus the explicitly supplied
# Qualcomm dispatch plugin. Never rename a V73/V79 binary to make it look like V75.
for name in "${host_libraries[@]}" "${stub_libraries[@]}"; do
  source="$qairt_host_dir/$name"
  target="$destination/$name"
  if [[ -e "$target" ]]; then
    cmp -s "$source" "$target" || {
      echo "Refusing to overwrite a different native library: $target" >&2
      exit 1
    }
  else
    install -m 0644 "$source" "$target"
  fi
done
for name in "${dsp_libraries[@]}"; do
  source="$qairt_hexagon_dir/$name"
  target="$destination/$name"
  if [[ -e "$target" ]]; then
    cmp -s "$source" "$target" || {
      echo "Refusing to overwrite a different native library: $target" >&2
      exit 1
    }
  else
    install -m 0644 "$source" "$target"
  fi
done
target="$destination/libLiteRtDispatch_Qualcomm.so"
if [[ -e "$target" ]]; then
  cmp -s "$dispatch_so" "$target" || {
    echo "Refusing to overwrite a different Qualcomm dispatch plugin: $target" >&2
    exit 1
  }
else
  install -m 0644 "$dispatch_so" "$target"
fi

metadata_tmp="$(mktemp -d "$temp_root/mistbell-qualcomm-runtime.XXXXXX")"
trap 'rm -rf "$metadata_tmp"' EXIT
properties_tmp="$metadata_tmp/qualcomm-sm8650.properties"
checksum_tmp="$metadata_tmp/qualcomm-sm8650.sha256"
inventory_tmp="$metadata_tmp/qualcomm-sm8650-inventory.txt"

{
  printf 'source=Qualcomm QAIRT SDK (locally supplied)\n'
  printf 'target_soc=SM8650\n'
  printf 'htp_arch=V75\n'
  printf 'litertlm_android=0.18.0\n'
  printf 'qairt_version=%s\n' "$QUALCOMM_QAIRT_VERSION"
  printf 'qairt_abi=unverified\n'
  printf 'dispatch_abi=unverified\n'
  printf 'status=packaged_not_device_verified\n'
  printf 'note=Package presence does not prove LiteRT-LM ABI compatibility or NPU execution.\n'
} > "$properties_tmp"

: > "$checksum_tmp"
: > "$inventory_tmp"
for name in "${host_libraries[@]}" "${stub_libraries[@]}" "${dsp_libraries[@]}" libLiteRtDispatch_Qualcomm.so; do
  library="$destination/$name"
  sha256sum "$library" >> "$checksum_tmp"
  printf '%s\t%s\n' "$name" "$(stat -c '%s' "$library")" >> "$inventory_tmp"
done

for name in qualcomm-sm8650.properties qualcomm-sm8650.sha256 qualcomm-sm8650-inventory.txt; do
  case "$name" in
    qualcomm-sm8650.properties) generated="$properties_tmp" ;;
    qualcomm-sm8650.sha256) generated="$checksum_tmp" ;;
    qualcomm-sm8650-inventory.txt) generated="$inventory_tmp" ;;
  esac
  target="$asset_dir/$name"
  if [[ -e "$target" ]]; then
    cmp -s "$generated" "$target" || {
      echo "Refusing to overwrite conflicting Qualcomm runtime metadata: $target" >&2
      exit 1
    }
  else
    install -m 0644 "$generated" "$target"
  fi
done

echo "Staged Qualcomm HTP V75 runtime files for LiteRT-LM 0.18.0 (QAIRT $QUALCOMM_QAIRT_VERSION)."
echo "Manifest intentionally remains packaged_not_device_verified; NPU stays disabled until ABI and real SM8650 inference are verified."
