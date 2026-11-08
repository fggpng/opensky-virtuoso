# OpenSky Virtuoso Controller

App Android nativa (Kotlin + Jetpack Compose, MVVM/Clean) para controlar la montura **Sky-Watcher Virtuoso GTi 150P** por Wi-Fi: alineación por estrellas con ajuste matricial por mínimos cuadrados, GoTo, seguimiento alt-az en lazo cerrado, catálogo Tycho-2, Sistema Solar, cometas/asteroides y puente para **Stellarium**.

> ⚠️ **Sol**: nunca apuntes al Sol sin un filtro solar certificado de apertura completa (y el buscador tapado). La app bloquea el GoTo al Sol y a objetivos a menos de 15° de él salvo confirmación explícita, y el puente Stellarium/LX200 nunca permite esa zona.

## Correcciones técnicas respecto a la especificación

| Especificación | Realidad del hardware / formato | Implementación |
|---|---|---|
| Sockets **TCP** a 192.168.4.1:11880 | El módulo Wi-Fi de la Virtuoso GTi/AZ-GTi habla **UDP** en 11880 | `UdpMountTransport` (reintentos, validación de longitud de trama, descarte de datagramas tardíos). `TcpMountTransport` queda para puentes ESP32/serie |
| Comandos `:Slewed`, `:GZ`, `:AX` | La tarjeta de motores usa el *Motor Controller Command Set* (`:e1`, `:a1`, `:j1`, `:f1`, `:G1xx`, `:H1`, `:I1`, `:J1`, `:K1`…). `:GZ#` es **LX200** | `SkyWatcherProtocol.kt` + `MountClient`; `:GZ#` está en el servidor LX200 |
| `tycho2.dat/.idx/.ref` binarios | El Tycho-2 oficial (CDS I/259) es ASCII con campos separados por `|` | Conversor propio → formato binario mmap de 3 archivos (`Tycho2Format.kt`) |
| Conexión Wi-Fi automática | Desde Android 10 ninguna app se une sola a una Wi-Fi | `WifiNetworkSpecifier` (diálogo del sistema) + `Network.bindSocket`, conservando datos móviles para NTP |
| Servidor LX200 en 10001 | 10001 es el puerto típico del **protocolo binario** de Stellarium escritorio; LX200/TCP (SkySafari, Stellarium Mobile) suele usar 4030 | Ambos servidores, puertos configurables |
| SynMatrix AutoAlign | En SynScan se apoya en cámara/plate-solving | Selección automática de 4 estrellas repartidas + GoTo + reajuste tras cada centrado; hook para un plate-solver futuro |

## Arquitectura

```
:core  (Kotlin/JVM puro, probado con JUnit)
 ├─ astronomy        AstroTime (UTC/UT1/TT), EarthOrientation (precesión IAU76, nutación, GMST/GAST/LAST IAU2006),
 │                   AstroEngine (J2000→aparente→topocéntrica→Alt/Az + refracción), Coordinates
 │   └─ ephemeris    Sol/planetas (elementos JPL), Luna (Meeus cap. 47), cometas/asteroides (Kepler elíptico/parabólico/hiperbólico)
 ├─ alignment        PointingModel (inclinación del trípode, cero de Az, índice, cono, no-perpendicularidad),
 │                   AlignmentSolver (Taki + Levenberg–Marquardt), AlignmentManager (modos, sync, corrección local)
 ├─ database         tycho2 (conversor + lector mmap con índice por regiones), targets (estrellas con nombre, dobles, búsqueda)
 ├─ network          protocol (Sky-Watcher), transport (UDP/TCP), MountClient, simulator (montura UDP simulada),
 │                   lx200, bridge (servidores LX200 y Stellarium), time (SNTP), web (MPC + JPL SBDB)
 └─ control          MountController: GoTo en 2 pasadas, seguimiento, pad Alt/Az y AR/Dec, límites, Sol
:app   (Android)
 ├─ network.wifi     SynScanWifiManager (WifiNetworkSpecifier, reconexión)
 ├─ sensors          OrientationSensorEngine (TYPE_ROTATION_VECTOR, declinación WMM, interferencia magnética)
 ├─ location         LocationTimeManager (FusedLocationProvider, SNTP, relojes de red/GNSS de Android 13+)
 ├─ service          TelescopeSession + TelescopeService (primer plano, tipo connectedDevice)
 ├─ data             DataStore (ajustes), objetos de usuario, cometas/asteroides, importación Tycho-2
 ├─ di               AppContainer (inyección manual: sin KSP/kapt)
 └─ ui               Compose Material 3, tema rojo de visión nocturna, ViewModels por pantalla
:tools:catalog-builder   CLI para convertir/descargar el Tycho-2 en el PC
```

