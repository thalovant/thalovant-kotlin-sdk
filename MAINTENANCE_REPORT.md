Last Edit: Claude Opus 5.5 - 2026-11-03 - Motive: Record the chat-apps parity acknowledgment (not applicable).

# Maintenance Report

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

## Transparency Report

- **AI Model:** Claude Opus 5.5.
- **Actions Taken:**
  - Read the six open threads and checked each one against current code and against thalovant-android.
  - Wrote the three fixes and their regression tests, and checked that each test fails when its fix is reverted.
  - Refreshed the parity evidence hashes for the three changed files.
  - Replied to the old threads and resolved them.
- **Oversight:** The work was done under an agent-run CodeRabbit audit requested by the repository owner. CI and this pull request's review are the merge gate. No release was cut by hand; `auto-release.yml` handles versioning on merge.
