#!/usr/bin/env bash
# Experimental: fetch the official Freenet binaries and place them where the
# process backend can execute them. Android only runs files from the extracted
# native library directory, so the binaries are packaged as libfreenet.so.
set -euo pipefail

version="${FREENET_PREBUILT_VERSION:-0.2.155}"
version="${version#v}"
base="https://github.com/freenet/freenet-core/releases/download/v${version}"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${repo_root}/android/app/src/main/jniLibs"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

download() {
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$2" "$1"
  else
    wget -q -O "$2" "$1"
  fi
}

download "${base}/SHA256SUMS.txt" "${tmp}/SHA256SUMS.txt"

install_node() {
  local abi="$1"
  local triple="$2"
  local archive="freenet-${triple}.tar.gz"

  download "${base}/${archive}" "${tmp}/${archive}"
  (cd "${tmp}" && grep "  ${archive}\$" SHA256SUMS.txt | sha256sum -c -)
  tar -xzf "${tmp}/${archive}" -C "${tmp}" freenet

  mkdir -p "${destination}/${abi}"
  install -m 0755 "${tmp}/freenet" "${destination}/${abi}/libfreenet.so"
  echo "${abi}: $(sha256sum "${destination}/${abi}/libfreenet.so" | cut -d' ' -f1)"
}

# Release builds ask for one ABI; debug may keep both.
for abi in ${FREENET_ABIS:-arm64-v8a}; do
  case "${abi}" in
    arm64-v8a) install_node arm64-v8a aarch64-unknown-linux-musl ;;
    x86_64) install_node x86_64 x86_64-unknown-linux-musl ;;
    *) echo "Unsupported ABI: ${abi}" >&2; exit 1 ;;
  esac
done

echo "Freenet ${version} prebuilt binaries are ready in ${destination}"
