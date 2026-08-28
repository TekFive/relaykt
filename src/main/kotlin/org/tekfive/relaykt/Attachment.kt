package org.tekfive.relaykt

import org.tekfive.jfk.FromJsonObject
import org.tekfive.jfk.ToJsonObject

/**
 * A file attached to a message. JFK serializes [content] as Base64, so attachments survive a trip
 * through the persisted queue.
 *
 * Deliberately not a `data class`: generated `equals`/`hashCode` would compare [content] by
 * reference, and generated `toString` would dump the payload.
 */
class Attachment(
    val fileName: String,
    val contentType: String,
    val content: ByteArray,
) : ToJsonObject {

    init {
        require(fileName.isNotBlank()) { "Attachment file name must not be blank" }
        require(contentType.isNotBlank()) { "Attachment content type must not be blank" }
    }

    override fun toString(): String = "Attachment(fileName=$fileName, contentType=$contentType, size=${content.size})"

    companion object : FromJsonObject<Attachment>
}
