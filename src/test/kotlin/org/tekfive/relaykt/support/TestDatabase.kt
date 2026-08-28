package org.tekfive.relaykt.support

import com.google.crypto.tink.aead.AeadConfig
import org.jetbrains.exposed.v1.core.Table
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.tekfive.ack.configuration.AckRegistry
import org.tekfive.ack.sources.MapSource
import org.tekfive.keep.db.DbConnection
import org.tekfive.keep.db.db
import org.tekfive.keep.encryption.DatabaseEncryptionProvider
import org.tekfive.keep.encryption.EncryptionKeysetMode
import org.tekfive.keep.encryption.KeysetIO
import org.tekfive.keep.encryption.KeysetLoader
import org.tekfive.keep.encryption.KeysetTemplate
import org.tekfive.keep.job.db.JobRecordLogsTable
import org.tekfive.keep.job.db.JobRecordsTable
import org.tekfive.keep.schema.AppSchema
import org.tekfive.relaykt.queue.RelayKtTables
import java.nio.file.Files

/**
 * Singleton PostgreSQL container for integration tests, started on first access. Configures ACK,
 * KEEP's connection, column encryption, and creates the RelayKt + KEEP job tables.
 */
object TestDatabase {

    val dockerAvailable: Boolean by lazy {
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (e: Throwable) {
            false
        }
    }

    private object Schema : AppSchema() {
        override val tables: List<Table> = listOf(JobRecordsTable, JobRecordLogsTable) + RelayKtTables.all
    }

    private val started: Unit by lazy {
        val container = PostgreSQLContainer("postgres:17-alpine").apply {
            withDatabaseName("relaykt_test")
            withUsername("test")
            withPassword("test")
            start()
        }

        AckRegistry.clear().addSource(
            MapSource(
                mapOf(
                    "JDBC_URL" to container.jdbcUrl,
                    "JDBC_USER" to container.username,
                    "JDBC_PASSWORD" to container.password,
                    "POOL_JDBC_CONNECTIONS" to "false",
                    "RELAY_QUEUE_RETRY_BASE_DELAY_SECONDS" to "0",
                ),
            ),
        )

        // Encrypted columns resolve their Aead at class-init time, so the encryption provider must
        // be ready before any test touches those tables.
        AeadConfig.register()
        DatabaseEncryptionProvider.resetForTesting()
        val keysetPath = Files.createTempDirectory("relaykt-test-keyset").resolve("keyset.json")
        KeysetIO.write(KeysetTemplate.generateNewKeysetHandle(), keysetPath)
        DatabaseEncryptionProvider.configure(KeysetLoader.Config(mode = EncryptionKeysetMode.PLAINTEXT, file = keysetPath))
        DatabaseEncryptionProvider.ensureInitialized()

        DbConnection.startup()
        db {
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec("CREATE EXTENSION IF NOT EXISTS citext")
            Schema.create()
        }
    }

    fun ensureStarted() {
        started
    }

    fun truncateAll() {
        db {
            val names = (RelayKtTables.all + JobRecordsTable + JobRecordLogsTable).joinToString(", ") { it.tableName }
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec("TRUNCATE TABLE $names RESTART IDENTITY CASCADE")
        }
    }
}
