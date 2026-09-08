package org.tekfive.relaykt.team.tigerconnect

import okhttp3.OkHttpClient
import okhttp3.Request
import org.tekfive.jfk.JsonObject
import org.tekfive.jfk.json
import org.tekfive.relaykt.http.HttpResponse
import org.tekfive.relaykt.http.JsonHttpClient
import org.tekfive.relaykt.http.RelayHttpClient
import org.tekfive.relaykt.provider.ProviderException

/** TigerConnect v2 REST client. Contract sources and UAT setup are in docs/tigerconnect.md. */
open class TigerConnectClient(
    private val configuration: TigerConnectConfiguration,
    client: OkHttpClient = RelayHttpClient.clientFor(configuration.normalizedBaseUrl, configuration.tls.certificatePins),
    executeOverride: ((Request) -> HttpResponse)? = null,
) : JsonHttpClient(configuration.normalizedBaseUrl, client, executeOverride) {

    override val providerName: String = "TigerConnect"

    override fun authorizationHeaders(): Map<String, String> = buildMap {
        put("Authorization", configuration.authorizationHeader)
        configuration.organizationId?.let { put("TT-X-Organization-Key", it) }
    }

    /** This endpoint performs an exact address lookup, not a fuzzy directory search. */
    open fun findUser(address: String): TigerConnectRecord? {
        val response = executeSuccessful(get(url("user_lookup", address)), "user lookup", notFoundAsNull = true) ?: return null
        val reply = response.reply()
        return TigerConnectRecord(
            id = reply.string("token"),
            name = reply.string("display_name"),
            email = address.takeIf { it.contains('@') },
        )
    }

    open fun findUsersByEmail(email: String): List<TigerConnectRecord> = listOfNotNull(findUser(email))

    open fun findGroupsByName(name: String): List<TigerConnectRecord> = search("group", name)

    open fun findRolesByName(name: String): List<TigerConnectRecord> = search("account", name, rolesOnly = true)

    open fun findDistributionListsByName(name: String): List<TigerConnectRecord> = search("distribution_list", name)

    open fun sendMessage(request: TigerConnectSendRequest): TigerConnectSendResponse {
        require(request.targetType in setOf("user", "group", "role", "distribution_list")) { "Unsupported TigerConnect target type" }
        require(request.targetId.isNotBlank()) { "TigerConnect recipient token is required" }
        val payload = request.toJsonObject()
        if (request.targetType == "role") payload["recipient"] = createRoleGroup(request.targetId)
        configuration.organizationId?.let {
            payload["sender_organization"] = it
            payload["recipient_organization"] = it
        }
        val response = executeSuccessful(postJson(url("message", query = mapOf("response_format" to "message")), payload.toJsonString()), "send")!!
        // The normal REST response can have an empty body. The SDK uses this header as the id.
        val headerId = response.header("TT-X-Message-Id")?.takeIf { it.isNotBlank() }
        val envelope = response.jsonBody()
        requireSuccess(envelope)
        val reply = envelope.obj("reply")
        val messageId = headerId ?: reply?.string("message_id")?.takeIf { it.isNotBlank() }
            ?: throw ProviderException("TigerConnect response did not include a message id")
        return TigerConnectSendResponse(messageId = messageId, status = "sent")
    }

    open fun getMessageStatus(messageId: String): TigerConnectMessageStatusResponse? {
        val response = executeSuccessful(get(url("message", messageId, "status")), "status lookup", notFoundAsNull = true) ?: return null
        val reply = response.reply()
        return TigerConnectMessageStatusResponse(
            messageId = reply.string("client_id"),
            recipientStatuses = reply.array("statuses")?.toReqObjList()?.map { it.string("status") }.orEmpty(),
            isRecalled = reply.boolean("is_recalled") ?: false,
        )
    }

    /** Roles use a role P2P group, as in the official SDK's roles.createP2PGroup. */
    private fun createRoleGroup(roleToken: String): String {
        require(!configuration.organizationId.isNullOrBlank() && !configuration.senderUserId.isNullOrBlank()) {
            "TigerConnect role messages require organizationId and senderUserId"
        }
        require(roleToken != configuration.senderUserId) { "TigerConnect role recipient must differ from the sender" }
        val payload = json {
            "created_by" set configuration.senderUserId
            "members" set listOf(configuration.senderUserId, roleToken)
            "name" set "RelayKt notification"
            "replay_history" set false
        }
        val response = executeSuccessful(postJson(url("role_group"), payload.toJsonString()), "role group creation")!!
        return response.reply().string("token")?.takeIf { it.isNotBlank() }
            ?: throw ProviderException("TigerConnect role group response did not include a token")
    }

    /** Fetch every page before selecting an exact name so later duplicates remain ambiguous. */
    private fun search(type: String, name: String, rolesOnly: Boolean = false): List<TigerConnectRecord> {
        require(!configuration.organizationId.isNullOrBlank()) { "TigerConnect name lookup requires organizationId" }
        val records = mutableListOf<TigerConnectRecord>()
        val continuations = mutableSetOf<String>()
        var continuation: String? = null
        do {
            val payload = json {
                "type" set listOf(type)
                "directory" set listOf(configuration.organizationId)
                "display_name" set name
                "results_format" set "entities"
                "render_metadata" set true
                if (continuation != null) "continuation" set continuation
            }
            val reply = executeSuccessful(postJson(url("search"), payload.toJsonString()), "directory search")!!.reply()
            val results = reply.array("results")?.toReqObjList()
                ?: throw ProviderException("TigerConnect search response did not include results")
            for (result in results) {
                if (result.string("type") != "tigertext:entity:$type") continue
                if (result.string("organization_id") != configuration.organizationId) continue
                val entity = result.obj("entity") ?: continue
                if (rolesOnly && entity.obj("metadata")?.string("feature_service") != "role") continue
                records.add(TigerConnectRecord(entity.string("token"), entity.string("display_name") ?: entity.string("name")))
            }
            continuation = reply.obj("metadata")?.string("continuation")?.takeIf { it.isNotBlank() }
            if (continuation != null && (!continuations.add(continuation) || continuations.size >= 100)) {
                throw ProviderException("TigerConnect search pagination did not complete")
            }
        } while (continuation != null)
        return records
    }

    private fun HttpResponse.reply(): JsonObject {
        val envelope = jsonBody()
        requireSuccess(envelope)
        return envelope.obj("reply") ?: throw ProviderException("TigerConnect response did not include a reply")
    }

    private fun requireSuccess(envelope: JsonObject) {
        if (envelope.string("status")?.lowercase() in setOf("fail", "failed", "error")) {
            throw ProviderException("TigerConnect returned an unsuccessful API response")
        }
    }
}
