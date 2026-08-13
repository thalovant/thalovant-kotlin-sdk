# Changelog

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
