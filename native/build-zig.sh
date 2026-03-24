#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: native/build-zig.sh [--setup-only] [all|host|platform[,platform...]]

Build bundled FreeType JNI natives with a project-local Zig toolchain.

Supported platforms:
  macos-arm64, macos-x64, linux-arm64, linux-x64, windows-arm64, windows-x64

Environment overrides:
  ZIG_VERSION     Tagged Zig version to bootstrap locally (default: 0.13.0)
  JAVA_HOME       JDK to use for generating JNI headers
  FREETYPE_DIR    FreeType source checkout (default: native/freetype)
  FREETYPE_TAG    Exact FreeType git tag expected in FREETYPE_DIR
EOF
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_DIR="$ROOT_DIR/native"
BUILD_ROOT="$NATIVE_DIR/build/zig"
RESOURCES_DIR="${NATIVE_RESOURCE_DIR:-$ROOT_DIR/build/generated/freetype-resources/natives}"
TOOLCHAIN_ROOT="$NATIVE_DIR/toolchains/zig"
FREETYPE_VERSION="${FREETYPE_VERSION:-2.14.3}"
FREETYPE_TAG="${FREETYPE_TAG:-VER-${FREETYPE_VERSION//./-}}"
FREETYPE_DIR="${FREETYPE_DIR:-$NATIVE_DIR/freetype}"
ZIG_VERSION="${ZIG_VERSION:-0.13.0}"
SUPPORTED_PLATFORMS=(
    macos-arm64
    macos-x64
    linux-arm64
    linux-x64
    windows-arm64
    windows-x64
)

resolve_java_home() {
    if [[ -n "${JAVA_HOME:-}" ]]; then
        printf '%s\n' "$JAVA_HOME"
        return 0
    fi

    if [[ -x /usr/libexec/java_home ]]; then
        local detected
        detected="$(/usr/libexec/java_home 2>/dev/null || true)"
        if [[ -n "$detected" ]]; then
            printf '%s\n' "$detected"
            return 0
        fi
    fi

    if command -v javac >/dev/null 2>&1; then
        local javac_bin javac_dir
        javac_bin="$(command -v javac)"
        javac_dir="$(cd "$(dirname "$javac_bin")/.." && pwd)"
        printf '%s\n' "$javac_dir"
        return 0
    fi

    echo "Could not determine JAVA_HOME; set it explicitly." >&2
    exit 1
}

ensure_tools() {
    local missing=()
    command -v curl >/dev/null 2>&1 || missing+=("curl")
    command -v git >/dev/null 2>&1 || missing+=("git")
    command -v tar >/dev/null 2>&1 || missing+=("tar")
    command -v javac >/dev/null 2>&1 || missing+=("javac")

    if [[ "${#missing[@]}" -gt 0 ]]; then
        echo "Missing required tools: ${missing[*]}" >&2
        exit 1
    fi
}

ensure_freetype_source() {
    if [[ ! -f "$FREETYPE_DIR/include/freetype/freetype.h" ]]; then
        echo "Missing FreeType source under $FREETYPE_DIR." >&2
        echo "Run ./gradlew syncFreetypeSubmodule${FREETYPE_VERSION:+ -PfreetypeVersion=$FREETYPE_VERSION} to initialize it." >&2
        exit 1
    fi

    if git -C "$FREETYPE_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        local exact_tag
        exact_tag="$(git -C "$FREETYPE_DIR" describe --tags --exact-match HEAD 2>/dev/null || true)"
        if [[ "$exact_tag" != "$FREETYPE_TAG" ]]; then
            echo "FreeType checkout is at ${exact_tag:-$(git -C "$FREETYPE_DIR" rev-parse --short HEAD)}, expected $FREETYPE_TAG." >&2
            echo "Run ./gradlew syncFreetypeSubmodule${FREETYPE_VERSION:+ -PfreetypeVersion=$FREETYPE_VERSION} to repin it." >&2
            exit 1
        fi
    fi
}

is_supported_platform() {
    local candidate="$1"
    local platform
    for platform in "${SUPPORTED_PLATFORMS[@]}"; do
        if [[ "$platform" == "$candidate" ]]; then
            return 0
        fi
    done
    return 1
}

