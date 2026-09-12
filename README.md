# Thalovant Kotlin SDK

Kotlin SDK for connecting JVM and Android apps, services, and agents to
Thalovant hubs.

The control API is used to discover hubs and provision a client identity. After
that, the SDK talks directly to the hub data plane over WSS.

Full docs: <https://docs.thalovant.com/developers/sdks/kotlin/>

## What You Need

- A Thalovant account with API access for authenticated control-plane actions.
- A hub id or slug.
- A client identity for that hub. You can create one through the API or use one
  downloaded from the dashboard.

## Install

```kotlin
dependencies {
    implementation("com.thalovant:thalovant-sdk:0.6.0")
}
```

JVM 17 or newer (or Android with `minSdk` supporting Java 17 bytecode via
desugaring/AGP defaults) is required. The SDK uses OkHttp for HTTP and
WebSocket, kotlinx-serialization for JSON, kotlinx-coroutines for async, and
Bouncy Castle 1.85 for Noise cryptographic primitives.

## Quick Start

```kotlin
import com.thalovant.sdk.CreateClientIdentityOptions
import com.thalovant.sdk.ThalovantClient
import com.thalovant.sdk.ThalovantControlPlane
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val api = ThalovantControlPlane()

    // Public hub discovery does not require auth.
    val publicHubs = api.listPublicHubs(limit = 12)
    println(publicHubs["data"])

    // Auth is required when creating a client identity.
    api.login("you@example.com", "password")

    val result = api.createClientIdentity(
        "hub-id",
        CreateClientIdentityOptions(name = "kotlin-demo-client"),
    )

    val client = ThalovantClient(result.identity)
    try {
        val reply = client.ask("Tell me a short clean joke.")
        println(reply.text)
    } finally {
        client.close()
    }
}
```

`ThalovantControlPlane()` uses `https://api.thalovant.com` by default. Pass a
different URL only for local development or a self-hosted control plane.

Keep `result.identity` secret, and treat the raw `result.hub` and
`result.client` API resources the same way: they carry the bootstrap
credentials (`initial_identify`, the one-shot `initial_identify_token`, and the
secret `spec` fields). The default `result.asJson()` redacts all of these — the
identity secrets, the hub/client secret subkeys, and any URL userinfo
credentials — so it is safe to log. `result.asJson(includeSecrets = true)`
returns the real secrets unchanged, so never log or persist it in a
world-readable place.

Default bootstrap and identity JSON displays also remove recognized credential
fields recursively from metadata (`authorization`, `client_secret`,
`private_key`, `api_secret`, `secret_key`, `credentials`, token fields, and
`initial_identify`), ignoring case,
underscores, and hyphens. Reference fields such as `apiKeyRef` remain intact.
This does not sanitize arbitrary text or alter the explicit `includeSecrets`
serialization used for persistence.

Intent descriptions may return partial results after a timeout only when at
least one reply supplied parsed definitions. Empty or refused descriptions
alone do not hide a missing reply, including in a later batch. When every
requested description is answered explicitly, an empty inventory is valid.

## Log In With MFA

Accounts with multi-factor authentication enabled must include a TOTP code or a
recovery code with the login. Without one the API responds with HTTP 401 and
code `mfa_required`.

```kotlin
api.login("you@example.com", "password", otpCode = "123456")

// Or use a one-time recovery code instead:
api.login("you@example.com", "password", recoveryCode = "abcd-efgh-ijkl")
```

## Sign In With The Browser (Device Flow)

Accounts without a password (for example Google sign-in) authenticate through
the browser device flow. The SDK prints a short code and a verification URL,
optionally opens the browser, and polls until you approve the request:

```kotlin
import com.thalovant.sdk.DeviceLoginOptions

api.loginWithBrowser(
    DeviceLoginOptions(scopes = listOf("hubs:read"), clientName = "kotlin-demo"),
)
```

On approval the returned `access_token` is a durable scoped API token and is
stored on the client exactly like `login(...)`. The server may normalize and
expand the echoed scopes.

- `openBrowser` (default `true`) is best-effort: the browser is opened through
  a reflective `java.awt.Desktop` lookup, which is safely skipped on Android
  and headless JVMs. Pass `prompt = { grant -> ... }` to present
  `verification_uri` and `user_code` yourself (for example in an Android UI).
