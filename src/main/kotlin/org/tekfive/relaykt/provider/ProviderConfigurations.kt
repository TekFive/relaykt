package org.tekfive.relaykt.provider

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.JsonObject
import java.lang.reflect.InvocationTargetException

/** Helpers for turning endpoint configuration JSON into a provider's typed configuration class. */
object ProviderConfigurations {

    /**
     * Parses [json] with [reader], surfacing validation failures raised in the configuration
     * class's `init` block as their original exception (JFK constructs through reflection, which
     * would otherwise wrap them in [InvocationTargetException]).
     */
    fun <T : Any> parse(reader: FromJsonObject<T>, json: JsonObject): T {
        try {
            return reader.fromJson(json)
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: RuntimeException) {
            val target = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<InvocationTargetException>().firstOrNull()?.targetException
            throw (target as? RuntimeException) ?: e
        }
    }

    /** Unwraps reflective wrappers so callers see the exception the configuration class threw. */
    fun unwrap(e: Throwable): Throwable {
        var current = e
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }
}