Flujo de un GoTo: objetivo → `TargetResolver` (posición aparente + paralaje + refracción) → `AlignmentManager.skyToMount` (modelo global + corrección local) → pasos de encoder → `MountClient.gotoSteps` → segunda pasada corta → `trackingLoop` (prealimentación + corrección proporcional cada segundo).

## Requisitos del flujo y dónde están

1. **Wi-Fi/red**: `SynScanWifiManager`, `UdpMountTransport`, `MountClient` (reintentos, reapertura de socket, `LinkState`).
2. **IMU**: `OrientationSensorEngine` + pantalla *Orientación*: promedia 2.5 s y llama a `MountController.setInitialOrientation`.
3. **GPS/tiempo**: `LocationTimeManager.syncTime` (SNTP con varias muestras; GNSS/red sin Internet).
4. **Tycho-2**: `Tycho2Converter`, `Tycho2Catalog.cone/box/findByTyc/findByHip`.
5. **Matemática**: `EarthOrientation`, `AstroEngine`, `Refraction`, `CoordinateTransforms`.
6. **Alineación**: 1-Star, Brightest (1/2), 2-Star, 3-Star, SynMatrix, Sync Samples, Reset (`AlignmentMode`, `AlignmentManager`).
7. **Precisión**: `AlignmentQuality` — RMS corregido por grados de libertad, `precisión % = 100·(1 − RMS/R)` con R = radio del campo real del ocular de referencia, y probabilidad de que el GoTo caiga en el campo `1 − exp(−R²/RMS²)`. Con 1–2 estrellas el ajuste es exacto y no hay redundancia para estimar el error: la app lo indica en vez de inventar un porcentaje.
8. **Objetivos**: Sistema Solar, 115 estrellas con nombre (refinadas con Tycho-2 vía HIP), 36 dobles, cometas (MPC) y asteroides/cometas (JPL SBDB o archivo MPC), objetos de usuario; búsqueda por nombre, letra griega, HIP o TYC.
9. **Pad**: 1×…800× sidérea, modo Alt/Az o AR/Dec (Jacobiano numérico), pulsar-mantener-soltar seguro (mutex).
10. **Seguimiento**: sidéreo, lunar, solar o por efemérides; el centrado manual durante el seguimiento se conserva como desplazamiento.
11. **Stellarium**: `Lx200TcpServer` (por defecto 4030, JNow) y `StellariumTcpServer` (10001, J2000) en el servicio en primer plano.
12. **Ajustes**: límites de altura, distancia al Sol, velocidad máxima, inversión de ejes, ubicación manual, zona horaria/DST, atmósfera, ocular de referencia, puertos, objetos de usuario.

## Compilar

Requisitos: Android Studio con soporte de **AGP 9.5** (Gradle 9.6, JDK 17+), SDK de Android 17 (compileSdk 37). La app apunta a Android 16 (`targetSdk 36`), `minSdk 29`.

```bash
./gradlew :core:test            # 41 pruebas JVM (Meeus, protocolo, simulador, catálogo, alineación, servidores)
./gradlew :app:assembleDebug
```

Versiones fijadas en `gradle/libs.versions.toml` (Kotlin 2.4.21, Compose BOM 2026.09.00, etc.). AGP 9 compila Kotlin de forma integrada, por eso **no** se aplica `org.jetbrains.kotlin.android`.

## Preparar el Tycho-2

```bash
# Descarga (~165 MB) y conversión en el PC
./gradlew :tools:catalog-builder:run --args="--download $PWD/tycho2-out"
# o con archivos ya descargados (tyc2.dat.00.gz … tyc2.dat.19.gz, suppl_1.dat.gz)
./gradlew :tools:catalog-builder:run --args="$PWD/tycho2-out /ruta/a/los/archivos"
```

