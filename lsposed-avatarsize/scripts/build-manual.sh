#!/bin/bash
#
# Build the LSPosed module WITHOUT Gradle, using only:
#   - a JDK (javac, keytool)
#   - Android SDK: build-tools (d8.jar, apksigner.jar) + a platform android.jar
#
# This is the exact toolchain used to produce the first working build on an
# arm64 device under a proot Ubuntu rootfs.
#
# On arm64 hosts the native aapt2/zipalign shipped in build-tools are x86-64 and
# will NOT run. Install the distro package instead:  apt install aapt2
# (Debian/Ubuntu). d8 and apksigner are pure Java, so `java -jar lib/d8.jar`
# and `java -jar lib/apksigner.jar` work everywhere.
#
set -e

# ---- paths (override via env) ----
: "${ANDROID_HOME:?set ANDROID_HOME to your SDK root}"
: "${JAVA_HOME:?set JAVA_HOME to your JDK}"

BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORMS="$ANDROID_HOME/platforms"
# pick any installed platform
PLATFORM_JAR="$(ls "$PLATFORMS"/*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -n "$PLATFORM_JAR" ] || { echo "no android.jar found under $PLATFORMS"; exit 1; }

# aapt2: prefer the distro one (arm64-friendly), fall back to build-tools
AAPT2="$(command -v aapt2 || echo "$BT/aapt2")"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"
JAR="$ROOT/libs/XposedBridgeApi-82.jar"

echo "ANDROID_HOME = $ANDROID_HOME"
echo "platform jar = $PLATFORM_JAR"
echo "aapt2        = $AAPT2"

# ---- Xposed API jar (compile-only) ----
mkdir -p "$ROOT/libs"
if [ ! -f "$JAR" ]; then
  echo "fetching XposedBridgeApi-82.jar ..."
  curl -fsSL -o "$JAR" \
    https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
fi

# ---- 1) javac ----
rm -rf "$OUT"; mkdir -p "$OUT/classes"
javac -source 8 -target 8 -bootclasspath "$PLATFORM_JAR" \
  -cp "$JAR" -d "$OUT/classes" \
  $(find "$APP/java" -name '*.java')

# ---- 2) d8 ----
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 26 --lib "$PLATFORM_JAR" --output "$OUT" \
  $(find "$OUT/classes" -name '*.class')

# ---- 3) aapt2 link ----
APK="$OUT/avsize-unsigned.apk"
"$AAPT2" link -o "$APK" \
  --manifest "$APP/AndroidManifest.xml" \
  -I "$PLATFORM_JAR" \
  --min-sdk-version 26 --target-sdk-version 34

# also compile res/ if present (arrays.xml / strings.xml)
if [ -d "$APP/res" ]; then
  "$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip" || true
  [ -f "$OUT/res.zip" ] && "$AAPT2" link -o "$APK" \
    --manifest "$APP/AndroidManifest.xml" \
    -I "$PLATFORM_JAR" \
    --min-sdk-version 26 --target-sdk-version 34 \
    -R "$OUT/res.zip" || true
fi

# ---- 4) add dex + assets ----
mkdir -p "$OUT/assets"
cp "$APP/assets/xposed_init" "$OUT/assets/"
(cd "$OUT" && zip -q -X "$APK" classes.dex assets/xposed_init)

# ---- 5) sign ----
KS="$OUT/ks.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias k -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=AvatarSize" >/dev/null
fi
SIGNED="$OUT/avsize-signed.apk"
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$SIGNED" "$APK"

echo
echo "OK -> $SIGNED"
