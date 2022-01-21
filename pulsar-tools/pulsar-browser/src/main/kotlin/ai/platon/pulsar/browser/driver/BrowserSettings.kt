package ai.platon.pulsar.browser.driver

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.Systems
import ai.platon.pulsar.common.Wildchar
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import com.github.kklisura.cdt.protocol.types.network.ResourceType
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.random.Random

/**
 * The chrome display mode
 * SUPERVISED: supervised by other programs
 * GUI: open as a normal browser
 * HEADLESS: open in headless mode
 * */
enum class DisplayMode { SUPERVISED, GUI, HEADLESS }

/**
 * The emulate settings
 * */
data class EmulateSettings(
    var scrollCount: Int = 10,
    var scrollInterval: Duration = Duration.ofMillis(500),
    var scriptTimeout: Duration = Duration.ofMinutes(1),
    var pageLoadTimeout: Duration = Duration.ofMinutes(3)
) {
    constructor(conf: ImmutableConfig) : this(
        scrollCount = conf.getInt(FETCH_SCROLL_DOWN_COUNT, 5),
        scrollInterval = conf.getDuration(FETCH_SCROLL_DOWN_INTERVAL, Duration.ofMillis(500)),
        scriptTimeout = conf.getDuration(FETCH_SCRIPT_TIMEOUT, Duration.ofMinutes(1)),
        pageLoadTimeout = conf.getDuration(FETCH_PAGE_LOAD_TIMEOUT, Duration.ofMinutes(3)),
    )

    fun apply() {
        Systems.setProperty(FETCH_SCROLL_DOWN_COUNT, scrollCount)
        Systems.setProperty(FETCH_SCROLL_DOWN_INTERVAL, scrollInterval)
        Systems.setProperty(FETCH_SCRIPT_TIMEOUT, scriptTimeout)
        Systems.setProperty(FETCH_PAGE_LOAD_TIMEOUT, pageLoadTimeout)
    }

    fun apply(conf: MutableConfig) {
        conf.setInt(FETCH_SCROLL_DOWN_COUNT, scrollCount)
        conf.setDuration(FETCH_SCROLL_DOWN_INTERVAL, scrollInterval)
        conf.setDuration(FETCH_SCRIPT_TIMEOUT, scriptTimeout)
        conf.setDuration(FETCH_PAGE_LOAD_TIMEOUT, pageLoadTimeout)
    }

    companion object {
        val DEFAULT = EmulateSettings()

        var goodNetSettings = EmulateSettings()

        var worseNetSettings = EmulateSettings(
            scrollCount = 10,
            scrollInterval = Duration.ofSeconds(1),
            scriptTimeout = Duration.ofMinutes(2),
            Duration.ofMinutes(3),
        )

        var worstNetSettings = EmulateSettings(
            scrollCount = 15,
            scrollInterval = Duration.ofSeconds(3),
            scriptTimeout = Duration.ofMinutes(3),
            Duration.ofMinutes(4),
        )
    }
}

/**
 * The block rules of urls and resources
 * */
open class BlockRules {

    open val blockingResourceTypes = listOf(ResourceType.IMAGE, ResourceType.MEDIA, ResourceType.FONT).toMutableList()

    /**
     * amazon.com note:
     * The following have to pass, or the site refuses to serve:
     * .woff,
     * .mp4
     * */
    open val mustPassUrls = mutableListOf<String>()

    /**
     * Blocking urls patten using widcards
     * */
    open val blockingUrls = listOf(
        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.ico", "*.webp",
        "*.woff", "*.woff2",
        "*.mp4", "*.svg",
        "*.png?*", "*.jpg?*", "*.gif?*", "*.ico?*", "*.webp?*",
        "https://img*"
    ).filterNot { it in mustPassUrls }.toMutableList()

    open val mustPassUrlPatterns
        get() = listOf(
            "about:blank",
            "data:.+",
        ).map { it.toRegex() }.union(mustPassUrls.map { Wildchar(it).toRegex() }).toMutableList()

    open val blockingUrlPatterns get() = blockingUrls.map { Wildchar(it).toRegex() }.toMutableList()
}

