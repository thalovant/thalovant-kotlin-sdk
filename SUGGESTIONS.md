Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Follow-ups from browser sign-in (0.10.0): Android's browserLogin, ai_disclosure on ApiToken, a typed browser-unavailable error, a Java-friendly sign-in.

# Suggestions: thalovant-kotlin-sdk

## Decide what `browserLogin()` does on Android

- **Problem:** browser sign-in is for a JVM on a desktop or a server. On Android, `interactiveLogin()` already picks a device login (no `DISPLAY`), and the platform opener does nothing (`openInPlatformBrowser`, `src/main/kotlin/com/thalovant/sdk/BrowserLogin.kt`). A forced `browserLogin()` still binds `127.0.0.1` and waits for a browser nobody opened, unless the caller passes an `opener`. The porting brief says not to advertise it there, so the README says it is not supported, but the code does not refuse it.
- **Proposal:** either refuse `browserLogin()` and `LoginMethod.BROWSER` on Android with an `IllegalStateException` that names `NativeSignIn.begin`, or support it properly with a documented `opener` that launches a Custom Tab (RFC 8252 allows a loopback redirect on a phone). thalovant-android signs in as `thalovant-android` with its own redirect, so neither changes the app.
- **Impact:** low. It turns a documented limit into an enforced one, or into a feature.

## Carry `ai_disclosure` on `ApiToken`

- **Problem:** the native and device token answers carry `ai_disclosure`. `ApiToken.fromJson` (`ControlPlane.kt`) keeps five members and drops it. `browserLogin()` and `loginWithBrowser()` return the raw `JsonObject`, where it survives; `pollDeviceLogin()` returns an `ApiToken`, where it does not. This round left `ApiToken` alone, as the Python reference did.
- **Proposal:** add `aiDisclosure: String? = null` to `ApiToken` (with a hidden constructor overload for binary compatibility) in the coordinated round the Python reference proposes.
- **Impact:** low. It moves the reference digest and every consumer's token type.

## A typed "browser sign-in unavailable" error

- **Problem:** `browserLogin()` throws a plain `ThalovantApiException` with status 404 when the API does not offer browser sign-in, and a status-less one for an unusable configuration. A caller that wants its own fallback has to read `statusCode`; `interactiveLogin()` already falls back.
- **Proposal:** a `ThalovantBrowserLoginUnavailableException : ThalovantApiException` for both, once the Python reference adds its counterpart. The contract deliberately added no error types this round.
- **Impact:** low.

## A sign-in Java callers can use without coroutines

- **Problem:** every sign-in (`login`, `loginWithBrowser`, `browserLogin`, `interactiveLogin`) is a `suspend` function. A Java desktop tool has to bring `kotlinx-coroutines` and `runBlocking` itself, where the Python reference offers a sync form of each.
- **Proposal:** `CompletableFuture`-returning wrappers (`interactiveLoginAsync`, `browserLoginAsync`) on a small facade, or the `kotlinx-coroutines-jdk8` `future { }` bridge documented in the README.
- **Impact:** low to medium, depending on how many JVM callers are not Kotlin.

## Decide whether this SDK should manage chat apps

- **Problem:** chat apps (`/v1/bridges`) are not covered here; they are recorded as not applicable.
- **Proposal:** if an app or service built on this SDK needs to connect or pause chat apps, record the obligation in the API's impact file first, then port the Python reference's `chat-apps` capability (models, `SecretValue`, the twelve owner routes, `fields` on the API error).
- **Impact:** low today; avoids a silent gap if such an app appears.
