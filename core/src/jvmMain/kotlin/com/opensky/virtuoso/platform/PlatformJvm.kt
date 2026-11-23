package com.opensky.virtuoso.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

actual fun epochMillisNow(): Long = System.currentTimeMillis()

actual fun systemUtcOffsetSeconds(epochMillis: Long): Int =
    ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds

actual fun systemTimeZoneId(): String = ZoneId.systemDefault().id

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

/** En la JVM (pruebas) no hay hilo de interfaz: los avisos se entregan en el acto. */
actual val uiDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

actual class AtomicFlag actual constructor(initial: Boolean) {
    private val value = AtomicBoolean(initial)
    actual fun get(): Boolean = value.get()
    actual fun set(value: Boolean) = this.value.set(value)
    actual fun getAndSet(value: Boolean): Boolean = this.value.getAndSet(value)
}

actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

internal actual fun deepSkyCatalogText(): String {
    val stream = PlatformLock::class.java.getResourceAsStream("/catalogs/deepsky.tsv")
        ?: error("Falta el recurso catalogs/deepsky.tsv")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
