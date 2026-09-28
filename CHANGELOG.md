# Changelog

## 0.8.0

The Home Assistant link: the four capabilities the Python reference added in 0.9.0 (`device-login`, `connection-kinds`, `connection-admission`, `home-link`), each run against its five shared vector files and recorded in `contracts/conformance-results.json`, where all 105 cases produce the reference's digests (reference `2460eb90bd56`). Nothing existing changes shape: every addition is a new type, a new function, or a defaulted parameter.

- **Sign in one step at a time.** `beginDeviceLogin(scopes, clientName)` returns a `DeviceAuthorization` -- the code to show, the URL, how often to poll -- and each `pollDeviceLogin(authorization)` asks once: it returns an `ApiToken` and stores it (`accessToken`, and the new `tokenId`), or throws `ThalovantDeviceLoginPendingException` with the interval to wait (a `slow_down` adds five seconds, for good), `ThalovantDeviceLoginExpiredException`, or `ThalovantDeviceLoginDeniedException`. A Home Assistant config flow, a TV, anything that shows a code and cannot block inside one call, runs its own loop. An empty scope list is left out of the request, in `loginWithBrowser` too, so the API's default applies: the API answers `[]` with a 422. `revokeApiToken()` revokes the token the device login minted -- a token may always revoke itself -- and forgets it, and only it: a sign-in that completes while the revoke is on its way keeps its new token. Revoking it twice is not an error, since a 401 on revoking the token in use means it is revoked already. `HOME_ASSISTANT_SCOPES` is what a Home Assistant link asks for, and all a Free plan can approve. `loginWithBrowser` runs on the same steps. Every sign-in -- `login`, `completeNativeSignIn`, the device flow -- sets `tokenId` to the id its answer carried, or null, so it always describes the token in use: a device token's id left behind by a later password sign-in would have had `revokeApiToken()` revoke the wrong token. Neither the device code nor the token appears in an error, and `DeviceAuthorization` and `ApiToken` never print them.
- `ThalovantDeviceLoginDeniedException` and `ThalovantDeviceLoginExpiredException` carry the API's `statusCode` and `body`. They stay `ThalovantException`s, as they have been, so a `catch` written against them catches what it caught.
- **Create a connection of a kind.** `CreateClientIdentityOptions.connectionType` is sent as `spec.connection_type` (`CONNECTION_TYPE_HOME_ASSISTANT`, `voice_satellite`, ...). The API must answer with the same kind: a connection it made without it is deleted -- with `If-Match` its `etag` -- and the call throws `ThalovantUnsupportedConnectionTypeException`, as it does for a 422 about the field -- not one about another field that merely echoes the request, `connection_type` and all, back under `input`. `BootstrapIdentityResult` gains `clientId`, `connectionType` and `operation`.
- **Refusals come typed, on every control-plane call.** A 401, 423, or 403 `Insufficient scopes` is `ThalovantAuthException` (sign in again); a 402 or 403 `plan_limit` is `ThalovantPlanException`; a 409 `home_assistant_already_linked` is `ThalovantAlreadyLinkedException`, whose `clientId` names the connection that holds the link. Each is a `ThalovantApiException` with `statusCode`, `errorCode`, `detail` and `problem`, so every existing `catch` still catches them. `ThalovantApiException` is now `open`.
- `deleteClient(clientId, etag)` takes the etag as optional: without one it reads it first (`getClient`, new). A 412 -- another writer changed the client -- reads it again and retries once, and a 404 on either request counts as deleted, so deleting twice is safe. Its KDoc sat above `listClients`, which is where your IDE showed it; it is back on `deleteClient`.
- **Wait for admission.** `waitForAdmission(result)` follows the operation the create answered with, about ninety seconds, and no read runs past its deadline:
  - `ready` returns, as do no operation and a 404; a 5xx is ridden out, and so is a 429, for as long as it says: `ThalovantApiException.retryAfterSeconds` (new) reads its body's `retry_after_seconds`, else its `Retry-After`, else its `RateLimit-Reset` -- the API's own rate limiter answers in plain text with only that header -- and a wait longer than what is left is a timeout at once;
  - `failed` and `timed_out` throw `ThalovantAdmissionFailedException` with the operation's `errorCode`; any other refusal of the wait throws it too, keeping the API's `statusCode`, `code`, `detail` and `problem`; a 401 or 403 is thrown as the API's own `ThalovantAuthException` (sign in again), and an API out of reach as the `IOException` it is -- neither says anything about the connection;
  - the deadline (`DEFAULT_ADMISSION_TIMEOUT_MS`, 180 s) throws `ThalovantAdmissionTimeoutException`, whose message ends "it may still admit it later": it is a `ThalovantConnectionException` and a `ThalovantTimeout` -- a new interface `ThalovantTimeoutException` implements too -- because the connection may still be admitted;
  - an operation link to another origin than the API's -- scheme, host and port, the default port spelled out -- is refused before anything is fetched. `ThalovantConnectionException` is now `open`.
