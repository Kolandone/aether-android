#!/bin/bash
# Cross-compile Aether for all Android architectures
# Requires: Rust, Android NDK (r25+), aether source alongside this repo

set -e

ANDROID_API=26
NDK_HOME="${NDK_HOME:-$HOME/android-ndk-r25c}"

if [ ! -d "$NDK_HOME" ]; then
    echo "Error: Android NDK not found at $NDK_HOME"
    echo "Download: https://developer.android.com/ndk/downloads"
    exit 1
fi

TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
APP_DIR="$(dirname "$0")/../app/src/main/assets/libs"

declare -A TARGETS=(
    ["arm64-v8a"]="aarch64-linux-android"
    ["armeabi-v7a"]="armv7-linux-androideabi"
    ["x86_64"]="x86_64-linux-android"
    ["x86"]="i686-linux-android"
)

declare -A TARGETS_ALT=(
    ["arm64-v8a"]="aarch64"
    ["armeabi-v7a"]="arm"
    ["x86_64"]="x86_64"
    ["x86"]="i686"
)

for ABI in "${!TARGETS[@]}"; do
    TARGET="${TARGETS[$ABI]}"
    CC_TARGET="${TARGETS_ALT[$ABI]}"
    OUT_DIR="$APP_DIR/$ABI"
    mkdir -p "$OUT_DIR"

    echo "Building for $ABI ($TARGET)..."

    export CC="${TOOLCHAIN}/bin/${CC_TARGET}${ANDROID_API}-clang"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export CARGO_TARGET="${TARGET}"

    rustup target add "$TARGET" 2>/dev/null || true

    cd "${1:-.}"  # Aether source directory
    cargo build --release --target "$TARGET" \
        --config "target.${TARGET}.linker=\"${CC}\""

    cp "target/$TARGET/release/aether" "$OUT_DIR/aether"
    chmod +x "$OUT_DIR/aether"

    echo "✓ $ABI done: $OUT_DIR/aether"
done

echo ""
echo "All architectures built:"
ls -la "$APP_DIR"/*/
