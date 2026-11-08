package com.opensky.virtuoso.tools

import com.opensky.virtuoso.astronomy.EquatorialCoord
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/** Réplica verificada del catálogo I/259 (también: https://cdsarc.cds.unistra.fr/ftp/I/259/). */
private const val DEFAULT_BASE_URL = "https://vizier.cfa.harvard.edu/ftp/cats/1/259/"

private fun usage(): Nothing {
    System.err.println(
        """
        Uso:
          catalog-builder [--download] [--base-url URL] <directorio-salida> [archivos...]

          --download   descarga tyc2.dat.00.gz … tyc2.dat.19.gz y suppl_1.dat.gz (~165 MB)
          archivos     tyc2.dat[.NN][.gz], suppl_1.dat[.gz] o un directorio que los contenga

        Genera tycho2.dat, tycho2.idx y tycho2.ref para importarlos en la app (Más → Catálogo).
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    var download = false
    var baseUrl = DEFAULT_BASE_URL
    val positional = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--download" -> download = true
            "--base-url" -> baseUrl = args.getOrNull(++i)?.let { if (it.endsWith("/")) it else "$it/" } ?: usage()
            "-h", "--help" -> usage()
            else -> positional += args[i]
        }
        i++
    }
    if (positional.isEmpty()) usage()
    val outDir = File(positional.first())
    val inputs = positional.drop(1).flatMap { p ->
        val f = File(p)
        if (f.isDirectory) f.listFiles().orEmpty().filter { it.name.startsWith("tyc2.dat") || it.name.startsWith("suppl_1.dat") }.sortedBy { it.name }
        else listOf(f)
    }.toMutableList()

    if (download) {
        val cache = File(outDir, "cds").apply { mkdirs() }
        val names = (0..19).map { "tyc2.dat.%02d.gz".format(it) } + "suppl_1.dat.gz"
        for (name in names) {
            val target = File(cache, name)
            if (!target.isFile || target.length() == 0L) {
                println("Descargando $baseUrl$name …")
                URI("$baseUrl$name").toURL().openStream().use { Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            }
            inputs += target
        }
    }
    if (inputs.isEmpty()) usage()

    val converter = Tycho2Converter { phase, n -> println("  $phase: %,d".format(n)) }
    for (file in inputs) {
        require(file.isFile) { "No existe: $file" }
        println("Leyendo ${file.name}")
        converter.addFile(file)
    }
    val result = converter.write(outDir)
    println("Listo: %,d estrellas en %d regiones, %,d referencias HIP, %d líneas descartadas".format(
        result.stars, result.regions, result.hipReferences, result.skippedLines))

    // Verificación rápida: Vega (HIP 91262) y estrellas alrededor de Albireo.
    Tycho2Catalog.open(outDir).use { cat ->
        cat.findByHip(91262)?.let { println("HIP 91262 → ${it.designation} V=%.2f RA=%.5f Dec=%.5f".format(it.vMag, it.raDeg, it.decDeg)) }
        val t0 = System.nanoTime()
        val near = cat.cone(EquatorialCoord.fromHoursDegrees(19.512, 27.96), 1.0, 10.0)
        println("Cono de 1° en Albireo hasta V=10: ${near.size} estrellas en %.1f ms".format((System.nanoTime() - t0) / 1e6))
    }
    println("Copia ${outDir.absolutePath}/tycho2.{dat,idx,ref} al teléfono e impórtalos desde la app.")
}
