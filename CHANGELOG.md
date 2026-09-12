# Changelog

## 0.5.0 — 2026-09-12

- Match Python 0.6.3 request hints, location construction, ordered embedded audio replies, strict bounded hex decoding, and speakable intent examples with original phrase priority.
- Default runtime configuration updates to revision-guarded deep merges. Retry only HTTP 412 (three attempts maximum); fail before writing against older servers. Explicit replacement remains available. Merging now requires both hubs:read and paid hubs:write.
- Add regression coverage for conflict preservation, retry limits, unsupported revisions, audio bounds, caller context preservation, and example ranking.

## 0.4.0 — 2026-09-12

- Preserve the complete accepted skill response in `HubSkillOperationException` after automatic polling fails, so install and removal waits can resume safely.

- Add hub-addressed skill listing, history, install, update and removal.
- Add optional bounded polling and explicit operation resumption without repeating accepted writes.
- Document shared-runtime scope, authorization and cancellation behavior.

## 0.3.3

- Preserve a description timeout when earlier replies contain only empty or refused definitions; fully answered empty inventories still succeed.

- Redact recognized credential fields recursively in default bootstrap and identity metadata displays, including case, underscore, and hyphen variants; preserve explicit secret serialization and reference fields.

- Reject hub ratings outside 1–5 before making an HTTP request.
- Reject duplicate active Ask request IDs and Query IDs on the same client before subscribing or dispatching; preserve separate namespaces and remove reservations on collector cleanup.
- Document fresh correlation IDs for later operations and caller-retained idempotency keys for retryable hub creation.

## 0.3.2

- Apply one Ask timeout across connection admission, authentication, sending and
  reply collection. Clip fixed empty-reply and settling windows to that deadline.
- Freeze Ask collection on policy denial or query timeout, retaining only speech
  received before the hard failure, and interrupt optional waits immediately.
- Apply Query deadlines and terminal replies independently of a retiring send,
  preserving physical transport ownership without replay.
- Propagate non-cancellation write failures during Ask reply phases before
  terminal completion or phase expiry.
- Return the first correlated runtime session ID from Ask and Query while preserving strict request
  correlation and the original request ID.
- Add regressions for blocked connection/send, clipped reply phases, cancellation
  cleanup and explicit event-stream buffer overflow.

## 0.3.1

- Validate both device authorization URLs before displaying a prompt, invoking
  a browser callback, or polling. Accept only HTTP(S) URLs with a host and no
  userinfo, raw whitespace, or control characters; launch with a single URL argument.

- Reject automatic control-plane redirects so 307/308 responses cannot replay
  login credentials to another endpoint.
- Require HTTPS for authorization and request bodies except explicit loopback
  development hosts, and reject URL userinfo without disclosing its contents.
- Enforce redirect policy for injected HTTP transports and add real loopback
  redirect regressions plus validation before I/O.

## 0.3.0

- Add scoped conversations, direct HiveMind query/cascade replies, bounded event
  streams and waits, action/code input helpers, and local connection/health diagnostics.
- Fall back to engine intent names when the detailed listing is silent as well
  as denied. Discover fallback handlers with a bounded optional probe and expose
  known/unknown discovery plus conservative language answerability.
- Keep query collection open after soft intent misses, recover on later speech,
  and retain partial speech when a policy denial or query timeout terminates it.
- Ignore foreign correlated denials and describe replies; retain content-based
  describe matching only when a reply carries no request id.
- Include queued connect admission in each caller's deadline without cancelling
  another caller's socket. Cancel the underlying HTTP call when its coroutine is
  cancelled; prevent raw reflected response bodies entering API error messages.
- Dispatch authenticated callbacks outside the send lock and isolate callback
  failures from the Noise session.
- Add JVM 17/21 CI and an independent pinned Node XX-to-KK loopback peer covering
  exactly two connections, six encrypted exchanges and two scoped queries.

## 0.2.0

- Implement HiveMind v3 Noise WSS with XXpsk2/KKpsk0, both offered cipher suites, Argon2id PSK derivation and persisted client/server identities using Bouncy Castle 1.85.
- Reject legacy downgrade, plaintext application frames, invalid pins, replay and malformed chunks; clear ephemeral session state on reconnect and failure.
- Add independent Node/noble handshake and transport vectors, upstream Argon2/AEAD vectors, loopback encrypted ask/reply and reconnect tests.
- Add `HiveMindNoiseStore` and client injection for application-private persistence. WSS no longer accepts legacy crypto-key handshakes; HTTP/MQTT runtime support remains explicitly unavailable.

## 0.1.8

