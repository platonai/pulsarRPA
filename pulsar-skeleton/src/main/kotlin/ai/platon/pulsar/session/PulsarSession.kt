package ai.platon.pulsar.session

import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.options.LoadOptions
import ai.platon.pulsar.common.urls.NormUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.context.PulsarContext
import ai.platon.pulsar.crawl.PageEvent
import ai.platon.pulsar.crawl.common.DocumentCatch
import ai.platon.pulsar.crawl.common.GlobalCache
import ai.platon.pulsar.crawl.common.PageCatch
import ai.platon.pulsar.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import com.google.common.annotations.Beta
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * [PulsarSession] defines an interface to load webpages from local storage or fetch from the Internet,
 * as well as methods for parsing, extracting, saving and exporting webpages.
 *
 * Key methods:
 *
 * * [load]: load a webpage from local storage, or fetch it from the Internet.
 * * [parse]: parse a webpage into a document.
 * * [scrape]: load a webpage, parse it into a document and then extract fields from the document.
 * * [submit]: submit a url to the URL pool, the url will be processed in the main loop later.
 *
 * And also the batch versions:
 *
 * * [loadOutPages]: load the portal page and out pages.
 * * [scrapeOutPages]: load the portal page and out pages, extract fields from out pages.
 *
 * The first thing to understand is how to load a page. Load methods like [load] first
 * check the local storage and return the local version if the required page exists and meets the
 * requirements, otherwise it will be fetched from the Internet.
 *
 * `Load parameters` or `load options` can be used to specify when the system will fetch a webpage
 * from the Internet:
 *
 * 1. Expiration
 * 2. Force refresh
 * 3. Page size
 * 4. Required fields
 * 5. Other conditions
 *
 * Once a page is loaded from local storage, or fetched from the Internet, we come to the next process steps:
 * 1. parse the page content into an HTML document
 * 2. extract fields from the HTML document
 * 3. write the extraction results to a destination, such as
 *    1. plain file, avro file, CSV, excel, mongodb, mysql, etc.
 *    2. solr, elastic, etc.
 *
 * There are many ways to fetch the content of a page from the Internet:
 * 1. through HTTP protocol
 * 2. through a real browser
 *
 * Since the webpages are becoming more and more complex, fetching webpages through
 * real browsers is the primary way nowadays.
 *
 * When we fetch webpages using a real browser, we need to interact with pages to
 * ensure the required fields are loaded correctly and completely. Enable [PageEvent]
 * and use [WebDriver] to archive such purpose.
 *
 * ```kotlin
 * val options = session.options(args)
 * options.event.browseEvent.onDocumentSteady.addLast { page, driver ->
 *   driver.fill("input[name='search']", "geek")
 *   driver.click("button[type='submit']")
 * }
 * session.load(url, options)
 * ```
 *
 * [WebDriver] provides a complete method set for RPA, just like selenium, playwright
 * and puppeteer does, all actions and behaviors are optimized to mimic real people as closely as possible.
 *
 * @see UrlAware
 * @see LoadOptions
 * @see WebPage
 * @see FeaturedDocument
 * @see PageEvent
 * @see WebDriver
 * */
interface PulsarSession : AutoCloseable {
    /**
     * The session id
     * */
    val id: Int
    /**
     * The pulsar context
     * */
    val context: PulsarContext
    /**
     * An immutable config which is loaded from the config file at process startup, it will never change once the
     * process is started.
     * */
    val unmodifiedConfig: ImmutableConfig
    /**
     * The session scope volatile config, every setting is supposed to be changed at any time and any place.
     * */
    val sessionConfig: VolatileConfig
    /**
     * A short descriptive display text.
     * */
    val display: String
    /**
     * The global page cache
     * */
    val pageCache: PageCatch
    /**
     * The global document cache
     * */
    val documentCache: DocumentCatch
    /**
     * The global cache
     * */
    val globalCache: GlobalCache
    /**
     * Disable page cache and document cache
     * */
    fun disablePDCache()
    
    @Deprecated("Inappropriate name", ReplaceWith("data(name)"))
    fun getVariable(name: String): Any? = data(name)
    
    @Deprecated("Inappropriate name", ReplaceWith("data(name, value)"))
    fun setVariable(name: String, value: Any) = data(name, value)
    
