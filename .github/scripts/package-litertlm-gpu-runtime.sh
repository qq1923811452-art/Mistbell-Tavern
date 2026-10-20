#!/usr/bin/env bash
set -euo pipefail

# Pin the GPU plugin payload to the exact LiteRT-LM release used by the 3.0 app.
readonly upstream_tag="v0.18.0"
readonly upstream_commit="b2f686e2ed4718fb84ec398a61dd59ca0f0aff27"
readonly temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
readonly upstream_dir="$temp_root/LiteRT-LM-${upstream_commit:0:7}"
readonly source_dir="$upstream_dir/prebuilt/android_arm64"
readonly destination="mistbell-v3/app/src/main/jniLibs/arm64-v8a"
readonly asset_dir="mistbell-v3/app/src/main/assets/native-runtime"

rm -rf "$upstream_dir"
git clone --depth 1 --branch "$upstream_tag" https://github.com/google-ai-edge/LiteRT-LM.git "$upstream_dir"
test "$(git -C "$upstream_dir" describe --tags --exact-match)" = "$upstream_tag"
test "$(git -C "$upstream_dir" rev-parse HEAD)" = "$upstream_commit"
test -d "$source_dir"

mkdir -p "$destination" "$asset_dir"
shopt -s nullglob
libraries=("$source_dir"/*.so)
test "${#libraries[@]}" -gt 0 || { echo "No LiteRT-LM arm64 GPU runtime libraries found." >&2; exit 1; }

# Treat the upstream native payload as an explicit allowlist. A new library in
# a future upstream tag must be reviewed for purpose, dependencies and license
# before it can enter the APK.
readonly expected_libraries=(
  libGemmaModelConstraintProvider.so
  libLiteRtGpuAccelerator.so
  libLiteRtOpenClAccelerator.so
  libLiteRtTopKOpenClSampler.so
  libLiteRtTopKWebGpuSampler.so
  libLiteRtWebGpuAccelerator.so
  libwebgpu_dawn.so
)
actual_names="$(printf '%s\n' "${libraries[@]##*/}" | sort)"
expected_names="$(printf '%s\n' "${expected_libraries[@]}" | sort)"
if [[ "$actual_names" != "$expected_names" ]]; then
  echo "Upstream LiteRT-LM GPU library set differs from the reviewed allowlist." >&2
  diff -u <(printf '%s\n' "$expected_names") <(printf '%s\n' "$actual_names") || true
  exit 1
fi

for library in "${libraries[@]}"; do
  test -s "$library" || { echo "Empty upstream library: $library" >&2; exit 1; }
  target="$destination/$(basename "$library")"
  if [[ -e "$target" ]]; then
    cmp -s "$library" "$target" || {
      echo "Refusing to overwrite a different existing native library: $target" >&2
      exit 1
    }
  else
    install -m 0644 "$library" "$target"
  fi
done

for required in libLiteRtGpuAccelerator.so libLiteRtOpenClAccelerator.so; do
  test -s "$destination/$required" || {
    echo "Required LiteRT-LM GPU plugin is missing: $required" >&2
    exit 1
  }
done

metadata_tmp="$(mktemp -d "$temp_root/mistbell-litertlm-metadata.XXXXXX")"
trap 'rm -rf "$metadata_tmp"' EXIT
license_tmp="$metadata_tmp/LITERT_LM_LICENSE.txt"
properties_tmp="$metadata_tmp/litertlm-gpu.properties"
checksum_tmp="$metadata_tmp/litertlm-gpu.sha256"
inventory_tmp="$metadata_tmp/litertlm-gpu-inventory.txt"

cp "$upstream_dir/LICENSE" "$license_tmp"
printf 'source_url=https://github.com/google-ai-edge/LiteRT-LM\n' > "$properties_tmp"
printf 'source_tag=%s\n' "$upstream_tag" >> "$properties_tmp"
printf 'source_commit=%s\n' "$upstream_commit" >> "$properties_tmp"
printf 'source_directory=prebuilt/android_arm64\n' >> "$properties_tmp"
printf 'validation_state=packaged_not_device_verified\n' >> "$properties_tmp"
printf 'packaged_libraries=%s\n' "${#libraries[@]}" >> "$properties_tmp"

for library in "${libraries[@]}"; do
  (cd "$source_dir" && sha256sum "$(basename "$library")")
done > "$checksum_tmp"

: > "$inventory_tmp"
for library in "${libraries[@]}"; do
  printf '%s\t%s bytes\n' "$(basename "$library")" "$(stat -c '%s' "$library")" >> "$inventory_tmp"
done

# Generated metadata is treated like a native library: never silently overwrite
# a different existing file or license notice in the working tree.
for name in LITERT_LM_LICENSE.txt litertlm-gpu.properties litertlm-gpu.sha256 litertlm-gpu-inventory.txt; do
  case "$name" in
    LITERT_LM_LICENSE.txt) generated="$license_tmp" ;;
    litertlm-gpu.properties) generated="$properties_tmp" ;;
    litertlm-gpu.sha256) generated="$checksum_tmp" ;;
    litertlm-gpu-inventory.txt) generated="$inventory_tmp" ;;
  esac
  target="$asset_dir/$name"
  if [[ -e "$target" ]]; then
    cmp -s "$generated" "$target" || {
      echo "Refusing to overwrite conflicting runtime metadata/license: $target" >&2
      exit 1
    }
  else
    install -m 0644 "$generated" "$target"
  fi
done

echo "Pinned LiteRT-LM GPU runtime staged: tag=$upstream_tag commit=$upstream_commit libraries=${#libraries[@]}."
echo "Packaged the pinned GPU runtime (7 libraries); this is packaging evidence only, not proof of GPU execution. The final APK native dependency audit and real Redmi K70 Pro inference are separate checks."
