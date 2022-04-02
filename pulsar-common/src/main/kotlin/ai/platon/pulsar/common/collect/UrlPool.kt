package ai.platon.pulsar.common.collect

import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.UrlPool.Companion.REAL_TIME_PRIORITY
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.urls.UrlAware
import com.google.common.primitives.Ints
import org.apache.commons.collections4.queue.SynchronizedQueue
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class DelayUrl(
    val url: UrlAware,
    val delay: Duration,
) : Delayed {
    // The time to start the task
    val startTime = System.currentTimeMillis() + delay.toMillis()

    override fun compareTo(other: Delayed): Int {
        return Ints.saturatedCast(startTime - (other as DelayUrl).startTime)
    }

    override fun getDelay(unit: TimeUnit): Long {
        val diff = startTime - System.currentTimeMillis()
        return unit.convert(diff, TimeUnit.MILLISECONDS)
    }
}

/**
 * The fetch pool
 * */
interface UrlPool {
    companion object {
        val REAL_TIME_PRIORITY = Priority13.HIGHEST.value
    }

    /**
     * The priority fetch caches
     * */
    val orderedCaches: MutableMap<Int, UrlCache>
    val unorderedCaches: MutableList<UrlCache>
    val realTimeCache: UrlCache
    val delayCache: Queue<DelayUrl>
    val totalItems: Int

    val lowestCache: UrlCache
    val lower5Cache: UrlCache
    val lower4Cache: UrlCache
    val lower3Cache: UrlCache
    val lower2Cache: UrlCache
    val lowerCache: UrlCache
    val normalCache: UrlCache
    val higherCache: UrlCache
    val higher2Cache: UrlCache
    val higher3Cache: UrlCache
    val higher4Cache: UrlCache
    val higher5Cache: UrlCache
    val highestCache: UrlCache

    fun initialize()
    fun removeDeceased()
    fun clear()
}

/**
 * The abstract fetch pool
 * */
abstract class AbstractUrlPool(val conf: ImmutableConfig) : UrlPool {
    protected val initialized = AtomicBoolean()
    override val totalItems get() = ensureInitialized().orderedCaches.values.sumOf { it.size }

    override val lowestCache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.LOWEST.value]!!
    override val lower5Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.LOWER5.value]!!
    override val lower4Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.LOWER4.value]!!
    override val lower3Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.LOWER3.value]!!
    override val lower2Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.LOWER2.value]!!
    override val lowerCache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.LOWER.value]!!
    override val normalCache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.NORMAL.value]!!
    override val higherCache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.HIGHER.value]!!
    override val higher2Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.HIGHER2.value]!!
    override val higher3Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.HIGHER3.value]!!
    override val higher4Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.HIGHER4.value]!!
    override val higher5Cache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.HIGHER5.value]!!
    override val highestCache: UrlCache get() = ensureInitialized().orderedCaches[Priority13.HIGHEST.value]!!

    override fun removeDeceased() {
        ensureInitialized()
        orderedCaches.values.forEach { it.removeDeceased() }
        unorderedCaches.forEach { it.removeDeceased() }
        val now = Instant.now()
        delayCache.removeIf { it.url.deadTime < now }
    }

    override fun clear() {
        orderedCaches.clear()
        unorderedCaches.clear()
        realTimeCache.clear()
        delayCache.clear()
    }

    private fun ensureInitialized(): AbstractUrlPool {
        if (initialized.compareAndSet(false, true)) {
            initialize()
        }
        return this
    }
}

/**
 * The global cache
 * */
open class ConcurrentUrlPool(conf: ImmutableConfig) : AbstractUrlPool(conf) {
    /**
     * The priority fetch caches
     * */
    override val orderedCaches = ConcurrentSkipListMap<Int, UrlCache>()

    override val unorderedCaches: MutableList<UrlCache> = Collections.synchronizedList(mutableListOf())

    /**
     * The real time fetch cache
     * */
    override val realTimeCache: UrlCache = ConcurrentUrlCache("realtime", REAL_TIME_PRIORITY)

    /**
     * The delayed fetch cache
     * */
    override val delayCache: Queue<DelayUrl> = SynchronizedQueue.synchronizedQueue(DelayQueue())

    override fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            Priority13.values().forEach { orderedCaches[it.value] = ConcurrentUrlCache(it.name, it.value) }
        }
    }
}

class LoadingUrlPool(
    val loader: ExternalUrlLoader,
    val capacity: Int = 10_000,
    conf: ImmutableConfig,
) : ConcurrentUrlPool(conf) {
    /**
     * The real time fetch cache
     * */
    override val realTimeCache: UrlCache = LoadingUrlCache("realtime", REAL_TIME_PRIORITY, loader, capacity)

    override fun initialize() {
        if (initialized.compareAndSet(false, true)) {
            Priority13.values().forEach {
                // TODO: better fetch cache name, it affects the topic id
                orderedCaches[it.value] = LoadingUrlCache(it.name, it.value, loader, capacity)
            }
        }
    }
}