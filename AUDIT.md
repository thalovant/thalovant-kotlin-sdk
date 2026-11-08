Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Browser sign-in (0.10.0) at the final reference 39e31d8: known limits, the flaky revoke-race test, the CodeRabbit findings on #59, and OkHttp replaying the exchange.

# Audit

These findings come from the CodeRabbit threads on merged PRs #35, #37, #41 and #46, and from the browser sign-in round (0.10.0). They were checked against the code and against `./gradlew build`, which passes 433 tests at the time of writing (branch `feat/browser-sign-in`, 2026-11-08, JVM 17 and 21).

## Browser sign-in (0.10.0)

Read at branch `feat/browser-sign-in` (from `main` 090dbd4), ported to the final reference (thalovant-python-sdk `39e31d8`, digest `6dfb3bb31ac7`). Behind it:

- the full suite;
- `BrowserLoginVectorsTest`: all 119 shared vectors, with results identical to the reference's, case for case. The 98 earlier cases are unchanged.
- `BrowserLoginTest`: 43 edge tests.

Hand mutations of the new code, each caught by its own test or vector section:

- against the vectors: `URLEncoder` in the URL builder, a missing state accepted, a fifth exchange field, `CI` ignored, and the RFC 6749 error filter removed;
- against the edge tests: no redaction before the message, no one-shot body, a `HEAD` with a body, a 100 ms bound on the tab, and no drain.

- **Another user on the same computer can sign the program in as themselves.** The authorization URL reaches the browser through `java.awt.Desktop.browse`, or as a process argument to `open`, `xdg-open` or `rundll32` (`openInPlatformBrowser` and `browserCommand`, `src/main/kotlin/com/thalovant/sdk/BrowserLogin.kt`). On a shared machine, another local user can read that argument from the process table. They can open it in their own browser and approve it with their own account, and the redirect lands on the same `127.0.0.1` port with the right `state`. The program is then signed in to *their* account. This is RFC 8252's loopback flow as `gh auth login` and `aws sso login` run it, and the reference records the same limit. No mitigation in the SDK. A single-user desktop is unaffected.
- **The origin check cannot fire through `request()` today.** `request()` appends a path to the API's address, so a path that reads as a URL of its own (`/https://elsewhere/x`) stays on the API, unlike the reference's `urljoin`. The check (`sameOrigin`, `ControlPlane.kt`) is there for every request anyway, as the contract asks. It is tested directly, and `BrowserLoginTest`, `no request leaves the API's origin`, proves that no such path reaches another server.
- **A reset is seen differently on each system.** On this Linux host the reader sees a clean end of stream, and the sender sees the reset, because the JDK shuts the write side before it closes. macOS drops the unread answer instead. `an early answer is not lost to a reset` therefore keeps sending after it has read the answer. Without the drain it fails with `Broken pipe`. The macOS case is not run.
- **Deny reaches the SDK only once the dashboard sends it.** `error=access_denied` raises `ThalovantDeviceLoginDeniedException`. Until the dashboard change in the same train ships, a Deny never calls back, and the sign-in expires after `timeoutMillis` (300 s).
- **`canOpenBrowser()` does not prove a browser exists.** It reads `CI`, `SSH_*`, `DISPLAY` and `WAYLAND_DISPLAY` (`NativeSignIn.canOpenBrowser`, `NativeSignIn.kt`), as the contract says. A Linux desktop with a display but neither a browsing `java.awt.Desktop` nor `xdg-open` still chooses the browser. The printed URL is the fallback there. Once the browser is meant to open, the sign-in does not turn into a device login.
- **`browserLogin()` is not refused on Android.** Android is `linux` without a display, so `interactiveLogin()` picks a device login, and the platform opener does nothing there (`isAndroid`, `BrowserLogin.kt`). A forced `browserLogin()` still binds a port and waits for a browser nobody opened, unless the caller passes an `opener`. It is documented as unsupported. See [SUGGESTIONS.md](SUGGESTIONS.md).
- **The default device name can wait on DNS on macOS.** `defaultDeviceName()` reads `COMPUTERNAME`, `/proc/sys/kernel/hostname` and `HOSTNAME` first, then falls back to `InetAddress.getLocalHost().hostName` (`localHostName`, `NativeSignIn.kt`). On macOS that can block for seconds when the host name does not resolve. Pass `deviceName` to avoid it.
- **Windows and macOS were not run.** Only Linux was run (Fedora, JDK 17 and 21), and CI runs `ubuntu-latest`. The Windows exclusive bind (`reuseAddress = false` in `LoopbackListener.start`) and the `open` and `rundll32` commands are untested here. `browserCommand` is checked as data.
- **`ai_disclosure` is still dropped by `ApiToken`.** It stays in the raw `JsonObject` that `browserLogin()` returns. See [SUGGESTIONS.md](SUGGESTIONS.md).
- **Nothing secret in errors.** An exchange error goes through `withoutSecrets` (`BrowserLogin.kt`), which scrubs its message and its body, keys included, and builds a new error with no cause. `BrowserLoginTest`, `an echoed code and verifier never reach the error`, pins this with a 422 that echoes the code and the verifier, one of them in a key. A first version scrubbed only bodies that parse as JSON, and kotlinx reads a bare `<p>…</p>` as a literal, so a text body was left alone. `scrubbing keeps the kind of error, its status and its retry hint` caught that, and it is fixed.

