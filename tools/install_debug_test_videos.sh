#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/video-folder" >&2
  echo "Expected files: front.mp4 right.mp4 left.mp4 rear.mp4" >&2
  exit 2
fi

SRC_DIR="$1"
PACKAGE="com.drivehub.kamera"
TMP_DIR="/data/local/tmp/mg4-camera-test-videos"
APP_DIR="files/mg4-camera-test"

for name in front right left rear; do
  if [[ ! -f "$SRC_DIR/$name.mp4" ]]; then
    echo "Missing $SRC_DIR/$name.mp4" >&2
    exit 2
  fi
done

adb push "$SRC_DIR" "$TMP_DIR"
adb shell run-as "$PACKAGE" mkdir -p "$APP_DIR"
for name in front right left rear; do
  adb shell run-as "$PACKAGE" cp "$TMP_DIR/$name.mp4" "$APP_DIR/$name.mp4"
done
adb shell run-as "$PACKAGE" ls -lh "$APP_DIR"
