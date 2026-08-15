# Changelog

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
- `createHub` always sends an `Idempotency-Key` header, generated when not supplied, so a create retried after a timeout returns the hub that already exists instead of making a second one.
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