host_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Darwin)
            if [[ "$arch" == "arm64" || "$arch" == "aarch64" ]]; then
                printf '%s\n' "macos-arm64"
            else
                printf '%s\n' "macos-x64"
            fi
            ;;
        Linux)
            if [[ "$arch" == "arm64" || "$arch" == "aarch64" ]]; then
                printf '%s\n' "linux-arm64"
            else
                printf '%s\n' "linux-x64"
            fi
            ;;
        MINGW*|MSYS*|CYGWIN*)
            if [[ "$arch" == "arm64" || "$arch" == "aarch64" ]]; then
                printf '%s\n' "windows-arm64"
            else
                printf '%s\n' "windows-x64"
            fi
            ;;
        *)
            echo "Unsupported host platform: $os/$arch" >&2
            exit 1
            ;;
    esac
}

host_zig_bundle() {
    case "$(host_platform)" in
        macos-arm64)
            printf '%s\n' "macos-aarch64"
            ;;
        macos-x64)
            printf '%s\n' "macos-x86_64"
            ;;
        linux-arm64)
            printf '%s\n' "linux-aarch64"
            ;;
        linux-x64)
            printf '%s\n' "linux-x86_64"
            ;;
        windows-arm64)
            printf '%s\n' "windows-aarch64"
            ;;
        windows-x64)
            printf '%s\n' "windows-x86_64"
            ;;
        *)
            echo "Unsupported host platform for Zig bootstrap." >&2
            exit 1
            ;;
    esac
}

host_zig_archive_ext() {
    case "$(host_platform)" in
        windows-*)
            printf '%s\n' "zip"
            ;;
        *)
            printf '%s\n' "tar.xz"
            ;;
    esac
}

local_zig_dir() {
    local bundle
    bundle="$(host_zig_bundle)"
    printf '%s\n' "$TOOLCHAIN_ROOT/$ZIG_VERSION/zig-$bundle-$ZIG_VERSION"
}

local_zig_bin() {
    local dir
    dir="$(local_zig_dir)"
    case "$(host_platform)" in
        windows-*)
            printf '%s\n' "$dir/zig.exe"
            ;;
        *)
            printf '%s\n' "$dir/zig"
            ;;
    esac
}

ensure_local_zig() {
    local zig_dir zig_bin bundle archive_ext archive_name archive_path download_url
    zig_dir="$(local_zig_dir)"
    zig_bin="$(local_zig_bin)"

    if [[ -x "$zig_bin" ]]; then
        printf '%s\n' "$zig_bin"
        return 0
    fi

    bundle="$(host_zig_bundle)"
    archive_ext="$(host_zig_archive_ext)"
    archive_name="zig-$bundle-$ZIG_VERSION.$archive_ext"
    archive_path="$TOOLCHAIN_ROOT/$ZIG_VERSION/$archive_name"
    download_url="https://ziglang.org/download/$ZIG_VERSION/$archive_name"

    mkdir -p "$TOOLCHAIN_ROOT/$ZIG_VERSION"
    echo "Downloading Zig $ZIG_VERSION for $bundle..." >&2
    curl -L --fail --output "$archive_path" "$download_url"

    case "$archive_ext" in
        tar.xz)
            tar -xf "$archive_path" -C "$TOOLCHAIN_ROOT/$ZIG_VERSION"
            ;;
        zip)
            if ! command -v unzip >/dev/null 2>&1; then
                echo "Missing required tool: unzip" >&2
                exit 1
            fi
            unzip -q "$archive_path" -d "$TOOLCHAIN_ROOT/$ZIG_VERSION"
            ;;
        *)
            echo "Unsupported Zig archive format: $archive_ext" >&2
            exit 1
            ;;
    esac

    if [[ ! -x "$zig_bin" ]]; then
        echo "Expected Zig binary not found after extraction: $zig_bin" >&2
        exit 1
    fi

    printf '%s\n' "$zig_bin"
}

platform_jni_dir() {
    case "$1" in
        macos-*)
            printf '%s\n' "darwin"
            ;;
        linux-*)
            printf '%s\n' "linux"
            ;;
        windows-*)
            printf '%s\n' "win32"
            ;;
        *)
            echo "Unsupported platform '$1'" >&2
            exit 1
            ;;
    esac
}

platform_lib_name() {
    case "$1" in
        macos-*)
            printf '%s\n' "libfreetype-jni.dylib"
            ;;
        linux-*)
            printf '%s\n' "libfreetype-jni.so"
            ;;
        windows-*)
            printf '%s\n' "freetype-jni.dll"
            ;;
        *)
            echo "Unsupported platform '$1'" >&2
            exit 1
            ;;
    esac
}

