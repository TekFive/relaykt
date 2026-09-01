# RelayKt vs. Konnekt messaging — design decisions

RelayKt replaces the `org.tekfive.konnekt.message` module of the legacy Konnekt library. Konnekt
was used as the specification of *what* to support (email, SMS, team messaging; direct sends and
a persisted queue with attempts and receipts; templates; PHI-safe error handling). The code was
restructured rather than ported. This document lists every deliberate difference and why.

## 1. Scope and project

| | Konnekt | RelayKt |
|---|---|---|
| Scope | LLM clients, blob storage, geocoding, *and* messaging | Messaging only |
| Root package | `org.tekfive.konnekt.message` | `org.tekfive.relaykt` |
| Java / Kotlin / Gradle | 21 / 2.3 / 8.12 | **25 / 2.4.10 / 9.7** (Kotlin DSL, version catalog) |
| OkHttp | 4.12 | 5.4 |
| Dependencies | keep v1.0.0, ack v1.0.0, jfk `55b9e2676e`, kviash | keep **v1.0.9**, ack v1.0.0, jfk **v1.0.1** (pinned with a strict constraint because keep's POM requests the `55b9e2676e` build); kviash dropped (unused by messaging) |
| Exposed | `compileOnly` | Inherited transitively from keep's `api` dependency |

The `konnekt/` checkout in this repository is reference material only and is ignored by git.

## 2. One model instead of three parallel ones

Konnekt grew one vocabulary per channel: `EmailProviderTypeConfiguration` /
`SmsEndpoint` / `TeamMessageEndpoint`, three resolver interfaces, three `*Response` types, three
`*Status` enums, three `*Capability` enums, and three service objects with copy-pasted
exception-classification blocks. RelayKt collapses these:

| Konnekt | RelayKt | Rationale |
|---|---|---|
| `MessageAddress` **and** `MessageRecipient` (identical) | `MessageAddress` | One type; a sender and a recipient are the same shape. `toString` is redacted (addresses are PII and end up in logs). |
| `EmailAttachment`, `TeamMessageAttachment` | `Attachment` | Identical fields. |
| `MessageType` (email/sms/team_message) | `Channel` | Same `DataEnum`, but it also knows how to deserialize its message type (`Channel.readMessage`), which removes the `when` in the send job. |
| `EmailStatus`, `SmsStatus`, `TeamMessageStatus` | `DeliveryStatus` | Union of all values (`QUEUED, SENT, DELIVERED, OPENED, READ, FAILED, UNKNOWN`) with `isReceived` so receipt tracking is channel-neutral. |
| `EmailCapability`, `SmsCapability`, `TeamMessageCapability` | `Capability` | Adds `MULTIPLE_RECIPIENTS`, which replaces Twilio's ad-hoc "exactly one recipient" checks. |
| `EmailResponse`, `SmsResponse`, `TeamMessageResponse` | `SendResult` | Adds `recipientMessageIds` so fan-out providers (Slack, TigerConnect) report one id per recipient; receipts are then tracked and resolved per recipient instead of sharing one aggregate status. |
| `EmailProviderTypeConfiguration`, `SmsEndpoint`, `TeamMessageEndpoint` (+ `MessageProviderTypeConfiguration`) | `Endpoint(id, providerId, configuration, maxAttachmentsSizeBytes)` | |
| Three `*Resolver` fun-interfaces registered on three services | One `EndpointResolver` registered on `Relay` (+ `StaticEndpointResolver`) | |
| `EmailProvider`, `SmsSender`, `TeamMessageSender` interfaces | `Provider<M : Message>` | One contract: `send`, optional `status`, `capabilities`, `validateConfiguration` (invoked by `Relay.validate` before every send/enqueue). |
| `EmailProviderType`, `SmsServiceProvider`, `TeamMessageServiceProvider` enums | `ProviderRegistry` keyed by string id | Enums are closed; applications could not add a provider without forking. Built-ins are registered by default, custom ones via `ProviderRegistry.register`. |
| `EmailService`, `SmsService`, `TeamMessageService` | `Relay` | One facade, one validation path, one `classify` for retryability. |
| `MessagingException(recoverable, …)`, `TeamMessageException`, `HttpStatusException`, `TwilioSendGridException`, `TwilioSmsException`, `SlackException`, `TigerConnectException` | `RelayException(recoverable)` + `UnsupportedCapabilityException`; providers throw one `ProviderException(statusCode)` | Providers no longer reason about retries at all; `Relay.classify` maps status codes / I/O errors to `recoverable` in one place. |
| `QueuedSmsMessage`, `QueuedTeamMessage` (queue-only DTOs duplicating message fields) + `QueuedMessageMetadata` | `QueueOptions` + the same `Message` types for every path | Sync, async and queued sends take the same message object. |

`Message` is an abstract class (not sealed) so channel types can live in `email`, `sms`, `team`
sub-packages; `Channel` is the closed list.

## 3. Three delivery modes

Konnekt's "asynchronous" path was only the persisted queue. RelayKt offers:

1. `Relay.send` — synchronous.
2. `Relay.sendAsync` — in-process, returns `CompletableFuture<SendResult>` on a
   virtual-thread-per-task executor (Java 25). Validation still runs on the caller's thread so
   programming errors surface immediately. Suitable for fire-and-forget where durability is not required.
3. `Relay.enqueue` — durable, KEEP-backed queue.

All three share `Relay.validate` (capabilities, attachment limit) and `Relay.classify`.

## 4. Queue changes

| Area | Konnekt | RelayKt |
|---|---|---|
| Tables | `queued_messages`, `queued_message_attempts`, `message_receipt`, `message_templates` | `relay_queued_messages`, `relay_delivery_attempts`, `relay_delivery_receipts`, `relay_message_templates` — prefixed so they coexist with application tables and with a Konnekt-era schema during migration. `RelayKtTables.all` lists them for the app's `AppSchema`. |
| Retry timing | Fixed `MQP_DEFAULT_MIN_WAIT_RETRY_SECS`, evaluated from `lastStateChangeAt` | Explicit `next_attempt_at` column set by the send job with exponential backoff (`RETRY_BASE_DELAY_SECONDS` × 2^(attempt−1), capped by `RETRY_MAX_DELAY_SECONDS`). The poller query is simpler and the schedule is visible in the row. |
| Default `maxAttempts` | 1 | 3 |
| Attempt record | state + details | + `recoverable` flag, so failure reports can distinguish transient from permanent causes |
| Provider message id | Only inside `receipt_details` JSON | First-class `provider_message_id` column on the queued message |
| Receipts | `MessageReceiptDetails` / `receipt_details` JSON blob; no polling job in the module | `DeliveryReceipt` rows carry `providerId`, `providerMessageId`, `lastDeliveryStatus`, `lastCheckedAt`; **`UpdateDeliveryReceiptsJob`** polls providers (one status call per provider message id), resolving receipts to `RECEIVED` / `DELIVERY_FAILURE` / `TIMED_OUT`. |
| Poller | `MessageQueueProcessor` thread, unbounded select | Same design, plus `batchSize`, `processOnce()` for tests/external schedulers, a daemon thread, and `isRunning`. Stall recovery semantics are unchanged (PENDING → QUEUED, PROCESSING → TIMED_OUT). |
| Cancellation | none | `MessageQueue.cancel` from QUEUED/PENDING/WAITING_TO_RETRY (state-guarded so a running job is never raced; returns the previous state) |
| Cleanup | `CleanMessagesJob`, 60×24 days | `CleanQueuedMessagesJob`, 90 days sent / same for failed, `exclusiveExecution` |
| ACK namespaces | `MQP_*`, `EMAIL_*`, unprefixed `CLEAN_MESSAGES_*` | `RELAY_*`, `RELAY_QUEUE_*`, `SMTP_*` (see README) |

Kept from Konnekt because they were right: encryption at rest of recipients and payload
(`encryptedStringList`, `encryptedJsonObject`, `encryptedText`), optimistic state guards on every
transition, receipt-persistence failures never flipping a sent message to FAILED, and the
"external transition while sending" guard.

## 5. Providers

All HTTP provider clients extend one `JsonHttpClient` base (URL building, auth headers, scrubbed
status-only errors, 404→null lookups, `executeOverride` for tests) instead of five near-identical
hand-written clients. Every external provider, including SMTP, accepts a strongly typed
`TlsConfiguration` containing SHA-256 SPKI pins. HTTP clients enforce them with OkHttp and SMTP uses
a platform-trust-delegating TLS trust manager, so pinning never replaces CA or hostname validation.

| Provider | Change |
|---|---|
| SMTP | `replyTo` support; returns the generated `Message-ID` as `messageId` (Konnekt returned `""`). Header-injection defences and address validation retained. |
| SendGrid | `baseUrl` must be https. Renamed from "Twilio SendGrid" (`twilio-sendgrid` → `sendgrid`); `reply_to`; `click` events map to `OPENED`; 403 on activity lookup (missing permission) returns null instead of throwing. |
| ZeptoMail | `bounceAddress` / `trackOpens` / `trackClicks` are typed fields on `ZeptoMailConfiguration` instead of raw JSON reads; `reply_to` support. Status parsing reads `data` as the object the email-log API actually returns (Konnekt expected an array, so real lookups always resolved to UNKNOWN); `baseUrl` must be https. |
| Twilio SMS | id `twilio-sms`; a message-level `from` overrides the endpoint `fromNumber`; `from` is optional on `SmsMessage` because messaging services provide it. Single-recipient rule expressed through the missing `MULTIPLE_RECIPIENTS` capability so it fails before any request. `TEST` provider replaced by `InMemoryProvider`. |
| Slack | Partial-failure handling shared through `TeamMessageSupport`. Only definitive lookup failures (`users_not_found`, 4xx) leave a recipient unresolved; transient errors (I/O, 408/429/5xx) propagate as recoverable so the queue retries instead of failing permanently (Konnekt swallowed both). `baseUrl` must be https. |
| TigerConnect | Same transient-vs-definitive lookup rule as Slack; `baseUrl` must be https. Distribution-list target type corrected from `distribution_type` (flagged as a suspected typo in Konnekt) to `distribution_list`; user lookup is skipped for addresses without `@`; lookup responses parsed through one `TigerConnectRecord` type. |
| **Microsoft Teams (new)** | `msteams` posts an Adaptive Card to an Incoming Webhook / Power Automate URL. Recipients are rendered into the card (a webhook targets a fixed channel), priority becomes header colour + label. No status lookup. |
| **InMemoryProvider (new)** | Registered for every channel; records sends, scriptable failures and statuses, usable by applications' own tests. |

`TeamMessage.from` is optional (bots and webhooks identify themselves).

## 6. Templates

`TemplateRenderer` is carried over essentially intact (its injection-safety design is sound).
Differences: `RenderedMessage` → `RenderedTemplate`, which now converts to any channel
(`toEmailMessage`, `toSmsMessage`, `toTeamMessage`) rather than only email, and falls back to the
text body / `text/plain` when a template has no HTML body. `MessageTemplate.render(...)` is a
convenience on the entity. The subject control-character regex uses `\p{Cntrl}` (same set).

## 7. Testing

Konnekt's messaging tests needed a live SMTP fake and hand-rolled HTTP servers. RelayKt provider
tests stub the HTTP layer through `JsonHttpClient.executeOverride`, and the queue is tested end to
end against PostgreSQL via Testcontainers (skipped automatically when Docker is unavailable) using
`SendQueuedMessageJob`, `UpdateDeliveryReceiptsJob` and `CleanQueuedMessagesJob` executed directly
with a fake `JobContext`.

## 8. Things intentionally *not* carried over

- Konnekt's LLM, `Krate` storage and geocoding modules — out of scope.
- `EndpointBuilder`-style construction of endpoints from `KONNEKT_*` ACK properties — endpoints are
  application data; the app supplies an `EndpointResolver`.
- The `active` flag on senders — activation is an endpoint concern.
- `kviash` dependency — nothing in messaging used it.
