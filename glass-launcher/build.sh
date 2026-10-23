#!/bin/bash
# Build Glass Home (debug-signed) without Gradle.
# Needs: a JDK (javac, keytool) and Android build tools. Uses, in order:
#   $ANDROID_HOME/build-tools/<newest> (aapt, d8, zipalign, apksigner) + platforms/android-34+/android.jar
#   or Debian/Ubuntu packages: apt install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
set -e
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
AJ=$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$BT" ] && [ -n "$AJ" ]; then
  AAPT="$BT/aapt"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"; DEX="$BT/d8"
else
  AAPT=aapt; ZIPALIGN=zipalign; APKSIGNER=apksigner; DEX=dalvik-exchange
  AJ=$(ls /usr/lib/android-sdk/platforms/*/android.jar | sort -V | tail -1)
fi
echo "android.jar: $AJ"
rm -rf out && mkdir -p out/classes
javac --release 8 -nowarn -cp "$AJ" -d out/classes $(find src -name '*.java') 2>&1 | grep -v -iE 'warning|bootstrap|^Note' || true
if [ "$(basename "$DEX")" = d8 ]; then
  "$DEX" --min-api 26 --lib "$AJ" --output out $(find out/classes -name '*.class')
else
  "$DEX" --dex --min-sdk-version=26 --output=out/classes.dex out/classes
fi
"$AAPT" package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F out/unsigned.apk
(cd out && "$AAPT" add unsigned.apk classes.dex >/dev/null)
"$ZIPALIGN" -f 4 out/unsigned.apk out/aligned.apk
[ -f debug.jks ] || keytool -genkeypair -keystore debug.jks -storepass android -keypass android \
   -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Home Debug" >/dev/null 2>&1
"$APKSIGNER" sign --ks debug.jks --ks-pass pass:android --out GlassHome.apk out/aligned.apk
echo "Built $(pwd)/GlassHome.apk ($(wc -c < GlassHome.apk) bytes)"