Copia `tycho2.dat`, `tycho2.idx` y `tycho2.ref` al teléfono y en la app: **Más → Catálogo Tycho-2 → Importar**. También puedes seleccionar directamente los `.gz` del CDS y convertir en el teléfono.

## Uso en el campo

1. Enciende la montura, espera su red `SynScan_xxxx` y pulsa **Conectar** (aprueba el diálogo). Sin hardware: **Modo demo**.
2. Comprueba **Ubicación** y **Tiempo** (NTP verificado o GNSS).
3. **Orientación**: calibra la brújula (figura en 8), apoya el teléfono en el tubo y pulsa *Usar como orientación inicial*.
4. **Alinear**: elige SynMatrix o 2/3 estrellas, centra cada una con el pad y confirma. Revisa RMS y % de precisión.
5. **Objetivos** → GoTo + seguimiento. **Sync** en objetos cercanos para mejorar localmente.
6. **Stellarium**: conecta al `127.0.0.1` (mismo teléfono) o a la IP de `wlan0`.

## Verificación incluida

Las 41 pruebas de `:core` se ejecutaron con el compilador Kotlin 2.0.21 y JUnit 4: tiempo sidéreo, nutación, precesión y lugar aparente (θ Persei) frente a los ejemplos de Meeus; Sol (1.4″), Luna (0.1″) y Venus (0.5″) frente a sus valores de referencia; codificación del protocolo; GoTo, velocidades y pérdida de paquetes contra el simulador UDP; conversor y consultas Tycho-2 frente a fuerza bruta; recuperación del modelo de alineación con ruido; alineación SynMatrix + GoTo + seguimiento de extremo a extremo; LX200, protocolo binario de Stellarium y SNTP.

El módulo `:app` se verificó por tipos contra *stubs* de las API de Android/Compose, pero **no** se compiló con el SDK real de Android: el primer `assembleDebug` en Android Studio puede requerir ajustes menores.

## Limitaciones y siguientes pasos

- Planetas con elementos keplerianos (error ≤ ~10′ en Júpiter/Saturno): suficiente para GoTo; sustituible por VSOP87.
- Sin evitación del Sol *durante* la trayectoria del GoTo (solo en el destino).
- Mapa estelar propio, catálogos Messier/NGC, plate-solving para SynMatrix y textos en `strings.xml` (i18n).

## Referencias

J. Meeus, *Astronomical Algorithms* (2.ª ed.); E. M. Standish, *Keplerian Elements for Approximate Positions of the Major Planets* (JPL); Sky-Watcher *Motor Controller Command Set*; formatos del Minor Planet Center; JPL SBDB API; plugin *Telescope Control* de Stellarium; Tycho-2 (Høg et al. 2000, CDS I/259).

## Actualizaciones automáticas (GitHub Actions)

Cada `push` a `main` ejecuta `.github/workflows/release.yml`, que:

1. corre las pruebas del módulo `core` (incluida la prueba de extremo a extremo con el simulador);
2. compila un APK de *release* firmado con la clave guardada en los secretos del repositorio;
3. lo publica como release `v1.0.<N>` (N = número de ejecución = `versionCode`).

En el teléfono, **Más → Actualizar la app** consulta la última release, la descarga e instala
(Android pide confirmar). Al abrir la app también se comprueba en silencio y se avisa con un mensaje.

Secretos necesarios (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

Notas:
- El repositorio debe ser **público**: las releases de repositorios privados requieren autenticación.
- Las compilaciones de Android Studio (debug) se instalan como app aparte (`com.opensky.virtuoso.dev`),
  así no chocan con la firma de las versiones publicadas.
- La primera vez, si tienes instalada una versión compilada en Android Studio con el id
  `com.opensky.virtuoso`, desinstálala antes de instalar la de GitHub.

## Créditos de datos

- **Cielo profundo** (`core/src/main/resources/catalogs/deepsky.tsv`): derivado de
  [OpenNGC](https://github.com/mattiaverga/OpenNGC), © Mattia Verga y colaboradores,
  licencia [CC-BY-SA-4.0](https://creativecommons.org/licenses/by-sa/4.0/). El archivo derivado se
  distribuye bajo la misma licencia. Se regenera con `tools/deepsky/build_deepsky.py`.
  Incluye los 110 objetos Messier (M102 = NGC 5866, la identificación más aceptada) y los objetos
  NGC/IC hasta magnitud 12.
