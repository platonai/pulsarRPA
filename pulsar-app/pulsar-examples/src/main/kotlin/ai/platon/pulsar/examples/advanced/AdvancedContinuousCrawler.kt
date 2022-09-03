package ai.platon.pulsar.examples.advanced

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.context.PulsarContexts
import ai.platon.pulsar.crawl.common.url.ListenableHyperlink
import ai.platon.pulsar.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.persist.WebPage

fun main() {
    val hyperlinkCreator = { url: String ->
        val link = ListenableHyperlink(url)
        link.eventHandler.loadEventHandler.apply {
            onFilter.addLast { url ->
                url
            }
            onNormalize.addLast { url ->
                url
            }
            onWillLoad.addLast { url ->

            }
            onWillFetch.addLast { page ->

            }
            onWillLaunchBrowser.addLast { page ->

            }
            onBrowserLaunched.addLast { page, driver ->

            }
            onFetched.addLast { page ->

            }
            onWillParseHTMLDocument.addLast { page ->

            }
            onWillParseHTMLDocument.addLast { page ->

            }
            onWillExtract.addLast { page ->

            }
            onExtracted.addLast { page: WebPage, document: FeaturedDocument ->

            }
            onHTMLDocumentParsed.addLast { page: WebPage, document: FeaturedDocument ->

            }
            onParsed.addLast { page ->

            }
            onLoaded.addLast { page ->

            }
        }

        link.eventHandler.simulateEventHandler.apply {
            onWillCheckDOMState.addLast { page: WebPage, driver: WebDriver ->

            }
            onDOMStateChecked.addLast { page: WebPage, driver: WebDriver ->

            }
            onWillComputeFeature.addLast { page: WebPage, driver: WebDriver ->

            }
            onFeatureComputed.addLast { page: WebPage, driver: WebDriver ->

            }
        }

        link.eventHandler.crawlEventHandler.apply {
            onFilter.addLast { url: UrlAware ->
                url
            }
            onNormalize.addLast { url: UrlAware ->
                url
            }
            onWillLoad.addLast { url: UrlAware ->

            }
            onLoaded.addLast { url, page ->

            }
        }
        link
    }

    // load urls from resource, and convert them into listenable hyperlinks
    val urls = LinkExtractors.fromResource("seeds.txt").map { hyperlinkCreator("$it -refresh") }
    // create a custom context
    val context = PulsarContexts.create("classpath:pulsar-beans/app-context.xml")
    // submit a batch of urls
    context.submitAll(urls)
    // feel free to submit millions of urls here
    // ...
    // wait until all done
    context.await()
}
