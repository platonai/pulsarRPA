package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.config.AppConstants.BROWSER_TAB_REQUIRED_MEMORY
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_DRIVER_POOL_IDLE_TIMEOUT
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_MAX_ACTIVE_TABS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.Parameterized
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.metrics.AppMetrics
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.crawl.fetch.privacy.BrowserId
import ai.platon.pulsar.protocol.browser.DriverLaunchException
import ai.platon.pulsar.protocol.browser.emulator.WebDriverPoolExhaustedException
import org.slf4j.LoggerFactory
import oshi.SystemInfo
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Created by vincent on 18-1-1.
 * Copyright @ 2013-2017 Platon AI. All rights reserved
 */
class LoadingWebDriverPool(
    val browserId: BrowserId,
    val priority: Int = 0,
    val driverFactory: WebDriverFactory,
    val immutableConfig: ImmutableConfig
): Parameterized, AutoCloseable {

    companion object {
        var CLOSE_ALL_TIMEOUT = Duration.ofSeconds(60)
        var POLLING_TIMEOUT = Duration.ofSeconds(60)
        val instanceSequencer = AtomicInteger()
    }

    private val logger = LoggerFactory.getLogger(LoadingWebDriverPool::class.java)
    val id = instanceSequencer.incrementAndGet()
    val capacity get() = immutableConfig.getInt(BROWSER_MAX_ACTIVE_TABS, AppContext.NCPU)
    val onlineDrivers = ConcurrentSkipListSet<WebDriver>()

    val freeDrivers = ArrayBlockingQueue<WebDriver>(2 * capacity)
//    val freeDrivers = Channel<WebDriver>(2 * capacity)
    private val lock = ReentrantLock()
    private val notBusy = lock.newCondition()
    // TODO: never wait for notEmpty
    private val notEmpty = lock.newCondition()

    private val closed = AtomicBoolean()
    private val systemInfo = SystemInfo()
    // OSHI cached the value, so it's fast and safe to be called frequently
    private val availableMemory get() = systemInfo.hardware.memory.available

    private val registry = AppMetrics.defaultMetricRegistry
    val counterRetired = registry.counter(this, "retired")
    val counterQuit = registry.counter(this, "quit")

    var isRetired = false
    val isActive get() = !isRetired && !closed.get() && AppContext.isActive
    val numWaiting = AtomicInteger()
    val numWorking = AtomicInteger()
    val numTasks = AtomicInteger()
    val numFree get() = freeDrivers.size
    val numActive get() = numWorking.get() + numFree
    val numAvailable get() = capacity - numWorking.get()
    val numOnline get() = onlineDrivers.size

    var lastActiveTime = Instant.now()
    var idleTimeout = immutableConfig.getDuration(BROWSER_DRIVER_POOL_IDLE_TIMEOUT, Duration.ofMinutes(10))
    val idleTime get() = Duration.between(lastActiveTime, Instant.now())
    val isIdle get() = (numWorking.get() == 0 && idleTime > idleTimeout)

    /**
     * Allocate [capacity] drivers
     * */
    fun allocate(conf: VolatileConfig) {
        repeat(capacity) {
            runCatching { put(poll(priority, conf, POLLING_TIMEOUT.seconds, TimeUnit.SECONDS)) }.onFailure {
                logger.warn("Unexpected exception", it)
            }
        }
    }

    /**
     * Retrieves and removes the head of this free driver queue,
     * or returns {@code null} if there is no free drivers.
     *
     * @return the head of the free driver queue, or {@code null} if this queue is empty
     */
    fun poll(): WebDriver? {
        numWaiting.incrementAndGet()
        return freeDrivers.poll().also { numWaiting.decrementAndGet() }
    }

    @Throws(DriverLaunchException::class, WebDriverPoolExhaustedException::class)
    fun poll(conf: VolatileConfig): WebDriver = poll(0, conf, POLLING_TIMEOUT.seconds, TimeUnit.SECONDS)

    @Throws(DriverLaunchException::class, WebDriverPoolExhaustedException::class)
    fun poll(conf: VolatileConfig, timeout: Long, unit: TimeUnit): WebDriver = poll(0, conf, timeout, unit)

    @Throws(DriverLaunchException::class, WebDriverPoolExhaustedException::class)
    fun poll(priority: Int, conf: VolatileConfig, timeout: Duration): WebDriver {
        return poll(0, conf, timeout.seconds, TimeUnit.SECONDS)
    }

    @Throws(DriverLaunchException::class, WebDriverPoolExhaustedException::class)
    fun poll(priority: Int, conf: VolatileConfig, timeout: Long, unit: TimeUnit): WebDriver {
        return poll0(priority, conf, timeout, unit).also {
            numWorking.incrementAndGet()
            lastActiveTime = Instant.now()
        }
    }

    fun put(driver: WebDriver) {
        numWorking.decrementAndGet()

        // close open tabs to reduce memory usage
        if (availableMemory < BROWSER_TAB_REQUIRED_MEMORY) {
            if (numOnline > 0.5 * capacity) {
                driver.retire()
            }
        }

        if (numOnline > capacity) {
            driver.retire()
        }

        if (driver.isWorking) offer(driver) else dismiss(driver)

        lastActiveTime = Instant.now()

        if (numWorking.get() == 0) {
            lock.withLock { notBusy.signalAll() }
        }
    }

    fun forEach(action: (WebDriver) -> Unit) = onlineDrivers.forEach(action)

    fun firstOrNull(predicate: (WebDriver) -> Boolean) = onlineDrivers.firstOrNull(predicate)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                doClose(CLOSE_ALL_TIMEOUT)
            } catch (e: InterruptedException) {
                logger.warn("Thread interrupted when closing | {}", this)
                Thread.currentThread().interrupt()
            }
        }
    }

    fun formatStatus(verbose: Boolean = false): String {
        val p = this
        val status = if (verbose) {
            String.format("online: %d, free: %d, waiting: %d, working: %d, active: %d",
                    p.numOnline, p.numFree, p.numWaiting.get(), p.numWorking.get(), p.numActive)
        } else {
            String.format("%d/%d/%d/%d/%d (online/free/waiting/working/active)",
                    p.numOnline, p.numFree, p.numWaiting.get(), p.numWorking.get(), p.numActive)
        }

        val time = idleTime.readable()
        return when {
            isIdle -> "[Idle] $time | $status"
            isRetired -> "[Retired] $time | $status"
            else -> status
        }
    }

    override fun toString(): String = formatStatus(false)

    @Synchronized
    private fun offer(driver: WebDriver) {
        freeDrivers.offer(driver.apply { free() })
        lock.withLock { notEmpty.signalAll() }
    }

    @Synchronized
    private fun dismiss(driver: WebDriver, external: Boolean = true) {
        if (external && !isActive) {
            return
        }

        counterRetired.inc()
        freeDrivers.remove(driver)
        driver.runCatching { quit().also { counterQuit.inc() } }.onFailure {
            logger.warn("[Unexpected] Quit $driver", it)
        }
        onlineDrivers.remove(driver)
    }

    @Throws(DriverLaunchException::class, WebDriverPoolExhaustedException::class)
    private fun poll0(priority: Int, conf: VolatileConfig, timeout: Long, unit: TimeUnit): WebDriver {
        createDriverIfNecessary(priority, conf)

        numWaiting.incrementAndGet()
        val driver = try {
            freeDrivers.poll(timeout, unit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            numWaiting.decrementAndGet()
        }

        return driver ?: throw WebDriverPoolExhaustedException("Driver pool is exhausted (" + formatStatus() + ")")
    }

    @Throws(DriverLaunchException::class)
    private fun createDriverIfNecessary(priority: Int, volatileConfig: VolatileConfig) {
        synchronized(driverFactory) {
            try {
                if (shouldCreateDriver()) {
                    createWebDriver(volatileConfig)
                }
            } catch (e: DriverLaunchException) {
                logger.debug("[Unexpected]", e)

                if (isActive) {
                    throw e
                }
            }
        }
    }

    private fun shouldCreateDriver(): Boolean {
        return isActive && availableMemory > BROWSER_TAB_REQUIRED_MEMORY && onlineDrivers.size < capacity
    }

    @Throws(DriverLaunchException::class)
    private fun createWebDriver(volatileConfig: VolatileConfig) {
        val driver = driverFactory.create(browserId, priority, volatileConfig, start = false)

        lock.withLock {
            freeDrivers.add(driver)
            onlineDrivers.add(driver)
            notEmpty.signalAll()
        }

        if (logger.isDebugEnabled) {
            logDriverOnline(driver)
        }
    }

    private fun doClose(timeToWait: Duration) {
        freeDrivers.clear()

//        val heavyRendering = conf.getBoolean(CapabilityTypes.BROWSER_HEAVY_RENDERING, false)
//        if (isActive && heavyRendering) {
//            waitUntilIdleOrTimeout(timeToWait)
//        }

        val nonSynchronized = onlineDrivers.toList().also { onlineDrivers.clear() }
        nonSynchronized.parallelStream().forEach { it.cancel() }

        waitUntilIdle(timeToWait)

        closeAllDrivers(nonSynchronized)

        driverFactory.close()
    }

    private fun closeAllDrivers(drivers: List<WebDriver>) {
        val i = AtomicInteger()
        val total = drivers.size
        drivers.parallelStream().forEach { driver ->
            driver.quit().also { counterQuit.inc() }
            logger.debug("Quit driver {}/{} | {}", i.incrementAndGet(), total, driver)
        }
    }

    @Synchronized
    private fun waitUntilIdle(timeout: Duration) {
        var ttl = timeout.seconds
        try {
            while (ttl-- > 0 && numWorking.get() > 0) {
                lock.withLock { notBusy.await(1, TimeUnit.SECONDS) }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun logDriverOnline(driver: WebDriver) {
        val driverSettings = driverFactory.driverSettings
        logger.trace("The {}th web driver is online, browser: {} pageLoadStrategy: {} capacity: {}",
            numOnline, driver.name,
            driverSettings.pageLoadStrategy, capacity)
    }
}
