Last Edit: Claude Opus 5.5 - 2026-11-03 - Motive: Note the chat-apps capability this SDK does not implement, and add the review-body item fixed in HomeLinkControlPlaneTest.

# Audit

These findings come from the CodeRabbit threads on merged PRs #35, #37, #41 and #46. They were checked against the code and against `./gradlew build`, which passes 376 tests at the time of writing.

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