open class BrowserSettings(
    parameters: Map<String, Any> = mapOf(),
    var jsDirectory: String = "js",
    val conf: ImmutableConfig = ImmutableConfig()
) {
    companion object {
        // required
        var viewPort = AppConstants.DEFAULT_VIEW_PORT

        fun withGoodNetwork(): Companion {
            return BrowserSettings
        }

        fun withWorseNetwork(): Companion {
            EmulateSettings.worseNetSettings.apply()
            return BrowserSettings
        }

        fun withWorstNetwork(): Companion {
            EmulateSettings.worstNetSettings.apply()
            return BrowserSettings
        }

        fun withGUI(): Companion {
            arrayOf(BROWSER_LAUNCH_SUPERVISOR_PROCESS, BROWSER_LAUNCH_SUPERVISOR_PROCESS_ARGS).forEach {
                System.clearProperty(it)
            }
            System.setProperty(BROWSER_DISPLAY_MODE, DisplayMode.GUI.name)
            return BrowserSettings
        }

        fun enableUrlBlocking(): Companion {
            System.setProperty(BROWSER_ENABLE_URL_BLOCKING, "true")
            return BrowserSettings
        }

        fun blockImages(): Companion {
            enableUrlBlocking()
            return BrowserSettings
        }

        fun generateUserDataDir(): Path {
            val numInstances = Files.list(AppPaths.BROWSER_TMP_DIR).filter { Files.isDirectory(it) }.count().inc()
            val rand = Random.nextInt(0, 1000000).toString(Character.MAX_RADIX)
            return AppPaths.BROWSER_TMP_DIR.resolve("br.$numInstances$rand")
        }
    }

    val supervisorProcess get() = conf.get(BROWSER_LAUNCH_SUPERVISOR_PROCESS)
    val supervisorProcessArgs get() = conf.getTrimmedStringCollection(BROWSER_LAUNCH_SUPERVISOR_PROCESS_ARGS)
    /**
     * Add a --no-sandbox flag to launch the chrome if we are running inside a virtual machine,
     * for example, virtualbox, vmware or WSL
     * */
    val noSandbox get() = conf.getBoolean(BROWSER_LAUNCH_NO_SANDBOX, false)

    val displayMode get() = conf.getEnum(BROWSER_DISPLAY_MODE, DisplayMode.GUI)
    val isSupervised get() = supervisorProcess != null && displayMode == DisplayMode.SUPERVISED
    val isHeadless get() = displayMode == DisplayMode.HEADLESS
    val isGUI get() = displayMode == DisplayMode.GUI

    val eagerAllocateTabs get() = conf.getBoolean(BROWSER_EAGER_ALLOCATE_TABS, false)
    val imagesEnabled get() = conf.getBoolean(BROWSER_IMAGES_ENABLED, false)
    val jsInvadingEnabled get() = conf.getBoolean(BROWSER_JS_INVADING_ENABLED, true)
    val userDataDir get() = conf.getPathOrNull(BROWSER_DATA_DIR) ?: generateUserDataDir()
    val enableUrlBlocking get() = conf.getBoolean(BROWSER_ENABLE_URL_BLOCKING, false)

    // We will wait for document ready manually using javascript
    var pageLoadStrategy = "none"

    // The javascript to execute by Web browsers
    val propertyNames
        get() = conf.getTrimmedStrings(
            FETCH_CLIENT_JS_COMPUTED_STYLES,
            AppConstants.CLIENT_JS_PROPERTY_NAMES
        )

    var clientJsVersion = "0.2.3"
    val scripts = mutableMapOf<String, String>()

    // Available user agents
    val userAgents = mutableListOf<String>()

    private val jsParameters = mutableMapOf<String, Any>()
    private var mainJs = ""
    private var libJs = ""

    constructor(immutableConfig: ImmutableConfig) : this(mapOf(), "js", immutableConfig)

    init {
        mapOf(
            "propertyNames" to propertyNames,
            "viewPortWidth" to viewPort.width,
            "viewPortHeight" to viewPort.height
        ).also { jsParameters.putAll(it) }

        jsParameters.putAll(parameters)

        generateUserAgents()

        loadDefaultResource()
    }

    fun formatViewPort(delimiter: String = ","): String {
        return "${viewPort.width}$delimiter${viewPort.height}"
    }

    // also see https://github.com/arouel/uadetector
    fun generateUserAgents() {
        if (userAgents.isNotEmpty()) return

        ResourceLoader.readAllLines("ua/chrome-user-agents-linux.txt")
            .filter { it.startsWith("Mozilla/5.0") }
            .toCollection(userAgents)
    }

    fun buildChromeUserAgent(
        mozilla: String = "5.0",
        appleWebKit: String = "537.36",
        chrome: String = "70.0.3538.77",
        safari: String = "537.36"
    ): String {
        return "Mozilla/$mozilla (X11; Linux x86_64) AppleWebKit/$appleWebKit (KHTML, like Gecko) Chrome/$chrome Safari/$safari"
    }

    fun parseLibJs(reload: Boolean = false): String {
        if (reload || libJs.isEmpty()) {
            // Note: Json-2.6.2 does not recognize MutableMap, but knows Map
            val configs = GsonBuilder().create().toJson(jsParameters.toMap())

            val sb = StringBuilder()
            sb.append(";\n")
            // set predefined variables shared between javascript and jvm program
            sb.appendLine("let META_INFORMATION_ID = \"${AppConstants.PULSAR_META_INFORMATION_ID}\";")
            sb.appendLine("let SCRIPT_SECTION_ID = \"${AppConstants.PULSAR_SCRIPT_SECTION_ID}\";")
            sb.appendLine("let ATTR_HIDDEN = \"${AppConstants.PULSAR_ATTR_HIDDEN}\";")
            sb.appendLine("let ATTR_OVERFLOW_HIDDEN = \"${AppConstants.PULSAR_ATTR_OVERFLOW_HIDDEN}\";")
            sb.appendLine("let ATTR_OVERFLOW_VISIBLE = \"${AppConstants.PULSAR_ATTR_OVERFLOW_VISIBLE}\";")
            sb.appendLine("let PULSAR_CONFIGS = $configs;")
            scripts.values.joinTo(sb, ";\n")
            libJs = sb.toString()
        }

        return libJs
    }

    fun parseJs(reload: Boolean = false): String {
        if (reload || mainJs.isEmpty()) {
            val sb = StringBuilder(parseLibJs(reload))
            sb.append(";\n__utils__.emulate();")
            sb.append(";\nreturn JSON.stringify(document.pulsarData);")
            mainJs = sb.toString()
        }

        return mainJs
    }

    fun randomUserAgent(): String {
        if (userAgents.isNotEmpty()) {
            return userAgents[Random.nextInt(userAgents.size)]
        }
        return ""
    }

    private fun loadDefaultResource() {
        arrayOf(
            "configs.js",
            "__utils__.js",
            "node_ext.js",
            "node_traversor.js",
            "feature_calculator.js",
            "humanize.js"
        ).associateTo(scripts) {
            it to ResourceLoader.readAllLines("$jsDirectory/$it").joinToString("\n") { it }
        }
    }
}
