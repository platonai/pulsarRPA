package ai.platon.pulsar.common.options

import ai.platon.pulsar.common.urls.NormUrl
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.common.urls.UrlUtils
import ai.platon.pulsar.crawl.common.url.ListenableUrl
import ai.platon.pulsar.crawl.filter.CrawlUrlNormalizers

class CommonUrlNormalizer(private val urlNormalizers: CrawlUrlNormalizers? = null) {
    /**
     * Normalize an url.
     *
     * If both url arguments and LoadOptions are present, the url arguments overrides the LoadOptions.
     * */
    fun normalize(url: UrlAware, options: LoadOptions, toItemOption: Boolean): NormUrl {
        val (spec, args1) = UrlUtils.splitUrlArgs(url.url)
        val args2 = url.args ?: ""
        val args3 = options.toString()
        // args1 has the #1 priority, and then args2, and at last args3.
        // the later args overwrites the earlier ones.
        val args = "$args3 $args2 $args1".trim()

        val finalOptions = createLoadOptions(url, LoadOptions.parse(args, options), toItemOption)
        val event = finalOptions.event

        var normUrl = if (event?.loadEvent?.onNormalize?.isNotEmpty == true) {
            // 1. normalizer in event listener has the #1 priority
            event.loadEvent.onNormalize(spec) ?: return NormUrl.NIL
        } else {
            // 2. global normalizers has the #2 priority
            val normalizers = urlNormalizers
            if (!options.noNorm && normalizers != null) {
                normalizers.normalize(spec) ?: return NormUrl.NIL
            } else spec
        }

        // 3. UrlUtils.normalize comes at last to remove fragment, and query string if required
        normUrl = UrlUtils.normalizeOrNull(normUrl, options.ignoreUrlQuery) ?: return NormUrl.NIL

        // already done
//        finalOptions.overrideConfiguration()

        val href = url.href?.takeIf { UrlUtils.isValidUrl(it) }
        return NormUrl(normUrl, finalOptions, href, url)
    }

    private fun createLoadOptions(url: UrlAware, options: LoadOptions, toItemOption: Boolean = false): LoadOptions {
        val options2 = if (toItemOption) options.createItemOptions() else options
        val options3 = createLoadOptions0(url, options2)

        options3.overrideConfiguration()

        return options3
    }

    private fun createLoadOptions0(url: UrlAware, options: LoadOptions): LoadOptions {
        val clone = options.clone()
        require(options.event == clone.event)
        require(options.itemEvent == clone.itemEvent)

        clone.conf.name = clone.label
        clone.nMaxRetry = url.nMaxRetry

        if (url is ListenableUrl) {
            clone.enableEvent().combine(url.event)
        }

        return clone
    }
}