- The poll honors the server interval and `slow_down` responses.
- Denial throws `ThalovantDeviceLoginDeniedException`, an expired code throws
  `ThalovantDeviceLoginExpiredException`, and exceeding `timeoutMillis`
  (default 900 s) throws `ThalovantTimeoutException`.

## Use A Pre-Made API Token (CI)

Automation that already holds a durable API token (for example one issued by
the device flow or the dashboard) can skip the login entirely:

```kotlin
val api = ThalovantControlPlane(accessToken = System.getenv("THALOVANT_API_TOKEN"))
```

`accessToken` is a mutable property, so a token can also be set (or rotated)
after construction.

## List Your Hubs

Authenticated accounts can list owned or visible hubs:

```kotlin
val page = api.listHubs(limit = 50)
println(page["data"])
```

## Provision Hubs

Hubs, runtime groups, and skills can be created and managed from code. These
routes need a **paid plan** and a token with the **`hubs:write`** scope
("Create and update your hubs" on the dashboard's API Tokens page). A free-plan
token fails with HTTP 402 `API access requires a paid plan.`, and a token
without the scope fails with HTTP 403 `Insufficient scopes`; both arrive as
`ThalovantApiException` with the status code on `statusCode`.

```kotlin
import com.thalovant.sdk.HubCreatePayload
import com.thalovant.sdk.ReleaseOptions
import com.thalovant.sdk.RuntimeGroupCreatePayload
import com.thalovant.sdk.ThalovantControlPlane
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

val api = ThalovantControlPlane(accessToken = System.getenv("THALOVANT_API_TOKEN"))

// 1. Discover what is installable before provisioning anything.
//    The catalog needs only `hubs:read` and is not paid-gated.
for (skill in api.listMarketplaceSkills()["data"]!!.jsonArray) {
    val entry = skill.jsonObject
    println("${entry["skill_id"]?.jsonPrimitive?.content} ${entry["access_tier"]?.jsonPrimitive?.content}")
}

// 2. Create a runtime group to run the skills.
val group = api.createRuntimeGroup(
    RuntimeGroupCreatePayload(name = "kiosks", description = "Lobby kiosks"),
)
val groupId = group["id"]!!.jsonPrimitive.content

// 3. Create a hub attached to it.
val hubPayload = HubCreatePayload(
    name = "joke-garden",
    spec = buildJsonObject {
        put("version", "1.0.0")
        put("protocols", buildJsonObject { put("wss", buildJsonObject { put("enabled", true) }) })
    },
    runtimeGroupId = groupId,
)
val createKey = java.util.UUID.randomUUID().toString()
val hub = api.createHub(hubPayload, idempotencyKey = createKey)
val hubId = hub["id"]!!.jsonPrimitive.content

// 4. Install a skill from the marketplace catalog.
api.installRuntimeGroupSkill(groupId, "skill-weather")

// 5. Release: roll the runtime and the hub onto a release channel.
api.releaseRuntimeGroup(groupId, ReleaseOptions(channel = "stable"))
api.releaseHub(hubId, ReleaseOptions(channel = "stable"))
```

`createHub` sends an `Idempotency-Key` header. Omitting `idempotencyKey`
generates a new key for each call. For a retryable create, generate and retain
one key before the first attempt, then reuse that key and the same payload if
you retry after a timeout. The SDK does not retry automatically:

```kotlin
// Retry only when needed, using the original hubPayload and createKey above.
val hub = api.createHub(hubPayload, idempotencyKey = createKey)
// If this call times out, retry with the same hubPayload and createKey.
```

Updating and deleting a hub use optimistic locking, so `etag` is a **required**
parameter rather than an optional one — the API rejects a missing `If-Match`
exactly as it rejects a stale one, with HTTP 412 and no change made:

```kotlin
import com.thalovant.sdk.HubUpdatePayload

val current = api.getHub(hubId)
val etag = current["etag"]!!.jsonPrimitive.content
api.updateHub(hubId, HubUpdatePayload(active = false), etag = etag)
api.deleteHub(hubId, etag = api.getHub(hubId)["etag"]!!.jsonPrimitive.content)
```

Build the patch from the fields you mean to change rather than round-tripping a
whole hub resource: `name`, `namespace`, and `domain` are immutable after
creation, and sending a *changed* value for any of them fails with HTTP 400.
Deleting a hub also deletes its clients and ACLs. Runtime groups have no
`If-Match` requirement — no runtime-group route reads one — but the API refuses
to delete the workspace default group or a group that still has hubs attached
(HTTP 409).

Runtime configuration is deep-merged using a revision precondition, and `personas` is replaced only
when you pass it:

```kotlin
api.updateRuntimeGroupConfig(groupId, buildJsonObject { put("lang", "en-us") })
println(api.getRuntimeGroupConfig(groupId)["config"])
```

Rating a public hub needs the `hubs:write` scope but **no paid plan**:

```kotlin
api.setHubRating(hubId, 5)
api.clearHubRating(hubId)
```

## Discover Skills

The marketplace catalog is readable with the **`hubs:read`** scope and, unlike
the provisioning routes above, is **not paid-gated** — a free-plan token can
browse the whole catalog before upgrading, and only the install needs a paid
plan. Each entry carries what an install needs (`skill_id`, `source_type`,
`source_ref`, `config_schema`, `secret_schema`) next to presentation fields
(`title`, `summary`, `tags`, `verified`). Admin tokens can additionally pass
`ownerId` to read another tenant's catalog and `includeInactive = true` to see
retired entries; both are silently ignored for non-admin callers rather than
rejected. `forceRefresh = true` re-syncs the global catalog from source first,
which is slower.

Two group-scoped reads need the **`hubs:inspect`** scope and are likewise not
paid-gated. The first resolves the catalog against one runtime group, so each
entry reports whether it is already desired, whether it was observed running,
and whether the tenant plan allows installing it. The second answers what the
group is actually running right now:

```kotlin
val view = api.listRuntimeGroupMarketplace(groupId)
val inventory = api.listRuntimeGroupInventory(groupId, refresh = true)
println("${inventory["source"]} ${inventory["data"]?.jsonArray?.size}")
```

Both answer from a cached snapshot by default; pass `refreshInventory = true`
or `refresh = true` to force a live read from the runtime operator. Neither
fails when nothing is reporting yet — they return an empty `data` list and say
so through the envelope's `source` (`ovos-runtime-operator`,
`runtime-group-cache`, `runtime-group-cache-empty`, or
`ovos-runtime-operator-pending`).