- Add the intent inventory: `ThalovantClient.intents(languages, options)` reads the hub runtime's intent manifest (OVOS-INTENT-4 §10) over the client's own session and returns a `HubIntentInventory` — every intent each skill registered, per language, with the sentences a person says to reach it as the skill's locale files wrote them, `{slot}` placeholders included. No control-plane credential is involved. `listIntents(lang, options)` and `describeIntent(skillId, intentName, lang, options)` expose the two underlying queries (`ovos.intent.list` / `ovos.intent.describe`) as `IntentRegistration` rows and `IntentDefinition`s; all three are `suspend` functions like `ask()`. The model types are `HubIntentInventory` (`languages`, `skills`, `intents`, `source`, `denied`, `hasPhrases`, `asJson()`), `HubSkillIntents`, and `HubIntent` (`id`, `engine`, `phrases`, `phrasesFor(lang)`, `examples(lang, limit)`), with `IntentInventoryOptions` / `ListIntentsOptions` / `DescribeIntentOptions` carrying the deadline and switches.
- Queries are correlated by `context.request_id` like every other request, and a reply delivered more than once is taken once. Describes are sent together and matched by request id, or by the definition's own `skill_id`/`intent_name`/`lang` for a hub that does not echo the id; a describe that never comes leaves that intent without sentences rather than failing the inventory. Language tags compare case-insensitively with `_`/`-` folded (`sameLanguage`).
- Add `ThalovantPolicyDeniedException` (a `ThalovantRuntimeException`, which is now `open`), thrown at once from the hub's `hive.policy.denied` with `deniedType`, `code`, `reason` and the `allowed` list, instead of waiting for a timeout. `IntentInventoryOptions(fallback = true)`, the default, falls back to the engines' own manifests (`intent.service.adapt.manifest.get` / `intent.service.padatious.manifest.get`) when `ovos.intent.list` is refused; the result then carries names only, `source = HubIntentSource.ENGINE_MANIFESTS`, and `denied = ["ovos.intent.list"]`.
- A runtime that attaches each row's `definition` to `ovos.intent.list` when asked with `include_definitions` is used as such; one that does not is described row by row.
- `listIntents` throws `ThalovantRuntimeException` when the hub answers `ovos.intent.list` with `ok: false`, instead of reading the missing `intents` key as an empty list. A refused listing is not an empty hub, and reporting it as no intents showed a person a device that can do nothing. `describeIntent` keeps returning an empty list for `ok: false`, which is a real answer: the hub does not know that registration.
- `ThalovantPolicyDeniedException.allowed` keeps only string entries. A number or a null in the hub's `allowed` list is not a message type, and stringifying one put `"3"` or `"null"` in front of an operator reading which types to allow.
- `ThalovantEvents` gains the eight bus names involved: `INTENT_LIST`, `INTENT_LIST_RESPONSE`, `INTENT_DESCRIBE`, `INTENT_DESCRIBE_RESPONSE`, `ADAPT_MANIFEST_GET`, `ADAPT_MANIFEST`, `PADATIOUS_MANIFEST_GET`, `PADATIOUS_MANIFEST`.
- Describes go out in windows of at most `DESCRIBE_BATCH` (32), each window its own subscription with its own deadline, rather than putting every request in flight at once. A hub with 69 intents in two languages is 138 requests and, with every reply delivered twice, 276 inbound events; an SDK whose reply queue is bounded drops replies past its capacity and returns an inventory missing sentences. Batching also spares the hub a burst it never asked for, and the per-window deadline means a hub that answers nothing fails after one window rather than holding every request open.
- A describe window that received no reply contributes nothing rather than failing the call; the inventory fails only when no window produced a definition, so a hub silent from the start still fails at the first window. Windows are contiguous slices of the work, so without this an unresponsive skill owning a whole window turned the entire inventory into a timeout while the same skill with fewer intents only lost its sentences.
- `HubIntentInventory.hasPhrases` is true only when at least one intent carries at least one sentence; an inventory whose describes all came back empty does not read as having phrases.
- `intents(languages)` trims each tag and drops a repeat of a language already asked for under another spelling (`en-us`, `en-US` and `en_us` are one language), so the hub is asked once per language; `HubIntentInventory.languages` keeps the first spelling seen, in the order given.
- An intent registered under both engines in one language keeps the template row's sentences: the keyword row, which carries none, no longer overwrites them, whichever order the rows arrive in, and the first row seen names the intent's `engine`. On the names-only fallback the first engine to name an intent decides its `engine` (adapt is asked before padatious, so a name both list is `adapt`).

## 0.1.7

- Automated patch release of the unreleased changes on `main` since v0.1.6.

## 0.1.5

