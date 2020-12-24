package ai.platon.pulsar.common.collect

import ai.platon.pulsar.common.Priority
import ai.platon.pulsar.common.url.UrlAware
import java.time.Duration
import java.time.Instant

interface ExternalUrlLoader {
    /**
     * The estimated size of the external storage
     * */
    var estimatedSize: Int
    /**
     * The estimated remaining size of the external storage
     * */
    var estimatedRemainingSize: Int
    /**
     * The delay time to load after another load
     * */
    var loadDelay: Duration
    /**
     * If the loader is cooling down
     * */
    val isExpired: Boolean
    /**
     * Force the loading time to expire
     * */
    fun expire()
    /**
     * Force the loading time to expire
     * */
    fun reset() = expire()
    /**
     * Save the url to the external repository
     * */
    fun save(url: UrlAware, group: Int = 0)
    /**
     * Save all the url to the external repository
     * */
    fun saveAll(urls: Iterable<UrlAware>, group: Int = 0)
    /**
     * If there are more items in the source
     * */
    fun hasMore(): Boolean
    /**
     * Load items from the source to the sink
     * */
    fun loadToNow(sink: MutableCollection<UrlAware>,
                  maxSize: Int = 10_000, group: Int = 0, priority: Int = Priority.NORMAL.value): Collection<UrlAware>
    /**
     * Load items from the source to the sink
     * */
    fun <T> loadToNow(sink: MutableCollection<T>,
                      maxSize: Int = 10_000, group: Int, priority: Int, transformer: (UrlAware) -> T): Collection<T>
    /**
     * Load items from the source to the sink
     * */
    fun loadTo(sink: MutableCollection<UrlAware>,
               maxSize: Int = 10_000, group: Int = 0, priority: Int = Priority.NORMAL.value)
    /**
     * Load items from the source to the sink
     * */
    fun <T> loadTo(sink: MutableCollection<T>,
                   maxSize: Int = 10_000, group: Int = 0, priority: Int = Priority.NORMAL.value, transformer: (UrlAware) -> T)
}

abstract class AbstractExternalUrlLoader(
        override var loadDelay: Duration = Duration.ofSeconds(10)
): ExternalUrlLoader {

    override var estimatedSize: Int = Int.MAX_VALUE
    override var estimatedRemainingSize: Int = Int.MAX_VALUE

    protected var lastLoadTime = Instant.EPOCH
    override val isExpired get() = lastLoadTime + loadDelay < Instant.now()

    override fun expire() { lastLoadTime = Instant.EPOCH }
    override fun reset() { lastLoadTime = Instant.EPOCH }
    override fun hasMore(): Boolean = isExpired

    override fun saveAll(urls: Iterable<UrlAware>, group: Int) = urls.forEach { save(it, group) }

    override fun loadToNow(sink: MutableCollection<UrlAware>, maxSize: Int, group: Int, priority: Int) =
            loadToNow(sink, maxSize, group, priority) { it }

    override fun loadTo(sink: MutableCollection<UrlAware>,
                        maxSize: Int, group: Int, priority: Int) = loadTo(sink, maxSize, group, priority) { it }

    override fun <T> loadTo(sink: MutableCollection<T>,
                            maxSize: Int, group: Int, priority: Int, transformer: (UrlAware) -> T) {
        if (!isExpired) {
            return
        }

        lastLoadTime = Instant.now()

        loadToNow(sink, maxSize, group, priority, transformer)
    }
}