Reading what one *hub* is running needs `hubs:inspect` too, and is the one
discovery read that can fail instead of answering empty:

```kotlin
val capabilities = api.getHubRuntimeCapabilities(hubId)
println(capabilities["counts"]?.jsonObject?.get("total_intents"))
```

With no connected client the API first falls back to the hub's runtime-group
snapshot and answers HTTP 200 with a `source` saying the inventory is not live;
only when there is no snapshot either does it answer HTTP 409. Branch on
`source` rather than assuming a disconnected hub means 409. The route is also
rate-limited and may answer HTTP 429 with a `Retry-After` header.

## Workspace Analytics

Authenticated accounts can read the same overview used by the dashboard:

```kotlin
import com.thalovant.sdk.AnalyticsOverviewOptions

val overview = api.getAnalyticsOverview(
    AnalyticsOverviewOptions(range = "7d", hubId = "hub-id"),
)
println(overview["totals"])
```

## Durable Memory

Private Daily Desk and workspace assistants can manage explicit opt-in memory:

```kotlin
import com.thalovant.sdk.MemoryCreatePayload
import com.thalovant.sdk.MemoryListOptions
import com.thalovant.sdk.MemoryScope
import com.thalovant.sdk.MemoryKind

val memory = api.createMemoryItem(
    MemoryCreatePayload(
        scope = MemoryScope.WORKSPACE,
        kind = MemoryKind.PREFERENCE,
        content = "Prefer America/Toronto for scheduling.",
        tags = listOf("timezone"),
    ),
)
println(memory["id"])

val items = api.listMemoryItems(
    MemoryListOptions(scope = MemoryScope.WORKSPACE, query = "timezone"),
)
println(items["data"])
```

## What Can I Ask?

A connected client can ask its hub what it can be asked, over its own session,
with no control-plane token:

