package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.pulsar.protocol.browser.driver.cdt.ChromeDevtoolsDriver
import com.github.kklisura.cdt.protocol.v2023.events.network.ResponseReceivedExtraInfo
import com.github.kklisura.cdt.protocol.v2023.types.network.Response

class CDPResponse(
        val driver: ChromeDevtoolsDriver,
        val request: CDPRequest,
        val response: Response,
        val extraInfo: ResponseReceivedExtraInfo? = null
) {
    fun resolveBody(body: String?) {
        // TODO: what can i do?
    }
}