    /**
     * Get a variable from this session
     *
     * @param name The name of the variable
     * @return The value of the variable
     * */
    fun data(name: String): Any?
    /**
     * Set a variable into this session
     *
     * @param name The name of the variable
     * @param value The value of the variable
     * */
    fun data(name: String, value: Any)
    /**
     * Get a property.
     * */
    fun property(name: String): String?
    /**
     * Set a session scope property.
     * */
    fun property(name: String, value: String)
    /**
     * Create a new [LoadOptions] object with [args], [event], and [sessionConfig].
     * */
    fun options(args: String = "", event: PageEvent? = null): LoadOptions
    /**
     * Normalize a url.
     * */
    fun normalize(url: String): NormUrl
    /**
     * Normalize a url.
     * */
    fun normalize(url: String, args: String, toItemOption: Boolean = false): NormUrl
    /**
     * Normalize a url.
     * */
    fun normalize(url: String, options: LoadOptions, toItemOption: Boolean = false): NormUrl
    /**
     * Normalize a url.
     * */
    fun normalizeOrNull(url: String?, options: LoadOptions = options(), toItemOption: Boolean = false): NormUrl?
    /**
     * Normalize urls.
     * */
    fun normalize(urls: Iterable<String>): List<NormUrl>
    /**
     * Normalize urls.
     * */
    fun normalize(urls: Iterable<String>, args: String, toItemOption: Boolean = false): List<NormUrl>
    /**
     * Normalize urls.
     * */
    fun normalize(urls: Iterable<String>, options: LoadOptions, toItemOption: Boolean = false): List<NormUrl>
    /**
     * Normalize a url.
     * */
    fun normalize(url: UrlAware): NormUrl
    /**
     * Normalize a url.
     * */
    fun normalize(url: UrlAware, args: String, toItemOption: Boolean = false): NormUrl
    /**
     * Normalize a url.
     * */
    fun normalize(url: UrlAware, options: LoadOptions, toItemOption: Boolean = false): NormUrl
    /**
     * Normalize a url.
     * */
    fun normalizeOrNull(url: UrlAware?, options: LoadOptions = options(), toItemOption: Boolean = false): NormUrl?
    /**
     * Normalize urls.
     * */
    fun normalize(urls: Collection<UrlAware>): List<NormUrl>
    /**
     * Normalize urls, remove invalid ones
     *
     * @param urls The urls to normalize
     * @param args The arguments
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return All normalized urls, all invalid input urls are removed
     * */
    fun normalize(urls: Collection<UrlAware>, args: String, toItemOption: Boolean = false): List<NormUrl>
    
    /**
     * Normalize urls, remove invalid ones
     *
     * @param urls The urls to normalize
     * @param options The LoadOptions applied to each url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return All normalized urls, all invalid input urls are removed
     * */
    fun normalize(urls: Collection<UrlAware>, options: LoadOptions, toItemOption: Boolean = false): List<NormUrl>
    
    /**
     * Get a page from storage.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.get(url)
     * ```
     *
     * @param url The url
     * @return The webpage in storage if exists, otherwise returns a NIL page
     */
    fun get(url: String): WebPage
    
    /**
     * Get a page from storage.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.get(url, "content", "metadata")
     * ```
     *
     * @param url The url
     * @param fields The fields to load from local storage
     * @return The webpage in storage if exists, otherwise returns a NIL page
     */
    fun get(url: String, vararg fields: String): WebPage
    
    /**
     * Get a page from storage.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.getOrNull(url)
     * ```
     *
     * @param url The url
     * @return The page in storage if exists, otherwise returns null
     */
    fun getOrNull(url: String): WebPage?
    
    /**
     * Get a page from storage.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.getOrNull(url, "content", "metadata")
     * ```
     *
     * @param url The url
     * @param fields The fields to load from local storage
     * @return The page in storage if exists, otherwise returns null
     */
    fun getOrNull(url: String, vararg fields: String): WebPage?
    
    /**
     * Get the content of the page from the storage
     *
     * ```kotlin
     * val url = "http://example.com"
     * val content = session.getContent(url)
     * ```
     *
     * @param url The url of the page to retrieve
     * @return The page content or null
     */
    fun getContent(url: String): ByteBuffer?
    
    /**
     * Get the content of the page from the storage
     *
     * ```kotlin
     * val url = "http://example.com"
     * val content = session.getContentAsString(url)
     * ```
     *
     * @param url The url of the page to retrieve
     * @return The page content in string format or null
     */
    @Beta
    fun getContentAsString(url: String): String?
    
    /**
     * Check if the page exists in the storage.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val exists = session.exists(url)
     * ```
     *
     * @param url The url to check
     * @return true if the page exists, false otherwise
     */
    fun exists(url: String): Boolean
    
    /**
     * Return the fetch state of the page.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val state = session.fetchState(url)
     * ```
     *
     * @param page The webpage
     * @param options The load options
     * @return The fetch state of the page
     */
    fun fetchState(page: WebPage, options: LoadOptions): CheckState
    
    /**
     * Open a url.
     *
     * This method opens the url immediately, regardless of the previous state of the page.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.open(url)
     * ```
     *
     * @param url The url to open
     * @return The webpage loaded or NIL
     */
    fun open(url: String): WebPage
    
    /**
     * Open a url with page events.
     *
     * This method opens the url immediately, regardless of the previous state of the page.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val event = PrintFlowEvent()
     * val page = session.open(url, event)
     * ```
     *
     * @param url The url to open
     * @return The webpage loaded or NIL
     */
    fun open(url: String, event: PageEvent): WebPage
    
    /**
     * Load a url.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * Other fetch conditions can be specified by load arguments:
     *
     * 1. expiration
     * 2. page size requirement
     * 3. fields requirement
     * 4. other
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.load(url)
     * ```
     *
     * @param url The url to load
     * @return The webpage loaded or NIL
     */
    fun load(url: String): WebPage
    
    /**
     * Load a url with specified arguments.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * Fetch conditions can be specified by load arguments:
     *
     * 1. expiration
     * 2. page size requirement
     * 3. fields requirement
     * 4. other
     *
     * ```kotlin
     * val url = "http://example.com"
     * val args = "-expire 1d"
     * val page = session.load(url, args)
     * ```
     *
     * @param url The url to load
     * @param args The load arguments
     * @return The webpage loaded or NIL
     */
    fun load(url: String, args: String): WebPage
    
