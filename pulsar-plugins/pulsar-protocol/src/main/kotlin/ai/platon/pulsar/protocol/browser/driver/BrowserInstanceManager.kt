package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.browser.driver.chrome.common.ChromeOptions
import ai.platon.pulsar.browser.driver.chrome.common.LauncherOptions
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.crawl.fetch.privacy.BrowserInstanceId
import ai.platon.pulsar.persist.metadata.BrowserType
import ai.platon.pulsar.protocol.browser.driver.cdt.ChromeDevtoolsBrowserInstance
import ai.platon.pulsar.protocol.browser.driver.playwright.PlaywrightBrowserInstance
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BrowserInstanceManager: AutoCloseable {
    private val closed = AtomicBoolean()
    private val browserInstances = ConcurrentHashMap<String, BrowserInstance>()

    val instanceCount get() = browserInstances.size

    @Synchronized
    fun hasLaunched(userDataDir: String): Boolean {
        return browserInstances.containsKey(userDataDir)
    }

    @Synchronized
    fun launchIfAbsent(
        instanceId: BrowserInstanceId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): BrowserInstance {
        val userDataDir = instanceId.contextDir
        return browserInstances.computeIfAbsent(userDataDir.toString()) {
            createAndLaunch(instanceId, launcherOptions, launchOptions)
        }
    }

    @Synchronized
    fun closeIfPresent(dataDir: Path) {
        browserInstances.remove(dataDir.toString())?.close()
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            doClose()
        }
    }

    private fun createAndLaunch(
        instanceId: BrowserInstanceId, launcherOptions: LauncherOptions, launchOptions: ChromeOptions
    ): BrowserInstance {
        return when(instanceId.browserType) {
            BrowserType.PLAYWRIGHT_CHROME -> PlaywrightBrowserInstance(instanceId, launcherOptions, launchOptions)
            else -> ChromeDevtoolsBrowserInstance(instanceId, launcherOptions, launchOptions)
        }.apply { launch() }
    }

    private fun doClose() {
        kotlin.runCatching {
            val unSynchronized = browserInstances.values.toList()
            browserInstances.clear()

            getLogger(this).info("Closing {} browser instances", unSynchronized.size)
            unSynchronized.parallelStream().forEach { it.close() }
        }.onFailure {
            getLogger(this).warn("Failed to close | {}", it.message)
        }
    }
}
