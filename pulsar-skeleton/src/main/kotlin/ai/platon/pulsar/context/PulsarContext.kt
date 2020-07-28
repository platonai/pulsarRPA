package ai.platon.pulsar.context

import ai.platon.pulsar.PulsarSession
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.common.options.LoadOptions
import ai.platon.pulsar.common.options.NormUrl
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.gora.generated.GWebPage
import org.springframework.context.ApplicationContext
import java.net.URL

/**
 * Main entry point for Pulsar functionality.
 *
 * A PulsarContext can be used to inject, fetch, load, parse, store Web pages.
 */
interface PulsarContext: AutoCloseable {
    val applicationContext: ApplicationContext

    fun createSession(): PulsarSession

    fun closeSession(session: PulsarSession)

    /**
     * Close objects when sessions closes
     * */
    fun registerClosable(closable: AutoCloseable)

    fun normalize(url: String, isItemOption: Boolean = false): NormUrl

    fun normalize(url: String, options: LoadOptions, isItemOption: Boolean = false): NormUrl

    fun normalize(urls: Iterable<String>, isItemOption: Boolean = false): List<NormUrl>

    fun normalize(urls: Iterable<String>, options: LoadOptions, isItemOption: Boolean = false): List<NormUrl>

    /**
     * Inject an url
     *
     * @param url The url followed by config options
     * @return The web page created
     */
    fun inject(url: String): WebPage

    fun get(url: String): WebPage?

    fun getOrNil(url: String): WebPage

    fun scan(urlPrefix: String): Iterator<WebPage>

    fun scan(urlPrefix: String, fields: Iterable<GWebPage.Field>): Iterator<WebPage>

    fun scan(urlPrefix: String, fields: Array<String>): Iterator<WebPage>

    /**
     * Load a url, options can be specified following the url, see [LoadOptions] for all options
     *
     * @param url The url followed by options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPage.NIL] is returned
     */
    fun load(url: String): WebPage

    /**
     * Load a url with specified options, see [LoadOptions] for all options
     *
     * @param url     The url followed by options
     * @param options The options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPage.NIL] is returned
     */
    fun load(url: String, options: LoadOptions): WebPage

    /**
     * Load a url, options can be specified following the url, see [LoadOptions] for all options
     *
     * @param url The url followed by options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPage.NIL] is returned
     */
    fun load(url: URL): WebPage

    /**
     * Load a url with specified options, see [LoadOptions] for all options
     *
     * @param url     The url followed by options
     * @param options The options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPage.NIL] is returned
     */
    fun load(url: URL, options: LoadOptions): WebPage

    /**
     * Load a url, options can be specified following the url, see [LoadOptions] for all options
     *
     * @param url The url followed by options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPage.NIL] is returned
     */
    fun load(url: NormUrl): WebPage

    suspend fun loadDeferred(url: NormUrl): WebPage

    /**
     * Load a batch of urls with the specified options.
     *
     * If the option indicates prefer parallel, urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     *
     * If a page does not exists neither in local storage nor at the given remote location, [WebPage.NIL] is returned
     *
     * @param urls    The urls to load
     * @param options The options
     * @return Pages for all urls.
     */
    fun loadAll(urls: Iterable<String>, options: LoadOptions = LoadOptions.create()): Collection<WebPage>

    fun loadAll(urls: Collection<NormUrl>, options: LoadOptions = LoadOptions.create()): Collection<WebPage>

    /**
     * Load a batch of urls with the specified options.
     *
     * Urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     *
     * If a page does not exists neither in local storage nor at the given remote location, [WebPage.NIL] is returned
     *
     * @param urls    The urls to load
     * @param options The options
     * @return Pages for all urls.
     */
    fun parallelLoadAll(urls: Iterable<String>, options: LoadOptions = LoadOptions.create()): Collection<WebPage>

    fun parallelLoadAll(urls: Collection<NormUrl>, options: LoadOptions = LoadOptions.create()): Collection<WebPage>

    /**
     * Parse the WebPage using Jsoup
     */
    fun parse(page: WebPage): FeaturedDocument

    fun parse(page: WebPage, mutableConfig: MutableConfig): FeaturedDocument

    fun persist(page: WebPage)

    fun delete(url: String)

    fun delete(page: WebPage)

    fun flush()

    fun registerShutdownHook()
}