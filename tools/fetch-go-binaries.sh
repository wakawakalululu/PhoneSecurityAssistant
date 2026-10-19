#!/usr/bin/env bash
#
# =============================================================================
# tools/fetch-go-binaries.sh
#
# Download the official helper binaries used by Sshd4a and place them into
# app/src/main/jniLibs/<abi>/ as "lib<name>.so", so that they are packaged
# into the APK as native libraries and extracted by the installer into the
# app's native library directory - the only place from which Android 10+
# allows an app to execute files.
#
# Usage:
#     tools/fetch-go-binaries.sh [--force] [abi ...]
#
#     --force   re-download targets that already exist
#     abi       one or more of: arm64-v8a, armeabi-v7a, x86_64, x86
#               (default: arm64-v8a)
#
# Binaries (all official, statically linked release artifacts):
#
#     libfrpc.so          frpc          frp v0.65.0       (fatedier/frp)
#     libtailscaled.so    tailscaled    Tailscale 1.90.6  (pkgs.tailscale.com)
#     libtailscale.so     tailscale     Tailscale 1.90.6  (pkgs.tailscale.com)
#     libnezha_agent.so   nezha-agent   v2.3.5            (nezhahq/agent)
#
# WARNING:
#     * The files are LARGE (the Tailscale daemon alone is tens of megabytes);
#       enabling several ABIs will make the APK very big.
#     * Only the arm64-v8a binaries are usable on real (physical) devices.
#       The other ABIs are intended for emulators/testing only.
#     * frp v0.65.0 is not published for 32-bit x86; for the "x86" ABI the
#       libfrpc.so target is skipped (there is no upstream build).
#     * app/src/main/jniLibs/ is listed in .gitignore: the binaries are NOT
#       committed to git.
#
# Requirements: bash, curl, tar, unzip
# =============================================================================
set -euo pipefail

readonly FRP_VERSION="0.65.0"
readonly TAILSCALE_VERSION="1.90.6"
readonly NEZHA_VERSION="2.3.5"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" > /dev/null && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." > /dev/null && pwd)"
JNI_LIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs"

FORCE=0
HAS_ABI=0
ABIS=()
WRITTEN=()
SKIPPED=()

# Temporary directory for the downloaded archives and their contents.
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/fetch-go-binaries.XXXXXX")"

cleanup() {
    rm -rf -- "${TMP_DIR}"
}
trap cleanup EXIT

usage() {
    cat <<'EOF'
Usage: tools/fetch-go-binaries.sh [--force] [abi ...]

  --force   re-download targets that already exist
  abi       one or more of: arm64-v8a, armeabi-v7a, x86_64, x86
            (default: arm64-v8a)

Downloads the official frpc (v0.65.0), tailscaled/tailscale (1.90.6) and
nezha-agent (v2.3.5) binaries and writes them to:

  app/src/main/jniLibs/<abi>/lib<name>.so

NOTE: Only the arm64-v8a binaries are usable on real devices. The files are
      large and are NOT committed to git (see .gitignore).
EOF
}

# Fail early when a required tool is missing.
require_tool() {
    if ! command -v "$1" > /dev/null 2>&1; then
        echo "error: '$1' is required but was not found in PATH" >&2
        exit 1
    fi
}

# abi_to_arch <abi> -> Go/release architecture name.
abi_to_arch() {
    case "$1" in
        arm64-v8a)   echo "arm64" ;;
        armeabi-v7a) echo "arm" ;;
        x86_64)      echo "amd64" ;;
        x86)         echo "386" ;;
        *)           return 1 ;;
    esac
}

# download <url> <dest>
download() {
    local url="$1"
    local dest="$2"
    echo "    GET ${url}"
    curl -fL --retry 3 --connect-timeout 30 --progress-bar -o "${dest}" "${url}"
    if [[ ! -s "${dest}" ]]; then
        echo "error: download failed (empty file): ${url}" >&2
        exit 1
    fi
}