## Fixed on 2026-11-08

- **OkHttp could present a sign-in code twice.**
  - Where: the exchange in `ControlPlane.kt`.
  - Cause: OkHttp's `RetryAndFollowUpInterceptor` replays a request whose body is not one-shot. It does so after a connection failure on a pooled connection, which is the exchange's case, because it follows the configuration GET. It also replays on a 408, and on a 503 with `Retry-After: 0`; that last replay ignores `retryOnConnectionFailure`. A code presented twice revokes the token the first exchange minted (RFC 9700).
  - Fix: the exchange goes through a client with `retryOnConnectionFailure(false)`, and its body is one-shot (`oneShot`). The bytecode of OkHttp 4.12 shows that the interceptor returns rather than follows up whenever the follow-up's body is one-shot.
  - Test: `the exchange is sent at most once, whatever the answer asks`, which covers the 408 and the 503, and `a transport failure during the exchange carries nothing of the request, and is not retried`. The 503 case failed before the one-shot body.
- **The final reference's rules were ported.** The changes are: the origin check, the `token_endpoint` and `authorization_endpoint` rules, the RFC 6749 error allow-list, an empty device name, the tab with no deadline of its own, `HEAD`, redaction before the message, and the drain. See MAINTENANCE_REPORT.md. Each has its vector cases or a test, and the earlier `HEAD`-with-a-body limit is gone.
- **The chooser did not fall back when the API could not be reached.** CodeRabbit raised it on #59, about the FAQ.
  - Where: the `LoginMethod.AUTO` branch of `interactiveLogin` in `src/main/kotlin/com/thalovant/sdk/ControlPlane.kt`.
  - Cause: the branch caught only `ThalovantApiException`. A configuration request that could not reach the API threw the `IOException` out of `interactiveLogin()`. The Python reference reads that failure as `ThalovantAPIUnreachableError`, which is a `ThalovantAPIError`, and falls back to a device login. The porting brief counts "another failure" as not usable. So the FAQ described the reference and the code did not.
  - Fix: the branch also catches `IOException`. `LoginMethod.BROWSER` still throws it.
  - Test: `BrowserLoginTest`, `an API out of reach for the configuration means a device login, unless the browser is forced`, uses a connection dropped after the request. Without the catch, the test fails.
- **The chooser's opener ran on the calling coroutine for a device login.** CodeRabbit raised it on #59.
  - Where: the `launch` that `interactiveLogin` passes to `deviceLogin`, in `ControlPlane.kt`.
  - Cause: `openBrowserBestEffort` calls `launch` inline. An opener that blocks, such as a console browser, held up polling and the device login's deadline. `BrowserLoginOptions.opener` promises a daemon thread of its own, and the browser path kept that promise.
  - Fix: both paths go through `openOnBrowserThread` in `BrowserLogin.kt`. `loginWithBrowser`, which takes no opener, is unchanged.
  - Tests: `an opener that blocks does not hold up a device login`, which is bounded, so a regression fails rather than hangs. Also `the opener the chooser was given opens a device login's page too, on a thread of its own`. Both fail with the opener inline.
- **A revoke-versus-sign-in test was flaky.**
  - Where: `HomeLinkControlPlaneTest`, `a revoke racing a sign-in on other threads never splits a token from its id`.
  - Cause: it failed on unmodified `main` (`090dbd4`) in 3 of 4 runs on a loaded machine. When `pollDeviceLogin` finished before `revokeApiToken` read `tokenId`, the revoke targeted the *new* token and correctly forgot it. The test asserted the new token survived either way.
  - Fix: the sign-in's answer is held until the DELETE has arrived, so the revoke always reads its target first. The race left is the one the test is about: which answer lands first.
  - Test: 5 of 5 runs pass. With `revokeApiToken` changed to forget unconditionally, the test fails.

## Fixed on 2026-11-07