    /**
     * Load a url with specified options.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * Fetch conditions can be specified by load arguments:
     *
     * 1. expiration
     * 2. page size requirement
     * 3. fields requirement
     * 4. other
     *
     * ```kotlin
     * val url = "http://example.com"
     * val options = session.options("-expire 1d")
     * val page = session.load(url, options)
     * ```
     *
     * @param url The url to load
     * @param options The load options
     * @return The webpage loaded or NIL
     */
    fun load(url: String, options: LoadOptions): WebPage
    
    /**
     * Load a url with the specified arguments.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val page = session.load(url)
     * ```
     *
     * @param url  The url to load
     * @return The webpage loaded or NIL
     */
    fun load(url: UrlAware): WebPage
    
    /**
     * Load a url with the specified arguments.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val page = session.load(url, "-expire 1d")
     * ```
     *
     * @param url  The url to load
     * @param args The load arguments
     * @return The webpage loaded or NIL
     */
    fun load(url: UrlAware, args: String): WebPage
    
    /**
     * Load a url with options.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val options = session.options("-expire 1d")
     * val page = session.load(url, options)
     * ```
     *
     * @param url     The url to load
     * @param options The load options
     * @return The webpage loaded or NIL
     */
    fun load(url: UrlAware, options: LoadOptions): WebPage
    
    /**
     * Load a normal url.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val url = session.normalize("http://example.com")
     * val page = session.load(url)
     * ```
     *
     * * @param url The normal url
     * @return The webpage loaded or NIL
     */
    fun load(url: NormUrl): WebPage
    
    /**
     * Load a url with specified options.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val args = "-expire 1d"
     * val page = session.loadDeferred(url, args)
     * ```
     *
     * @param url     The url to load
     * @param args The load options
     * @return The webpage loaded or NIL
     */
    suspend fun loadDeferred(url: String, args: String): WebPage
    
    /**
     * Load a url with specified options.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val url = "http://example.com"
     * val options = session.options("-expire 1d")
     * val page = session.loadDeferred(url, options)
     * ```
     *
     * @param url     The url to load
     * @param options The load options
     * @return The webpage loaded or NIL
     */
    suspend fun loadDeferred(url: String, options: LoadOptions = options()): WebPage
    
    /**
     * Load a url with specified arguments.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val page = session.loadDeferred(url, "-expire 1d")
     * ```
     *
     * @param url  The url to load
     * @param args The load args
     * @return The webpage loaded or NIL
     */
    suspend fun loadDeferred(url: UrlAware, args: String): WebPage
    
    /**
     * Load a url with specified options.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val options = session.options("-expire 1d")
     * val page = session.loadDeferred(url, options)
     * ```
     *
     * @param url     The url to load
     * @param options The load options
     * @return The webpage loaded or NIL
     */
    suspend fun loadDeferred(url: UrlAware, options: LoadOptions = options()): WebPage
    
    /**
     * Load a url with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val url = session.normalize("http://example.com")
     * val page = session.loadDeferred(url)
     * ```
     *
     * @param url The normal url
     * @return The webpage loaded or NIL
     */
    suspend fun loadDeferred(url: NormUrl): WebPage
    
    /**
     * Load all urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1")
     * val pages = session.loadAll(urls)
     * ```
     *
     * @param urls    The urls to load
     * @return The webpage loaded or NIL
     */
    fun loadAll(urls: Iterable<String>): List<WebPage>
    
    /**
     * Load all urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1")
     * val pages = session.loadAll(urls, "-expire 1d")
     * ```
     *
     * @param urls    The urls to load
     * @param args The load arguments
     * @return The webpage loaded or NIL
     */
    fun loadAll(urls: Iterable<String>, args: String): List<WebPage>
    
    /**
     * Load all urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1")
     * val pages = session.loadAll(urls, session.options("-expire 1d"))
     * ```
     *
     * @param urls    The urls to load
     * @param options The load options
     * @return The webpage loaded or NIL
     */
    fun loadAll(urls: Iterable<String>, options: LoadOptions): List<WebPage>
    
    /**
     * Load all urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1"))
     * val pages = session.loadAll(urls)
     * ```
     *
     * @param urls    The urls to load
     * @return The webpage loaded or NIL
     */
    fun loadAll(urls: Collection<UrlAware>): List<WebPage>
    
    /**
     * Load all urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1"))
     * val pages = session.loadAll(urls, "-expire 1d")
     * ```
     *
     * @param urls    The urls to load
     * @param args The load arguments
     * @return The webpage loaded or NIL
     */
    fun loadAll(urls: Collection<UrlAware>, args: String): List<WebPage>
    
    /**
     * Load all urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1"))
     * val pages = session.loadAll(urls, session.options("-expire 1d"))
     * ```
     *
     * @param urls    The urls to load
     * @param options The load options
     * @return The webpage loaded or NIL
     */
    fun loadAll(urls: Collection<UrlAware>, options: LoadOptions): List<WebPage>
    
