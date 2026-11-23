package com.opensky.virtuoso.testing

import com.opensky.virtuoso.platform.CivilDateTime
import com.opensky.virtuoso.platform.PlatformLock
import kotlin.math.PI

/*
 * Aserciones con la firma de JUnit 4 (mensaje primero) sobre kotlin.test: las pruebas se
 * escribieron con JUnit y así corren igual en la JVM y en iOS sin reescribir cada aserción.
 */

fun assertTrue(condition: Boolean) = kotlin.test.assertTrue(condition)
fun assertTrue(message: String?, condition: Boolean) = kotlin.test.assertTrue(condition, message)
fun assertFalse(condition: Boolean) = kotlin.test.assertFalse(condition)
fun assertFalse(message: String?, condition: Boolean) = kotlin.test.assertFalse(condition, message)
fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
fun assertEquals(message: String?, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)
fun assertEquals(expected: Double, actual: Double, delta: Double) = kotlin.test.assertEquals(expected, actual, delta)
fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) =
    kotlin.test.assertEquals(expected, actual, delta, message)
fun assertNull(actual: Any?) = kotlin.test.assertNull(actual)
fun assertNull(message: String?, actual: Any?) = kotlin.test.assertNull(actual, message)
fun assertNotNull(actual: Any?) { kotlin.test.assertNotNull(actual) }
fun assertNotNull(message: String?, actual: Any?) { kotlin.test.assertNotNull(actual, message) }
fun fail(message: String? = null): Nothing = kotlin.test.fail(message)

fun toDegrees(rad: Double): Double = rad * 180.0 / PI
fun toRadians(deg: Double): Double = deg * PI / 180.0

/** "2026-11-08T05:00:00Z" → ms Unix (lo que daba Instant.parse(...).toEpochMilli()). */
fun isoMillis(iso: String): Long {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?Z""").matchEntire(iso)
        ?: throw IllegalArgumentException("Fecha ISO no válida: $iso")
    val g = m.groupValues
    return CivilDateTime(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(),
        g[7].ifEmpty { "0" }.padEnd(3, '0').toInt()).toEpochMillis()
}

/** Lista segura entre hilos para registrar llamadas desde coroutines (Collections.synchronizedList). */
class SyncList<T> {
    private val lock = PlatformLock()
    private val items = mutableListOf<T>()
    fun add(item: T) { lock.withLock { items.add(item) } }
    operator fun plusAssign(item: T) = add(item)
    operator fun get(index: Int): T = lock.withLock { items[index] }
    operator fun contains(item: T): Boolean = lock.withLock { item in items }
    fun toList(): List<T> = lock.withLock { items.toList() }
    val size: Int get() = lock.withLock { items.size }
    fun isEmpty(): Boolean = size == 0
    fun isNotEmpty(): Boolean = !isEmpty()
    fun last(): T = lock.withLock { items.last() }
    fun clear() { lock.withLock { items.clear() } }
}