```kotlin
import com.thalovant.sdk.IntentInventoryOptions
import com.thalovant.sdk.ThalovantClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val client = ThalovantClient.fromIdentityFile("_identity.json")
    try {
        val inventory = client.intents(listOf("en-us", "fr-fr"))
        for (skill in inventory.skills) {
            for (intent in skill.intents) {
                println("${intent.id} ${intent.examples("fr-fr")}")
            }
        }
    } finally {
        client.close()
    }
}
```

Each intent carries the sentences a person says to reach it, per language, as
the skill wrote them (`{location}` marks a slot): `phrasesFor(lang)` is the
whole list, `examples(lang, limit)` a few worth showing (whole sentences before
ones with a slot, shorter first), and `engine` says which matcher owns it
(`padatious` for template intents, `adapt` for keyword ones). `asJson()` on
the inventory, a skill, or an intent gives a JSON-ready view.

The hub's connection must be allowed to publish the types the call uses.
`IntentInventoryOptions(describe = false)` lists names and engines and needs
`ovos.intent.list` alone; the default `describe = true` fetches the sentences
too and needs `ovos.intent.describe` as well. Connections the control plane
provisions for SDK clients allow both, and the two engine-manifest types, by
default.

A hub that refuses a type throws `ThalovantPolicyDeniedException` naming it at
once (no timeout), or — with the default `IntentInventoryOptions(fallback =
true)` — lists intent names only from the engines' own manifests and marks the
result `source = HubIntentSource.ENGINE_MANIFESTS` with `denied` naming the
refused query. A hub that answers the listing with `ok: false` throws
`ThalovantRuntimeException` carrying its error text, rather than reporting a
device that can do nothing. `timeoutMs` bounds each query.

The describes go out in windows of at most `DESCRIBE_BATCH` (32) requests,
each window with its own deadline, so a hub with many intents is never sent a
burst larger than a bounded reply queue can hold. A describe that never
answers leaves its intent without sentences rather than failing the call, and
a whole window that answers nothing simply contributes nothing — only a hub
that answers no window at all raises `ThalovantTimeoutException`.

The two underlying queries are exposed too:

```kotlin
val rows = client.listIntents("fr-fr")                          // IntentRegistration per registration
val definitions = client.describeIntent(rows[0].skillId, rows[0].intentName, "fr-fr")
println(definitions.firstOrNull()?.samples)
```

A silent `ovos.intent.list` now uses the same default engine-manifest fallback
as an explicit policy denial. `fallback = false` keeps strict listing timeout
behavior. The `denied` list retains `ovos.intent.list` as the query that triggered
fallback; that marker alone is not proof of a policy denial. Engine-query
failures still reach the caller.

`listFallbacks()` discovers `ovos.skills.fallback.list` handlers. Every inventory
also makes an optional probe, capped at 1.5 seconds including connection,
sending and waiting. `fallbacksKnown = false` means discovery was denied,
silent or explicitly failed; a known empty handler list is different.
`inventory.mayAnswer(lang)` remains true when an enabled intent has phrases,
any fallback handler exists, or fallback discovery is unknown. Missing phrases
alone do not establish that the hub cannot answer a language.

## Use An Existing Identity

Raw identity files (for example the `initial_identify` payload downloaded from
the dashboard) are supported. The SDK rejects identity files that are readable
or writable by other users on Linux and macOS; keep them out of git.

```kotlin
val client = ThalovantClient.fromIdentityFile("_identity.json")
```

Or parse an identity payload directly:

```kotlin
import com.thalovant.sdk.ThalovantIdentity

val identity = ThalovantIdentity.fromJson(identityJson)
```

Identity fields match the API `ClientIdentifyResource` schema: `access_key`,
`password`, `crypto_key`, `site_id`, `default_port`, `default_master`, plus
optional `data_plane_endpoints`, `protocols`, and `mqtt` broker credentials
(`endpoint`, `username`, `password`, `topic_prefix`, `tls`).

## Ask deadlines and correlation

Each logical Ask or Query operation needs a fresh correlation ID. Defaults
already generate one. If you supply an ID, simultaneous Ask calls on one client
must use distinct request IDs, and simultaneous Query calls must use distinct
query IDs. A duplicate active ID raises a runtime error before dispatch. Ask
and Query have separate namespaces, and separate clients are independent.
The reservation ends when its collector unsubscribes, including on cancellation;
it does not cancel or release an admitted physical write. Never reuse an ID for
a later logical operation while a delayed reply from an earlier operation may
still arrive.

