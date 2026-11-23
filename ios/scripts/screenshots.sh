#!/usr/bin/env bash
# Capturas de la app en el simulador del iPhone 16 Pro Max (modo demo con la montura simulada).
# Se imprimen en el registro como JPEG en base64 (entre marcadores) para revisarlas sin descargar
# artefactos. Requiere OpenSkyCore.xcframework y el proyecto de XcodeGen ya generados.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
BUNDLE=com.opensky.virtuoso

xcodebuild -project ios/OpenSky.xcodeproj -scheme OpenSky -configuration Debug \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath build/sim \
  ARCHS=arm64 EXCLUDED_ARCHS=x86_64 ONLY_ACTIVE_ARCH=NO \
  CODE_SIGNING_ALLOWED=NO build > build/xcodebuild-sim.log 2>&1
grep -q "BUILD SUCCEEDED" build/xcodebuild-sim.log || {
  echo "Falló la compilación para el simulador"
  grep -E "error:|Undefined symbol|ld: |referenced from|warning: ignoring file" build/xcodebuild-sim.log | head -60
  exit 1
}
APP=build/sim/Build/Products/Debug-iphonesimulator/OpenSky.app

RUNTIME=$(xcrun simctl list runtimes -j | python3 -c "
import sys, json
rs = [r for r in json.load(sys.stdin)['runtimes'] if r.get('platform') == 'iOS' and r.get('isAvailable')]
rs.sort(key=lambda r: [int(x) for x in r['version'].split('.')])
print(rs[-1]['identifier'] if rs else '')")
TYPE=$(xcrun simctl list devicetypes -j | python3 -c "
import sys, json
ts = json.load(sys.stdin)['devicetypes']
names = [t for t in ts if t['name'] == 'iPhone 16 Pro Max'] or [t for t in ts if 'Pro Max' in t['name']]
print(names[0]['identifier'] if names else ts[-1]['identifier'])")
echo "Simulador: $TYPE con $RUNTIME"
UDID=$(xcrun simctl create OpenSkyCapturas "$TYPE" "$RUNTIME")
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl status_bar "$UDID" override --time "21:30" --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularMode notSupported || true
xcrun simctl location "$UDID" set 28.6353,-106.0889 || true
xcrun simctl install "$UDID" "$APP"
xcrun simctl privacy "$UDID" grant location "$BUNDLE" || true
xcrun simctl privacy "$UDID" grant motion "$BUNDLE" || true

mkdir -p build/screens
shot() {
  local name=$1; shift
  xcrun simctl terminate "$UDID" "$BUNDLE" > /dev/null 2>&1 || true
  xcrun simctl launch "$UDID" "$BUNDLE" "$@" > /dev/null
  sleep 10
  xcrun simctl io "$UDID" screenshot "build/screens/$name.png" > /dev/null 2>&1
  sips -s format jpeg -s formatOptions 55 -Z 1000 "build/screens/$name.png" --out "build/screens/$name.jpg" > /dev/null
  echo "===CAPTURA $name==="
  base64 -i "build/screens/$name.jpg" | fold -w 900
  echo "===FIN $name==="
}
shot inicio --demo --tab=home
shot alinear --demo --tab=align
shot objetivos --demo --tab=targets
shot mando --demo --tab=control
shot mas --demo --tab=more
shot inicio-noche --demo --night --tab=home
shot mando-noche --demo --night --tab=control

echo "===REGISTRO==="
xcrun simctl spawn "$UDID" log show --last 5m --style compact --predicate 'process == "OpenSky" AND (messageType == error OR messageType == fault OR eventMessage CONTAINS[c] "kotlin" OR eventMessage CONTAINS[c] "exception")' 2>/dev/null | tail -60
ls ~/Library/Logs/DiagnosticReports 2>/dev/null | grep -i opensky && echo "¡HAY INFORMES DE CIERRE INESPERADO!"
echo "===FIN REGISTRO==="
xcrun simctl shutdown "$UDID" || true
exit 0
