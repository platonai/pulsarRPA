package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.common.UserAgent
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.Fingerprint
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.crawl.fetch.FetchResult
import ai.platon.pulsar.crawl.fetch.FetchTask
import ai.platon.pulsar.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.crawl.fetch.privacy.PrivacyContext
import ai.platon.pulsar.crawl.fetch.privacy.PrivacyContextId
import ai.platon.pulsar.crawl.fetch.privacy.SequentialPrivacyContextIdGenerator
import ai.platon.pulsar.persist.WebPageExt
import ai.platon.pulsar.protocol.browser.emulator.DefaultWebDriverPoolManager
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.runBlocking
import org.apache.commons.lang3.RandomStringUtils
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PrivacyContextManagerTests {
    private val contextPathBase = Files.createTempDirectory("test-")
    private val contextPath = contextPathBase.resolve("cx.5kDMDS2")
    private val contextPath2 = contextPathBase.resolve("cx.7KmtAC2")
    private val conf = ImmutableConfig()
    private val driverPoolManager = DefaultWebDriverPoolManager(conf)

    @BeforeTest
    fun setup() {
        System.setProperty(CapabilityTypes.PRIVACY_CONTEXT_ID_GENERATOR_CLASS, SequentialPrivacyContextIdGenerator::class.java.name)
        BrowserSettings.privacy(6).maxTabs(10)
    }

    @Test
    fun testPrivacyContextComparison() {
        val privacyManager = MultiPrivacyContextManager(driverPoolManager, conf)
        val fingerprint = Fingerprint(BrowserType.MOCK_CHROME)

        val pc = privacyManager.computeNextContext(fingerprint)
        assertTrue { pc.isActive }
        privacyManager.close(pc)
        assertTrue { !pc.isActive }
        assertFalse { privacyManager.activeContexts.containsKey(pc.id) }
        assertFalse { privacyManager.activeContexts.containsValue(pc) }

        val pc2 = privacyManager.computeNextContext(fingerprint)
        assertTrue { pc2.isActive }
        assertNotEquals(pc.id, pc2.id)
        assertNotEquals(pc, pc2)
        assertTrue { privacyManager.activeContexts.containsKey(pc2.id) }
        assertTrue { privacyManager.activeContexts.containsValue(pc2) }
    }

    @Test
    fun testPrivacyContextClosing() {
        val privacyManager = MultiPrivacyContextManager(driverPoolManager, conf)
        val userAgents = UserAgent()

        repeat(100) {
            val proxyServer = "127.0.0." + Random.nextInt(200)
            val userAgent = userAgents.getRandomUserAgent()
            val fingerprint = Fingerprint(BrowserType.PULSAR_CHROME, proxyServer, userAgent = userAgent)
            val pc = privacyManager.computeNextContext(fingerprint)
            assertTrue { pc.isActive }
            privacyManager.close(pc)
            assertTrue { !pc.isActive }
            assertFalse { privacyManager.activeContexts.containsKey(pc.id) }
            assertFalse { privacyManager.activeContexts.containsValue(pc) }
        }
    }

    @Test
    fun testPrivacyContextClosingConcurrently() {
        val privacyManager = MultiPrivacyContextManager(driverPoolManager, conf)
        val userAgents = UserAgent()

        val activeContexts = ConcurrentLinkedDeque<PrivacyContext>()
        val producer = Executors.newSingleThreadScheduledExecutor()
        val closer = Executors.newSingleThreadScheduledExecutor()

        producer.scheduleWithFixedDelay({
            val proxyServer = "127.0.0." + Random.nextInt(200)
            val userAgent = userAgents.getRandomUserAgent()
            val fingerprint = Fingerprint(BrowserType.MOCK_CHROME, proxyServer, userAgent = userAgent)
            val pc = privacyManager.computeNextContext(fingerprint)

            activeContexts.add(pc)
            assertTrue { pc.isActive }
        }, 1, 800, TimeUnit.MILLISECONDS)

        closer.scheduleWithFixedDelay({
            activeContexts.forEach { pc ->
                // proxy server can be changed, which will be improved in the further
                pc.id.fingerprint.proxyServer = "127.0.0." + Random.nextInt(200)

                privacyManager.close(pc)
                assertTrue { !pc.isActive }
                assertFalse { privacyManager.activeContexts.containsKey(pc.id) }
                assertFalse { privacyManager.activeContexts.containsValue(pc) }
            }
        }, 2, 1, TimeUnit.SECONDS)

        producer.awaitTermination(20, TimeUnit.SECONDS)
        closer.awaitTermination(20, TimeUnit.SECONDS)
    }

    @Test
    fun `When close a privacy context then it's removed from the active contexts queue`() {
        val manager = MultiPrivacyContextManager(driverPoolManager, conf)

        val id = PrivacyContextId(contextPath, BrowserType.MOCK_CHROME)
        val privacyContext = manager.computeIfAbsent(id)

        assertTrue { manager.activeContexts.containsKey(id) }
        manager.close(privacyContext)
        assertFalse { manager.activeContexts.containsKey(id) }
    }

    @Test
    fun `When run tasks the contexts rotates`() {
        val manager = MultiPrivacyContextManager(driverPoolManager, conf)
        val url = "about:blank"
        val page = WebPageExt.newTestWebPage(url)

        runBlocking {
            repeat(10) {
                val task = FetchTask.create(page)
                task.fingerprint.userAgent = RandomStringUtils.randomAlphanumeric(10)
                manager.run(task) { task, driver -> mockFetch(task, driver) }
                assertTrue { manager.activeContexts.size <= manager.maxAllowedBadContexts }
            }
        }
    }

    private suspend fun mockFetch(task: FetchTask, driver: WebDriver): FetchResult {
        return FetchResult.canceled(task)
    }
}