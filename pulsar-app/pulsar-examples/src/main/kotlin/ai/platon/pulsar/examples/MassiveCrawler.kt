package ai.platon.pulsar.examples

import ai.platon.pulsar.common.LinkExtractors
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.common.urls.PlainUrl
import ai.platon.pulsar.context.PulsarContexts
import ai.platon.pulsar.crawl.common.url.ParsableHyperlink
import ai.platon.pulsar.persist.WebPage
import org.jsoup.nodes.Document

fun main() {
    val context = PulsarContexts.create()
    val parseHandler = { _: WebPage, document: Document ->
        // do something wonderful with the document
        println(document.title() + "\t|\t" + document.baseUri())
    }
    val urls = LinkExtractors.fromResource("seeds.txt")
        .map { ParsableHyperlink("$it -refresh", parseHandler) }
    context.submitAll(urls)
    // feel free to submit millions of urls here
    context.submitAll(urls)
    // ...
    context.await()
}