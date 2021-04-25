package ai.platon.pulsar

import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.AppPaths.WEB_CACHE_DIR
import ai.platon.pulsar.common.BeanFactory
import ai.platon.pulsar.common.IllegalApplicationContextStateException
import ai.platon.pulsar.common.concurrent.ExpiringItem
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.options.LoadOptions
import ai.platon.pulsar.common.urls.NormUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.common.urls.Urls
import ai.platon.pulsar.context.support.AbstractPulsarContext
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.dom.select.appendSelectorIfMissing
import ai.platon.pulsar.dom.select.firstTextOrNull
import ai.platon.pulsar.dom.select.selectFirstOrNull
import ai.platon.pulsar.persist.WebPage
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by vincent on 18-1-17.
 * Copyright @ 2013-2017 Platon AI. All rights reserved
 */
abstract class AbstractPulsarSession(
    /**
     * The pulsar context
     * */
    override val context: AbstractPulsarContext,
    /**
     * The session scope volatile config, every setting is supposed to be changed at any time and any place
     * */
    override val sessionConfig: VolatileConfig,
    /**
     * The session id. Session id is expected to be set by the container, e.g. the h2 database runtime
     * */
    override val id: Int
) : PulsarSession {

    companion object {
        const val ID_CAPACITY = 1_000_000
        const val ID_START = 1_000_000
        const val ID_END = ID_START + ID_CAPACITY - 1

        private val idGen = AtomicInteger()
        fun generateNextId() = ID_START + idGen.incrementAndGet()
    }

    private val log = LoggerFactory.getLogger(AbstractPulsarSession::class.java)

    override val unmodifiedConfig get() = context.unmodifiedConfig

    /**
     * The scoped bean factory: for each volatileConfig object, there is a bean factory
     * TODO: session scoped?
     * */
    override val sessionBeanFactory = BeanFactory(sessionConfig)

    override val display get() = "$id"

    private val closed = AtomicBoolean()
    val isActive get() = !closed.get() && context.isActive

    private val variables = ConcurrentHashMap<String, Any>()
    private var enableCache = true
    override val pageCache get() = context.globalCache.pageCache
    override val documentCache get() = context.globalCache.documentCache
    private val closableObjects = mutableSetOf<AutoCloseable>()

    /**
     * Close objects when sessions closes
     * */
    override fun registerClosable(closable: AutoCloseable) = ensureActive { closableObjects.add(closable) }

    override fun disableCache() = run { enableCache = false }

    /**
     * Create a new options, with a new volatile config
     * */
    override fun options(args: String) = LoadOptions.parse(args, sessionConfig.toVolatileConfig())

    override fun normalize(url: String, options: LoadOptions, toItemOption: Boolean) =
        context.normalize(url, options, toItemOption)

    override fun normalizeOrNull(url: String?, options: LoadOptions, toItemOption: Boolean) =
        context.normalizeOrNull(url, options, toItemOption)

    override fun normalize(urls: Iterable<String>, options: LoadOptions, toItemOption: Boolean) =
        context.normalize(urls, options, toItemOption).onEach { it.options }

    override fun normalize(url: UrlAware, options: LoadOptions, toItemOption: Boolean) =
        context.normalize(url, options, toItemOption)

    override fun normalizeOrNull(url: UrlAware?, options: LoadOptions, toItemOption: Boolean) =
        context.normalizeOrNull(url, options, toItemOption)

    override fun normalize(urls: Collection<UrlAware>, options: LoadOptions, toItemOption: Boolean) =
        context.normalize(urls, options, toItemOption).onEach { it.options }

    /**
     * Inject a url
     *
     * @param url The url followed by options
     * @return The web page created
     */
    override fun inject(url: String): WebPage = ensureActive { context.inject(normalize(url)) }

    override fun get(url: String): WebPage = ensureActive { context.get(url) }

    override fun getOrNull(url: String): WebPage? = ensureActive { context.getOrNull(url) }

    override fun exists(url: String): Boolean = ensureActive { context.exists(url) }

    /**
     * Load a url with specified options
     *
     * @param url     The url to load
     * @param args The load args
     * @return The web page
     */
    @Throws(Exception::class)
    override fun load(url: String, args: String): WebPage = load(url, options(args))

    /**
     * Load a url with specified options
     *
     * @param url     The url to load
     * @param options The load options
     * @return The web page
     */
    @Throws(Exception::class)
    override fun load(url: String, options: LoadOptions): WebPage = load(normalize(url, options))

    @Throws(Exception::class)
    override fun load(url: UrlAware, args: String): WebPage = load(normalize(url, options(args)))

    @Throws(Exception::class)
    override fun load(url: UrlAware, options: LoadOptions): WebPage = load(normalize(url, options))

    @Throws(Exception::class)
    override fun load(normUrl: NormUrl): WebPage {
        ensureActive()

        if (enableCache) {
            val (url, options) = normUrl
            val now = Instant.now()
            return pageCache.getDatum(url, options.expires, now)
                ?: context.load(normUrl).also { pageCache.put(url, ExpiringItem(it, now)) }
        }

        return context.load(normUrl)
    }

    @Throws(Exception::class)
    override suspend fun loadDeferred(url: String, options: LoadOptions) = loadDeferred(normalize(url, options))

    @Throws(Exception::class)
    override suspend fun loadDeferred(url: UrlAware, args: String): WebPage =
        loadDeferred(normalize(url, options(args)))

    @Throws(Exception::class)
    override suspend fun loadDeferred(url: UrlAware, options: LoadOptions): WebPage =
        loadDeferred(normalize(url, options))

    @Throws(Exception::class)
    override suspend fun loadDeferred(normUrl: NormUrl): WebPage {
        if (enableCache) {
            val (url, options) = normUrl
            val now = Instant.now()
            return pageCache.getDatum(url, options.expires, now)
                ?: context.loadDeferred(normUrl).also { pageCache.put(url, ExpiringItem(it, now)) }
        }

        return context.loadDeferred(normUrl)
    }

    /**
     * Load all urls with specified options, this may cause a parallel fetching if required
     *
     * @param urls    The urls to load
     * @param options The load options for all urls
     * @return The web pages
     */
    override fun loadAll(urls: Iterable<String>, options: LoadOptions, areItems: Boolean): Collection<WebPage> {
        ensureActive()
        val normUrls = normalize(urls, options, areItems)
        val opts = normUrls.firstOrNull()?.options ?: return listOf()

        return if (enableCache) {
            loadAllWithCache(normUrls, opts)
        } else {
            context.loadAll(normUrls, opts)
        }
    }

    /**
     * Load all urls with specified options, this causes a parallel fetching whenever applicable
     *
     * @param urls    The urls to load
     * @param options The load options
     * @return The web pages
     */
    override fun parallelLoadAll(urls: Iterable<String>, options: LoadOptions, areItems: Boolean): Collection<WebPage> {
        ensureActive()
        options.preferParallel = true
        val normUrls = normalize(urls, options, areItems)
        val opt = normUrls.firstOrNull()?.options ?: return listOf()

        return if (enableCache) {
            loadAllWithCache(normUrls, opt)
        } else {
            context.loadAll(normUrls, opt)
        }
    }

    /**
     * Load all out pages in a portal page
     *
     * @param portalUrl    The portal url from where to load pages
     * @param args         The load args
     * @return The web pages
     */
    override fun loadOutPages(portalUrl: String, args: String) = loadOutPages(portalUrl, options(args))

    /**
     * Load all out pages in a portal page
     *
     * @param portalUrl    The portal url from where to load pages
     * @param options The load options
     * @return The web pages
     */
    override fun loadOutPages(portalUrl: String, options: LoadOptions): Collection<WebPage> {
        val outlinkSelector = appendSelectorIfMissing(options.outLinkSelector, "a")

        val normUrl = normalize(portalUrl, options)

        val opts = normUrl.options
        val links = loadDocument(normUrl).select(outlinkSelector) {
            parseLink(it, !opts.noNorm, opts.ignoreUrlQuery)?.substringBeforeLast("#")
        }.mapNotNullTo(mutableSetOf()) { it }.take(opts.topLinks)

        return loadAll(links, normUrl.options.createItemOptions())
    }

    /**
     * Parse the Web page into DOM.
     * If the Web page is not changed since last parse, use the last result if available
     */
    override fun parse(page: WebPage, noCache: Boolean) = parse0(page, noCache)

    override fun loadDocument(url: String, args: String) = loadDocument(url, options(args))

    override fun loadDocument(url: String, options: LoadOptions): FeaturedDocument {
        ensureActive()
        val normUrl = normalize(url, options)
        return parse(load(normUrl))
    }

    override fun loadDocument(normUrl: NormUrl) = parse(load(normUrl))

    override fun scrape(url: String, args: String, fieldCss: Iterable<String>): Map<String, String?> {
        val document = loadDocument(url, args)
        return fieldCss.associateWith { document.selectFirstOrNull(it)?.text() }
    }

    override fun scrape(url: String, args: String, fieldCss: Map<String, String>): Map<String, String?> {
        val document = loadDocument(url, args)
        return fieldCss.entries.associate { it.key to document.selectFirstOrNull(it.value)?.text() }
    }

    override fun scrape(
        url: String,
        args: String,
        restrictCss: String,
        fieldCss: Iterable<String>
    ): List<Map<String, String?>> {
        return loadDocument(url, args).select(restrictCss).map { ele ->
            fieldCss.associateWith { ele.selectFirstOrNull(it)?.text() }
        }
    }

    override fun scrape(
        url: String,
        args: String,
        restrictCss: String,
        fieldCss: Map<String, String>
    ): List<Map<String, String?>> {
        return loadDocument(url, args).select(restrictCss).map { ele ->
            fieldCss.entries.associate { it.key to ele.selectFirstOrNull(it.value)?.text() }
        }
    }

    override fun scrapeOutPages(portalUrl: String, args: String, fieldsCss: Iterable<String>) =
        scrapeOutPages(portalUrl, args, ":root", fieldsCss)

    override fun scrapeOutPages(
        portalUrl: String,
        args: String, restrictCss: String, fieldsCss: Iterable<String>
    ): List<Map<String, String?>> {
        return loadOutPages(portalUrl, args).asSequence().map { parse(it) }
            .mapNotNull { it.selectFirstOrNull(restrictCss) }
            .map { ele -> fieldsCss.associateWith { ele.firstTextOrNull(it) } }
            .toList()
    }

    override fun scrapeOutPages(portalUrl: String, args: String, fieldsCss: Map<String, String>) =
        scrapeOutPages(portalUrl, args, ":root", fieldsCss)

    override fun scrapeOutPages(
        portalUrl: String,
        args: String, restrictCss: String, fieldsCss: Map<String, String>
    ): List<Map<String, String?>> {
        return loadOutPages(portalUrl, args).asSequence().map { parse(it) }
            .mapNotNull { it.selectFirstOrNull(restrictCss) }
            .map { ele -> fieldsCss.entries.associate { it.key to ele.firstTextOrNull(it.value) } }
            .toList()
    }

    override fun cache(page: WebPage): WebPage = page.also { pageCache.putDatum(it.url, it) }
    override fun disableCache(page: WebPage): WebPage? = pageCache.remove(page.url)?.datum

    override fun cache(doc: FeaturedDocument): FeaturedDocument = doc.also { documentCache.putDatum(it.baseUri, it) }
    override fun disableCache(doc: FeaturedDocument): FeaturedDocument? = documentCache.remove(doc.baseUri)?.datum

    override fun disableCache(url: String): WebPage? {
        documentCache.remove(url)
        return pageCache.remove(url)?.datum
    }

    override fun getVariable(name: String): Any? = let { variables[name] }

    override fun setVariable(name: String, value: Any) = run { variables[name] = value }

    override fun putSessionBean(obj: Any) = ensureActive { sessionBeanFactory.putBean(obj) }

    inline fun <reified T> getSessionBean(): T? = sessionBeanFactory.getBean()

    override fun delete(url: String) = ensureActive { context.delete(url) }

    override fun flush() = ensureActive { context.webDb.flush() }

    override fun persist(page: WebPage) = ensureActive { context.webDb.put(page) }

    override fun export(page: WebPage, ident: String): Path {
        ensureActive()
        val filename = AppPaths.fromUri(page.url, "", ".htm")
        val path = WEB_CACHE_DIR.resolve("export").resolve(ident).resolve(filename)
        return AppFiles.saveTo(page.contentAsString, path, true)
    }

    override fun export(doc: FeaturedDocument, ident: String): Path {
        ensureActive()
        val filename = AppPaths.fromUri(doc.baseUri, "", ".htm")
        val path = WEB_CACHE_DIR.resolve("export").resolve(ident).resolve(filename)
        return AppFiles.saveTo(doc.prettyHtml, path, true)
    }

    override fun exportTo(doc: FeaturedDocument, path: Path): Path {
        ensureActive()
        return AppFiles.saveTo(doc.prettyHtml.toByteArray(), path, true)
    }

    override fun equals(other: Any?) = other === this || (other is AbstractPulsarSession && other.id == id)

    override fun hashCode(): Int = id

    override fun toString(): String = "#$id"

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closableObjects.forEach { o -> o.close() }
            log.info("Session is closed | #{}", display)
        }
    }

    private fun loadAllWithCache(urls: Iterable<NormUrl>, options: LoadOptions): Collection<WebPage> {
        ensureActive()

        val pages = ArrayList<WebPage>()
        val pendingUrls = ArrayList<NormUrl>()

        val now = Instant.now()
        for (url in urls) {
            val page = pageCache.getDatum(url.spec, options.expires, now)
            if (page != null) {
                pages.add(page)
            } else {
                pendingUrls.add(url)
            }
        }

        val freshPages = if (options.preferParallel) {
            context.parallelLoadAll(pendingUrls, options)
        } else {
            context.loadAll(pendingUrls, options)
        }

        pages.addAll(freshPages)

        // Notice: we do not cache batch loaded pages, batch loaded pages are not used frequently
        // do not do this: sessionCachePutAll(freshPages);

        return pages
    }

    private fun parse0(page: WebPage, noCache: Boolean = false): FeaturedDocument {
        ensureActive()

        if (page.isNil) {
            return FeaturedDocument.NIL
        }

        if (noCache || !enableCache) {
            return context.parse(page)
        }

        return documentCache.computeIfAbsent(page.url) { context.parse(page) }
    }

    private fun parseLink(ele: Element, normalize: Boolean = false, ignoreQuery: Boolean = false): String? {
        var link = ele.attr("abs:href").takeIf { it.startsWith("http") } ?: return null
        if (normalize) {
            link = normalizeOrNull(link)?.spec ?: return null
        }

        return link.takeUnless { ignoreQuery } ?: Urls.getUrlWithoutParameters(link)
    }

    private fun ensureActive() {
        if (!isActive) {
            throw IllegalApplicationContextStateException("Pulsar session $this is not active")
        }
    }

    private fun <T> ensureActive(action: () -> T): T =
        if (isActive) action() else throw IllegalApplicationContextStateException("Pulsar session is not alive")

    private fun <T> ensureActive(defaultValue: T, action: () -> T): T = defaultValue.takeIf { !isActive } ?: action()
}