- **Answer the hub.** `client.reply(event, msgType, data, context)` answers a message back along its route (`replyContext`, OVOS-MSG-1 §5.2: the context the hub sent, with `source` and `destination` turned round; a request with a destination and no source gets a reply with no destination, rather than one addressed to its own sender). `ThalovantHome` holds the contract; `client.answerHomeRequests { request -> HomeAnswer(...) }` answers every `thalovant.home.request` until it is cancelled, each on its own coroutine, and `answerHomeRequest` answers one.
  - Every request gets at most one answer, and never after the hub's 10 s counted from its arrival: the handler gets 9 s or what is left, the reply's sending gets what the handler left, and a reply that could only arrive late is withdrawn -- `answerHomeRequest` returns null then.
  - Withdrawing a reply is not a failure of the link: a reply still waiting behind another frame when the bound passes is not sent, and the connection is left exactly as it was. Waiting for the send turn can be cancelled; the write itself cannot, since half a frame would break the Noise stream. A connection `session.reply` had to make first belongs to the session and stays up.
  - A handler that throws is answered `failed_to_handle`, one too slow `timeout`, and one outside the contract `unknown`, each with empty speech for the hub to fill in the device's language. The handler runs detached, so the answer -- and the call -- goes out at the deadline even when the handler ignores cancellation; its late result is dropped.
  - `plainSpeech` turns markup into speech by the rules every SDK shares: only real tags, comments and processing instructions go ("5 < 6 and 7 > 3" survives); numeric references, the five XML entities and `&nbsp;` are decoded and nothing else (`&eacute;` stays as written: platform HTML tables differ); runs of Unicode White_Space collapse to one space, and only White_Space is trimmed from the ends (U+001C..U+001F are kept). All of it in linear time: a scanner, not a regular expression, reads the tags.
- **Keep the link.** `HubSession.run()` stays connected until `close()`: it reconnects on the policy's 10 s to 120 s ladder, notices a dropped link as it drops and dials again at once, and reads a refusal as "not admitted yet" for `HubSessionPolicy.refusalGraceSeconds` (600 s, new) before throwing it; a hub whose key changed is thrown at once. `LinkSupervisor` (new, with `LinkOutcome`, `LinkAction` and `LinkDecision`) holds those rules as a pure function a host driving `connect()` itself can ask too. `session.connect()` makes one attempt, `session.connected` is a `StateFlow` of whether the link is up, and `session.answerHomeRequests` answers on every client the session builds. Attempts are logged at `FINE` on `com.thalovant.sdk.session`, and nowhere louder.
- **A reply never waits behind a turn.** `session.reply(...)` goes out on the live link at once. `HubSession` holds its lock for as long as an `ask` waits for its answer -- seconds -- and a hub that asked something waits with a deadline of its own: answered through the lock, a home request behind a running ask would have missed the hub's ten seconds.
- **What counts as a refusal at connect.** The rule every SDK now shares:
  - a close with 1000, no status (1005) or 1008 is the hub turning the client away when it comes during the handshake -- any step of it: until now only 1008 counted there -- or within 750 ms after it; a 1001, 1011 or 1013, a socket that ended with no close frame, or a close after the window is the hub's trouble or the network's, a `ThalovantConnectionException` rather than a "pair this client again";
  - a hub answer that does not authenticate under the password is a refusal too (`ThalovantIdentityException`); it used to surface as a bare cipher exception;
  - a KK attempt that fails that way, or that the hub closes on, is followed at once, inside the same `connect`, by one XX attempt, which is the only way to tell a changed password (a refusal) from a hub whose key changed (`ThalovantHubIdentityChangedException`). XX is not a downgrade: the pin is still checked, now before this side answers with its own key, and the SDK never replaces it;
  - an upgrade answered 401 or 403 is the `ThalovantIdentityException` a refused credential always was elsewhere.
