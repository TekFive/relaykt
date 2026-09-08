# TigerConnect integration and test environment

Research checked on **2026-09-08**. A TigerConnect provider already existed and was registered in
`ProviderRegistry`. Its HTTP contract was incorrect: unversioned paths, invented plural lookup
routes, `targetType`/`targetId` in the send payload, and flat status responses. The implementation
now follows the published v2 REST API and, where that reference is incomplete, TigerConnect's
published JavaScript SDK. Authenticated vendor testing remains outstanding.

## Sandbox / UAT

**TigerConnect documents a UAT environment.** Its [SDK initialization guide](https://tigerconnect.github.io/js-sdk/api/client/initialize-client/)
recommends `apiEnv: 'uat'` for testing and says to coordinate the environment and base URL with
TigerConnect support. The [official SDK distribution](https://github.com/tigerconnect/js-sdk/blob/master/tigerconnect-sdk-node.min.js)
maps UAT to `https://uat-devapi.tigertext.me`; RelayKt exposes
`TigerConnectConfiguration.UAT_BASE_URL` as `https://uat-devapi.tigertext.me/v2`.

A read-only request without credentials to
`https://uat-devapi.tigertext.me/v2/user_lookup/relaykt-probe%40example.invalid` returned **401**
during this research. This establishes reachability and an authentication requirement, not access
to a provisioned tenant. No TigerConnect credentials or test configuration were present in this
workspace's environment or `.env` files. No authenticated lookup or live message send was performed.

I did not find a public, anonymous sandbox or a verified self-service UAT signup. The
[developer overview](https://developer.tigertext.com/docs/tigertext-platform-overview) directs
customers to contact TigerConnect to review workflows and pricing. Ask the existing account
representative or [TigerConnect support](https://tigerconnect.com/support/) for:

- UAT access, the approved v2 base URL, and a dedicated sender's API key and secret.
- The test organization token and test user/group/distribution-list tokens.
- The sender's user token and role bot tokens if role messaging is needed.
- Confirmation that these credentials can use directory search, priority messages, role groups,
  and message receipts, plus any tenant-specific limits.

The [official SDK README](https://github.com/tigerconnect/js-sdk) also lists
`developersupport@tigerconnect.com`. No support request has been sent. That repository is archived;
confirm continued support for its supplemental behavior with TigerConnect before deployment.

## API contract implemented

| Concern | Implementation and evidence |
|---|---|
| Authentication | HTTP Basic: API key as username, API secret as password. [REST overview](https://developer.tigertext.com/reference/rest-api) |
| API version / host | v2. Production SDK host is `https://api.tigertext.me`; REST reference examples use `https://developer.tigertext.me/v2`. Base URLs remain configurable. A host-only URL gets `/v2` appended. [REST reference](https://developer.tigertext.com/reference/message-1), [SDK distribution](https://github.com/tigerconnect/js-sdk/blob/master/tigerconnect-sdk-node.min.js) |
| User lookup | `GET /v2/user_lookup/{address}` accepts an email, phone number, or user token. Parse `reply.token`; 404 means unresolved. [User lookup](https://developer.tigertext.com/reference/user_lookupuser) |
| Directory search | `POST /v2/search`, using `type`, `directory`, and `display_name`. Search all pages before selecting an exact, case-insensitive name. Cross-type duplicates fail as ambiguous. [Search reference](https://developer.tigertext.com/reference/search) |
| Send | `POST /v2/message?response_format=message`, with `recipient` and `body`, plus optional `sender_organization` / `recipient_organization`. Read `TT-X-Message-Id`, including empty successful responses; fall back to `reply.message_id`. [Message reference](https://developer.tigertext.com/reference/message-1), [SDK distribution](https://github.com/tigerconnect/js-sdk/blob/master/tigerconnect-sdk-node.min.js) |
| Priority | Numeric wire values: 0 normal, 1 high. RelayKt HIGH and URGENT both map to high; TigerConnect has no separate urgent level in the published SDK. [Priority guide](https://tigerconnect.github.io/js-sdk/quickstart/js/messages/priority-messages/), [SDK distribution](https://github.com/tigerconnect/js-sdk/blob/master/tigerconnect-sdk-node.min.js) |
| Status | `GET /v2/message/{id}/status`, parsing `reply.statuses[]`, `reply.client_id`, and `reply.is_recalled`. `New` means SENT, `Delivered` means DELIVERED, `Read`/`Confirmed` mean READ. Recalled messages map to FAILED. [Status reference](https://developer.tigertext.com/reference/messagemessagestatus), [SDK status guide](https://tigerconnect.github.io/js-sdk/quickstart/js/messages/statuses/) |
| Roles | Search account entities whose metadata identifies a role, then create a role P2P group with `POST /v2/role_group` before sending to its token. Requires `organizationId` and `senderUserId`. [SDK role method](https://tigerconnect.github.io/js-sdk/api/messages/sendToRole/), [SDK distribution](https://github.com/tigerconnect/js-sdk/blob/master/tigerconnect-sdk-node.min.js) |

Search result envelopes (`reply.results[].entity`), namespace types, metadata, continuation tokens,
role-group creation, numeric priorities, and the message-ID header are supplemented from the official
SDK, because the REST reference does not fully specify these details. They have local tests but
still need verification against the provisioned UAT tenant. The SDK file inspected had SHA-256
`e281ffa54962398c930a92d15a80d1523152a104d0c33a776dab5f890e21d4f3`.

Message subjects are prepended to the body as `subject + "\n\n" + body`, since the REST send API does
not define a subject field. Attachments are not implemented by this provider. Unknown or failed
receipt lookups keep aggregate delivery UNKNOWN; delivery/read is reported only when all returned
recipient statuses support it. Search fails if pagination repeats or exceeds its 100-page bound.

## Configure RelayKt

```kotlin
val endpoint = Endpoint(
    id = "tigerconnect-uat",
    providerId = TigerConnectProvider.id,
    configuration = TigerConnectConfiguration(
        apiKey = requireNotNull(System.getenv("TIGERCONNECT_API_KEY")),
        apiSecret = requireNotNull(System.getenv("TIGERCONNECT_API_SECRET")),
        baseUrl = TigerConnectConfiguration.UAT_BASE_URL,
        organizationId = requireNotNull(System.getenv("TIGERCONNECT_ORGANIZATION_ID")),
        senderUserId = System.getenv("TIGERCONNECT_SENDER_USER_ID"), // needed for roles
    ).toJsonObject(),
)

val result = Relay.send(
    TeamMessage(
        to = listOf(MessageAddress("user:<test-user-token>")),
        body = "Synthetic RelayKt integration test",
    ),
    endpoint,
)
val status = Relay.status(result.messageId, endpoint)
```

Recipient formats:

- Email, E.164 phone number, or UUID user token: exact user lookup.
- `user:<token>`, `group:<token>`, `role:<role-bot-token>`, `distribution_list:<token>`:
  explicit targets that bypass directory lookup. A role still uses the role-group flow.
- Plain group, role, or distribution-list name: organization-scoped directory search. An exact
  match must be unique across all three types. Use explicit tokens when names collide.

Name lookup requires `organizationId`; direct users/groups/lists can use the sender's default
organization when it is omitted. Role sends require both organization and sender user tokens;
configuration is checked before sending to any recipient. The API key determines the authenticated
sender; `TeamMessage.from` does not impersonate another account.

## Run tests

Local contract tests and a real HTTP round trip against a loopback mock require no credentials:

```bash
./gradlew test --tests 'org.tekfive.relaykt.team.TigerConnect*'
```

These tests check request paths, authentication, body encoding, response envelopes, pagination,
ambiguous recipients, role routing, transient failures, partial sends, and receipt aggregation.
The mock only simulates the contract and does not establish vendor compatibility.

For an authenticated UAT lookup, load the key and secret into the shell environment from your secret
store, then configure the provisioned test tenant. The task requires an explicit URL and never
falls back to the production default:

```bash
export TIGERCONNECT_TEST_BASE_URL='https://uat-devapi.tigertext.me/v2'
export TIGERCONNECT_ORGANIZATION_ID='<test-organization-token>'
export TIGERCONNECT_TEST_USER='<test-user-email-or-token>'
# TIGERCONNECT_API_KEY and TIGERCONNECT_API_SECRET must already be set.
./gradlew tigerConnectLiveTest
```

By default, the task only looks up the test user. Two optional checks are available:

```bash
# Read an existing, unexpired test message's receipt:
TIGERCONNECT_TEST_MESSAGE_ID='<existing-test-message-id>' ./gradlew tigerConnectLiveTest

# Send ONE synthetic notification and fetch its receipt:
TIGERCONNECT_SEND_TEST_MESSAGE=true \
TIGERCONNECT_TEST_RECIPIENT='user:<test-user-token>' \
./gradlew tigerConnectLiveTest
```

The send flag is exact and case-sensitive (`true`). Set `TIGERCONNECT_SENDER_USER_ID` before testing
roles. The ordinary `test` / `build` tasks exclude these live tests even when credentials and the
send flag are present. Explicit live runs are never treated as up-to-date or cached. Test reports
are in `build/reports/tests/tigerConnectLiveTest/index.html`.

Start with a direct test user, then verify groups, lists, priorities, and roles in the provisioned
organization. Check receipt changes after opening the message in the recipient's TigerConnect app.