platform_zig_target() {
    case "$1" in
        macos-arm64)
            printf '%s\n' "aarch64-macos"
            ;;
        macos-x64)
            printf '%s\n' "x86_64-macos"
            ;;
        linux-arm64)
            printf '%s\n' "aarch64-linux-gnu"
            ;;
        linux-x64)
            printf '%s\n' "x86_64-linux-gnu"
            ;;
        windows-arm64)
            printf '%s\n' "aarch64-windows-gnu"
            ;;
        windows-x64)
            printf '%s\n' "x86_64-windows-gnu"
            ;;
        *)
            echo "Unsupported platform '$1'" >&2
            exit 1
            ;;
    esac
}

prepare_java_bindings() {
    local javac_bin classes_dir headers_dir java_sources
    javac_bin="$JAVA_HOME_RESOLVED/bin/javac"
    classes_dir="$BUILD_ROOT/java/classes"
    headers_dir="$BUILD_ROOT/java/headers"
    java_sources=("$NATIVE_DIR"/freetype-jni/freetype-jni/*.java)

    rm -rf "$classes_dir" "$headers_dir"
    mkdir -p "$classes_dir" "$headers_dir"

    "$javac_bin" -source 8 -target 8 -d "$classes_dir" -h "$headers_dir" "${java_sources[@]}"

    printf '%s\n' "$headers_dir"
}

build_platform() {
    local platform="$1"
    local build_dir install_dir jni_dir zig_target lib_name target_resource_dir built_lib

    jni_dir="$(platform_jni_dir "$platform")"
    zig_target="$(platform_zig_target "$platform")"
    lib_name="$(platform_lib_name "$platform")"
    build_dir="$BUILD_ROOT/$platform"
    install_dir="$build_dir/install"
    target_resource_dir="$RESOURCES_DIR/$platform"

    mkdir -p "$build_dir" "$install_dir" "$target_resource_dir"

    "$ZIG_BIN" build \
        --build-file "$NATIVE_DIR/freetype-jni/build.zig" \
        --cache-dir "$build_dir/.zig-cache" \
        --global-cache-dir "$BUILD_ROOT/.zig-global-cache" \
        --prefix "$install_dir" \
        -Dtarget="$zig_target" \
        -Doptimize=ReleaseFast \
        -Dfreetype-src="$FREETYPE_DIR" \
        -Djava-include="$JAVA_HOME_RESOLVED/include" \
        -Djni-md-include="$NATIVE_DIR/jni-headers/$jni_dir" \
        -Dgenerated-headers="$GENERATED_HEADERS_DIR"

    built_lib="$(find "$install_dir" -type f -name "$lib_name" | head -n 1)"
    if [[ -z "$built_lib" ]]; then
        echo "Expected native library not found under $install_dir for $platform" >&2
        exit 1
    fi

    cp "$built_lib" "$target_resource_dir/$lib_name"
}

main() {
    local setup_only=0
    local target_arg
    local targets=()
    local platform

    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi

    if [[ "${1:-}" == "--setup-only" ]]; then
        setup_only=1
        shift
    fi

    target_arg="${1:-all}"

    case "$target_arg" in
        all|"")
            targets=("${SUPPORTED_PLATFORMS[@]}")
            ;;
        host)
            targets=("$(host_platform)")
            ;;
        *)
            IFS=',' read -r -a targets <<<"$target_arg"
            ;;
    esac

    if [[ "${#targets[@]}" -eq 0 ]]; then
        echo "No build targets selected." >&2
        exit 1
    fi

    for platform in "${targets[@]}"; do
        if ! is_supported_platform "$platform"; then
            echo "Unsupported platform '$platform'" >&2
            echo "Supported platforms: ${SUPPORTED_PLATFORMS[*]}" >&2
            exit 1
        fi
    done

    ensure_tools

    JAVA_HOME_RESOLVED="$(resolve_java_home)"
    export JAVA_HOME="$JAVA_HOME_RESOLVED"
    ZIG_BIN="$(ensure_local_zig)"

    if [[ "$setup_only" -eq 1 ]]; then
        echo "Local Zig ready at $ZIG_BIN" >&2
        exit 0
    fi

    ensure_freetype_source
    mkdir -p "$RESOURCES_DIR"
    mkdir -p "$BUILD_ROOT"
    GENERATED_HEADERS_DIR="$(prepare_java_bindings)"

    for platform in "${targets[@]}"; do
        build_platform "$platform"
    done

    echo "Built natives for: ${targets[*]}" >&2
}

main "$@"
