package org.tekfive.relaykt.support

import okhttp3.Request
import okio.Buffer
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.relaykt.http.HttpResponse

/** Records requests and replays scripted responses, for provider clients' `executeOverride`. */
class StubHttp(private val responder: (Request, String) -> HttpResponse) {

    class Recorded(val request: Request, val body: String) {
        val url: String get() = request.url.toString()
        val method: String get() = request.method
        val json: JsonObject get() = body.asRequiredJsonObject()
        fun header(name: String): String? = request.header(name)
    }

    val requests = mutableListOf<Recorded>()

    val execute: (Request) -> HttpResponse = { request ->
        val body = request.body?.let { requestBody ->
            Buffer().also { requestBody.writeTo(it) }.readUtf8()
        }.orEmpty()
        requests.add(Recorded(request, body))
        responder(request, body)
    }

    companion object {
        fun json(code: Int, body: String, headers: Map<String, String> = emptyMap()) = HttpResponse(code, body, headers)

        /** Responds by matching the URL path against ordered (substring -> response) rules. */
        fun routes(vararg rules: Pair<String, HttpResponse>): StubHttp = StubHttp { request, _ ->
            val path = request.url.encodedPath + "?" + (request.url.encodedQuery ?: "")
            rules.firstOrNull { (fragment, _) -> path.contains(fragment) }?.second
                ?: HttpResponse(404, """{"error":"no stub for $path"}""")
        }
    }
}
