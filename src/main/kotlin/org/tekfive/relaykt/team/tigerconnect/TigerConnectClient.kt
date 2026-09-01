package org.tekfive.relaykt.team.tigerconnect

import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.JsonObject
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient

/** Minimal TigerConnect REST client (https://developer.tigertext.com/reference/rest-api). */
open class TigerConnectClient(
    private val configuration: TigerConnectConfiguration,
    client: OkHttpClient = RelayHttpClient.clientFor(configuration.normalizedBaseUrl, configuration.tls.certificatePins),
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.normalizedBaseUrl, client, executeOverride) {

    override val providerName: String = "TigerConnect"

    override fun authorizationHeaders(): Map<String, String> = mapOf("Authorization" to configuration.authorizationHeader)

    open fun findUsersByEmail(email: String): List<TigerConnectRecord> = lookup("users", "email", email, "user", "users")

    open fun findGroupsByName(name: String): List<TigerConnectRecord> = lookup("groups", "name", name, "group", "groups")

    open fun findRolesByName(name: String): List<TigerConnectRecord> = lookup("roles", "name", name, "role", "roles")

    open fun findDistributionListsByName(name: String): List<TigerConnectRecord> =
        lookup("distribution-lists", "name", name, "distributionList", "distributionLists", "distribution_list", "distribution_lists")

    open fun sendMessage(request: TigerConnectSendRequest): TigerConnectSendResponse {
        val response = executeSuccessful(postJson(url("message"), request.toJsonString()), "send")!!
        return TigerConnectSendResponse.fromJson(response.jsonBody())
    }

    open fun getMessageStatus(messageId: String): TigerConnectMessageStatusResponse? {
        val response = executeSuccessful(get(url("message", messageId, "status")), "status lookup", notFoundAsNull = true) ?: return null
        return TigerConnectMessageStatusResponse.fromJson(response.jsonBody())
    }

    /** Collects the single-object and list forms of a lookup response into one list. */
    private fun lookup(path: String, queryName: String, queryValue: String, vararg fields: String): List<TigerConnectRecord> {
        val response = executeSuccessful(get(url(path, query = mapOf(queryName to queryValue))), "$path lookup", notFoundAsNull = true)
            ?: return emptyList()
        val json: JsonObject = response.jsonBody()
        val records = mutableListOf<TigerConnectRecord>()
        for (field in fields) {
            json.obj(field)?.let { records.add(TigerConnectRecord.fromJson(it)) }
            json.array(field)?.toReqObjList()?.forEach { records.add(TigerConnectRecord.fromJson(it)) }
        }
        return records
    }
}