- `ThalovantEvents.FAILURE_EVENTS` now also recognises `ovos.intent.unmatched`, the current OVOS bus event for an utterance that matched no intent (renamed from the legacy Mycroft `complete_intent_failure`, which is retained).
- **Behavior change:** `ask()` now fails fast on an intent failure. Both intent-failure names (`ovos.intent.unmatched` and the legacy `complete_intent_failure`) now set the reply's `failureEvent` and terminate the ask loop immediately, exactly like `hive.policy.denied` / `hive.query.timeout`. Previously `complete_intent_failure` was merely recorded and never marked terminal, so `ask()` waited out its full empty-reply window before giving up; this matches the fail-fast behavior of the Python/Node/Go/Rust SDKs (#22).

## 0.1.4

- Automated patch release of the unreleased changes on `main` since v0.1.3.

## Unreleased

- **BREAKING (security):** Removed the admin analytics endpoint from `getAnalyticsOverview`. `AnalyticsOverviewOptions` no longer has an `admin` flag or an `ownerId` field, and the SDK only ever calls `GET /v1/analytics/overview` (never `GET /v1/admin/analytics/overview`). This SDK ships to non-admin customers, for whom the admin route only ever returned HTTP 403; callers that passed `admin = true` or `ownerId` must drop those arguments.
- **Security:** `BootstrapIdentityResult.asJson()` (the default, `includeSecrets = false`) now redacts the bootstrap secrets the raw `hub` and `client` resources carry — `initial_identify`, the one-shot `initial_identify_token`, the secret `spec` fields (`apiKey` / `password` / `cryptoKey`), and URL userinfo credentials — the same way it already redacted the identity. Previously the redacted view returned `hub` and `client` verbatim and leaked those `POST /v1/clients` credentials. `asJson(includeSecrets = true)` still returns the raw resources unchanged, and no wire-protocol or identity-persistence serialization is affected.
- **Security:** `ThalovantApiException` messages no longer interpolate the raw HTTP response body. The message now carries the status plus a short, single-line, bounded slice of the server detail, so an error body that echoes sent credentials (for example from `POST /v1/clients`, `POST /v1/auth/token`, or `POST /v1/auth/device/token`) is no longer dumped wholesale into logs and stack traces. The full body remains available on `ThalovantApiException.body` for programmatic use, so device-flow error parsing is unaffected.
- **Security:** Documented and test-pinned the secret-bearing types (`ThalovantIdentity`, `MqttBrokerCredentials`, `BootstrapIdentityResult`, `ThalovantControlPlane`) as intentionally plain classes (not `data class`), so their `toString()` cannot leak secrets into logs; a test fails if any is later converted to a `data class`.
- Corrected README guidance that implied the default `asJson()` view was safe while only `asJson(includeSecrets = true)` was sensitive: the raw `hub` / `client` fields carry secrets, and the default `asJson()` now redacts them.

## 0.1.3

- Hub provisioning on `ThalovantControlPlane`: `createHub`, `updateHub`, `deleteHub`, `releaseHub`, `setHubRating`, `clearHubRating`, and `getHubRuntimeCapabilities`, with `HubCreatePayload` / `HubUpdatePayload` / `ReleaseOptions` mapping camelCase Kotlin fields to the API's snake_case body and omitting unset options so the server applies its own defaults.
- `createHub` always sends an `Idempotency-Key` header, generated when not supplied. Retrying one logical create requires the caller to retain and reuse the same explicit key and payload.
- `updateHub` and `deleteHub` take `etag` as a **required** parameter, sent as `If-Match`. The API enforces optimistic locking on both routes and rejects a missing header exactly as it rejects a stale one — HTTP 412 `ETag mismatch`, with nothing changed — so a nullable, defaulted parameter would only have turned a compile-time requirement into a runtime failure.
- Runtime groups: `listRuntimeGroups`, `getRuntimeGroup`, `createRuntimeGroup`, `updateRuntimeGroup`, `getRuntimeGroupConfig`, `updateRuntimeGroupConfig`, `releaseRuntimeGroup`, and `deleteRuntimeGroup`, with `RuntimeGroupCreatePayload` / `RuntimeGroupUpdatePayload`. `updateRuntimeGroupConfig` merges `config` into the stored configuration and sends `personas` only when given. No runtime-group route uses `If-Match` or `Idempotency-Key`, and the SDK sends neither.
- Skills: `installRuntimeGroupSkill` (`InstallSkillOptions` defaulting to `sourceType = "catalog"` and `active = true`) and `uninstallRuntimeGroupSkill`. The skill id is the one free-form path parameter the SDK sends, so it is percent-encoded rather than interpolated raw.
- Skill discovery: `listMarketplaceSkills`, `listRuntimeGroupMarketplace`, and `listRuntimeGroupInventory`. The marketplace catalog needs only `hubs:read` and is **not** paid-gated, so a free-plan token can browse before upgrading; the two group-scoped reads need `hubs:inspect` and are likewise not paid-gated. Neither answers HTTP 409 when no client is connected — they report freshness through the envelope's `source` — while `getHubRuntimeCapabilities` can still 409 when it has neither a live client nor a runtime-group snapshot to fall back on.
- Documented the provisioning walkthrough in the README (discover, create hub, create runtime group, install skill, release) with the paid-plan and scope gates: provisioning writes need a paid plan and `hubs:write` (HTTP 402 / 403), while the rating routes need `hubs:write` without a paid plan.

