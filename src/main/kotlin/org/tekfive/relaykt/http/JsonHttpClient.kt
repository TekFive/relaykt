package org.tekfive.relaykt.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.asRequiredJsonObject
import org.tekfive.relaykt.provider.ProviderException

/**
 * Base class for the small REST clients behind HTTP providers. Handles URL building, headers,
 * execution, and scrubbed error reporting so each provider client only describes its API calls.
 *
 * [executeOverride] lets tests intercept requests without a network; when set, [client] is unused.
 */
abstract class JsonHttpClient(
    private val baseUrl: String,
    private val client: OkHttpClient = RelayHttpClient.client,
    private val executeOverride: ((Request) -> HttpResponse)? = null,
) {

    /** Human-readable provider name used in exception messages (never carries data). */
    protected abstract val providerName: String

    /** Headers added to every request, typically `Authorization`. */
    protected abstract fun authorizationHeaders(): Map<String, String>

    protected fun url(vararg pathSegments: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
        pathSegments.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    protected fun get(url: HttpUrl, headers: Map<String, String> = emptyMap()): Request {
        return Request.Builder()
            .url(url)
            .applyHeaders(headers)
            .header("Accept", JSON_MEDIA_TYPE_NAME)
            .get()
            .build()
    }

    protected fun postJson(url: HttpUrl, json: String, headers: Map<String, String> = emptyMap()): Request {
        return post(url, json.toRequestBody(JSON_MEDIA_TYPE), headers)
    }

    protected fun post(url: HttpUrl, body: RequestBody, headers: Map<String, String> = emptyMap()): Request {
        return Request.Builder()
            .url(url)
            .applyHeaders(headers)
            .header("Accept", JSON_MEDIA_TYPE_NAME)
            .post(body)
            .build()
    }

    /** Executes [request] and returns the response regardless of status code. */
    protected fun execute(request: Request): HttpResponse {
        executeOverride?.let { return it(request) }

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            val headers = response.headers.toMultimap().mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            return HttpResponse(response.code, body, headers)
        }
    }

    /**
     * Executes [request] and fails with a [ProviderException] carrying only the HTTP status when the
     * response is not 2xx. [notFoundAsNull] turns a 404 into a null result for lookups.
     */
    protected fun executeSuccessful(request: Request, action: String, notFoundAsNull: Boolean = false): HttpResponse? {
        val response = execute(request)
        if (notFoundAsNull && response.code == 404) {
            return null
        }
        if (!response.isSuccessful) {
            // Never include the response body: providers echo addresses and message content in errors.
            throw ProviderException("$providerName $action failed with HTTP status ${response.code}", statusCode = response.code)
        }
        return response
    }

    /** Parses a JSON object body, treating a blank body as an empty object. */
    protected fun HttpResponse.jsonBody(): JsonObject {
        val trimmed = body.trim()
        return if (trimmed.isBlank()) JsonObject() else trimmed.asRequiredJsonObject()
    }

    private fun Request.Builder.applyHeaders(extra: Map<String, String>): Request.Builder {
        authorizationHeaders().forEach { (name, value) -> header(name, value) }
        extra.forEach { (name, value) -> header(name, value) }
        return this
    }

    companion object {
        const val JSON_MEDIA_TYPE_NAME = "application/json"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
