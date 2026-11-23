package com.opensky.virtuoso.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMTForDate
import platform.Foundation.timeIntervalSince1970

actual fun epochMillisNow(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun systemUtcOffsetSeconds(epochMillis: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)).toInt()

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual class AtomicFlag actual constructor(initial: Boolean) {
    private val lock = NSLock()
    private var value = initial

    actual fun get(): Boolean {
        lock.lock()
        try { return value } finally { lock.unlock() }
    }

    actual fun set(value: Boolean) {
        lock.lock()
        try { this.value = value } finally { lock.unlock() }
    }

    actual fun getAndSet(value: Boolean): Boolean {
        lock.lock()
        try {
            val old = this.value
            this.value = value
            return old
        } finally {
            lock.unlock()
        }
    }
}

actual class PlatformLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
