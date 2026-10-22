#!/usr/bin/env bash
# Build and sign the Cube Speaker Router APK.
# Usage: scripts/build_app.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="$ROOT/.work/jdk"
SDK="/tmp/opencode/sdk/android-9"
BT="/tmp/opencode/bt28/android-9"
OUT="$ROOT/.work/build"
KEYSTORE="$ROOT/.work/build/debug.keystore"

if [ ! -x "$JDK/bin/javac" ]; then
    echo "JDK not found at $JDK" >&2
    exit 1
fi
if [ ! -f "$SDK/android.jar" ]; then
    echo "android.jar not found at $SDK" >&2
    exit 1
fi
if [ ! -x "$BT/aapt" ] || [ ! -x "$BT/d8" ]; then
    echo "Android build-tools not found at $BT" >&2
    exit 1
fi

export PATH="$JDK/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] javac"
find "$ROOT/app/src" -name '*.java' -print0 \
    | xargs -0 "$JDK/bin/javac" -source 8 -target 8 -Xlint:-options \
        -bootclasspath "$SDK/android.jar" -d "$OUT/classes"

echo "[2/5] d8"
find "$OUT/classes" -name '*.class' -print0 \
    | xargs -0 "$BT/d8" --lib "$SDK/android.jar" --output "$OUT/dex"

echo "[3/5] aapt package"
"$BT/aapt" package -f \
    -M "$ROOT/app/AndroidManifest.xml" \
    -I "$SDK/android.jar" \
    -F "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && "$BT/aapt" add "$OUT/app-unsigned.apk" classes.dex >/dev/null)

echo "[4/5] zipalign"
"$BT/zipalign" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "[5/5] sign"
if [ ! -f "$KEYSTORE" ]; then
    "$JDK/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/CubeSpeakerRouter.apk" "$OUT/app-aligned.apk"

echo "built: $ROOT/CubeSpeakerRouter.apk"
