#!/bin/bash
# Launch the Android Auto Desktop Head Unit (DHU) against a phone or emulator.
#
# Prerequisites (one-time, on the phone-side device):
#   1. Android Auto app -> tap "Version" 10x to unlock developer mode
#   2. Developer settings -> enable "Unknown sources" (to see AAYT)
#   3. Developer settings menu -> "Start head unit server"
#
# Then run this script.
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
DHU="$SDK/extras/google/auto/desktop-head-unit"
ADB="$SDK/platform-tools/adb"

[ -x "$DHU" ] || { echo "DHU not found: install with 'sdkmanager \"extras;google;auto\"'"; exit 1; }

# DHU 2.0 links against LLVM libc++/libunwind which Ubuntu doesn't ship by
# default; locally-extracted copies live next to the binary.
export LD_LIBRARY_PATH="$SDK/extras/google/auto/libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Target a specific device with AAYT_DHU_SERIAL=<serial>; otherwise prefer the
# USB-attached phone (-d) so a running emulator doesn't shadow it.
if [ -n "${AAYT_DHU_SERIAL:-}" ]; then
  "$ADB" -s "$AAYT_DHU_SERIAL" forward tcp:5277 tcp:5277
elif "$ADB" -d get-state >/dev/null 2>&1; then
  "$ADB" -d forward tcp:5277 tcp:5277
else
  "$ADB" forward tcp:5277 tcp:5277
fi
exec "$DHU" --adb "$@"
