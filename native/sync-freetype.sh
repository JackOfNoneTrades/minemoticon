#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: native/sync-freetype.sh [--verify|--sync]

Initialize the FreeType submodule and optionally pin it to the configured tag.

Environment overrides:
  FREETYPE_VERSION  FreeType version number (default: 2.14.3)
  FREETYPE_TAG      Exact git tag to check out (default: derived from FREETYPE_VERSION)
EOF
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SUBMODULE_REL="native/freetype"
SUBMODULE_DIR="${FREETYPE_DIR:-$ROOT_DIR/$SUBMODULE_REL}"
FREETYPE_VERSION="${FREETYPE_VERSION:-2.14.3}"
FREETYPE_TAG="${FREETYPE_TAG:-VER-${FREETYPE_VERSION//./-}}"

ensure_tools() {
    if ! command -v git >/dev/null 2>&1; then
        echo "Missing required tool: git" >&2
        exit 1
    fi
}

ensure_registered() {
    if [[ ! -f "$ROOT_DIR/.gitmodules" ]]; then
        echo "Missing .gitmodules; expected FreeType submodule registration." >&2
        exit 1
    fi

    if ! git config -f "$ROOT_DIR/.gitmodules" --get-regexp '^submodule\..*\.path$' | grep -q " $SUBMODULE_REL\$"; then
        echo "FreeType submodule is not registered at $SUBMODULE_REL." >&2
        exit 1
    fi
}

init_submodule() {
    if [[ -f "$SUBMODULE_DIR/include/freetype/freetype.h" ]]; then
        return 0
    fi

    git -C "$ROOT_DIR" submodule update --init "$SUBMODULE_REL"
}

require_clean_submodule() {
    if ! git -C "$SUBMODULE_DIR" diff --quiet || ! git -C "$SUBMODULE_DIR" diff --cached --quiet; then
        echo "FreeType submodule has local changes; commit or discard them before switching tags." >&2
        exit 1
    fi
}

current_exact_tag() {
    git -C "$SUBMODULE_DIR" describe --tags --exact-match HEAD 2>/dev/null || true
}

current_ref() {
    local exact_tag
    exact_tag="$(current_exact_tag)"
    if [[ -n "$exact_tag" ]]; then
        printf '%s\n' "$exact_tag"
    else
        git -C "$SUBMODULE_DIR" rev-parse --short HEAD
    fi
}

verify_checkout() {
    if [[ ! -f "$SUBMODULE_DIR/include/freetype/freetype.h" ]]; then
        echo "FreeType source is missing under $SUBMODULE_DIR." >&2
        echo "Run ./gradlew syncFreetypeSubmodule to populate the submodule." >&2
        exit 1
    fi

    local actual_tag
    actual_tag="$(current_exact_tag)"
    if [[ "$actual_tag" != "$FREETYPE_TAG" ]]; then
        echo "FreeType submodule is at $(current_ref), expected $FREETYPE_TAG." >&2
        echo "Run ./gradlew syncFreetypeSubmodule${FREETYPE_VERSION:+ -PfreetypeVersion=$FREETYPE_VERSION} and commit the updated submodule pointer." >&2
        exit 1
    fi
}

sync_checkout() {
    init_submodule
    require_clean_submodule
    git -C "$SUBMODULE_DIR" fetch --tags --force origin
    git -C "$SUBMODULE_DIR" checkout --detach "refs/tags/$FREETYPE_TAG"
    verify_checkout
    echo "FreeType submodule synced to $(current_ref)" >&2
}

main() {
    local mode="${1:---verify}"

    case "$mode" in
        -h|--help)
            usage
            exit 0
            ;;
        --verify|--sync)
            ;;
        *)
            usage >&2
            exit 1
            ;;
    esac

    ensure_tools
    ensure_registered

    case "$mode" in
        --verify)
            init_submodule
            verify_checkout
            ;;
        --sync)
            sync_checkout
            ;;
    esac
}

main "$@"