- A 2xx whose body is not JSON is the same local failure, with no status, as one that is JSON and not an object; it used to escape as a serialization exception.
- The conformance recorder writes JSON byte for byte as the reference's `json.dumps` does -- its own escapes, keys in code-point order -- rather than as the serialiser happens to, and spells any fraction in a vector file as the reference does, rather than failing inside a shutdown hook where it fails no build and writes no results; what a case produced is still held to whole numbers.

## 0.7.17

- `ThalovantApiException` carries the rest of what the API said. `problem` is the whole error body parsed, as a `JsonObject`, when it is a JSON object, and `errorCode` is its machine-readable code. A `platform_image_required` refusal names what a caller may pin instead in `refused_images`, `allowed_images` and `allowed_repositories`, and `plan_limit` sends `resource`, `limit`, `used` and `plan`; until now none of that reached a caller except by parsing `body` again by hand. The constructor and `body` are unchanged.
- `errorCode` and `detail` follow the rule every SDK now shares: a string with at least one non-whitespace character, else the same member of a `detail` that is itself an object -- FastAPI's own envelope, which the API's Problem+JSON handler normally lifts -- returned whole and exactly as sent. A `detail` of only spaces is now null rather than the spaces. The body is parsed once, when the exception is built, rather than on every read of `detail`.
- The message carries the API's sentence. It never read a string `detail`, so every Problem+JSON refusal read `Thalovant API request failed with HTTP 403: HTTPException`, its title. It now uses the `detail` sentence, still collapsed to one line and cut at 200 characters, and falls back to the fields it read before. So that it still never repeats a credential, the bearer token and every string of 8 characters or more in the request body are replaced with `[redacted]` before the cut; a validation error's echoed input is still never read into it. `problem`, `detail` and `body` are not redacted.
- An error body is read as UTF-8 whatever charset its Content-Type declares. The API declares none, which OkHttp already read as UTF-8; a charset some proxy declared would have re-spelled the API's sentence.
- `ReleaseOptions` states the whole rule for a caller who is not a platform administrator: each image key may name a catalog pin of the stable or alpha channel, the resource's current or recommended image, its release-policy image, or the platform's default image; runtime `core` also takes any tag or digest of `ghcr.io/thalovant/ovos-core`, and hub `listener` any tag or digest of `ghcr.io/thalovant/hivemind-listener`. `bus` and `preview_bridge` take only the listed images.
- New `api-errors` capability in the parity contract, with the Python reference's `api-error-vectors.json` vendored unchanged: thirteen responses, from the image and plan refusals the API sends to a body that is HTML, empty, or JSON that is not an object, each served by MockWebServer and read back through `getHub()`. What this SDK produced for each is recorded in `contracts/conformance-results.json` and matches the reference.

## 0.7.16

- Automated patch release of the unreleased changes on `main` since v0.7.15.

## 0.7.15

- Automated patch release of the unreleased changes on `main` since v0.7.14.

## 0.7.14

- Automated patch release of the unreleased changes on `main` since v0.7.13.

## 0.7.13

- **A hub that will not have a client no longer looks like a connection that works.** The v3 handshake carries this side's static key in its last message, so a hub judges that key after there is nothing left for it to send: it answers a key it accepts with silence, and one it will not have by closing the socket. `connect()` returned the instant it had written that last message, so a refusal arrived a few milliseconds later as an ordinary close on a connection the caller had already been told was ready -- nothing raised, nothing to show anybody.

  That is not a corner case. `hivemind-core` pins a client's Noise static key on first use and refuses any later handshake that contradicts the pin, and a satellite keeps its key in that install's private storage -- so a connection re-paired onto a reinstalled app or a second handset is refused every single time, invisibly. A phone in that state waits on its hub for ever, reconnecting on every probe and being refused silently on each one.

  `connect()` now holds the connection open for `DEFAULT_ACCEPTANCE_WINDOW_MS` (750 ms) before reporting it ready, and a close inside that window is raised as `ThalovantIdentityException` -- the same type as a refused access key, because it asks the same thing of a person. A close *after* the session has carried a frame stays a `ThalovantConnectionException`: that connection was plainly accepted, and telling somebody to pair again over a hub restart would throw away a perfectly good credential. `HiveMindWssTransport(acceptanceWindowMs = 0)` restores the old behaviour.