Ask uses one total timeout across connection, authentication, send, and replies.
The first nonempty speech starts a fixed settling window (250ms by default).
The first handled or soft-miss event without speech starts a fixed empty-reply
window (5s by default); subsequent speech switches to settling. Both windows
are clipped to the original deadline, and an empty window does not add settling.
Hard policy denial or query timeout freezes collection immediately: prior speech
is returned as a failed partial reply; otherwise the call raises a runtime error.
Caller cancellation removes owned subscriptions and preserves a different caller's
connection attempt. Ask requires a matching request ID and returns the first
nonblank correlated runtime session ID, falling back to the requested session.
Query replies use the same session selection from accepted query events.
Query also uses one deadline across connection, send, and collection; completion
or hard failure returns independently of a retiring send. Non-cancellation write
failures before a terminal reply or phase expiry remain errors. An admitted write
keeps transport ownership until its actual cleanup finishes and is never replayed.

## Control-Plane HTTP Security

Device login validates both verification URLs before displaying a prompt,
invoking a browser callback, or polling. Each URL must use HTTP(S), include a
host, and contain no userinfo, raw whitespace, or control characters. Invalid
grants fail with a generic API error; their URLs are not displayed or launched.

Control-plane requests never follow redirects automatically. Credentials and
request bodies require HTTPS, except explicit `localhost`, `127.0.0.1`, and
`[::1]` HTTP development endpoints. Anonymous body-free reads may use HTTP.
URLs containing userinfo are rejected before I/O. Configure the intended API
endpoint directly instead of relying on a redirect.

The SDK clones supplied OkHttp settings with redirects disabled. Custom
interceptors and authenticators remain trusted application code.

## Protocols

Hubs may expose one or more public data-plane protocols:

- `wss`: secure realtime WebSocket, the default public path and SDK preference.
- `https`: request/response HTTP protocol exposed as HTTPS.
- `mqtt`: broker-mediated MQTT over TLS. Requires per-client broker credentials.

This release connects over **WSS only**. Requesting `https` or `mqtt`
throws `ThalovantUnsupportedProtocolException`. Endpoint selection still honors
the shared preference order `wss, https, mqtt`.

WSS requires HiveMind v3 Noise: first contact uses `XXpsk2`, and a hub with a
persisted static-key pin can use `KKpsk0`. The SDK negotiates
`25519_ChaChaPoly_SHA256` or `25519_AESGCM_SHA256`, derives the PSK from the
identity password and hub node ID using Argon2id, and binds the complete server
HELLO and offer into the handshake transcript. Legacy `crypto_key` envelopes
remain available as standalone helpers; the WSS runtime rejects downgrade and
plaintext application traffic. `connect()` completes after authenticated key
exchange and sending the encrypted client HELLO.

The default Noise state directory is `~/.config/thalovant-kotlin/noise`. Keep its
client private key and server pins across restarts; on POSIX the SDK enforces
0700 directories and 0600 files. Atomic key publication requires filesystem
hard-link support; unsupported filesystems fail closed. Android applications should supply an
app-private path explicitly:

```kotlin
import com.thalovant.sdk.HiveMindNoiseStore
import java.nio.file.Path

val client = ThalovantClient(identity, noiseStore = HiveMindNoiseStore(Path.of(appPrivateDirectory, "noise")))
```

A changed server key fails authentication, including during XX handshakes;
pins are never automatically forgotten after a failure. Verify an intentional
server key rotation before replacing the corresponding saved pin. Reconnects
reuse the static identity and pins but create fresh ephemeral keys and cipher
counters. HTTPS and MQTT runtime implementations remain unsupported.

Inspect what an identity supports:

```kotlin
import com.thalovant.sdk.HubProtocol

println(identity.enabledProtocols())
println(identity.endpointFor(HubProtocol.WSS))
println(identity.endpointFor(HubProtocol.HTTPS))
println(identity.endpointFor(HubProtocol.MQTT))
println(identity.mqtt?.endpoint)
```

## Runtime helpers

Import `com.thalovant.sdk.*` to use the runtime extension helpers:

