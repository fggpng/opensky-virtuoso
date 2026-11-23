#!/usr/bin/env bash
# Compila la app de iOS sin firmar y la empaqueta como OpenSky.ipa (para Sideloadly / SideStore,
# que la firman con el Apple ID del usuario). Uso: ios/scripts/build-ipa.sh <número de versión>
set -euo pipefail
N=${1:-1}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"

echo "== Núcleo Kotlin: pruebas nativas y framework para iOS"
./gradlew --no-daemon --stacktrace -Popensky.skipAndroid=true \
  :core:macosArm64Test :core:assembleOpenSkyCoreReleaseXCFramework

echo "== Proyecto de Xcode (XcodeGen)"
command -v xcodegen >/dev/null || brew install xcodegen
(cd ios && xcodegen generate)

echo "== Compilación (sin firmar) de la versión 1.0.$N"
xcodebuild \
  -project ios/OpenSky.xcodeproj \
  -scheme OpenSky \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build/ios \
  MARKETING_VERSION="1.0.$N" \
  CURRENT_PROJECT_VERSION="$N" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  build | tee build/xcodebuild.log | grep -E "error:|warning: .*deprecated|BUILD (SUCCEEDED|FAILED)|\*\* " || true
grep -q "BUILD SUCCEEDED" build/xcodebuild.log || { echo "La compilación de Xcode falló"; grep -E "error:" build/xcodebuild.log | head -80; exit 1; }

echo "== Empaquetado"
APP=build/ios/Build/Products/Release-iphoneos/OpenSky.app
rm -rf build/ipa && mkdir -p build/ipa/Payload
cp -R "$APP" build/ipa/Payload/
(cd build/ipa && zip -qry ../OpenSky.ipa Payload)
ls -la build/OpenSky.ipa
