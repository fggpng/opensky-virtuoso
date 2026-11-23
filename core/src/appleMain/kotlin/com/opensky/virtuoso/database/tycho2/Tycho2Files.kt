package com.opensky.virtuoso.database.tycho2

import com.opensky.virtuoso.platform.AppleFiles
import com.opensky.virtuoso.platform.FileSink
import com.opensky.virtuoso.platform.MappedFileBlob

/** Archivos del Tycho-2 en iOS (rutas POSIX dentro del contenedor de la app). */
object Tycho2Files {
    fun isInstalled(directory: String): Boolean = Tycho2Catalog.FILE_NAMES.all { AppleFiles.exists("$directory/$it") }

    fun open(directory: String): Tycho2Catalog = Tycho2Catalog.open { name -> MappedFileBlob("$directory/$name") }

    /** Añade un archivo del CDS, comprimido (.gz) o no. */
    fun addFile(converter: Tycho2Converter, path: String) = AppleFiles.forEachLine(path) { converter.addTextLine(it) }

    fun write(converter: Tycho2Converter, directory: String): Tycho2Converter.Result {
        AppleFiles.mkdirs(directory)
        return converter.write { name -> FileSink("$directory/$name") }
    }
}