- **A rebuilt hub is no longer reported as a client that was never paired.** `HiveMindNoiseStore.verifyOrPin` refused a hub answering with a key other than the pinned one by way of `check`, so it arrived as a bare `IllegalStateException` -- which is also what an app throws for "this client is not paired", and is the first thing a caller matches. New `ThalovantHubIdentityChangedException`, extending `ThalovantIdentityException` so callers that only distinguish "the identity is the problem" are unaffected.

## 0.7.12

- Automated patch release of the unreleased changes on `main` since v0.7.11.

## 0.7.11

- The refusal behaviour 0.7.9 introduced is now the parity contract's `refusal` capability, held to the Python reference's shared `refusal-vectors.json`. Two of its cases found this SDK wrong:
  - **A denial carrying another ask's request id ended this ask** when nothing else was in flight. The request id is the one thing that does say whose a denial is; `refusalBelongsToAsk()` now judges a denial by it whenever it is there, and falls back to the sole-utterance rule only when it is not.
  - **`allowed` kept blank and untrimmed entries.** A message type an operator is told to allow is now a non-blank, trimmed string, as the reference already had it.
- `ThalovantPolicyDeniedException`'s message fits the refusal. It told everybody to "allow this connection to publish `recognizer_loop:utterance` in the dashboard" -- the fix for an allow-list, and no help for a spent quota or for `backend_unavailable`, a hub whose assistant is down, which arrives under the same event. `BACKEND_UNAVAILABLE` names that code.
- A fire-and-forget utterance -- `sendUtterance()`, `sendCode()`, or `emit()` of `recognizer_loop:utterance` -- counts as in flight for 10 s after it is sent, so a refusal of it cannot end an unrelated ask. 0.7.9 and 0.7.10 did not count them.
- A fire-and-forget utterance is recorded once the connection is up and immediately before the publish, so the grace window is not spent on a handshake; a connect that fails records nothing, and a publish that throws keeps its record, because a transport can fail after the hub already holds the frame. The deque is pruned as entries are added, so a client that only ever sends does not keep them for its lifetime.
- `Quota.limit` and `Quota.used` are `Long`: a hub may allow more than an `Int` holds, and the conversion wrapped such a limit negative.
- A refusal on a quota the hub sent no numbers for says a quota has run out, rather than claiming "all questions used".
- Quota counts are never negative and never past a signed 64-bit integer.
- The constructor takes `quota` (defaulted, so existing calls compile), and a quota refusal whose numbers are missing is still a quota, with zeros, rather than none.

## 0.7.10

- A denial the hub cannot correlate now ends an `ask` only when that ask is
  the one utterance this client has in flight. A `hive.policy.denied` names the
  type it refused and nothing that says which message, so with two asks
  waiting, 0.7.9 would end whichever read it first -- a question the hub never
  refused. With more than one in flight neither takes it, and each is left to
  its own reply or its own deadline, which is what every uncorrelated denial
  did before 0.7.9. `sendCode()` is fire-and-forget and still not counted: a
  refusal of it was never observable.
- `RuntimeTest.the carry is filed under the session id ask returns` asked with
  a settle window of zero, where the first speech closes the reply and whether
  `ovos.utterance.handled` -- the event that carries the conversation -- has
  been read yet is a race between the transport's I/O worker and the caller's
  thread. That is the documented behaviour at zero, not a fault, and it lost on
  a loaded runner and turned `main` red after 0.7.9 merged. The test now uses a
  window above zero, as every caller that keeps a conversation does; widening
  the race on purpose fails the old test every time and passes the new one.

## 0.7.9

