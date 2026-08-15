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
    implementation("com.thalovant:thalovant-sdk:0.1.4")
}
```

JVM 17 or newer (or Android with `minSdk` supporting Java 17 bytecode via
desugaring/AGP defaults) is required. The SDK uses OkHttp for HTTP and
WebSocket, kotlinx-serialization for JSON, and kotlinx-coroutines for async.

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
val hub = api.createHub(
    HubCreatePayload(
        name = "joke-garden",
        spec = buildJsonObject {
            put("protocols", buildJsonObject { put("wss", buildJsonObject { put("enabled", true) }) })
        },
        runtimeGroupId = groupId,
    ),
)
val hubId = hub["id"]!!.jsonPrimitive.content

// 4. Install a skill from the marketplace catalog.
api.installRuntimeGroupSkill(groupId, "skill-weather")

// 5. Release: roll the runtime and the hub onto a release channel.
api.releaseRuntimeGroup(groupId, ReleaseOptions(channel = "stable"))
api.releaseHub(hubId, ReleaseOptions(channel = "stable"))
```

Creating a hub is idempotent. `createHub` sends a generated `Idempotency-Key`
header, so a call retried after a timeout returns the hub that was already
created instead of making a second one. Pass your own `idempotencyKey` to
control the key.

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

Runtime configuration is merged, not replaced, and `personas` is replaced only
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

## Protocols

Hubs may expose one or more public data-plane protocols:

- `wss`: secure realtime WebSocket, the default public path and SDK preference.
- `https`: request/response HTTP protocol exposed as HTTPS.
- `mqtt`: broker-mediated MQTT over TLS. Requires per-client broker credentials.

This release (0.1.3) connects over **WSS only**. Requesting `https` or `mqtt`
throws `ThalovantUnsupportedProtocolException`. Endpoint selection still honors
the shared preference order `wss, https, mqtt`.

Inspect what an identity supports:

```kotlin
import com.thalovant.sdk.HubProtocol

println(identity.enabledProtocols())
println(identity.endpointFor(HubProtocol.WSS))
println(identity.endpointFor(HubProtocol.HTTPS))
println(identity.endpointFor(HubProtocol.MQTT))
println(identity.mqtt?.endpoint)
```

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
  0.1.3.
- A request times out: pass a larger `timeoutMs` to `ask(...)`.
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
- `client.sendUtterance(text, lang, sessionId, requestId, context)`
- `client.emit(eventType, data, context)`
- `client.on(eventName, sessionId, requestId, handler)`
- `client.close()`

## Development

```bash
./gradlew build
```
