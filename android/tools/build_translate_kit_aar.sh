#!/bin/sh
set -eu

REPOSITORY_URL="https://github.com/marcosholgado/translate-kit.git"
REPOSITORY_COMMIT="2dcdcb1559ed405d65ae1ff1e786d3a5ebb933c4"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ANDROID_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
WORK_DIR=${1:-"$ANDROID_DIR/.runtime/translate-kit"}

if [ ! -d "$WORK_DIR/.git" ]; then
    git clone "$REPOSITORY_URL" "$WORK_DIR"
fi

git -C "$WORK_DIR" checkout "$REPOSITORY_COMMIT"
git -C "$WORK_DIR" submodule update --init --recursive
"$WORK_DIR/scripts/apply-engine-patches.sh"

cd "$WORK_DIR/android"
./gradlew :translate-kit:assembleRelease -PtestAbi=arm64-v8a
cp "$WORK_DIR/android/translate-kit/build/outputs/aar/translate-kit-release.aar" \
    "$ANDROID_DIR/app/libs/translate-kit-android-0.1.0-arm64.aar"
