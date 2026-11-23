package com.opensky.virtuoso.app

import com.opensky.virtuoso.data.KeyValueStore
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter

/*
 * Lo que la capa compartida de la app (modelos de pantalla, sesión, repositorios) necesita de cada
 * plataforma. En iOS lo implementan appleMain (archivos, ajustes) y Swift (sensores, ubicación).
 */

/** Archivos de texto de la app, por nombre, dentro de su carpeta privada. */
interface TextFiles {
    fun read(name: String): String?
    /** Escritura atómica (archivo temporal + renombrado). */
    fun write(name: String, text: String)
    fun append(name: String, text: String)
    fun size(name: String): Long
    fun rename(from: String, to: String)
    fun delete(name: String)
    /** Ruta absoluta (para compartir el archivo desde la UI). */
    fun path(name: String): String
}

/** Operaciones del Tycho-2 que dependen del sistema de archivos. */
interface CatalogFiles {
    /** Carpeta donde viven tycho2.dat/.idx/.ref. */
    val directory: String
    fun isInstalled(): Boolean
    fun open(): Tycho2Catalog
    fun copyIn(sourcePath: String, name: String)
    fun addSourceFile(converter: Tycho2Converter, path: String)
    fun write(converter: Tycho2Converter): Tycho2Converter.Result
    fun delete()
}

/** Servicios del dispositivo que pone la UI nativa (en iOS: Swift con CoreMotion y CoreLocation). */
interface DeviceServices {
    /** ¿Hay sensores de movimiento y brújula? */
    val motionAvailable: Boolean
    fun startMotion()
    fun stopMotion()
    /** Pide un fix de ubicación nuevo (la respuesta llega por [LocationStore.onFix]). */
    fun requestLocation()
    /** Declinación magnética del sitio (°, + al este) o null si aún no se conoce. */
    fun magneticDeclinationDeg(): Double?
    /** Direcciones IPv4 del teléfono (para configurar Stellarium en otro equipo). */
    fun localIpv4Addresses(): List<String>
}

/** Todo lo que pone la plataforma. */
interface AppPlatform {
    val settings: KeyValueStore
    val files: TextFiles
    val catalogFiles: CatalogFiles
    val device: DeviceServices
    /** "1.0.12 (12)". */
    val appVersion: String
    /** "iPhone · iOS 27.1". */
    val deviceDescription: String
}
