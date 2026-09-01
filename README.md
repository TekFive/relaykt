# RelayKt

RelayKt is a Kotlin library for sending notifications from an application to external messaging
systems — email (SMTP, SendGrid, ZeptoMail), SMS (Twilio), and team chat (Slack, Microsoft Teams,
TigerConnect) — either synchronously, asynchronously in-process, or through a durable,
database-backed delivery queue with retries and delivery receipts.

It is built on the TekFive foundation libraries:

- [KEEP](https://github.com/TekFive/keep) — persistence (queue tables, delivery jobs)
- [ACK](https://github.com/TekFive/ack) — configuration properties
- [JFK](https://github.com/TekFive/jfk) — JSON (message payloads, endpoint configuration)

Requirements: Java 25, Kotlin 2.4, PostgreSQL (only for the durable queue and templates).

## Concepts

| Type | Role |
|---|---|
| `Message` | What to send: `EmailMessage`, `SmsMessage`, or `TeamMessage`. Immutable, JSON-serializable. |
| `MessageAddress` | A recipient or sender on any channel (email address, E.164 number, Slack channel, ...); whitespace is trimmed, blanks rejected. |
| `Provider` | A stateless integration with one external system, registered by id in `ProviderRegistry`. |
| `Endpoint` | An application-owned route: an `id`, a `providerId`, and the provider's configuration JSON (credentials, hosts). |
| `EndpointResolver` | Looks endpoints up by id; registered once at startup so the queue can deliver later. |
| `Relay` | The facade: `send`, `sendAsync`, `enqueue`, `status`. |
| `Capability` | What a provider supports (`STATUS_LOOKUP`, `ATTACHMENTS`, `PRIORITY`, `MULTIPLE_RECIPIENTS`); validated before every send. |
| `DeliveryStatus` | Provider-agnostic status: `QUEUED`, `SENT`, `DELIVERED`, `OPENED`, `READ`, `FAILED`, `UNKNOWN`. |

## Sending

```kotlin
val endpoint = Endpoint(
    id = "transactional-email",
    providerId = SendGridProvider.id,          // "sendgrid"
    configuration = json { "apiKey" set System.getenv("SENDGRID_API_KEY") },
)

val message = EmailMessage.html(
    to = listOf(MessageAddress("jane@example.com", "Jane")),
    from = MessageAddress("noreply@example.com", "Aideway"),
    subject = "Your report is ready",
    body = "<p>The report is available.</p>",
)

// Synchronous: blocks until the provider accepts or rejects the message.
val result: SendResult = Relay.send(message, endpoint)

// Asynchronous, in-process: runs on a virtual thread; nothing is persisted.
val future: CompletableFuture<SendResult> = Relay.sendAsync(message, endpoint)

// Asynchronous, durable: persisted and delivered by the queue processor with retries.
val queuedMessageId: Long = Relay.enqueue(message, endpoint, QueueOptions(label = "report-ready", maxAttempts = 3, trackReceipt = true))
```

Failures are raised as `RelayException`; `recoverable` tells you (and the queue) whether a retry
makes sense (network errors, HTTP 408/429/5xx). Requests that a provider cannot honour — attachments,
priorities, multiple recipients — fail fast with `UnsupportedCapabilityException` before any call is made.

Delivery status for providers with `Capability.STATUS_LOOKUP`:

```kotlin
val status: DeliveryStatus? = Relay.status(result.messageId, endpoint)
```

## Providers

| Provider id | Channel | Configuration keys | Capabilities |
|---|---|---|---|
| `smtp` | email | `host`, `port`, `startTls`, `sslEnabled`, `authenticate`, `username`, `password`, timeouts, `tls` | attachments, multiple recipients |
| `sendgrid` | email | `apiKey`, `baseUrl`, `tls` | + status lookup |
| `zeptomail` | email | `sendMailToken`, `oauthAccessToken` (status), `bounceAddress`, `trackOpens`, `trackClicks`, `tls` | + status lookup |
| `twilio-sms` | sms | `accountSid`, `authToken`, `fromNumber` or `messagingServiceSid`, `baseUrl`, `tls` | status lookup |
| `slack` | team | `botToken`, `baseUrl`, `tls` | multiple recipients (channels, ids, user emails) |
| `msteams` | team | `webhookUrl`, `tls` | priority (Adaptive Card) |
| `tigerconnect` | team | `apiKey`, `apiSecret`, `baseUrl`, `tls` | priority, status lookup, multiple recipients (users, groups, roles, distribution lists) |
| `memory-email` / `memory-sms` / `memory-team` | all | none | everything — records messages for tests (`InMemoryProvider`) |

Each provider has a typed `*Configuration` class documenting its keys; every `baseUrl` / `webhookUrl` must be https (loopback hosts excepted for tests), and `Relay` runs the provider's `validateConfiguration` before any send or enqueue. Add your own provider by
implementing `Provider<M>` and calling `ProviderRegistry.register(provider)`.

### TLS certificate pinning

Every built-in external provider has a strongly typed `TlsConfiguration` value. Its certificate
pins are SHA-256 hashes of certificate public keys in standard `sha256/<base64>` form. Pinning
verifies the remote server during the TLS handshake; the pin itself is not transmitted. Normal
CA-chain and hostname validation still run, and any certificate in the validated chain may satisfy
a configured pin. Supply at least two pins during certificate rotation:

```kotlin
val endpoint = Endpoint(
    id = "transactional-email",
    providerId = SendGridProvider.id,
    configuration = SendGridConfiguration(
        apiKey = System.getenv("SENDGRID_API_KEY"),
        tls = TlsConfiguration.pinned(
            "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", // current key
            "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", // backup key
        ),
    ).toJsonObject(),
)
```

HTTP providers pin the exact hostname in their configured URL. SMTP pinning applies to both
STARTTLS and implicit SSL and is rejected when TLS is disabled. Pinned HTTP clients reject redirects
because a different destination hostname would not be covered by the configured pins. A pin
mismatch fails as a recoverable network error, allowing a durable queued message to retry after
certificate rotation or configuration repair.

## Durable queue

The queue needs a KEEP database connection and its tables plus KEEP's job tables:

```kotlin
object AppSchema : org.tekfive.keep.schema.AppSchema() {
    override val tables = listOf(JobRecordsTable, JobRecordLogsTable) + RelayKtTables.all + myTables
}
```

At startup:

```kotlin
Relay.registerEndpointResolver { id -> endpointRepository.find(id) }   // your endpoint store
jobRegistry += SendQueuedMessageJob                                     // KEEP job coordinator
jobRegistry += UpdateDeliveryReceiptsJob
jobRegistry += CleanQueuedMessagesJob
MessageQueueProcessor.start()
```

`MessageQueueProcessor` polls for ready messages (honouring `deliverAfter` and retry backoff),
creates a `SendQueuedMessageJob` per message, and recovers messages stalled in PENDING/PROCESSING.
Each attempt is recorded in `relay_delivery_attempts`; with `trackReceipt = true` a
`relay_delivery_receipts` row per recipient is polled by `UpdateDeliveryReceiptsJob` until the
provider confirms delivery, reports failure, or `maxReceiptWaitMinutes` elapses.
`MessageQueue.find/attempts/receipts/cancel` expose the queue to the application.

Lifecycle: `QUEUED → PENDING → PROCESSING → SENT | WAITING_TO_RETRY | FAILED`, plus `TIMED_OUT`
(stalled) and `CANCELLED`.

## Templates

`MessageTemplate` stores subject / HTML / text templates with declared, typed variables and
renders them with `TemplateRenderer` (placeholders, `{{#if}}`, `{{#each}}`, number/date/boolean
format specifiers, HTML escaping, injection-safe substitution). A `RenderedTemplate` converts into
any channel's message:

```kotlin
val rendered = MessageTemplateTable.findByIdentifier("appointment-reminder")!!.render(mapOf("patientName" to "Jane"))
Relay.enqueue(rendered.toEmailMessage(to, from), "transactional-email")
Relay.send(rendered.toSmsMessage(listOf(MessageAddress("+15555550100"))), "twilio")
```

## Configuration properties (ACK)

| Property | Default | Purpose |
|---|---|---|
| `RELAY_MAX_ATTACHMENTS_SIZE_BYTES` | 26214400 | Global attachment limit (override per endpoint) |
| `RELAY_HTTP_CONNECT_TIMEOUT_SECONDS` / `RELAY_HTTP_READ_TIMEOUT_SECONDS` / `RELAY_HTTP_CALL_TIMEOUT_SECONDS` | 10 / 30 / 60 | Provider HTTP timeouts |
| `SMTP_CONNECTION_TIMEOUT_DEFAULT_MSECS` / `SMTP_TIMEOUT_DEFAULT_MSECS` / `SMTP_WRITE_TIMEOUT_DEFAULT_MSECS` | 10000 | SMTP timeouts when the endpoint sets none |
| `RELAY_QUEUE_POLL_SLEEP_SECONDS` | 20 | Processor idle sleep |
| `RELAY_QUEUE_BATCH_SIZE` | 100 | Messages dispatched per poll |
| `RELAY_QUEUE_MAX_PENDING_MINUTES` | 30 | Stall detection threshold |
| `RELAY_QUEUE_RETRY_BASE_DELAY_SECONDS` / `RELAY_QUEUE_RETRY_MAX_DELAY_SECONDS` | 120 / 3600 | Exponential retry backoff |
| `RELAY_QUEUE_DEFAULT_MAX_RECEIPT_WAIT_MINUTES` | 1440 | Receipt timeout when the message sets none |
| `RELAY_QUEUE_CLEAN_SENT_KEEP_DAYS` / `RELAY_QUEUE_CLEAN_FAILED_KEEP_DAYS` | 90 / = sent | Queue retention |
| `UPDATE_DELIVERY_RECEIPTS_JOB_FIXED_INTERVAL_SECONDS` / `CLEAN_QUEUED_MESSAGES_JOB_FIXED_INTERVAL_SECONDS` | 300 / 86400 | Scheduled job intervals |

## Installation

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroup("com.github.TekFive") } }
    }
}

dependencies {
    implementation("com.github.TekFive:relaykt:v1.0.1")
}
```

## Building

```bash
./gradlew build                                # unit + integration tests (Docker needed for the queue tests)
./gradlew -Prelaykt.useLocalProjects=true build  # against sibling ../keep ../ack ../jfk checkouts
```

See [differences.md](differences.md) for how RelayKt differs from the messaging module of the
legacy Konnekt library it replaces.
