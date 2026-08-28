package org.tekfive.relaykt.queue

import org.jetbrains.exposed.v1.core.Table
import org.tekfive.relaykt.template.MessageTemplateTable

/**
 * Every table RelayKt persists to. Applications add these to their KEEP `AppSchema` (or create
 * them with `SchemaUtils.create`). The queue additionally requires KEEP's job tables
 * (`JobRecordsTable`, `JobRecordLogsTable`) because delivery runs as KEEP jobs.
 */
object RelayKtTables {
    val queue: List<Table> = listOf(QueuedMessageTable, DeliveryAttemptTable, DeliveryReceiptTable)

    val templates: List<Table> = listOf(MessageTemplateTable)

    val all: List<Table> = queue + templates
}
