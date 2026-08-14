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
    implementation("com.thalovant:thalovant-sdk:0.1.2")
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

Keep `result.identity` secret. It contains the client credentials used by the
hub. Do not log `result.asJson(includeSecrets = true)`.

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

This release (0.1.2) connects over **WSS only**. Requesting `https` or `mqtt`
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
  0.1.2.
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
