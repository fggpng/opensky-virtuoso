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

# Un simulador ya creado en la imagen del Mac: el iPhone «Pro Max» más reciente del iOS más nuevo.
read -r UDID NAME < <(xcrun simctl list devices available -j | python3 -c "
import sys, json, re
devs = json.load(sys.stdin)['devices']
def ver(rt):
    m = re.search(r'iOS-(\d+)-(\d+)', rt)
    return (int(m.group(1)), int(m.group(2))) if m else (0, 0)
best = None
for rt, ds in devs.items():
    if 'iOS' not in rt:
        continue
    for d in ds:
        if not d['name'].startswith('iPhone'):
            continue
        score = (ver(rt), 'Pro Max' in d['name'], '16 Pro Max' in d['name'], d['name'])
        if best is None or score > best[0]:
            best = (score, d['udid'], d['name'])
print(best[1] + ' ' + best[2].replace(' ', '_') if best else '')")
echo "Simulador: ${NAME:-ninguno} ($UDID)"
[ -n "${UDID:-}" ] || { echo "No hay simuladores de iPhone disponibles"; xcrun simctl list devices available | head -40; exit 0; }
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl status_bar "$UDID" override --time "21:30" --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularMode notSupported || true
xcrun simctl location "$UDID" set 28.6353,-106.0889 || true
xcrun simctl install "$UDID" "$APP" || echo "no se pudo instalar la app"
xcrun simctl privacy "$UDID" grant location "$BUNDLE" || true
xcrun simctl privacy "$UDID" grant motion "$BUNDLE" || true

mkdir -p build/screens
shot() {
  local name=$1; shift
  xcrun simctl terminate "$UDID" "$BUNDLE" > /dev/null 2>&1 || true
  xcrun simctl launch "$UDID" "$BUNDLE" "$@" || echo "no se pudo abrir la app"
  sleep 10
  xcrun simctl io "$UDID" screenshot "build/screens/$name.png" || echo "no se pudo capturar"
  sips -s format jpeg -s formatOptions 55 -Z 1000 "build/screens/$name.png" --out "build/screens/$name.jpg" > /dev/null || true
  [ -f "build/screens/$name.jpg" ] || { echo "sin captura de $name"; return; }
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
