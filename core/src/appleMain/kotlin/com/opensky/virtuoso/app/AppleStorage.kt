@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.opensky.virtuoso.app

import com.opensky.virtuoso.data.KeyValueStore
import com.opensky.virtuoso.database.tycho2.Tycho2Catalog
import com.opensky.virtuoso.database.tycho2.Tycho2Converter
import com.opensky.virtuoso.database.tycho2.Tycho2Files
import com.opensky.virtuoso.platform.AppleFiles
import com.opensky.virtuoso.platform.IOException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite

/** Carpeta Documentos de la app (visible en la app Archivos: «En mi iPhone › OpenSky»). */
fun appleDocumentsDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: throw IOException("Sin carpeta Documentos")

/** Ajustes en NSUserDefaults (con prefijo para no chocar con otras claves de iOS). */
class UserDefaultsStore(private val prefix: String = "opensky.") : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(prefix + key)

    override fun putAll(values: Map<String, String>) {
        values.forEach { (k, v) -> defaults.setObject(v, forKey = prefix + k) }
    }
}

/** Archivos de texto dentro de [directory]. */
class AppleTextFiles(private val directory: String) : TextFiles {
    init {
        AppleFiles.mkdirs(directory)
    }

    override fun read(name: String): String? = AppleFiles.readText(path(name))

    override fun write(name: String, text: String) = AppleFiles.writeText(path(name), text)

    override fun append(name: String, text: String) {
        val bytes = text.encodeToByteArray()
        if (bytes.isEmpty()) return
        val f = fopen(path(name), "ab") ?: throw IOException("No se pudo abrir ${path(name)}")
        try {
            bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), f) }
        } finally {
            fclose(f)
        }
    }

    override fun size(name: String): Long {
        val f = fopen(path(name), "rb") ?: return 0L
        try {
            fseek(f, 0, SEEK_END)
            return ftell(f).toLong()
        } finally {
            fclose(f)
        }
    }

    override fun rename(from: String, to: String) {
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(path(to), error = null)
        fm.moveItemAtPath(path(from), toPath = path(to), error = null)
    }

    override fun delete(name: String) = AppleFiles.delete(path(name))

    override fun path(name: String): String = "$directory/$name"
}

/** Tycho-2 en [directory] (Documentos/tycho2). */
class AppleCatalogFiles(override val directory: String) : CatalogFiles {
    override fun isInstalled(): Boolean = Tycho2Files.isInstalled(directory)

    override fun open(): Tycho2Catalog = Tycho2Files.open(directory)

    override fun copyIn(sourcePath: String, name: String) {
        AppleFiles.mkdirs(directory)
        val target = "$directory/$name"
        val fm = NSFileManager.defaultManager
        fm.removeItemAtPath(target, error = null)
        if (!fm.copyItemAtPath(sourcePath, toPath = target, error = null)) throw IOException("No se pudo copiar $name")
    }

    override fun addSourceFile(converter: Tycho2Converter, path: String) = Tycho2Files.addFile(converter, path)

    override fun write(converter: Tycho2Converter): Tycho2Converter.Result = Tycho2Files.write(converter, directory)

    override fun delete() = AppleFiles.delete(directory)
}
