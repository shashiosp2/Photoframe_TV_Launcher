#!/bin/bash
set -e
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS

SDK="/opt/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
AAPT="/usr/bin/aapt"
ZIPALIGN="/usr/bin/zipalign"
APKSIGNER="$BT/apksigner"
DEX="$BT/d8"

echo "Using platform: $AJ"
rm -rf out && mkdir -p out/classes

echo "=== 1. Compiling Java sources ==="
javac --release 8 -nowarn -cp "$AJ" -d out/classes $(find src -name '*.java')

echo "=== 2. Stripping MethodParameters for Java 21 compatibility ==="
python3 strip_params.py out/classes

echo "=== 3. Dexing with d8 ==="
"$DEX" --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')

echo "=== 4. Packaging resources with aapt ==="
"$AAPT" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F out/unsigned.apk

echo "=== 5. Adding classes.dex to APK ==="
python3 -c "
import zipfile
with zipfile.ZipFile('out/unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as z:
    z.write('out/classes.dex', 'classes.dex')
"

echo "=== 6. Zipalign ==="
"$ZIPALIGN" -f 4 out/unsigned.apk out/aligned.apk

echo "=== 7. Signing APK ==="
[ -f debug.jks ] || keytool -genkeypair -keystore debug.jks -storepass android -keypass android \
   -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Home Debug" >/dev/null 2>&1
"$APKSIGNER" sign --ks debug.jks --ks-pass pass:android --out GlassHome.apk out/aligned.apk

echo "Built $(pwd)/GlassHome.apk ($(wc -c < GlassHome.apk) bytes)"