```kotlin
val conversation = client.conversation(lang = "fr-fr")
val reply = conversation.query("bonjour")
conversation.sendAction("show-details", title = "Details")
conversation.sendCode("001-09", label = "Ticket")
val health = client.healthcheck()
println(health.ok)
```

`conversation()` keeps one session and merges nested context without changing
caller input. Each request gets a fresh request id. `query()` uses HiveMind
query/cascade frames and requires a matching query id and completion event;
it does not automatically replay a request after a disconnect.

`waitForEvent()` accepts session/request filters and a predicate. `listen()`
returns a cold `Flow`, with optional `timeoutMs` and `maxEvents`. Both remove
subscriptions on completion or coroutine cancellation and fail promptly after
transport loss. Streams buffer at most 64 events and report overflow explicitly.

`connectWithInfo()`, `connectionInfo()`, `healthcheck()` and `doctor()` expose
local authenticated connection status. These checks do not assert that every
skill or external dependency is healthy. A queued `connect(timeoutMs)` caller's
deadline includes admission wait; its cancellation does not cancel another
caller's active connection. Cancelling a control-plane coroutine cancels its
underlying HTTP call.

## Events

Register listeners for hub bus events by name:

```kotlin
import com.thalovant.sdk.ThalovantEvents

val subscription = client.on(ThalovantEvents.SPEAK) { event ->
    println(event.text)
}
// ...
subscription.close()
```

## Common Issues

- `Missing Thalovant API access token`: call `api.login(...)` or
  `api.loginWithBrowser(...)` before private control-plane actions, or pass
  `accessToken` to `ThalovantControlPlane`.
- `API access requires a paid plan`: upgrade the workspace before using the SDK
  control-plane API to provision private resources.
- `Unsupported protocol`: the hub does not expose WSS, or the identity was
  created before WSS was enabled. `https` and `mqtt` runtimes are not part of
  this release.
- A request times out: pass a larger `timeoutMs` to `ask(...)`, or a larger
  `IntentInventoryOptions(timeoutMs = ...)` to `intents(...)`.
- `ThalovantPolicyDeniedException`: the hub refused a bus message type this
  connection may not publish (`deniedType`, with the `allowed` list). Allow
  the type in the dashboard's connection settings; for `intents(...)` the
  default fallback answers names only instead of throwing.
- `HTTP 429` with `"code": "token_rate_limited"`: the API token exceeded its
  plan's per-minute request rate (60 requests per minute on the free plan).
  The response carries a `Retry-After` header and a matching
  `retry_after_seconds`; wait that long and resend.
- `HTTP 429` with `"code": "token_quota_exceeded"`: the API token exhausted
  its plan's daily or monthly call quota. The body names which in `quota`
  (`daily` or `monthly`) alongside `limit` and `used`, and `Retry-After`
  points at the next UTC day or month boundary.

Both 429s apply to token-authenticated control-plane calls and surface as
`ThalovantApiException`, whose `statusCode` is 429 and whose `body` holds the
JSON above (the codes and fields live under `detail`). The SDK does not retry
automatically: `Retry-After` is authoritative, so honor it before resending.
Per-plan limits are listed in the dashboard and at
<https://docs.thalovant.com/developers/sdks/kotlin/>.

## API Shape

