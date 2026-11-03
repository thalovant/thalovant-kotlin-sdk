Last Edit: Claude Opus 5.5 - 2026-11-03 - Motive: CodeRabbit follow-ups on merged PRs #35, #37, #41 and #46.

# Maintenance Report

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
