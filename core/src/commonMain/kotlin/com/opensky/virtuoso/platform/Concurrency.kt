package com.opensky.virtuoso.platform

import kotlinx.coroutines.CoroutineDispatcher

/** Despachador para E/S bloqueante (sockets, archivos): Dispatchers.IO en JVM y en iOS. */
expect val ioDispatcher: CoroutineDispatcher

/** Booleano atómico (equivale a java.util.concurrent.atomic.AtomicBoolean). */
expect class AtomicFlag(initial: Boolean) {
    fun get(): Boolean
    fun set(value: Boolean)
    fun getAndSet(value: Boolean): Boolean
}

/** Cerrojo reentrante para secciones críticas cortas entre hilos (no suspende). */
expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}

/** Mapa protegido por un cerrojo (sustituye a ConcurrentHashMap en el código común). */
class LockedMap<K, V> {
    private val lock = PlatformLock()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }

    operator fun set(key: K, value: V) {
        lock.withLock { map[key] = value }
    }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    fun clear() {
        lock.withLock { map.clear() }
    }
}
