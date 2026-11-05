#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_root="${repo_root}/artifacts"

gradle_version_args=()
if [[ -n "${APP_VERSION_CODE:-}" ]]; then
  gradle_version_args+=("-PappVersionCode=${APP_VERSION_CODE}")
fi
if [[ -n "${APP_VERSION_NAME:-}" ]]; then
  gradle_version_args+=("-PappVersionName=${APP_VERSION_NAME}")
fi
if [[ -n "${CRASH_REPORT_TOKEN:-}" ]]; then
  gradle_version_args+=("-PcrashReportToken=${CRASH_REPORT_TOKEN}")
fi

# Release packages target real phones. Debug builds keep x86_64 for emulators.
export FREENET_ABIS="arm64-v8a"
gradle_version_args+=("-Pabis=arm64-v8a")
gradle_version_args+=("-PprebuiltNode=${PREBUILT_NODE:-false}")
gradle_version_args+=("-PcoreVersion=${FREENET_PREBUILT_VERSION:-unknown}")

if [[ "${PREBUILT_NODE:-false}" == "true" ]]; then
  # Release runs the official binary; the JNI library is not built or shipped.
  "${repo_root}/scripts/fetch-prebuilt-node.sh"
else
  "${repo_root}/scripts/build-native.sh"
fi
"${repo_root}/android/gradlew" \
  -p "${repo_root}/android" \
  --no-daemon \
  "${gradle_version_args[@]}" \
  :app:assembleRelease

mkdir -p "${artifact_root}/apk"

install -m 0644 \
  "${repo_root}/android/app/build/outputs/apk/release/app-release.apk" \
  "${artifact_root}/apk/freenet-android-node-release.apk"

if [[ "${PREBUILT_NODE:-false}" != "true" ]]; then
  mkdir -p "${artifact_root}/native/arm64-v8a"
  install -m 0644 \
    "${repo_root}/android/app/src/main/jniLibs/arm64-v8a/libfreenet_android.so" \
    "${artifact_root}/native/arm64-v8a/libfreenet_android.so"
fi

mapping="${repo_root}/android/app/build/outputs/mapping/release/mapping.txt"
if [[ -f "${mapping}" ]]; then
  mkdir -p "${artifact_root}/mapping"
  install -m 0644 "${mapping}" "${artifact_root}/mapping/release-mapping.txt"
fi

(
  cd "${artifact_root}"
  sha256sum apk/freenet-android-node-release.apk > SHA256SUMS.txt
  if [[ -f native/arm64-v8a/libfreenet_android.so ]]; then
    sha256sum native/arm64-v8a/libfreenet_android.so >> SHA256SUMS.txt
  fi
  if [[ -f mapping/release-mapping.txt ]]; then
    sha256sum mapping/release-mapping.txt >> SHA256SUMS.txt
  fi
)

echo "Exported build artifacts to ${artifact_root}"
