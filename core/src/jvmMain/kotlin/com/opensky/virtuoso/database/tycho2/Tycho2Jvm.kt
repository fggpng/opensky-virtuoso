package com.opensky.virtuoso.database.tycho2

import com.opensky.virtuoso.platform.FileSink
import com.opensky.virtuoso.platform.MappedFileBlob
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** ¿Están los tres binarios en [dir]? */
fun Tycho2Catalog.Companion.isInstalled(dir: File): Boolean = FILE_NAMES.all { File(dir, it).isFile }

/** Abre el catálogo de [dir] con archivos mapeados en memoria. */
fun Tycho2Catalog.Companion.open(dir: File): Tycho2Catalog = open { name -> MappedFileBlob(File(dir, name)) }

/** Añade un archivo del CDS (detecta .gz por la extensión). */
fun Tycho2Converter.addFile(file: File) = file.inputStream().use { addStream(it, gzip = file.name.endsWith(".gz")) }

fun Tycho2Converter.addStream(input: InputStream, gzip: Boolean = false) {
    val stream = if (gzip) GZIPInputStream(input, 1 shl 16) else input
    BufferedReader(InputStreamReader(stream, Charsets.US_ASCII), 1 shl 16).useLines { addLines(it) }
}

/** Escribe tycho2.dat, tycho2.idx y tycho2.ref en [outputDir]. */
fun Tycho2Converter.write(outputDir: File): Tycho2Converter.Result {
    outputDir.mkdirs()
    return write { name -> FileSink(File(outputDir, name)) }
}
