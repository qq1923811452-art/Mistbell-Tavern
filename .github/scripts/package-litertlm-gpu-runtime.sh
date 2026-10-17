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

install -m 0644 "$upstream_dir/LICENSE" "$asset_dir/LITERT_LM_LICENSE.txt"
printf 'source_url=https://github.com/google-ai-edge/LiteRT-LM\n' > "$asset_dir/litertlm-gpu.properties"
printf 'source_tag=%s\n' "$upstream_tag" >> "$asset_dir/litertlm-gpu.properties"
printf 'source_commit=%s\n' "$upstream_commit" >> "$asset_dir/litertlm-gpu.properties"
printf 'source_directory=prebuilt/android_arm64\n' >> "$asset_dir/litertlm-gpu.properties"
printf 'validation_state=packaged_not_device_verified\n' >> "$asset_dir/litertlm-gpu.properties"
printf 'packaged_libraries=%s\n' "${#libraries[@]}" >> "$asset_dir/litertlm-gpu.properties"

for library in "${libraries[@]}"; do
  (cd "$source_dir" && sha256sum "$(basename "$library")")
done > "$asset_dir/litertlm-gpu.sha256"

: > "$asset_dir/litertlm-gpu-inventory.txt"
for library in "${libraries[@]}"; do
  printf '%s\t%s bytes\n' "$(basename "$library")" "$(stat -c '%s' "$library")" >> "$asset_dir/litertlm-gpu-inventory.txt"
done

echo "Pinned LiteRT-LM GPU runtime staged: tag=$upstream_tag commit=$upstream_commit libraries=${#libraries[@]}."
echo "This records packaging only; real GPU inference on the Redmi K70 Pro is still required."