- A policy denial now ends an `ask` at once instead of letting it run to the
  deadline. The hub sends `hive.policy.denied` the instant it refuses, but
  builds it with only source and destination context, so it carries no request
  id and the reply listener -- which drops every uncorrelated event -- threw it
  away. Production showed pairs of denials 13.4s apart behind an app saying
  "your hub did not answer in time" about a question the hub had refused
  immediately and explained. The denial is now matched on the message type the
  ask published, which is the one thing it does carry.
- A refusal raises `ThalovantPolicyDeniedException` rather than the bare
  `ThalovantRuntimeException("Hub reported hive.policy.denied.")`. The richer
  type already existed, with a message naming the type and what to allow, and
  nothing was throwing it.
- An intent nothing handles raises `ThalovantUnansweredException` rather than a
  bare `ThalovantRuntimeException`. `ovos.intent.unmatched` is the hub saying it
  understood and has no skill for this, which is neither a refusal nor a
  failure; flattened into a runtime error a caller could only report that
  something went wrong, so an app told somebody their hub "would not do that"
  about a question it simply cannot answer.
- `ThalovantPolicyDeniedException.quota` carries the numbers behind a spent
  allowance: which counter ran out, its limit, how much was used, and how long
  until it resets. The intent-quota policy sends all four; a caller could
  previously only report "refused", which is what an app told somebody who had
  simply used up the day.

## 0.7.8

- Automated patch release of the unreleased changes on `main` since v0.7.7.

## 0.7.7

- Automated patch release of the unreleased changes on `main` since v0.7.6.

## 0.7.6

- Automated patch release of the unreleased changes on `main` since v0.7.5.

## 0.7.5

- Automated patch release of the unreleased changes on `main` since v0.7.4.

## 0.7.4

- Automated patch release of the unreleased changes on `main` since v0.7.3.

## 0.7.3

- Automated patch release of the unreleased changes on `main` since v0.7.2.

## 0.7.2

- Automated patch release of the unreleased changes on `main` since v0.7.1.

## 0.7.1 — 2026-09-13

- Expose advisory reply claim status and first-seen pipeline/skill identifiers, with shared conformance for fallback, mixed stages, legacy hubs and malformed stamps. Existing reply construction remains compatible.

## 0.7.0 — 2026-09-13

- Add managed hub sessions with persistent subscriptions, bounded background retry backoff, terminal close, and no automatic replay of admitted requests. Preferred-origin selection delegates address binding and failed-attempt cleanup to the transport builder.
- Add presentable skill/intent inventories, regional example selection, tri-state catalogue locale support, and private best-effort inventory caches. Explicit language order survives JSON serialization across SDKs; invalid cache records become misses.
- Match Python question detection, including unnamed-locale patterns and Unicode question marks, with shared executable conformance vectors.
- Require reviewed Python reference and consumer evidence in PR and publishing parity checks, with scheduled fresh-dependency conformance checks.

## 0.6.3 — 2026-09-13

- Replace stale WebSocket authorization query values with the current identity, preserving unrelated query parameters and URL fragments.
- Keep credential-bearing network diagnostics out of connection exceptions and their causes.

## 0.6.2 — 2026-09-12

- Compare sentence punctuation as full Unicode scalars. Supplementary letters sharing a UTF-16 surrogate with a punctuation mark are no longer mistaken for sentence endings or trimmed from a phrase.

## 0.6.1 — 2026-09-12

- Refresh bundled listing rules to thalovant-languages 0.2.1, matching Python 0.6.8 across 270 languages. Preserve regional inheritance and the corrected French/Spanish trailing-word behavior.
- Regenerate public-reference cases for every shipped locale, including Spanish questions and French complete phrases.

## 0.6.0 — 2026-09-12

- Add locale-aware sentence listings, canonical slot examples, fuller phrase ranking and OVOS-compatible regional language selection.
- Snapshot custom rule data, preserve selected locale and count unique rendered phrases toward limits.
- Bound question-regex character access and handle regex stack exhaustion conservatively.

## 0.5.0 — 2026-09-12

- Match Python 0.6.3 request hints, location construction, ordered embedded audio replies, strict bounded hex decoding, and speakable intent examples with original phrase priority.
- Default runtime configuration updates to revision-guarded deep merges. Retry only HTTP 412 (three attempts maximum); fail before writing against older servers. Explicit replacement remains available. Merging now requires both hubs:read and hubs:write scopes, plus a paid plan.
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