- `ThalovantControlPlane()`
- `ThalovantControlPlane(apiUrl, accessToken, userAgent)` for local or self-hosted control planes
- `controlPlane.login(email, password, scope, otpCode, recoveryCode)`
- `controlPlane.loginWithBrowser(DeviceLoginOptions(scopes, clientName, openBrowser, prompt, timeoutMillis))`
- `controlPlane.listPublicHubs(limit, cursor)`
- `controlPlane.getPublicHub(hubRef)`
- `controlPlane.listHubs(limit, cursor, ownerId)`
- `controlPlane.getHub(hubId)`
- `controlPlane.getOperation(operationId)` returning a typed `OperationResource`
- `controlPlane.createHub(payload, idempotencyKey)` — paid plan, `hubs:write`
- `controlPlane.updateHub(hubId, payload, etag)` / `controlPlane.deleteHub(hubId, etag)` — `etag` required, sent as `If-Match`
- `controlPlane.releaseHub(hubId, options)`
- `controlPlane.setHubRating(hubId, rating)` / `controlPlane.clearHubRating(hubId)` — `hubs:write`, no paid plan
- `controlPlane.getHubRuntimeCapabilities(hubId)` — `hubs:inspect`, no paid plan
- `controlPlane.listRuntimeGroups(ownerId)` / `controlPlane.getRuntimeGroup(runtimeGroupId)`
- `controlPlane.createRuntimeGroup(payload)` / `controlPlane.updateRuntimeGroup(runtimeGroupId, payload)`
- `controlPlane.getRuntimeGroupConfig(runtimeGroupId)` / `controlPlane.updateRuntimeGroupConfig(runtimeGroupId, config, personas)`
- `controlPlane.releaseRuntimeGroup(runtimeGroupId, options)` / `controlPlane.deleteRuntimeGroup(runtimeGroupId)`
- `controlPlane.installRuntimeGroupSkill(runtimeGroupId, skillId, options)` / `controlPlane.uninstallRuntimeGroupSkill(runtimeGroupId, skillId)`
- `controlPlane.listMarketplaceSkills(options)` — `hubs:read`, no paid plan
- `controlPlane.listRuntimeGroupMarketplace(runtimeGroupId, refreshInventory)` / `controlPlane.listRuntimeGroupInventory(runtimeGroupId, refresh)` — `hubs:inspect`, no paid plan
- `controlPlane.getAnalyticsOverview(options)`
- `controlPlane.listMemoryItems(options)`
- `controlPlane.getMemorySummary(ownerId)`
- `controlPlane.createMemoryItem(payload)`
- `controlPlane.getMemoryItem(memoryId)`
- `controlPlane.updateMemoryItem(memoryId, payload)`
- `controlPlane.deleteMemoryItem(memoryId)`
- `controlPlane.createClientIdentity(hubId, options)`
- `controlPlane.requireRuntimeProtocol(result, protocol)`
- `ThalovantIdentity.fromJson(json)` / `ThalovantIdentity.fromFile(path)`
- `ThalovantClient(identity)` / `ThalovantClient.fromIdentityFile(path)`
- `client.connect(timeoutMs)`
- `client.ask(text, timeoutMs, lang, sessionId, requestId, context)`
- `client.intents(languages, IntentInventoryOptions(timeoutMs, describe, fallback))` returning a `HubIntentInventory`
- `client.listIntents(lang, ListIntentsOptions(timeoutMs, includeDefinitions))` returning `IntentRegistration` rows
- `client.describeIntent(skillId, intentName, lang, DescribeIntentOptions(timeoutMs))` returning `IntentDefinition`s
- `client.sendUtterance(text, lang, sessionId, requestId, context)`
- `client.emit(eventType, data, context)`
- `client.on(eventName, sessionId, requestId, handler)`
- `client.close()`

## Development

```bash
./gradlew build
```


### Shared-runtime skill management

Hub-addressed skill methods select the runtime group attached to the hub UUID.
Every hub sharing that group sees the same skill changes and history. The API
requires a restricted token to cover all served hubs. Reads need `hubs:inspect`
(`hubs:read` implies it); writes need `hubs:write`, an eligible paid plan and ownership.

The history response contains newest-first `event` and `operation` entries,
including nullable actor/version fields. Its limit is 1–200 (50 where omitted).
An accepted mutation is not proof the skill is ready. Optional waiting polls the
operation, with a 120-second default timeout and two-second interval. Polling
never repeats an accepted mutation and starts no new read after its deadline;
an already-running HTTP request retains its normal request timeout.

Methods: `listHubSkills / listHubSkillHistory / installHubSkill / updateHubSkill / removeHubSkill / waitForHubSkillOperation`. Responses preserve API JSON fields. Use
`HubSkillWaitOptions` to opt into waiting. For cancellation-sensitive work, submit
without waiting, retain the complete accepted response (including `operation_id`
and `state`), then pass that response to the wait helper separately. Cancelling waiting does not undo the server operation. After a polling
failure, inspect/resume that operation instead of submitting the write again.

If an automatic wait fails, `HubSkillOperationException.accepted` preserves the
full response, including the install/remove state. Pass it to
`waitForHubSkillOperation` to resume. `timedOut` distinguishes the polling budget
expiring; coroutine cancellation propagates normally.

