#!/usr/bin/env bash
# Updated: AdrianBega/DualBytes
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [/path/to/video-folder-or-mp4]" >&2
  echo "Default sample: app/src/debug/assets/front_camera_sample_1.mp4" >&2
  echo "When a single MP4 is provided, it is installed for all camera slots." >&2
  exit 2
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INPUT_PATH="${1:-${ROOT_DIR}/app/src/debug/assets/front_camera_sample_1.mp4}"
PACKAGE="com.drivehub.dualbytes.kamera"
TMP_DIR="/data/local/tmp/mg4-camera-test-videos"
APP_DIR="files/mg4-camera-test"

if ! adb shell run-as "$PACKAGE" true >/dev/null 2>&1; then
  echo "Package $PACKAGE is not installed as a debuggable build; refusing to copy test videos." >&2
  exit 1
fi

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mg4-camera-test-videos.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

if [[ -f "$INPUT_PATH" ]]; then
  for name in front right left rear; do
    cp "$INPUT_PATH" "$WORK_DIR/$name.mp4"
  done
elif [[ -d "$INPUT_PATH" ]]; then
  for name in front right left rear; do
    if [[ -f "$INPUT_PATH/$name.mp4" ]]; then
      cp "$INPUT_PATH/$name.mp4" "$WORK_DIR/$name.mp4"
    elif [[ -f "$INPUT_PATH/front_camera_sample_1.mp4" ]]; then
      cp "$INPUT_PATH/front_camera_sample_1.mp4" "$WORK_DIR/$name.mp4"
    else
      echo "Missing $INPUT_PATH/$name.mp4 and $INPUT_PATH/front_camera_sample_1.mp4" >&2
      exit 2
    fi
  done
else
  echo "Missing input path: $INPUT_PATH" >&2
  exit 2
fi

adb shell rm -rf "$TMP_DIR"
adb shell mkdir -p "$TMP_DIR"
adb shell run-as "$PACKAGE" mkdir -p "$APP_DIR"
for name in front right left rear; do
  adb push "$WORK_DIR/$name.mp4" "$TMP_DIR/$name.mp4"
  adb shell run-as "$PACKAGE" cp "$TMP_DIR/$name.mp4" "$APP_DIR/$name.mp4"
done
adb shell run-as "$PACKAGE" ls -lh "$APP_DIR"
