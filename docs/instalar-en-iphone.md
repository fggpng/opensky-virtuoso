# Instalar OpenSky en el iPhone (sin pagar)

La app de iOS se publica en cada versión de GitHub como `opensky-virtuoso-N.ipa`, junto al APK de
Android. Está hecha para **iOS 27** y pensada para el **iPhone 16 Pro Max** (pantalla de 6.9″,
Dynamic Island y botón de Acción), pero funciona en cualquier iPhone con iOS 27.

Como no está en la App Store, se instala con tu **Apple ID gratuito** y una herramienta de
«sideloading». No hace falta pagar la cuenta de desarrollador de 99 USD/año.

## Lo que implica una cuenta gratuita

- **La firma dura 7 días.** Al caducar, la app no abre hasta volver a firmarla (tus ajustes,
  objetos y catálogos se conservan). Sideloadly y SideStore pueden renovarla solos.
- **Máximo 3 apps instaladas así** a la vez por Apple ID, y 10 identificadores de app por semana.
  OpenSky trae una extensión (Live Activity y control del Centro de control) que en AltStore y
  SideStore cuenta como otra app. Si llegas al límite, en Sideloadly marca
  *Advanced options › Remove app extensions* (pierdes solo la Live Activity y el control).
- **Modo de desarrollador**: iOS lo pide la primera vez (Configuración › Privacidad y seguridad ›
  Modo de desarrollador › activar y reiniciar).

En esta guía, **Configuración** es la app de ajustes del iPhone (en España se llama «Ajustes»).

## Opción A: Sideloadly (desde una PC con Windows o una Mac)

1. Descarga **Sideloadly** de <https://sideloadly.io>. En Windows instala también **iTunes** o
   **Dispositivos de Apple** (de la web de Apple o de la Microsoft Store) para que la PC vea el iPhone.
2. Descarga el `.ipa` más reciente de
   <https://github.com/fggpng/opensky-virtuoso/releases>.
3. Conecta el iPhone por cable, desbloquéalo y acepta «Confiar en esta computadora».
4. Abre Sideloadly, arrastra el `.ipa`, escribe tu Apple ID y pulsa **Start**. Te pedirá la
   contraseña del Apple ID (y el código de verificación en dos pasos).
5. En el iPhone: **Configuración › General › Admón. de dispositivos y VPN** › tu Apple ID (en
   «App de desarrollador») › **Confiar**.
6. Si iOS lo pide, activa el **Modo de desarrollador** y reinicia.

Para no tener que conectarlo cada semana: en Sideloadly activa *Automatic refresh* y deja la PC
encendida en la misma Wi-Fi que el iPhone (con «Sincronizar por Wi-Fi» activado en iTunes /
Dispositivos de Apple).

## Opción B: SideStore (renueva la firma desde el propio iPhone)

SideStore se instala una vez con una PC y después renueva la firma de OpenSky desde el iPhone,
sin cable. Sigue la guía oficial (<https://docs.sidestore.io>) y luego, en SideStore, pulsa **+** y
elige el `.ipa` de OpenSky.

## Primer arranque

iOS pedirá tres permisos; acéptalos:

- **Ubicación (al usar la app)**: latitud y longitud del sitio. Con la montura conectada también
  mantiene viva la sesión con la pantalla bloqueada (verás el indicador azul).
- **Red local**: imprescindible para hablar con la montura y para Stellarium. Si lo rechazaste:
  Configuración › Privacidad y seguridad › Red local › OpenSky.
- **Live Activities**: el estado y el botón DETENER en la pantalla bloqueada y la Dynamic Island.

## Conectar la montura

1. Enciende la Virtuoso GTi.
2. En **Configuración › Wi-Fi** únete a la red **SynScan_…**. iOS dirá «Sin conexión a Internet»: es
   normal (iOS no deja que una app se una sola a una Wi-Fi sin la cuenta de pago).
3. En OpenSky, **Inicio › Conectar por la Wi-Fi de la montura**.

Sin la montura, **Probar con el simulador** responde a los mismos comandos.

## Extras del iPhone 16 Pro Max

- **Botón de Acción**: Configuración › Botón de Acción › **Atajo** › OpenSky › **Detener telescopio**.
  Una pulsación detiene los motores aunque la app esté en segundo plano.
- **Centro de control**: mantén pulsado, «Añadir un control» › OpenSky › **Detener telescopio**.
- **Siri**: «Detén el telescopio con OpenSky».
- **Live Activity**: mientras la montura está conectada, la Dynamic Island muestra el estado
  (GoTo, siguiendo, quieta) y la altura; al expandirla, el objetivo y el botón DETENER.

## Modo nocturno

**Más › Modo nocturno (rojo)** pasa toda la app a rojo sobre negro (también la barra de pestañas,
los interruptores y la Live Activity) y oculta la barra de estado. Los menús y avisos del sistema
no se pueden teñir desde una app; para que todo el iPhone quede en rojo:

1. **Configuración › Accesibilidad › Pantalla y tamaño del texto › Filtros de colores**: actívalo,
   elige **Tinte de color** y lleva la intensidad y el tono al rojo.
2. **Configuración › Accesibilidad › Función rápida de accesibilidad** › **Filtros de colores**.

Con eso, tres clics en el botón lateral ponen o quitan el rojo en todo el teléfono. Baja también
el brillo al mínimo.

## Actualizar

Descarga el `.ipa` nuevo y vuelve a instalarlo igual (mismo Apple ID): se conservan los datos.
La app no puede actualizarse sola en iOS.

## Diferencias con Android

| | Android | iOS |
|---|---|---|
| Unirse a la Wi-Fi SynScan | La app la pide sola | En Configuración › Wi-Fi |
| Con la pantalla bloqueada | Servicio en primer plano | Ubicación en segundo plano (indicador azul) |
| Actualizaciones | Desde la app | Reinstalar el `.ipa` |
| DETENER sin abrir la app | Notificación | Live Activity, botón de Acción, Centro de control, Siri |
| Brújula | Modelo magnético WMM de Android | Declinación de iOS (rumbo verdadero − magnético) |