## 0.1.2

- Documented the two token 429 responses in the README's Common Issues section: `token_rate_limited` (per-plan per-minute request rate, 60/min on the free plan) and `token_quota_exceeded` (per-plan daily/monthly call quota, with `quota`, `limit`, and `used`). Both carry a `Retry-After` header and a matching `retry_after_seconds`, which is authoritative; the SDK does not retry them.
- `DEFAULT_USER_AGENT` is now derived from `SDK_VERSION` (`"ThalovantKotlinSDK/$SDK_VERSION"`) instead of repeating the version in a second literal, and the user-agent test asserts against `SDK_VERSION` rather than a hardcoded string, so a partial version bump can no longer pass the suite.
- The Auto Release workflow no longer rewrites the user-agent literals in `Constants.kt` and `ControlPlaneTest.kt`, which no longer exist; `SDK_VERSION` is the only user-agent source it has to move.

## 0.1.1

- `loginWithBrowser(DeviceLoginOptions)`: browser device-flow sign-in for accounts without a password (for example Google sign-in). Requests `POST /v1/auth/device/authorize` (optional `scopes` / `clientName`), presents `verification_uri` and `user_code` through a `prompt` callback (default prints to stdout), best-effort opens `verification_uri_complete` through a reflective `java.awt.Desktop` lookup that is safely skipped on Android and headless JVMs, and polls `POST /v1/auth/device/token` with coroutine `delay()`, honoring the server `interval` and growing it by 5 s on `slow_down`. On approval the durable scoped API token is stored on `accessToken` exactly like `login()`.
- New `ThalovantDeviceLoginDeniedException` (`access_denied`) and `ThalovantDeviceLoginExpiredException` (`expired_token`); the poll throws `ThalovantTimeoutException` after `timeoutMillis` (default 900 s) and rethrows other API errors as `ThalovantApiException`.
- Documented direct token auth for CI: `ThalovantControlPlane(accessToken = ...)` with a pre-provisioned API token, no login call required.

## 0.1.0

- Initial release of the Thalovant Kotlin SDK for JVM and Android (JVM 17 bytecode), published as `com.thalovant:thalovant-sdk`.
- `ThalovantControlPlane` control-plane client with `https://api.thalovant.com` default, trailing-`/v1` URL normalization, bearer-token auth, and the `ThalovantKotlinSDK/0.1.0` user agent:
  - `login(email, password, scope, otpCode, recoveryCode)` sending `otp_code` / `recovery_code` to `POST /v1/auth/token` only when provided (accounts with MFA enabled receive HTTP 401 `mfa_required` without one);
  - hubs: `listHubs`, `getHub`, and unauthenticated `listPublicHubs` / `getPublicHub`;
  - typed `getOperation()` returning the full 16-field `OperationResource` contract with the `OperationStatus` enum;
  - memory: `listMemoryItems`, `getMemorySummary`, `createMemoryItem`, `getMemoryItem`, `updateMemoryItem`, `deleteMemoryItem` with camelCase Kotlin options mapped to the API snake_case fields;
  - `getAnalyticsOverview(options)` with the admin/workspace endpoint switch (`owner_id` only on admin);
  - `createClientIdentity(hubId, options)` provisioning `POST /v1/clients` with locally generated secrets, `active`, `Idempotency-Key` support, and `initial_identify` parsing into `ThalovantIdentity`.
- `ThalovantIdentity` matching the API `ClientIdentifyResource` fields (`access_key`, `password`, `crypto_key`, `site_id`, `default_port`, `default_master`, `mqtt{endpoint,username,password,topic_prefix,tls}`) with `fromJson` / `fromFile` helpers and secure-permission checks on identity files.
- Protocol handling shared with the other SDKs: `HubProtocolSettings` (`spec.protocols.*.enabled`, WSS default-enabled), `HubDataPlaneEndpoints`, and `selectDataPlaneEndpoint` with the `wss, https, mqtt` preference order.
- `ThalovantClient` data-plane runtime over WSS (OkHttp WebSocket): preshared-key HiveMind handshake, AES-128-GCM encrypted frames, `suspend fun ask(...)` with request-id reply correlation, bus event listeners, and `close()`. `https` and `mqtt` transports throw `ThalovantUnsupportedProtocolException` in this release.
- `ThalovantApiException` carrying the HTTP status code and response body, alongside the shared identity/connection/timeout/runtime/protocol exception surface.
