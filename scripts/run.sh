#!/usr/bin/env bash
#
# Build + install AAYT and launch it.
#   ./scripts/run.sh            # debug build (default)
#   ./scripts/run.sh --release  # release/prod build (R8-minified, release-signed)
#
# Handles the debug<->release signing mismatch by uninstalling and reinstalling
# when the signatures differ.
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root

MODE="debug"
for a in "$@"; do
  case "$a" in
    --release|-r) MODE="release" ;;
    --debug|-d)   MODE="debug" ;;
    -h|--help)    echo "Usage: $0 [--release]"; exit 0 ;;
    *) echo "Unknown argument: $a" >&2; echo "Usage: $0 [--release]" >&2; exit 1 ;;
  esac
done

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG="com.nrknithin.aayt"
ACT="$PKG/.MainActivity"

if [ "$MODE" = "release" ]; then
  TASK="assembleRelease"; VARIANT="release"
else
  TASK="assembleDebug";   VARIANT="debug"
fi

echo "==> Building [$MODE] ..."
./gradlew "$TASK"

# Prefer the renamed APK (AAYT-<ver>[_debug].apk), fall back to the raw output.
APK="$(ls -t app/build/renamedApks/$VARIANT/*.apk 2>/dev/null | head -1 || true)"
[ -z "$APK" ] && APK="$(ls -t app/build/outputs/apk/$VARIANT/*.apk 2>/dev/null | head -1 || true)"
[ -z "$APK" ] && { echo "!! No $VARIANT APK found" >&2; exit 1; }
echo "==> APK: $APK"

echo "==> Waiting for a device ..."
"$ADB" wait-for-device
DEV="$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')"
[ -z "$DEV" ] && { echo "!! No connected device" >&2; exit 1; }
echo "==> Device: $DEV"

echo "==> Installing ..."
ERR="$(mktemp)"
if ! "$ADB" -s "$DEV" install -r "$APK" 2>"$ERR"; then
  if grep -qiE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|VERSION_DOWNGRADE" "$ERR"; then
    echo "==> Signature/version mismatch — uninstalling then reinstalling ..."
    "$ADB" -s "$DEV" uninstall "$PKG" >/dev/null 2>&1 || true
    "$ADB" -s "$DEV" install "$APK"
  else
    cat "$ERR" >&2; rm -f "$ERR"; exit 1
  fi
fi
rm -f "$ERR"

echo "==> Launching ..."
"$ADB" -s "$DEV" shell am force-stop "$PKG" >/dev/null 2>&1 || true
"$ADB" -s "$DEV" shell am start -n "$ACT" >/dev/null

echo "==> Done: [$MODE] installed and launched on $DEV"