# find_member <dir> <name> -> path of the extracted member
# (either at the top level, or inside a single sub-directory as used by the
# release archives).
find_member() {
    local dir="$1"
    local name="$2"
    if [[ -f "${dir}/${name}" ]]; then
        echo "${dir}/${name}"
        return 0
    fi
    local candidate
    for candidate in "${dir}"/*/"${name}"; do
        if [[ -f "${candidate}" ]]; then
            echo "${candidate}"
            return 0
        fi
    done
    return 1
}

# file_size <file> -> "1.2 MiB (1234567 bytes)"
file_size() {
    local bytes human
    bytes="$(wc -c < "$1" | tr -d '[:space:]')"
    human="$(awk -v b="${bytes}" 'BEGIN {
        split("B KiB MiB GiB", u, " ")
        i = 1
        while (b >= 1024 && i < 4) { b /= 1024; i++ }
        printf "%.1f %s", b, u[i]
    }')"
    echo "${human} (${bytes} bytes)"
}

# install_binary <source> <target>
install_binary() {
    local source="$1"
    local target="$2"
    mkdir -p -- "$(dirname -- "${target}")"
    cp -f -- "${source}" "${target}"
    chmod 755 -- "${target}"
    echo "    wrote ${target} [$(file_size "${target}")]"
    WRITTEN+=("${target}")
}

# fetch_frp <abi> <arch> <dest_dir>
fetch_frp() {
    local abi="$1"
    local arch="$2"
    local dest_dir="$3"
    local target="${dest_dir}/libfrpc.so"

    if [[ -f "${target}" && "${FORCE}" -eq 0 ]]; then
        echo "    skip (exists): ${target}"
        return 0
    fi

    if [[ "${arch}" == "386" ]]; then
        # frp v0.65.0 is not published for 32-bit x86 at all.
        echo "    WARNING: frp v${FRP_VERSION} has no 32-bit x86 build;"
        echo "             skipping libfrpc.so for ${abi} (use the arm64/amd64 build instead)"
        SKIPPED+=("${target} (no upstream build)")
        return 0
    fi

    local url="https://github.com/fatedier/frp/releases/download/v${FRP_VERSION}/frp_${FRP_VERSION}_linux_${arch}.tar.gz"
    local archive="${TMP_DIR}/frp-${abi}.tar.gz"
    local extract_dir="${TMP_DIR}/frp-${abi}"
    rm -rf -- "${extract_dir}"
    mkdir -p -- "${extract_dir}"

    echo "    frpc ${FRP_VERSION} for ${abi} (linux_${arch})"
    download "${url}" "${archive}"
    tar -xzf "${archive}" -C "${extract_dir}"

    local member
    if ! member="$(find_member "${extract_dir}" "frpc")"; then
        echo "error: archive does not contain 'frpc': ${url}" >&2
        exit 1
    fi
    install_binary "${member}" "${target}"
}

# fetch_tailscale <abi> <arch> <dest_dir>
fetch_tailscale() {
    local abi="$1"
    local arch="$2"
    local dest_dir="$3"
    local daemon_target="${dest_dir}/libtailscaled.so"
    local cli_target="${dest_dir}/libtailscale.so"

    local need_daemon=1
    local need_cli=1
    if [[ -f "${daemon_target}" && "${FORCE}" -eq 0 ]]; then
        echo "    skip (exists): ${daemon_target}"
        need_daemon=0
    fi
    if [[ -f "${cli_target}" && "${FORCE}" -eq 0 ]]; then
        echo "    skip (exists): ${cli_target}"
        need_cli=0
    fi
    if [[ "${need_daemon}" -eq 0 && "${need_cli}" -eq 0 ]]; then
        return 0
    fi

    local url="https://pkgs.tailscale.com/stable/tailscale_${TAILSCALE_VERSION}_${arch}.tgz"
    local archive="${TMP_DIR}/tailscale-${abi}.tgz"
    local extract_dir="${TMP_DIR}/tailscale-${abi}"
    rm -rf -- "${extract_dir}"
    mkdir -p -- "${extract_dir}"

    echo "    Tailscale ${TAILSCALE_VERSION} for ${abi} (linux_${arch})"
    download "${url}" "${archive}"
    tar -xzf "${archive}" -C "${extract_dir}"

    local member
    if [[ "${need_daemon}" -eq 1 ]]; then
        if ! member="$(find_member "${extract_dir}" "tailscaled")"; then
            echo "error: archive does not contain 'tailscaled': ${url}" >&2
            exit 1
        fi
        install_binary "${member}" "${daemon_target}"
    fi
    if [[ "${need_cli}" -eq 1 ]]; then
        if ! member="$(find_member "${extract_dir}" "tailscale")"; then
            echo "error: archive does not contain 'tailscale': ${url}" >&2
            exit 1
        fi
        install_binary "${member}" "${cli_target}"
    fi
}

# fetch_nezha <abi> <arch> <dest_dir>
fetch_nezha() {
    local abi="$1"
    local arch="$2"
    local dest_dir="$3"
    local target="${dest_dir}/libnezha_agent.so"

    if [[ -f "${target}" && "${FORCE}" -eq 0 ]]; then
        echo "    skip (exists): ${target}"
        return 0
    fi

    local url="https://github.com/nezhahq/agent/releases/download/v${NEZHA_VERSION}/nezha-agent_linux_${arch}.zip"
    local archive="${TMP_DIR}/nezha-${abi}.zip"
    local extract_dir="${TMP_DIR}/nezha-${abi}"
    rm -rf -- "${extract_dir}"
    mkdir -p -- "${extract_dir}"
    local tmp_target="${extract_dir}/nezha-agent.bin"

    echo "    nezha-agent ${NEZHA_VERSION} for ${abi} (linux_${arch})"
    download "${url}" "${archive}"

    local member="nezha-agent"
    if ! unzip -Z1 "${archive}" | grep -qx "${member}"; then
        # Fall back to the member inside a directory, if any.
        member="$(unzip -Z1 "${archive}" | grep -m 1 '/nezha-agent$' || true)"
    fi
    if [[ -z "${member}" ]]; then
        echo "error: archive does not contain 'nezha-agent': ${url}" >&2
        exit 1
    fi
    unzip -p "${archive}" "${member}" > "${tmp_target}"
    install_binary "${tmp_target}" "${target}"
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)
            FORCE=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            echo "error: unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
        *)
            ABIS+=("$1")
            HAS_ABI=1
            shift
            ;;
    esac
done

if [[ "${HAS_ABI}" -eq 0 ]]; then
    ABIS=("arm64-v8a")
fi

for abi in "${ABIS[@]}"; do
    if ! abi_to_arch "${abi}" > /dev/null; then
        echo "error: unsupported ABI: ${abi}" >&2
        echo "       supported ABIs: arm64-v8a, armeabi-v7a, x86_64, x86" >&2
        exit 2
    fi
done

require_tool curl
require_tool tar
require_tool unzip
require_tool awk
require_tool grep

cat <<'EOF'
=============================================================================
 Sshd4a helper binaries
=============================================================================
 WARNING: these files are LARGE (tens of megabytes each); enabling more than
          one ABI will noticeably increase the APK size.
 NOTE:    only the arm64-v8a binaries are usable on real (physical) devices.
          The other ABIs are intended for emulators/testing only.
=============================================================================
EOF

for abi in "${ABIS[@]}"; do
    arch="$(abi_to_arch "${abi}")"
    dest_dir="${JNI_LIBS_DIR}/${abi}"
    mkdir -p -- "${dest_dir}"

    echo
    echo "==> ${abi} (linux_${arch})"
    fetch_frp "${abi}" "${arch}" "${dest_dir}"
    fetch_tailscale "${abi}" "${arch}" "${dest_dir}"
    fetch_nezha "${abi}" "${arch}" "${dest_dir}"
done

echo
echo "============================================================================="
if [[ "${#WRITTEN[@]}" -eq 0 ]]; then
    echo "Nothing to do: all requested targets already exist (use --force to refresh)."
else
    echo "Written files:"
    for file in "${WRITTEN[@]}"; do
        echo "  $(file_size "${file}")  ${file}"
    done
fi
if [[ "${#SKIPPED[@]}" -gt 0 ]]; then
    echo "Skipped:"
    for entry in "${SKIPPED[@]}"; do
        echo "  ${entry}"
    done
fi
echo "============================================================================="