- **The `HubIntentInventory.listedIn` KDoc overstated its contract.**
  - Where: `HubIntentInventory.listedIn` in `src/main/kotlin/com/thalovant/sdk/Intents.kt`. CodeRabbit raised it on #42, in the review body.
  - Cause: the KDoc said the list always matched `languages`, in the same order. `inventoryFromNames()` builds the `ENGINE_MANIFESTS` fallback with an empty list, as the Python reference's `_inventory_from_names` does. Code that read the list by a language's index would fail on a fallback inventory.
  - Fix: documentation only. The KDoc now says the list is empty on the names-only fallback, and on a hand-built inventory unless one is passed, but not on a listing that came back empty. It tells callers to read the list with `getOrNull(index)`. The behaviour stays the same as the reference's.
  - Test: `IntentsTest`, `the fallback lists names and says what was refused`, now checks that `listedIn` and `asJson()["listed_in"]` are empty.
- **No regression test covered `disconnect()` during the acceptance window.**
  - Where: `HiveMindWssTransport.disconnect()` in `Transport.kt`. CodeRabbit raised it on #41, outside the diff. The code fix landed in #46 (`c9a5351`), which completes `ended`.
  - Test: `TransportTest`, `a disconnect inside the acceptance window fails the connect it interrupts`. With `ended.complete(error)` removed from `disconnect()`, the test fails: `connect()` keeps waiting out the 20 s window.

## Fixed on 2026-11-03

- **Key publication without hard links could replace a published key across processes.**
  - Where: `HiveMindNoiseStore.publish()` in `src/main/kotlin/com/thalovant/sdk/NoiseStore.kt`.
  - Cause: `Files.move` without `REPLACE_EXISTING` checks for the target and then calls `rename(2)`, which replaces the target. Two processes could both see the name free, and the second would overwrite the key the first had already presented to its hub.
  - Fix: the check and the move now happen under an exclusive lock on `.noise-publish.lock`.
  - Test: `CodeRabbitFollowupsTest`, `without links a publisher holding the lock is waited for and its key is the one used`.
- **The four-argument `HiveMindWssTransport` JVM constructor was missing from 0.7.13 to 0.9.0.**
  - Cause: adding `acceptanceWindowMs` removed both the Java signature and the Kotlin defaults bridge, so code compiled against 0.7.12 or earlier failed with `NoSuchMethodError`.
  - Fix: both signatures are restored in `Transport.kt`.
  - Test: `the transport constructor from before the acceptance window still links`.
- **A link made by an ask, emit, reply or warm-up did not reset the refusal grace.**
  - Where: `HubSession.ensure()` in `Session.kt`.
  - Cause: only `connect()` recorded `LinkOutcome.UP`. If an ask brought the link up while `run()` was waiting out a refusal, the first refusal after a later drop was judged against the old grace, and `run()` gave up at once.
  - Fix: `ensure()` now records `LinkOutcome.UP`.
  - Test: `a link an emit brings up gives a later refusal a fresh grace`.
- **`run()` could record a refusal after a concurrent call had already brought the link up.**
  - Where: `HubSession.run()` in `Session.kt`. CodeRabbit raised this on #53.
  - Cause: the failed attempt released `busy` before its decision was made, so an ask or emit could install a client and record `UP` first.
  - Fix: `decideFailed()` makes the decision under `busy`, and records nothing when a live link is already held.
  - Test: `a refusal run() saw is not recorded over a link another call brought up meanwhile`.
- **A revoke-versus-sign-in test was ordered by a 400 ms delay, not a gate.**
  - Where: `HomeLinkControlPlaneTest`, `a revoke that finishes after a new sign-in forgets only the token it revoked`. CodeRabbit raised it on #46, in the review body.
  - Cause: if the test thread paused past the delay, the DELETE finished before `login()`. A revoke that forgets the token unconditionally then passed this test, as shown with a 500 ms pause.
  - Fix: the DELETE response is held until `login()` returns, and the test asserts the revoke is still waiting.

## Known limits

- The publish lock is advisory. It protects stores that publish without links, which is every store on a filesystem that refuses `link(2)`, such as Android app storage. A process that skips this SDK and writes the key file directly is not covered.
- The lock covers only the no-link path. The `link(2)` path needs no lock, because a link never replaces an existing file. Both paths would race only if one directory accepted links from one process and refused them from another. No platform this SDK supports is known to do that.

## Chat apps not covered (2026-11-03)

- **What:** this SDK has no methods for the chat apps routes (`/v1/bridges`), which the Python, Node, Go and Rust SDKs and the MCP server cover. `contracts/sdk-parity.json` records `chat-apps` as not applicable.
- **Status:** a scope decision, not a defect: the API's impact records for chat apps list no obligation here. Revisit if an app built on this SDK needs to manage chat apps.