    /**
     * Load all normal urls with specified options
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1").map { session.normalize(it) }
     * val pages = session.loadAll(urls)
     * ```
     *
     * @param normUrls    The normal urls to load
     * @return The loaded webpages
     */
    fun loadAll(normUrls: List<NormUrl>): List<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.loadAsync(url).get()
     * ```
     *
     * @param url     The url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: String): CompletableFuture<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.loadAsync(url, "-expire 1d").get()
     * ```
     *
     * @param url     The url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: String, args: String): CompletableFuture<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = "http://example.com"
     * val page = session.loadAsync(url, session.options("-expire 1d")).get()
     * ```
     *
     * @param url     The url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: String, options: LoadOptions): CompletableFuture<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val page = session.loadAsync(url).get()
     * ```
     *
     * @param url     The url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: UrlAware): CompletableFuture<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val page = session.loadAsync(url, "-expire 1d").get()
     * ```
     *
     * @param url     The url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: UrlAware, args: String): CompletableFuture<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = Hyperlink("http://example.com")
     * val page = session.loadAsync(url, session.options("-expire 1d")).get()
     * ```
     *
     * @param url     The url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: UrlAware, options: LoadOptions): CompletableFuture<WebPage>
    
    /**
     * Load a normal url in java async style
     *
     * ```kotlin
     * val url = session.normalize("http://example.com")
     * val page = session.loadAsync(url).get()
     * ```
     *
     * @param url     The normal url to load
     * @return A completable future of webpage
     */
    fun loadAsync(url: NormUrl): CompletableFuture<WebPage>
    
    /**
     * Load all normal urls in java async style
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1")
     * val pages = session.loadAllAsync(urls)
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: Iterable<String>): List<CompletableFuture<WebPage>>
    
    /**
     * Load all normal urls in java async style.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1")
     * val pages = session.loadAllAsync(urls, "-expire 1d")
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: Iterable<String>, args: String): List<CompletableFuture<WebPage>>
    
    /**
     * Load all normal urls in java async style.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1")
     * val pages = session.loadAllAsync(urls, session.options("-expire 1d"))
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: Iterable<String>, options: LoadOptions): List<CompletableFuture<WebPage>>
    
    /**
     * Load all normal urls in java async style.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1"))
     * val pages = session.loadAllAsync(urls)
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: Collection<UrlAware>): List<CompletableFuture<WebPage>>
    
    /**
     * Load all normal urls in java async style.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1"))
     * val pages = session.loadAllAsync(urls, "-expire 1d")
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: Collection<UrlAware>, args: String): List<CompletableFuture<WebPage>>
    
    /**
     * Load all normal urls in java async style.
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1"))
     * val pages = session.loadAllAsync(urls, session.options("-expire 1d"))
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: Collection<UrlAware>, options: LoadOptions): List<CompletableFuture<WebPage>>
    
    /**
     * Load all normal urls in java async style
     *
     * This method initially verifies the presence of the page in the local store. If the page exists and meets the
     * specified requirements, it returns the local version. Otherwise, it fetches the page from the Internet.
     *
     * ```kotlin
     * val urls = listOf("http://example.com", "http://example.com/1").map { session.normalize(it) }
     * val pages = session.loadAllAsync(urls)
     * ```
     *
     * @param urls The normal urls to load
     * @return The completable futures of webpages
     */
    fun loadAllAsync(urls: List<NormUrl>): List<CompletableFuture<WebPage>>
    
    /**
     * Submit a url to the URL pool, the url will be processed in the crawl loop later
     *
     * ```kotlin
     * session.submit("http://example.com")
     * PulsarContexts.await()
     * ```
     *
     * @param url The url to submit
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submit(url: String): PulsarSession
    
    /**
     * Submit a url to the URL pool, and it will be processed in a crawl loop
     *
     * ```kotlin
     * session.submit("http://example.com", "-expire 1d")
     * PulsarContexts.await()
     * ```
     *
     * @param url The url to submit
     * @param args The load arguments
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submit(url: String, args: String): PulsarSession
    
    /**
     * Submit a url to the URL pool, and it will be processed in a crawl loop
     *
     * ```kotlin
     * session.submit("http://example.com", session.options("-expire 1d"))
     * PulsarContexts.await()
     * ```
     *
     * @param url The url to submit
     * @param options The load options
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submit(url: String, options: LoadOptions): PulsarSession
    
    /**
     * Submit a url to the URL pool, and it will be processed in a crawl loop
     *
     * ```kotlin
     * session.submit(Hyperlink("http://example.com"))
     * PulsarContexts.await()
     * ```
     *
     * @param url The url to submit
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submit(url: UrlAware): PulsarSession
    
    /**
     * Submit a url to the URL pool, and it will be processed in a crawl loop
     *
     * ```kotlin
     * session.submit(Hyperlink("http://example.com"), "-expire 1d")
     * PulsarContexts.await()
     * ```
     *
     * @param url The url to submit
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submit(url: UrlAware, args: String): PulsarSession
    
    /**
     * No such version, it's too complicated to handle events
     * */
    fun submit(url: UrlAware, options: LoadOptions): PulsarSession = throw NotImplementedError(
        "The signature submit(UrlAware, LoadOptions) is a confusing version, " +
            "it's too complicated to handle events and should not be implemented.")
    
    /**
     * Submit the urls to the URL pool, the submitted urls will be processed in a crawl loop
     *
     * ```kotlin
     * session.submitAll(listOf("http://example.com", "http://example.com/1"))
     * PulsarContexts.await()
     * ```
     *
     * @param urls The urls to submit
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submitAll(urls: Iterable<String>): PulsarSession
    
    /**
     * Submit the urls to the URL pool, the submitted urls will be processed in a crawl loop
     *
     * ```kotlin
     * session.submitAll(listOf("http://example.com", "http://example.com/1"), "-expire 1d")
     * PulsarContexts.await()
     * ```
     *
     * @param urls The urls to submit
     * @param args The load arguments
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submitAll(urls: Iterable<String>, args: String): PulsarSession
    
    /**
     * Submit the urls to the URL pool, the submitted urls will be processed in a crawl loop
     *
     * ```kotlin
     * session.submitAll(listOf("http://example.com", "http://example.com/1"), session.options("-expire 1d"))
     * PulsarContexts.await()
     * ```
     *
     * @param urls The urls to submit
     * @param options The load options
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submitAll(urls: Iterable<String>, options: LoadOptions): PulsarSession
    
    /**
     * Submit the urls to the URL pool, the submitted urls will be processed in a crawl loop
     *
     * ```kotlin
     * session.submitAll(listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1")))
     * PulsarContexts.await()
     * ```
     *
     * @param urls The urls to submit
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submitAll(urls: Collection<UrlAware>): PulsarSession
    
    /**
     * Submit the urls to the URL pool, the submitted urls will be processed in a crawl loop
     *
     * ```kotlin
     * session.submitAll(listOf(Hyperlink("http://example.com"), Hyperlink("http://example.com/1")), "-expire 1d")
     * PulsarContexts.await()
     * ```
     *
     * @param urls The urls to submit
     * @return The [PulsarSession] itself to enabled chained operations
     */
    fun submitAll(urls: Collection<UrlAware>, args: String): PulsarSession

    /**
     * No such version, it's too complicated to handle events
     * */
    fun submitAll(urls: Collection<UrlAware>, options: LoadOptions): PulsarSession =
        throw NotImplementedError("The signature submitAll(Collection<UrlAware>, LoadOptions) is a confusing version, " +
            "it's too complicated to handle events and should not be implemented.")

    /**
     * No such confusing version
     * */
    fun loadOutPages(portalUrl: String): List<WebPage> =
        throw NotImplementedError("The signature loadOutPages(String) is a confusing version and should not be " +
            "implemented.")
    
    /**
     * Load or fetch the portal page, and then load or fetch the out links selected by `-outLink` option.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * ```kotlin
     * val pages = session.loadOutPages("http://example.com", "-outLink a")
     * val pages2 = session.loadOutPages("http://example.com", "-outLink a -expire 1d")
     * ```
     *
     * @param portalUrl    The portal url from where to load pages
     * @param args         The load arguments
     * @return The loaded out pages
     */
    fun loadOutPages(portalUrl: String, args: String): List<WebPage>
    
    /**
     * Load or fetch the portal page, and then load or fetch the out links selected by `-outLink` option.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * ```kotlin
     * val pages = session.loadOutPages("http://example.com", session.options("-outLink a"))
     * val pages2 = session.loadOutPages("http://example.com", session.options("-outLink a -expire 1d"))
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param options   The load options
     * @return The loaded out pages
     */
    fun loadOutPages(portalUrl: String, options: LoadOptions): List<WebPage>

    /**
     * A confusing version, it's too complicated to handle events and should not be implemented.
     */
    fun loadOutPages(portalUrl: UrlAware): List<WebPage> =
        throw NotImplementedError("The signature loadOutPages(UrlAware) is a confusing version, it's too complicated to " +
            "handle events and should not be implemented.")
    
    /**
     * Load or fetch the portal page, and then load or fetch the out links selected by `-outLink` option.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * ```kotlin
     * val pages = session.loadOutPages("http://example.com", "-outLink a")
     * val pages2 = session.loadOutPages("http://example.com", "-outLink a -expire 1d")
     * ```
     *
     * @param portalUrl    The portal url from where to load pages
     * @param args         The load arguments
     * @return The loaded out pages
     */
    fun loadOutPages(portalUrl: UrlAware, args: String): List<WebPage>
    
    /**
     * Load or fetch the portal page, and then load or fetch the out links selected by `-outLink` option.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * ```kotlin
     * val pages = session.loadOutPages("http://example.com", session.options("-outLink a"))
     * val pages2 = session.loadOutPages("http://example.com", session.options("-outLink a -expire 1d"))
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param options   The load options
     * @return The loaded out pages
     */
    fun loadOutPages(portalUrl: UrlAware, options: LoadOptions): List<WebPage>

    /**
     * A confusing version, it's too complicated to handle events and should not be implemented.
     */
    fun loadOutPages(portalUrl: NormUrl): List<WebPage> =
        throw NotImplementedError("The signature loadOutPages(NormUrl) is " +
            "a confusing version, it's too complicated to handle events and should not be implemented.")
    
    /**
     * Load or fetch the portal page, and then load or fetch the out links selected by `-outLink` option asynchronously.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * ```kotlin
     * val pages = session.loadOutPagesAsync("http://example.com", "-outLink a")
     * val pages2 = session.loadOutPagesAsync("http://example.com", "-outLink a -expire 1d")
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param args   The load arguments
     * @return The loaded out pages
     */
    fun loadOutPagesAsync(portalUrl: String, args: String): List<CompletableFuture<WebPage>>
    
    /**
     * Load or fetch the portal page, and then load or fetch the out links selected by `-outLink` option asynchronously.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * ```kotlin
     * val pages = session.loadOutPagesAsync("http://example.com", session.options("-outLink a"))
     * val pages2 = session.loadOutPagesAsync("http://example.com", session.options("-outLink a -expire 1d"))
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param options   The load options
     * @return The loaded out pages
     */
    fun loadOutPagesAsync(portalUrl: String, options: LoadOptions): List<CompletableFuture<WebPage>>
    
    /**
     * Load the portal page and submit the out links specified by the `-outLink` option to the URL pool.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * The submitted urls will be processed in a crawl loop later.
     *
     * ```kotlin
     * session.submitForOutPages("http://example.com", "-outLink a[href*=review]")
     * session.submitForOutPages("http://example.com", "-outLink a[href*=item] -expire 1d")
     * PulsarContexts.await()
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param args      The load arguments
     * @return The [PulsarSession] itself to enable chained operation
     */
    fun submitForOutPages(portalUrl: String, args: String): PulsarSession
    
    /**
     * Load the portal page and submit the out links specified by the `-outLink` option to the URL pool.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * The submitted urls will be processed in a crawl loop later.
     *
     * ```kotlin
     * session.submitForOutPages("http://example.com", session.options("-outLink a[href*=review]"))
     * session.submitForOutPages("http://example.com", session.options("-outLink a[href*=item] -expire 1d"))
     * PulsarContexts.await()
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param options   The load options
     * @return The [PulsarSession] itself to enable chained operation
     */
    fun submitForOutPages(portalUrl: String, options: LoadOptions): PulsarSession
    
    /**
     * Load the portal page and submit the out links specified by the `-outLink` option to the URL pool.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * The submitted urls will be processed in a crawl loop later.
     *
     * ```kotlin
     * session.submitForOutPages(Hyperlink("http://example.com"), "-outLink a[href*=review]")
     * session.submitForOutPages(Hyperlink("http://example.com"), "-outLink a[href*=item] -expire 1d")
     * PulsarContexts.await()
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param args      The load arguments
     * @return The [PulsarSession] itself to enable chained operation
     */
    fun submitForOutPages(portalUrl: UrlAware, args: String): PulsarSession
    
    /**
     * Load the portal page and submit the out links specified by the `-outLink` option to the URL pool.
     *
     * Option `-outLink` specifies the cssSelector for links in the portal page to load.
     *
     * The submitted urls will be processed in a crawl loop later.
     *
     * ```kotlin
     * session.submitForOutPages(Hyperlink("http://example.com"), session.options("-outLink a[href*=review]"))
     * session.submitForOutPages(Hyperlink("http://example.com"), session.options("-outLink a[href*=item] -expire 1d"))
     * PulsarContexts.await()
     * ```
     *
     * @param portalUrl The portal url from where to load pages
     * @param options   The load options
     * @return The [PulsarSession] itself to enable chained operation
     */
    fun submitForOutPages(portalUrl: UrlAware, options: LoadOptions): PulsarSession
    
    /**
     * Load a url as a resource without browser rendering.
     *
     * Referrer will be opened by a browser first to obtain browsing environment,
     * such as headers and cookies, and then the browsing environment will be applied to the later resource fetching.
     *
     * ```kotlin
     * val page = session.loadResource("http://example.com/robots.txt", "http://example.com")
     * ```
     *
     * @param url  The url to load
     * @param referrer The referrer URL
     * @return The webpage containing the resource
     */
    fun loadResource(url: String, referrer: String): WebPage
    /**
     * Load a url as a resource without browser rendering.
     *
     * Referrer will be opened by a browser first to obtain browsing environment,
     * such as headers and cookies, and then the browsing environment will be applied to the later resource fetching.
     *
     * ```kotlin
     * val page = session.loadResource("http://example.com/robots.txt", "http://example.com", "-expire 1d")
     * ```
     *
     * @param url  The url to load
     * @param referrer The referrer URL
     * @param args The load arguments
     * @return The webpage containing the resource
     */
    fun loadResource(url: String, referrer: String, args: String): WebPage
    /**
     * Load a url as a resource without browser rendering.
     *
     * Referrer will be opened by a browser first to obtain browsing environment,
     * such as headers and cookies, and then the browsing environment will be applied to the later resource fetching.
     *
     * ```kotlin
     * val page = session.loadResource("http://example.com/robots.txt", "http://example.com", session.options("-expire 1d"))
     * ```
     *
     * @param url     The url to load
     * @param referrer The referrer URL
     * @param options The load options
     * @return The webpage containing the resource
     */
    fun loadResource(url: String, referrer: String, options: LoadOptions): WebPage
    
    /**
     * Load a url as a resource without browser rendering.
     *
     * Referrer will be opened by a browser first to obtain browsing environment,
     * such as headers and cookies, and then the browsing environment will be applied to the later resource fetching.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val page = session.loadResourceDeferred("http://example.com/robots.txt", "http://example.com")
     * ```
     *
     * @param url  The url to load
     * @param referrer The referrer URL
     * @return The webpage containing the resource
     */
    suspend fun loadResourceDeferred(url: String, referrer: String): WebPage
    /**
     * Load a url as a resource without browser rendering.
     *
     * Referrer will be opened by a browser first to obtain browsing environment,
     * such as headers and cookies, and then the browsing environment will be applied to the later resource fetching.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val page = session.loadResourceDeferred("http://example.com/robots.txt", "http://example.com", "-expire 1d")
     * ```
     *
     * @param url  The url to load
     * @param referrer The referrer URL
     * @param args The load arguments
     * @return The webpage containing the resource
     */
    suspend fun loadResourceDeferred(url: String, referrer: String, args: String): WebPage
    /**
     * Load a url as a resource without browser rendering.
     *
     * Referrer will be opened by a browser first to obtain browsing environment,
     * such as headers and cookies, and then the browsing environment will be applied to the later resource fetching.
     *
     * This function is a kotlin suspend function, which could be started, paused, and resume.
     * Suspend functions are only allowed to be called from a coroutine or another suspend function.
     *
     * ```kotlin
     * val page = session.loadResourceDeferred("http://example.com/robots.txt", "http://example.com", session.options("-expire 1d"))
     * ```
     *
     * @param url     The url to load
     * @param referrer The referrer URL
     * @param options The load options
     * @return The webpage containing the resource
     */
    suspend fun loadResourceDeferred(url: String, referrer: String, options: LoadOptions): WebPage
    /**
     * Parse a webpage into an HTML document.
     *
     * ```kotlin
     * val page = session.load("http://example.com")
     * val document = session.parse(page)
     * ```
     *
     * @param page The webpage to parse
     * @return The parsed HTML document
     */
    fun parse(page: WebPage): FeaturedDocument
    /**
     * Parse a webpage into an HTML document.
     *
     * ```kotlin
     * val page = session.load("http://example.com")
     * val document = session.parse(page, true)
     * ```
     *
     * @param page The webpage to parse
     * @param noCache Whether to skip the cache
     * @return The parsed HTML document
     */
    fun parse(page: WebPage, noCache: Boolean): FeaturedDocument
    /**
     * Load or fetch a webpage and parse it into an HTML document
     *
     * ```kotlin
     * val document = session.loadDocument("http://example.com")
     * ```
     *
     * @param url The url to load
     * @return The parsed HTML document
     * */
    fun loadDocument(url: String): FeaturedDocument
    /**
     * Load or fetch a webpage and parse it into an HTML document
     *
     * ```kotlin
     * val document = session.loadDocument("http://example.com", "-expire 1d")
     * ```
     *
     * @param url The url to load
     * @param args The load arguments
     * @return The parsed HTML document
     * */
    fun loadDocument(url: String, args: String): FeaturedDocument
    /**
     * Load or fetch a webpage and parse it into an HTML document
     *
     * ```kotlin
     * val document = session.loadDocument("http://example.com", session.options("-expire 1d"))
     * ```
     *
     * @param url The url to load
     * @param options The load options
     * @return The parsed HTML document
     * */
    fun loadDocument(url: String, options: LoadOptions): FeaturedDocument
    /**
     * Load or fetch a webpage and parse it into an HTML document
     *
     * ```kotlin
     * val document = session.loadDocument(Hyperlink("http://example.com"))
     * ```
     *
     * @param url The url to load
     * @return The parsed HTML document
     * */
    fun loadDocument(url: UrlAware): FeaturedDocument
    /**
     * Load or fetch a webpage and parse it into an HTML document
     *
     * ```kotlin
     * val document = session.loadDocument(Hyperlink("http://example.com"), "-expire 1d")
     * ```
     *
     * @param url The url to load
     * @param args The load arguments
     * @return The parsed HTML document
     * */
    fun loadDocument(url: UrlAware, args: String): FeaturedDocument
    /**
     * Load or fetch a webpage and parse it into an HTML document
     *
     * ```kotlin
     * val document = session.loadDocument(Hyperlink("http://example.com"), session.options("-expire 1d"))
     * ```
     *
     * @param url The url to load
     * @param options The load options
     * @return The parsed HTML document
     * */
    fun loadDocument(url: UrlAware, options: LoadOptions): FeaturedDocument
    /**
     * Load or fetch a webpage and then parse it into an HTML document.
     *
     * ```kotlin
     * val document = session.loadDocument("http://example.com")
     * ```
     *
     * @param url The url to load
     * @return The parsed HTML document
     * */
    fun loadDocument(url: NormUrl): FeaturedDocument
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", "-expire 1d", listOf(".title", ".content"))
     * ```
     *
     * @param url The url to scrape
     * @param args The load arguments
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their selectors
     * */
    fun scrape(url: String, args: String, fieldSelectors: Iterable<String>): Map<String, String?>
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", session.options("-expire 1d"), listOf(".title", ".content"))
     * ```
     *
     * @param url The url to scrape
     * @param options The load options
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their selectors
     * */
    fun scrape(url: String, options: LoadOptions, fieldSelectors: Iterable<String>): Map<String, String?>
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", "-expire 1d", ".title", ".content")
     * ```
     *
     * @param url The url to scrape
     * @param args The load arguments
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their names
     * */
    fun scrape(url: String, args: String, fieldSelectors: Map<String, String>): Map<String, String?>
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", session.options("-expire 1d"), ".title", ".content")
     * ```
     *
     * @param url The url to scrape
     * @param options The load options
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their names
     * */
    fun scrape(url: String, options: LoadOptions, fieldSelectors: Map<String, String>): Map<String, String?>
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", "-expire 1d", ".container", listOf(".title", ".content"))
     * ```
     *
     * @param url The url to scrape
     * @param args The load arguments
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their names
     * */
    fun scrape(
        url: String, args: String, restrictSelector: String, fieldSelectors: Iterable<String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", session.options("-expire 1d"), ".container",
     *      listOf(".title", ".content"))
     * ```
     *
     * @param url The url to scrape
     * @param options The load options
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their selectors
     * */
    fun scrape(
        url: String, options: LoadOptions, restrictSelector: String, fieldSelectors: Iterable<String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", "-expire 1d", ".container",
     *      mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param url The url to scrape
     * @param args The load arguments
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their names
     * */
    fun scrape(
        url: String, args: String, restrictSelector: String, fieldSelectors: Map<String, String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch a webpage located by the given url, and then extract fields specified by
     * field selectors.
     *
     * ```kotlin
     * val fields = session.scrape("http://example.com", session.options("-expire 1d"), ".container",
     *      mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param url The url to scrape
     * @param options The load options
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors The selectors to extract fields
     * @return All the extracted fields and their names
     * */
    fun scrape(
        url: String, options: LoadOptions, restrictSelector: String, fieldSelectors: Map<String, String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", "-outLink a", listOf(".title", ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param args Load arguments for both the portal page and out pages
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted
     *          with their selectors are saved in a map.
     * */
    fun scrapeOutPages(portalUrl: String, args: String, fieldSelectors: Iterable<String>): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", session.options("-outLink a"), listOf(".title", ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param options Load options for both the portal page and out pages
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their selectors are saved in a map.
     * */
    fun scrapeOutPages(portalUrl: String, options: LoadOptions, fieldSelectors: Iterable<String>): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", "-outLink a", mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param args Load arguments for both the portal page and out pages
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their selectors are saved in a map.
     * */
    fun scrapeOutPages(
        portalUrl: String, args: String, restrictSelector: String, fieldSelectors: Iterable<String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", session.options("-outLink a"),
     *      mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param options Load options for both the portal page and out pages
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their selectors are saved in a map.
     * */
    fun scrapeOutPages(
        portalUrl: String, options: LoadOptions, restrictSelector: String, fieldSelectors: Iterable<String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", "-outLink a", mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param args Load arguments for both the portal page and out pages
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their names are saved in a map.
     * */
    fun scrapeOutPages(portalUrl: String, args: String, fieldSelectors: Map<String, String>): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", session.options("-outLink a"),
     *      mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param options Load options for both the portal page and out pages
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their names are saved in a map.
     * */
    fun scrapeOutPages(portalUrl: String, options: LoadOptions, fieldSelectors: Map<String, String>): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", "-outLink a", ".container",
     *      mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param args Load arguments for both the portal page and out pages
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their names are saved in a map.
     * */
    fun scrapeOutPages(
        portalUrl: String, args: String, restrictSelector: String, fieldSelectors: Map<String, String>
    ): List<Map<String, String?>>
    
    /**
     * Load or fetch out pages specified by out link selector, and then extract fields specified by
     * field selectors from each out page.
     *
     * ```kotlin
     * val fields = session.scrapeOutPages("http://example.com", session.options("-outLink a"), ".container",
     *      mapOf("title" to ".title", "content" to ".content"))
     * ```
     *
     * @param portalUrl The portal url to start scraping
     * @param options Load options for both the portal page and out pages
     * @param restrictSelector A CSS selector to locate a DOM where all fields are restricted to
     * @param fieldSelectors CSS selectors to extract fields from out pages
     * @return All extracted fields. For each out page, fields extracted with their names are saved in a map.
     * */
    fun scrapeOutPages(
        portalUrl: String, options: LoadOptions, restrictSelector: String, fieldSelectors: Map<String, String>
    ): List<Map<String, String?>>
    
    /**
     * Export the content of a webpage.
     *
     * ```kotlin
     * val page = session.load("http://example.com")
     * val path = session.export(page)
     * ```
     *
     * @param page Page to export
     * @return The path of the exported page
     * */
    fun export(page: WebPage): Path
    
    /**
     * Export the content of a webpage.
     *
     * ```kotlin
     * val page = session.load("http://example.com")
     * val path = session.export(page, "example")
     * ```
     *
     * @param page Page to export
     * @param ident File name identifier used to distinguish from other files
     * @return The path of the exported page
     * */
    fun export(page: WebPage, ident: String = ""): Path
    
    /**
     * Export the whole HTML of the document to the given path.
     *
     * ```kotlin
     * val page = session.load("http://example.com")
     * val path = session.exportTo(page, Paths.get("/tmp/example.html"))
     * ```
     *
     * @param page Webpage to export
     * @param path Path to save the exported content
     * @return The path of the exported document
     * */
    fun exportTo(page: WebPage, path: Path): Path
    
    /**
     * Export the outer HTML of the document.
     *
     * @param doc Document to export
     * @return The path of the exported document
     * */
    fun export(doc: FeaturedDocument): Path
    
    /**
     * Export the outer HTML of the document.
     *
     * ```kotlin
     * val document = session.loadDocument("http://example.com")
     * val path = session.export(document, "example")
     * ```
     *
     * @param doc Document to export
     * @param ident File name identifier used to distinguish from other files
     * @return The path of the exported document
     * */
    fun export(doc: FeaturedDocument, ident: String = ""): Path
    
    /**
     * Export the whole HTML of the document to the given path.
     *
     * ```kotlin
     * val document = session.loadDocument("http://example.com")
     * val path = session.exportTo(document, Paths.get("/tmp/example.html"))
     * ```
     *
     * @param doc Document to export
     * @param path Path to save the exported content
     * @return The path of the exported document
     * */
    fun exportTo(doc: FeaturedDocument, path: Path): Path
    
    /**
     * Persist the content of a webpage.
     *
     * ```kotlin
     * val page = session.load("http://example.com")
     * session.persist(page)
     * ```
     *
     * @param page Page to persist
     * @return Whether the page is persisted successfully
     * */
    fun persist(page: WebPage): Boolean
    /**
     * Delete a webpage from the storage
     *
     * ```kotlin
     * session.delete("http://example.com")
     * ```
     *
     * @param url The url to delete
     * */
    fun delete(url: String)
    /**
     * Flush to the storage
     * */
    fun flush()
}