## Request helpers and safe configuration updates (0.6.0)

Request hints carry a recognized language, ordered intent pipeline, and caller
location without changing the caller's context. Empty hints are omitted. The
location helper requires a city and omits invalid or zero/zero coordinates.
The hub validates language hints against its configured languages.

Replies expose their reported language, ordered speech/audio events, and a
count of dropped media. Embedded skill clips are limited to 4 MiB each and
16 MiB per reply, checked before retention and decoding. Audio does not extend
the reply settlement window. Decoding accepts hexadecimal bytes with ASCII
whitespace between bytes; it never fetches a skill-supplied URL or file path.
The application owns playback (the `play`/`Play` function in this example).

```kotlin
val location = buildLocation(city = "Montréal", country = "CA")
val reply = client.askWithHints("Quel temps fait-il ?", sttLang = "fr-ca", location = location)
for (event in reply.mediaEvents) if (event.isAudio) play(event.audioBytes())
val examples = intent.examplesWithOptions("en-us", speakable = true)
api.updateRuntimeGroupConfig(groupId, delta)
// Explicit full replacement:
api.replaceRuntimeGroupConfig(groupId, fullConfig)
```

Guarded merging requires the `hubs:read` and `hubs:write` scopes and a paid plan.
Safe merging requires an API whose configuration GET returns a valid `revision`
and whose configuration PUT checks `expected_revision`. The SDK rereads and
reapplies the original delta only after HTTP 412, with at most three attempts.
Arrays and scalar values replace; objects merge recursively. Personas replace
only when explicitly supplied. Connection failures, redirects, other statuses,
and ambiguous write results are never retried. No unsafe PATCH fallback is used.
Unconditional replacements must still be coordinated with other writers.

Use the explicit replacement operation shown above when a complete replacement
is intended, including when working with an older API. Existing code relying on
replacement must opt into it when upgrading. Raw intent patterns remain the
default; speakable examples remove optional parts, choose alternatives, and
substitute caller-supplied slots while retaining complete-phrase priority.

The audio limits use encoded-length upper bounds before decoding, so formatting
whitespace consumes budget too. Like Python's `bytes.fromhex`, ASCII whitespace
alone decodes to zero bytes. Bounded malformed clips remain available as event
metadata and fail when decoded; they are never fetched or played automatically.
Distinct audio events may intentionally repeat identical sound content. Only
repeated delivery of the same event object is suppressed where object identity
is available, without counting it as a dropped clip. Rendered example ranking
uses the original pattern's slot presence even when sample values are supplied.

## Locale-aware intent listings

`intent.examplesWithListing("fr-CA", sentence = true)` renders the closest
registered locale as capitalized sentences. `asSentence("quelle heure est-il",
"fr-CA")` returns `"Quelle heure est-il?"`. `speakableWithLanguage(pattern,
slots, lang)` uses bundled thalovant-languages 0.1.1 examples before explicit
slot overrides. The existing `speakable` and `examplesWithOptions` signatures
remain available.

Complete phrases rank before prefixes and slot patterns, then fuller wording up
to eight words. Empty and duplicate rendered phrases do not consume limits. Raw
unlimited examples keep registration order. Omitted languages preserve the
selected registration's locale. Regional matching uses OVOS-compatible
langcodes 3.5.1 CLDR distances; ties preserve order and distances above ten do
not match.

`ListingRules(data)` snapshots a complete JSON tree. Set the `listing` option
or call its methods to use it. `ListingRules(null)` selects bare rendering
without locale data; unknown languages also retain slot names and bare lines.
Invalid regex patterns fail construction. Regex evaluation allows at most
200,000 character accesses per match; `asks` throws `ListingRuleLimitException`
on budget or stack exhaustion, while sentence rendering leaves the line bare.
Rules can be shared between threads; the language data is packaged in the jar.

The jar includes canonical data and its upstream licenses. Regenerate data and
reference cases using the public Python environment pinned in
`scripts/sync-listing-data.py`, selecting `--data-dir src/main/resources/thalovant`
and `--test-dir src/test/resources/thalovant`. Copy both generated license files
into the main resource directory before packaging.

The SDK code and CLDR matching tables are MIT-licensed; bundled
`thalovant-languages` data is Apache-2.0-licensed. Both notices ship with the SDK.
