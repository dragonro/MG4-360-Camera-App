#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_GRADLE="${ROOT_DIR}/app/build.gradle"
TOOLS_DIR="${ROOT_DIR}/tools"
RELEASE_DIR="${ROOT_DIR}/app/build/outputs/apk/release"
ANDROID_SDK_DIR="${HOME}/Library/Android/sdk"
if [[ ! -d "${ANDROID_SDK_DIR}" ]]; then
  ANDROID_SDK_DIR="${HOME}/Android/Sdk"
fi
EMULATOR_BIN="${ANDROID_SDK_DIR}/emulator/emulator"
ADB_BIN="${ANDROID_SDK_DIR}/platform-tools/adb"

ensure_emulator_running() {
  if "${ADB_BIN}" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
    return 0
  fi

  if [[ ! -x "${EMULATOR_BIN}" ]]; then
    echo "Could not find emulator binary at ${EMULATOR_BIN}" >&2
    exit 1
  fi

  local avd
  avd="$("${EMULATOR_BIN}" -list-avds | head -n 1)"
  if [[ -z "${avd}" ]]; then
    echo "No Android Virtual Device found" >&2
    exit 1
  fi

  nohup "${EMULATOR_BIN}" -avd "${avd}" >/tmp/mg4-emulator.log 2>&1 &

  local i
  for i in $(seq 1 120); do
    if "${ADB_BIN}" wait-for-device >/dev/null 2>&1 && "${ADB_BIN}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
      return 0
    fi
    sleep 2
  done

  echo "Emulator did not become ready in time" >&2
  exit 1
}

current_version() {
  sed -n 's/^[[:space:]]*versionName "\([^"]*\)".*/\1/p' "${APP_GRADLE}" | head -n 1
}

current_version_code() {
  sed -n 's/^[[:space:]]*versionCode \([0-9][0-9]*\).*/\1/p' "${APP_GRADLE}" | head -n 1
}

build_debug() {
  cd "${ROOT_DIR}"
  ./gradlew assembleDebug
}

install_debug() {
  cd "${ROOT_DIR}"
  ensure_emulator_running
  ./gradlew app:installDebug
}

run_debug() {
  ensure_emulator_running
  "${ADB_BIN}" shell am start -n com.drivehub.dualbytes.kamera/com.drivehub.kamera.MainActivity
}

build_release() {
  cd "${ROOT_DIR}"
  ./gradlew assembleRelease
}

promote_release() {
  cd "${ROOT_DIR}"

  local version
  version="$(current_version)"
  if [[ -z "${version}" ]]; then
    echo "Could not read versionName from app/build.gradle" >&2
    exit 1
  fi

  local unsigned_apk="${RELEASE_DIR}/app-release-unsigned.apk"
  local signed_apk="${RELEASE_DIR}/MG4-360-Camera-App-v${version}-release.apk"
  local sha_file="${signed_apk}.sha256"
  local apksigner_jar
  apksigner_jar="$(find "${HOME}/Library/Android/sdk/build-tools" -path '*/lib/apksigner.jar' | sort -V | tail -n 1)"
  if [[ -z "${apksigner_jar}" ]]; then
    apksigner_jar="$(find "${HOME}/Android/Sdk/build-tools" -path '*/lib/apksigner.jar' | sort -V | tail -n 1)"
  fi
  if [[ -z "${apksigner_jar}" ]]; then
    echo "Could not find apksigner.jar" >&2
    exit 1
  fi

  if [[ ! -f "${unsigned_apk}" ]]; then
    echo "Missing unsigned release APK: ${unsigned_apk}" >&2
    exit 1
  fi

  java -jar "${apksigner_jar}" sign \
    --key "${TOOLS_DIR}/platform.pk8" \
    --cert "${TOOLS_DIR}/platform.x509.pem" \
    --out "${signed_apk}" \
    "${unsigned_apk}"

  shasum -a 256 "${signed_apk}" | awk '{print $1}' > "${sha_file}"

  local target_commit
  target_commit="$(git rev-parse HEAD)"

  gh release create "v${version}" \
    "${signed_apk}" \
    "${sha_file}" \
    --title "v${version}" \
    --notes "Signed release for MG4-360-Camera-App ${version}.\n\nAssets:\n- MG4-360-Camera-App-v${version}-release.apk\n- MG4-360-Camera-App-v${version}-release.apk.sha256" \
    --target "${target_commit}"
}

increment_patch_version() {
  cd "${ROOT_DIR}"

  local version version_code major minor patch build new_version new_version_code
  version="$(current_version)"
  version_code="$(current_version_code)"
  if [[ -z "${version}" || -z "${version_code}" ]]; then
    echo "Could not read versionName/versionCode from app/build.gradle" >&2
    exit 1
  fi

  IFS='.' read -r major minor patch build <<< "${version}"
  if [[ -z "${major}" || -z "${minor}" || -z "${patch}" || -z "${build}" ]]; then
    echo "Version format must be 0.0.0.x" >&2
    exit 1
  fi

  if [[ "${major}" != "0" || "${minor}" != "0" || "${patch}" != "0" ]]; then
    echo "Increment option expects version format 0.0.0.x, found ${version}" >&2
    exit 1
  fi

  new_version="0.0.0.$((build + 1))"
  new_version_code="$((version_code + 1))"

  python3 - <<'PY' "${APP_GRADLE}" "${new_version_code}" "${new_version}"
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
new_version_code = sys.argv[2]
new_version = sys.argv[3]
text = path.read_text()
text_new = re.sub(r'versionCode \d+', f'versionCode {new_version_code}', text, count=1)
text_new = re.sub(r'versionName "([^"]+)"', f'versionName "{new_version}"', text_new, count=1)
if text_new == text:
    raise SystemExit("No version fields updated")
path.write_text(text_new)
PY

  echo "Updated version to ${new_version} (versionCode ${new_version_code})"
}

show_menu() {
  cat <<'EOF'
1. Build debug app
2. Install debug in the emulator
3. Run debug in the emulator
4. Build release
5. Promote release on GitHub
9. Increment 0.0.0.x
EOF
}

main() {
  while true; do
    show_menu
    read -r -p "Select an option: " choice
    case "${choice}" in
      1) build_debug ;;
      2) install_debug ;;
      3) run_debug ;;
      4) build_release ;;
      5) promote_release ;;
      9) increment_patch_version ;;
      *) echo "Invalid option: ${choice}" ;;
    esac
    echo
  done
}

main
