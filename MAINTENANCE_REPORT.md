Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Browser sign-in (0.10.0): port of the Python reference's browser-login capability, the flaky revoke-race test fix, verification and transparency report.

# Maintenance Report

## 2026-11-08: Browser sign-in (0.10.0)

**What changed:** browser sign-in is ported from the Python reference (thalovant-python-sdk#113, `e3999cb`, 0.11.0) as the `browser-login` capability. It is the authorization code grant with PKCE (S256) on a loopback redirect (RFC 8252).

| File | Change |
| :--- | :--- |
| `src/main/kotlin/com/thalovant/sdk/ControlPlane.kt` | `browserLogin(BrowserLoginOptions)` and `interactiveLogin(InteractiveLoginOptions)`, with the private steps `nativeSignInConfig`, `runBrowserLogin` and `exchangeBrowserCode`. `loginWithBrowser` delegates to a private `deviceLogin` with the same steps, so the chooser's opener reaches the verification page. |
| `src/main/kotlin/com/thalovant/sdk/NativeSignIn.kt` | `browserAuthorizationUrl`, `loopbackRedirectUri`, `newState`, `canOpenBrowser`, `defaultDeviceName`, the constants, and `NativeSignInConfig.fromJson`. `begin` and `codeFrom` are unchanged. |
| `src/main/kotlin/com/thalovant/sdk/Loopback.kt` (new) | `LoopbackListener` (a `ServerSocket` on `127.0.0.1`), `classifyLoopbackRequest`, `formDecode` and `loopbackResponse`. |
| `src/main/kotlin/com/thalovant/sdk/BrowserLogin.kt` (new) | `LoginMethod`, `BrowserLoginOptions`, `InteractiveLoginOptions`, the prompt and the platform opener, and `withoutSecrets`. |
| `src/main/kotlin/com/thalovant/sdk/Errors.kt` | `ThalovantApiException.errorCode` gets an internal setter. |
| `src/test/kotlin/com/thalovant/sdk/BrowserLoginVectorsTest.kt`, `BrowserLoginTest.kt` (new) | Run the 98 shared vectors, and test this SDK's own edges (29 tests). |
| `src/test/resources/thalovant/browser-login-vectors.json` (new) | Copied byte for byte from the reference (sha256 `9058adf2…1651e`). |
| `src/test/kotlin/com/thalovant/sdk/HomeLinkControlPlaneTest.kt` | The flaky revoke-race test is fixed (see AUDIT.md). |
| `contracts/sdk-parity.json`, `contracts/conformance-results.json` | The candidate reference digest, the `browser-login` capability, refreshed evidence hashes, and the re-recorded results. |
| `build.gradle.kts`, `Constants.kt`, `README.md`, `CHANGELOG.md` | 0.10.0, a README "Sign in" section, and the changelog entry. |
| `AGENTS.md`, `AUDIT.md`, `FAQ.md`, `QUICK_FACTS.md`, `SUGGESTIONS.md` | The sign-in rule, known limits, Q&A, facts and follow-ups. |

**Why:**
- A device code can be relayed to somebody else: device-code phishing.
- A loopback redirect cannot be relayed, because the browser that approves delivers the code to its own computer.
- The parity reference marks `browser-login` as required here.

**Decisions:**
- **Listener:** a `ServerSocket` with a small reader rather than `com.sun.net.httpserver`. Android does not have the JDK server, and the JDK server answers some malformed requests itself.
- **Android:** browser sign-in is documented as unsupported there. Android is `linux` with no display, so `interactiveLogin()` uses a device login, and the platform opener does nothing.
- **Error types:** none are new. Denied and expired reuse the device-login exceptions with no status.

### Verification

- Before the change, `./gradlew --no-daemon build -PtestJavaVersion=17` on unmodified `main` (`090dbd4`) ran 377 tests with 1 failure. The failure was the flaky revoke-race test, which failed in 3 of 4 runs.
- After the change, `THALOVANT_CONFORMANCE_OUT=$PWD/contracts/conformance-results.json ./gradlew --no-daemon clean build -PtestJavaVersion=17` ran 419 tests: 0 failed, 0 skipped, `BUILD SUCCESSFUL`.
- `./gradlew --no-daemon build -PtestJavaVersion=21 --rerun-tasks` ran 419 tests: 0 failed, 0 skipped, `BUILD SUCCESSFUL`.
- The compiler warnings left are the two that `ConformanceRecord.kt` already had.
- Conformance was re-recorded. All 98 `browser-login-vectors.json` case digests, and the file digest `b17cb434…`, are identical to the reference's recorded results at `e3999cb`. The other eight vector files' results are unchanged, and each equals the reference's.
- Hand mutations, each caught by the section it targets:
  - `URLEncoder` in the URL builder: `authorization_urls`
  - a missing state accepted: `callbacks`, `sign_ins`
  - a fifth exchange field: `sign_ins`, `chooser`
  - `CI` ignored: `environment`, `chooser`
  - With `revokeApiToken` changed to forget unconditionally, the fixed revoke-race test fails.
- The fixed revoke-race test passed 5 runs out of 5.
- `tools/interop/run.sh` against the pinned Node peer (`thalovant-node-sdk@261123f5`) passes `IndependentInteropTest`.
- `python3 -m unittest discover -s tools -p 'test_maven_publication_status.py'` passes 7 tests.
- `check-sdk-parity.py --workspace <checkouts> --consumer thalovant-kotlin-sdk` passes in two places:
  - Against thalovant-python-sdk `e3999cb`, the PR head with the candidate digest: "acceptance records verified".
  - Against `main` (`44b6e2b`, the current digest), where it notes `browser-login` as a capability the reference does not know yet. That note is the rollout state for a consumer that goes first.
- Not run: Windows, macOS, a real browser, or a live API. thalovant-api #501 is not deployed yet.

### Transparency Report

- **AI Model:** Claude Opus 5.5 (claude-opus-5-5), as a Claude Code agent, one of several agents porting the same reference to the other SDKs in parallel.
- **Actions Taken:**
  - Read the contract, the Python reference's porting brief, its implementation, its tests and the parity checker.
  - Wrote the Kotlin implementation and both test files.
  - Vendored the vectors.
  - Re-recorded conformance and checked it against the reference case for case.
  - Ran hand mutations to prove the vector runner bites.
  - Found and fixed the flaky test that failed on unmodified `main`.
  - Updated the parity record, with a review note for every refreshed hash.
  - Wrote the docs, the changelog and these records.
- **Oversight:** the platform owner's coordinating agent reviews and merges after the PR's hosted CI and review. Nothing was merged, tagged or released by this agent; a merge to `main` releases 0.10.0.

## 2026-11-07: CodeRabbit review-body items, second pass

An inventory found 14 CodeRabbit review-body items on merged PRs: nitpicks and outside-diff comments. The 2026-11-03 pass below answered six of them, and those answers still hold. The other eight were outside-diff comments on #41, #42, #46 and #47. Each was checked against `main` at `ad47594`.

| Old PR | Item | Outcome |
| :--- | :--- | :--- |
| #41 | `Transport.kt` `disconnect()`: complete `ended`, so a disconnect inside the acceptance window fails `connect()` | **Already fixed** in #46 (`c9a5351`). This change adds the missing regression test. |
| #41 | `Transport.kt`: mark an authenticated binary frame as hub traffic before decoding it | **Already fixed** in #47 (`4873fd4`). `heardFromHub` is set right after a successful decrypt. The test is `ReviewFindingsTest`, `a WIRE-1 binary frame is the hub speaking too, so a close after it is a drop`. |
| #42 | `Intents.kt`: serialise `listed_in`, and set `listedIn = languages` on the engine-manifest fallback | **Partly fixed, partly not valid.** `asJson()` has written `listed_in` since #52 (`b5b0da3`). The empty `listedIn` on a names-only inventory is what the Python reference does, so it stays. The KDoc that said otherwise is fixed in this change. |
| #46 | `ConformanceRecord.kt:131`: convert before applying the integral-float rule (`0.99999999999999999`) | **Already fixed** in #47 (`4873fd4`). Only a literal with no `.`, `e` or `E` is read as an exact int. Anything else becomes a double first, and a whole double is written as an int. `HomeLinkVectorsTest` pins `"1.0000000000000000001" to "1"`. |
| #46 | `ControlPlane.kt` `pollDeviceToken`: check the deadline before each POST, and bound each POST | **Already fixed** in #47 (`4873fd4`). Each POST runs under `withTimeoutOrNull(left)`, and none starts once `left <= 0`. The test is `ReviewFindingsTest`, `each device-token POST is bounded by what is left of the sign-in`. |
| #46 | `ControlPlane.kt` `operationOrNull`: an unfamiliar status loses the operation id | **Already fixed** in #47 (`4873fd4`). `CreateClientIdentityResult.operationId` and `operationStatus` hold the id and the raw status, and `waitForAdmission(result)` follows the id through an unknown status. The test is `ReviewFindingsTest`, `an operation status this SDK does not know keeps its id`. |
| #47 | `NoiseStore.kt` `forIdentity`: fall back to the default store when the folder beside the identity cannot be created | **Already fixed** in #47 before its merge (`4873fd4`; answered on the PR on 2026-09-28). |

### Verification

- `./gradlew --no-daemon build -PtestJavaVersion=17` passes 377 tests with 0 failures. That is the 376 tests from before plus the new transport test.
- With `ended.complete(error)` removed from `disconnect()`, the new transport test fails: `connect()` sits out the window.
- Conformance was re-recorded with `THALOVANT_CONFORMANCE_OUT`. The results are identical to `contracts/conformance-results.json`.
- The evidence hashes for `Intents.kt` (`listing`) and `TransportTest.kt` (`refusal`, `runtime`) are refreshed. The `Intents.kt` change is to the KDoc only. `check-sdk-parity.py --consumer thalovant-kotlin-sdk` passes against thalovant-python-sdk `main` (`bd4061f`).
- `python3 -m unittest discover -s tools -p 'test_maven_publication_status.py'` passes.
- No version change. `src/` is a release path, so this change merges with `[skip ci]` and does not cut a patch release that has no library change in it. The next release picks it up.

### Transparency Report

- **AI Model:** Claude Opus 5.5 (claude-opus-5-5), as a Claude Code agent.
- **Actions Taken:**
  - Read the 14 review-body items and checked each one against current code and against the Python reference.
  - Corrected one KDoc and added one regression test, which fails with its fix reverted. Extended one existing test.
  - Refreshed two parity evidence hashes.
  - Wrote this entry, the audit entry and the changelog entry.
  - Replied once on each old PR with how each item was handled.
- **Oversight:** Part of an agent-run CodeRabbit audit the repository owner asked for. Hosted CI and this pull request's review gate the merge.

## 2026-11-03 — Chat apps parity acknowledgment

**What changed:** `contracts/sdk-parity.json` records the Python reference digest `1e56099da5db` (thalovant-python-sdk#108) and answers its new `chat-apps` capability as not applicable, with the reason the reference gives:

> The Android SDK is the app's data plane to its hubs. The API's impact records for chat apps record no Kotlin obligation: an owner connects and manages chat apps from the console, the Python, Node, Go and Rust SDKs or the MCP server, never from the phone app.

No source, test or version file changes, so `auto-release.yml` publishes nothing.

**Why:** the reference's release gate refuses to publish while any consumer has not answered a capability. Chat apps (the `/v1/bridges` owner routes) ship in the Python, Node, Go and Rust SDKs and the MCP server.

**Verification:** `check-sdk-parity.py --workspace <checkouts> --consumer thalovant-kotlin-sdk` verifies this acceptance against the reference branch, and against `main` (where the new digest is only a candidate). The language suite runs in hosted CI on this PR.

### Transparency Report

- **AI Model:** Claude Opus 5.5 (claude-opus-5-5), as a Claude Code agent.
- **Actions Taken:** added the digest and the `chat-apps` entry; wrote this entry and the quick facts, FAQ and suggestions files.
- **Oversight:** hosted CI and the CodeRabbit review gate the merge; the platform owner's coordinating agent merges.

## 2026-11-03: CodeRabbit follow-ups

Six CodeRabbit review threads were still open on merged pull requests. Each one was checked against `main` at `b5b0da3`.

| Old PR | Thread | Outcome |
| :--- | :--- | :--- |
| #35 | `NoiseStore.kt` `publish()`: without `link(2)`, a move can replace a key another process published | **Fixed.** The look-then-move now runs under an exclusive lock on `.noise-publish.lock` in the store's directory. Without `REPLACE_EXISTING`, `Files.move` checks for the target and then calls `rename(2)`, which replaces, so the check alone was not atomic. |
| #35 | Android caller should pass a store under `filesDir` | **Not in this repo.** thalovant-android already passes `HiveMindNoiseStore(store.noiseDirectory)`, which is `filesDir/noise`. |
| #37 | An uncorrelated denial must not fail every pending `ask()` | **Already fixed** in #39 (`refusalBelongsToAsk`, Errors.kt). The shared refusal vectors cover it. |
| #41 | A rotated hub on the KKpsk0 path should raise the typed exception | **Already fixed** in #46. KKpsk0 carries no hub static key, so a rotated hub fails KK, then XX runs and `verifyOrPin` raises `ThalovantHubIdentityChangedException`. The test is `run stops at once when the hub's key changed`. |
| #41 | Keep the pre-0.7.13 four-argument `HiveMindWssTransport` JVM constructor | **Fixed.** An explicit secondary constructor restores both the Java-visible four-argument signature and the Kotlin defaults bridge. It delegates with `DEFAULT_ACCEPTANCE_WINDOW_MS`. |
| #46 | A link brought up by `reply()` (or any call) leaves `run()`'s refusal clock running | **Fixed.** `HubSession.ensure()`, the one place a client is installed, records `LinkOutcome.UP`. The extra call in `connect()` was removed. |
| #53 (this change's own review) | `run()` records a failed attempt after `connect()` releases the session, so a concurrent call's `UP` can come first and the stale refusal lands on top | **Fixed.** `run()` now decides a failed attempt under `busy`, against the link as it is at that moment. If another call has brought a link up, nothing is recorded. |

### Verification

- `./gradlew --no-daemon build -PtestJavaVersion=17` passes: 376 tests, 5 of them new, in `CodeRabbitFollowupsTest`.
- Each new test was run with its fix reverted, and each failed:
  - Removing the secondary constructor gives `NoSuchMethodException`.
  - Removing the `ensure()` record makes the refusal decision `GIVE_UP` where `RETRY` was expected.
  - Deciding outside `busy` again records the stale refusal after the link came up.
  - Removing only the publish lock makes the store use its own key while another process's key ends up on disk.
- Conformance was re-recorded with `THALOVANT_CONFORMANCE_OUT`, and the results are identical to `contracts/conformance-results.json`.
- The evidence hashes for `Transport.kt`, `Session.kt` and `NoiseStore.kt` were refreshed. After that, `check-sdk-parity.py --consumer thalovant-kotlin-sdk` passes against thalovant-python-sdk `main` (`5106933`).

## 2026-11-03: CodeRabbit review-body items

CodeRabbit also left six review-body items (nitpicks, not threads) on merged PRs. Each was checked against `main` at `c232edd`.

| Old PR | Item | Outcome |
| :--- | :--- | :--- |
| #10 | `Runtime.kt` `launchRuntimeIo`: rethrow `CancellationException` | **Already fixed** in #10's own merge (`09e1a0c`). |
| #11 | `tools/maven-publication-status.py`: tell "no deployment" from a failed lookup | **Already fixed** in #11's merge (`5e44591`). It exits 0 with `lookup_result: "no-match"`, and 1 only on failure. |
| #11 | `tools/test_maven_publication_status.py`: import `urllib.request` | **Already fixed** in `5e44591`. |
| #34 | `contracts/sdk-parity.json`: run the parity check | **Not applicable.** `sdk-parity.yml` runs `check-sdk-parity.py --consumer` on every pull request. |
| #35 | `NoiseStoreAndroidTest`: force the hard-link failure | **Already fixed** in #53 (`b600b5de`). The `hardLinks` switch makes `CodeRabbitFollowupsTest` publish without `link(2)`. |
| #46 | `HomeLinkControlPlaneTest`: order the late DELETE after `login()` by a gate, not a delay | **Fixed in this change.** The DELETE is held on a latch until `login()` returns, and the test asserts that the revoke is still waiting. |
| #55 (this change's own review) | A gate that times out still answered 204 | **Fixed.** A timed-out gate answers 500, so the revoke throws and the test fails. A gate that never opens fails the test. |

### Verification

- `./gradlew test` passes 376 tests.
- Injected bug: `revokeApiToken()` forgets the token whatever it is. The gated test fails (`expected session-token, was null`). The old delay-based test, with the test thread paused for 500 ms after the DELETE arrived, passed with the same bug. That gap is what the gate closes.
- The evidence hash for `HomeLinkControlPlaneTest.kt` is refreshed. `check-sdk-parity.py --consumer thalovant-kotlin-sdk` passes against thalovant-python-sdk `main` (`ced77a4`).
- The change is test-only. `src/` is a release path, so merging it cuts a patch release with no library change. It is held until the release window allows that.

## Transparency Report (review-body items)

- **AI Model:** Claude Opus 5.5.
- **Actions Taken:** Checked six CodeRabbit review-body items against current code. Rewrote one test to use a response gate and showed that it fails with the bug injected. Refreshed one parity evidence hash. Posted one comment per old PR saying how each item was handled.
- **Oversight:** Part of an agent-run CodeRabbit audit the repository owner asked for. CI and this pull request's review gate the merge, and the lead agent decides when it merges.

## Transparency Report (review threads)

- **AI Model:** Claude Opus 5.5.
- **Actions Taken:**
  - Read the six open threads and checked each one against current code and against thalovant-android.
  - Wrote the three fixes and their regression tests, and checked that each test fails when its fix is reverted.
  - Refreshed the parity evidence hashes for the three changed files.
  - Replied to the old threads and resolved them.
- **Oversight:** The work was done under an agent-run CodeRabbit audit requested by the repository owner. CI and this pull request's review are the merge gate. No release was cut by hand; `auto-release.yml` handles versioning on merge.
