package org.tekfive.relaykt.endpoint

/**
 * Looks up an [Endpoint] by id. The application registers one resolver with
 * [org.tekfive.relaykt.Relay.registerEndpointResolver]; the queue uses it when delivering a
 * message that was enqueued with only an endpoint id.
 */
fun interface EndpointResolver {
    fun resolve(endpointId: String): Endpoint?
}

/** Simple resolver backed by a fixed set of endpoints; handy for small applications and tests. */
class StaticEndpointResolver(endpoints: Collection<Endpoint>) : EndpointResolver {

    private val byId = endpoints.associateBy { it.id }

    constructor(vararg endpoints: Endpoint) : this(endpoints.toList())

    override fun resolve(endpointId: String): Endpoint? = byId[endpointId]
}
