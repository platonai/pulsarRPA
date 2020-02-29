package ai.platon.pulsar.proxy

import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.RuntimeUtils
import ai.platon.pulsar.common.SimpleLogger
import ai.platon.pulsar.common.StringUtil
import ai.platon.pulsar.common.config.AppConstants.*
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyException
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyPool
import com.github.monkeywie.proxyee.exception.HttpProxyExceptionHandle
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline
import com.github.monkeywie.proxyee.intercept.common.FullRequestIntercept
import com.github.monkeywie.proxyee.intercept.common.FullResponseIntercept
import com.github.monkeywie.proxyee.proxy.ProxyConfig
import com.github.monkeywie.proxyee.proxy.ProxyType
import com.github.monkeywie.proxyee.server.HttpProxyServer
import com.github.monkeywie.proxyee.server.HttpProxyServerConfig
import io.netty.channel.Channel
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.util.ResourceLeakDetector
import org.slf4j.LoggerFactory
import org.springframework.util.SocketUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

class ProxyManager(
        private val proxyPool: ProxyPool,
        private val conf: ImmutableConfig
): AutoCloseable {
    private val log = LoggerFactory.getLogger(ProxyManager::class.java)

    private val numBossGroupThreads = conf.getInt(PROXY_INTERNAL_SERVER_BOSS_THREADS, 1)
    private val numWorkerGroupThreads = conf.getInt(PROXY_INTERNAL_SERVER_WORKER_THREADS, 2)
    private val httpProxyServerConfig = HttpProxyServerConfig()
    private var forwardServer: HttpProxyServer? = null
    private var forwardServerThread: Thread? = null
    private val threadJoinTimeout = Duration.ofSeconds(30)

    private val watcherThread = Thread(this::startWatcher)
    private val watcherStarted = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val connected = AtomicBoolean()
    private val connectionLock: Lock = ReentrantLock()
    private val connectionCond: Condition = connectionLock.newCondition()
    private val conditionPollingInterval = Duration.ofMillis(100)
    private val conditionTimeout = Duration.ofSeconds(30)

    private var idleTimeout = conf.getDuration(PROXY_INTERNAL_SERVER_IDLE_TIMEOUT, Duration.ofMinutes(5))
    private var idleCount = 0

    private var numTotalConnects = 0
    private val numRunningTasks = AtomicInteger()
    private var lastActiveTime = Instant.now()
    private var idleTime = Duration.ZERO
    private val isConnected get() = connected.get()
    private val isClosed get() = closed.get()

    val isEnabled get() = ProxyPool.isProxyEnabled() && conf.getBoolean(PROXY_ENABLE_INTERNAL_SERVER, true)
    val isDisabled get() = !isEnabled
    var port = -1
        private set
    var report: String = ""
    var verbose = false
    var autoRefresh = true
    val isWatcherStarted get() = watcherStarted.get()

    var lastProxyEntry: ProxyEntry? = null
        private set
    var currentProxyEntry: ProxyEntry? = null
        private set

    init {
        httpProxyServerConfig.bossGroupThreads = numBossGroupThreads
        httpProxyServerConfig.workerGroupThreads = numWorkerGroupThreads
        httpProxyServerConfig.isHandleSsl = false
    }

    @Synchronized
    fun start() {
        if (isDisabled) {
            log.warn("Proxy manager is disabled")
            return
        }

        if (watcherStarted.compareAndSet(false, true)) {
            watcherThread.isDaemon = true
            watcherThread.name = "pm"
            watcherThread.start()
        }
    }

    /**
     * Run the task despite the proxy manager is disabled, it it's disabled, call the innovation directly
     * */
    fun <R> runAnyway(task: () -> R): R {
        return if (isDisabled) {
            task()
        } else {
            run(task)
        }
    }

    /**
     * Run the task in the proxy manager
     * */
    fun <R> run(task: () -> R): R {
        if (isClosed || isDisabled) {
            throw ProxyException("Proxy manager is " + if (isClosed) "closed" else "disabled")
        }

        idleTime = Duration.ZERO

        if (!ensureAvailable()) {
            throw ProxyException("Failed to wait for proxy manager to be available")
        }

        return try {
            numRunningTasks.incrementAndGet()
            task()
        } catch (e: Exception) {
            throw e
        } finally {
            lastActiveTime = Instant.now()
            numRunningTasks.decrementAndGet()
        }
    }

    fun ensureAvailable(): Boolean {
        if (isDisabled || isClosed) {
            return false
        }

        connectionLock.withLock {
            if (isConnected) {
                return true
            }

            log.info("Waiting for proxy manager to connect ...")

            try {
                var signaled = false
                var round = 0
                val maxRound = conditionTimeout.toMillis() / conditionPollingInterval.toMillis()
                while (!isClosed && !isConnected && !signaled && round++ < maxRound) {
                    signaled = connectionCond.await(conditionPollingInterval.toMillis(), TimeUnit.MILLISECONDS)
                }

                if (!signaled && !isClosed) {
                    log.warn("Timeout to wait for proxy manager to be ready after $round round")
                }
            } catch (e: InterruptedException) {
                log.warn("Interrupted to waiting for proxy manager")
                Thread.currentThread().interrupt()
            }
        }

        return !isClosed && isConnected
    }

    fun changeProxyIfRunning(excludedProxy: ProxyEntry) {
        if (isDisabled || isClosed) {
            return
        }

        if (!ensureAvailable()) {
            return
        }

        if (excludedProxy == this.currentProxyEntry) {
            tryConnectToNext()
        }
    }

    private fun startWatcher() {
        var tick = 0
        while (!isClosed && tick++ < Int.MAX_VALUE && !Thread.currentThread().isInterrupted) {
            try {
                checkAndReport(tick)

                try {
                    TimeUnit.SECONDS.sleep(1)
                } catch (e: InterruptedException) {
                    log.info("Proxy watcher loop is interrupted after {} rounds", tick)
                    Thread.currentThread().interrupt()
                }
            } catch (e: Throwable) {
                log.error("Unexpected proxy manager error: ", e)
            }
        }

        if (isClosed) {
            log.info("Quit proxy manager loop on close after {} rounds", tick)
        } else {
            log.error("Quit proxy manager loop abnormally after {} rounds", tick)
        }
    }

    private fun checkAndReport(tick: Int) {
        // Wait for 5 seconds
        if (tick % 5 != 0) {
            return
        }

        if (RuntimeUtils.hasLocalFileCommand(CMD_INTERNAL_PROXY_SERVER_DISCONNECT, Duration.ofSeconds(15))) {
            log.info("Find fcmd $CMD_INTERNAL_PROXY_SERVER_DISCONNECT, disconnect proxy")
            disconnect(true)
            return
        }

        val lastProxy = currentProxyEntry
        val availability = checkAvailability()

        // always false, feature disabled
        val isIdle = isIdle()
        if (isIdle) {
            if (availability.first && lastProxy != null) {
                proxyPool.retire(lastProxy)
                // all free proxies are very likely be expired
                log.info("Proxy manager is idle, clear proxy pool")
                proxyPool.clear()
            }

            log.info("Proxy manager is idle, disconnect proxy")
            disconnect(true)
        } else {
            if (!availability.first || !isConnected) {
                tryConnectToNext()
            }
        }

        idleCount = if (isIdle) idleCount++ else 0
        val duration = min(20 + idleCount / 5, 120)
        if (tick % duration == 0) {
            report(isIdle, availability.first, lastProxy)
            if (verbose) {
                log.info(report)
            }
        }
    }

    private fun checkAvailability(): Pair<Boolean, String> {
        if (!autoRefresh) {
            return true to ""
        }

        val lastProxy = currentProxyEntry
        val availability = when {
            lastProxy == null -> false to "no proxy"
            lastProxy.isTestIp -> true to "is test ip"
            lastProxy.willExpireAfter(Duration.ofMinutes(1)) -> false to "will expire"
            !lastProxy.test() -> false to "unreachable"
            else -> true to ""
        }

        if (!availability.first && lastProxy != null) {
            log.info("Proxy <{}> is retired ({})", lastProxy.display, availability.second)
            proxyPool.retire(lastProxy)
            currentProxyEntry = null
        }

        return availability
    }

    private fun isIdle(): Boolean {
        var isIdle = false
        if (numRunningTasks.get() == 0) {
            idleTime = Duration.between(lastActiveTime, Instant.now())
            if (idleTime > idleTimeout) {
                // do not waste the proxy resource, they are expensive!
                isIdle = true
            }

            if (RuntimeUtils.hasLocalFileCommand(CMD_INTERNAL_PROXY_SERVER_FORCE_IDLE, Duration.ZERO)) {
                isIdle = true
            }
        }
        return isIdle
    }

    private fun tryConnectToNext() {
        if (isClosed || isDisabled) {
            return
        }

        disconnect(notifyAll = false)

        val proxy = proxyPool.poll()
        if (proxy != null) {
            connectTo(proxy)
        }
    }

    @Synchronized
    private fun connectTo(proxy: ProxyEntry?) {
        if (isClosed || isDisabled) {
            return
        }

        if (isConnected) {
            log.warn("Proxy manager is already running")
            return
        }

        val nextPort = SocketUtils.findAvailableTcpPort(INTERNAL_PROXY_SERVER_PORT_BASE)
        if (log.isTraceEnabled) {
            log.trace("Ready to start proxy manager at {} with {}",
                    nextPort,
                    if (proxy != null) " <${proxy.display}>" else "no proxy")
        }

        try {
            val server = initForwardProxyServer(proxy)
            val thread = Thread { server.start(nextPort) }
            thread.isDaemon = true
            thread.start()

            var i = 0
            while (!isClosed && !NetUtil.testNetwork("127.0.0.1", nextPort) && !Thread.currentThread().isInterrupted) {
                if (i++ > 3) {
                    log.warn("Waited {}s for proxy manager to start ...", i)
                }
                if (i > 20) {
                    disconnect(notifyAll = true)
                    throw TimeoutException("Timeout to wait for proxy to connect")
                }

                try {
                    TimeUnit.SECONDS.sleep(1)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            log.info("Proxy manager is started at {} with {}",
                    nextPort,
                    if (proxy != null) "external proxy <${proxy.display}>" else "no proxy")

            connectionLock.withLock {
                forwardServer = server
                forwardServerThread = thread
                port = nextPort
                lastProxyEntry = currentProxyEntry
                currentProxyEntry = proxy
                ++numTotalConnects

                connected.set(true)
            }
        } finally {
            connectionLock.withLock {
                connectionCond.signalAll()
            }
        }
    }

    @Synchronized
    private fun disconnect(notifyAll: Boolean = true) {
        connected.set(false)
        // notify all to exit waiting
        if (notifyAll) {
            connectionLock.withLock {
                connectionCond.signalAll()
            }
        }

        connectionLock.withLock {
            val server = forwardServer
            if (server != null) {
                log.info("Disconnecting proxy with {} ...", server.proxyConfig?.hostPort)
            }
            forwardServer?.use { it.close() }
            forwardServerThread?.interrupt()
            forwardServerThread?.join(threadJoinTimeout.toMillis())
            forwardServer = null
            forwardServerThread = null
        }
    }

    @Synchronized
    override fun close() {
        if (isDisabled) {
            return
        }

        if (closed.compareAndSet(false, true)) {
            log.info("Closing proxy manager ...")

            try {
                disconnect(notifyAll = true)
                watcherThread.interrupt()
                watcherThread.join(threadJoinTimeout.toMillis())
            } catch (e: Throwable) {
                log.error("Failed to close proxy manager - {}", e)
            }
        }
    }

    private fun report(isIdle: Boolean, available: Boolean, lastProxy: ProxyEntry? = null) {
        report = String.format("%s%s running tasks, %s | %s",
                if (isIdle) "[Idle] " else "",
                numRunningTasks,
                formatProxy(available, lastProxy),
                proxyPool)
    }

    private fun formatProxy(proxyAlive: Boolean, lastProxy: ProxyEntry?): String {
        if (lastProxy != null) {
            return "proxy: ${lastProxy.display}" + if (proxyAlive) "" else "(retired)"
        }

        return "proxy: <none>"
    }

    private fun initForwardProxyServer(externalProxy: ProxyEntry?): HttpProxyServer {
        val server = HttpProxyServer()
        server.serverConfig(httpProxyServerConfig)

        if (externalProxy != null) {
            val proxyConfig = ProxyConfig(ProxyType.HTTP, externalProxy.host, externalProxy.port)
            server.proxyConfig(proxyConfig)
        }

        server.proxyInterceptInitializer(object : HttpProxyInterceptInitializer() {
            override fun init(pipeline: HttpProxyInterceptPipeline) {
                pipeline.addLast(object : FullRequestIntercept() {
                    override fun match(httpRequest: HttpRequest, pipeline: HttpProxyInterceptPipeline): Boolean {
                        return log.isTraceEnabled
                    }

                    override fun handelRequest(httpRequest: FullHttpRequest, pipeline: HttpProxyInterceptPipeline) {
                        val message = String.format("Ready to download %s", httpRequest.headers())
                        PROXY_LOG.write(SimpleLogger.DEBUG, "[proxy]", message)
                    }
                })

                pipeline.addLast(object : FullResponseIntercept() {
                    override fun match(httpRequest: HttpRequest, httpResponse: HttpResponse, pipeline: HttpProxyInterceptPipeline): Boolean {
                        return log.isTraceEnabled
                    }

                    override fun handelResponse(httpRequest: HttpRequest, httpResponse: FullHttpResponse, pipeline: HttpProxyInterceptPipeline) {
                        val message = String.format("Got resource %s, %s", httpResponse.status(), httpResponse.headers())
                        PROXY_LOG.write(SimpleLogger.DEBUG, "[proxy]", message)
                    }
                })
            }
        })

        server.httpProxyExceptionHandle(object: HttpProxyExceptionHandle() {
            override fun beforeCatch(clientChannel: Channel, cause: Throwable) {
                // log.warn("Internal proxy error - {}", StringUtil.stringifyException(cause))
            }

            override fun afterCatch(clientChannel: Channel, proxyChannel: Channel, cause: Throwable) {
                var message = cause.message
                when (cause) {
                    is io.netty.handler.proxy.ProxyConnectException -> {
                        // TODO: handle io.netty.handler.proxy.ProxyConnectException: http, none, /117.69.129.113:4248 => img59.ddimg.cn:80, disconnected
                        message = StringUtil.simplifyException(cause)
                    }
                }

                if (message == null) {
                    log.warn(StringUtil.stringifyException(cause))
                    return
                }

                // log.warn(StringUtil.simplifyException(cause))
                PROXY_LOG.write(SimpleLogger.WARN, javaClass, message)
            }
        })

        // ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.ADVANCED)
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.SIMPLE)

        return server
    }

    companion object {
        val PROXY_LOG = SimpleLogger(HttpProxyServer.PATH, SimpleLogger.INFO)
    }
}

fun main() {
    val conf = ImmutableConfig()
    val proxyPool = ProxyPool(conf)
    val manager = ProxyManager(proxyPool, conf)
    manager.start()
}
