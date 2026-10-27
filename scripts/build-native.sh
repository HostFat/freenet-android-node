#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jni_libs_dir="${repo_root}/android/app/src/main/jniLibs"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to the pinned Android NDK}"

rm -rf "${jni_libs_dir}"
mkdir -p "${jni_libs_dir}"
cd "${repo_root}/native"

read -r -a abis <<< "${FREENET_ABIS:-arm64-v8a x86_64}"
target_args=()
for abi in "${abis[@]}"; do
  target_args+=(--target "${abi}")
done

cargo ndk \
  --platform 28 \
  "${target_args[@]}" \
  --output-dir "${jni_libs_dir}" \
  build \
  --locked \
  --release
