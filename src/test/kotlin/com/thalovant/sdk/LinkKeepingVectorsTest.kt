package com.thalovant.sdk

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Keeping a hub link up, against `link-keeping-vectors.json`.
 *
 * `close` cases hold [closeRefuses], the rule the WSS transport reads every
 * close with, to the vectors. `handshake` cases run one real connect through
 * the SDK's WSS transport against [FakeHub], which speaks the Noise XX and KK
 * handshakes and records the pattern each attempt chose. `supervise` cases
 * drive [LinkSupervisor], which [HubSession.run] asks after every attempt.
 * What this SDK produced for each case is recorded before it is compared,
 * shaped as the Python reference's `tests/test_link_keeping_vectors.py` shapes it.
 */
class LinkKeepingVectorsTest {

    private val vectors: JsonObject = ThalovantJson.parseToJsonElement(
        javaClass.getResourceAsStream("/thalovant/link-keeping-vectors.json")!!.bufferedReader().use { it.readText() },
    ).jsonObject
    private val policy = vectors.getValue("policy").jsonObject

    private fun cases(kind: String): List<JsonObject> = vectors.getValue("cases").jsonArray
        .map { it.jsonObject }.filter { it["kind"]?.jsonPrimitive?.content == kind }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.millis(key: String): Long = getValue(key).jsonPrimitive.long

    private fun record(case: JsonObject, produced: JsonElement) {
        val name = case.text("name")!!
        ConformanceRecord.record("link-keeping-vectors.json", name, produced)
        assertEquals(case.getValue("expect"), produced, name)
    }

    @Test
    fun `the policy is the SDK's`() {
        val defaults = HubSessionPolicy()
        assertEquals(policy.millis("retry_ms"), (defaults.retrySeconds * 1_000).roundToLong())
        assertEquals(policy.millis("retry_ceiling_ms"), (defaults.retryCeilingSeconds * 1_000).roundToLong())
        assertEquals(policy.millis("probe_ms"), (defaults.probeSeconds * 1_000).roundToLong())
        assertEquals(policy.millis("probe_down_ms"), (defaults.probeDownSeconds * 1_000).roundToLong())
        assertEquals(policy.millis("refusal_grace_ms"), (defaults.refusalGraceSeconds * 1_000).roundToLong())
        assertEquals(policy.millis("settle_ms"), REFUSAL_SETTLE_MS)
        assertEquals(policy.millis("settle_ms"), DEFAULT_ACCEPTANCE_WINDOW_MS)
        assertEquals(policy.millis("close_code_grace_ms"), CLOSE_CODE_GRACE_MS)
        assertEquals(policy.getValue("refusal_close_codes").jsonArray.map { it.jsonPrimitive.int }, REFUSAL_CLOSE_CODES.sorted())
    }

    @Test
    fun `closes are read as the vectors read them`() {
        for (case in cases("close")) {
            val refused = closeRefuses(
                case["code"]?.jsonPrimitive?.intOrNull,
                closedAfterHandshakeMs = if (case.text("when") == "after_handshake") case.millis("after_ms") else null,
                codeLateMs = case["code_late_ms"]?.jsonPrimitive?.long ?: 0,
                afterAuthenticatedFrame = (case["after_authenticated_frame"] as? JsonPrimitive)?.boolean ?: false,
            )
            record(case, buildJsonObject { put("outcome", if (refused) "refused" else "dropped") })
        }
    }

    /** One connect as a kept link makes it: the handshake, then the settle window. */
    private suspend fun attempt(hub: FakeHub, password: String, stateDir: java.nio.file.Path): String = try {
        hub.connectedClient(password, stateDir).close()
        "connected"
    } catch (_: ThalovantClientKeyRejectedException) {
        "client_key_rejected"
    } catch (_: ThalovantHubIdentityChangedException) {
        "key_changed"
    } catch (_: ThalovantIdentityException) {
        "refused"
    } catch (_: ThalovantConnectionException) {
        "failed"
    }

    @Test
    fun `handshakes fail the way the vectors say`() {
        for (case in cases("handshake")) {
            val produced = FakeHub().use { hub ->
                runBlocking {
                    val situation = case.text("situation")!!
                    var password = FakeHub.PASSWORD
                    var state = hub.stateDir
                    if (situation in setOf("pinned", "password_changed_since_pinning", "hub_key_changed",
                            "client_key_changed", "client_key_changed_pinned_here")) {
                        hub.connectedClient().close() // first contact pins both ways
                    }
                    when (situation) {
                        "wrong_password" -> password = "a-wrong-password"
                        "password_changed_since_pinning" -> hub.password = "the-password-now"
                        "hub_key_changed" -> {
                            hub.serverKey = Noise.randomKey()
                            hub.offerKk = case.getValue("hub_offers_kk").jsonPrimitive.boolean
                        }
                        "upgrade_status" -> hub.upgradeStatus = case.getValue("status").jsonPrimitive.int
                        // Another program, with its own folder and so its own key.
                        "client_key_changed" -> state = java.nio.file.Files.createTempDirectory(hub.stateDir, "another-program")
                        // A new client key, beside the hub pins it had.
                        "client_key_changed_pinned_here" -> replaceClientKey(state)
                        "closed_after_first_frame" -> {
                            hub.refuseNext.set(1)
                            hub.speakBeforeClosing = true
                        }
                    }
                    val before = hub.patternsChosen.size
                    val outcome = attempt(hub, password, state)
                    buildJsonObject {
                        put("outcome", outcome)
                        put("patterns", JsonArray(hub.patternsChosen.drop(before).map { JsonPrimitive(it.take(2)) }))
                    }
                }
            }
            record(case, produced)
        }
    }

    @Test
    fun `the supervisor decides as the vectors decide`() {
        val vectorPolicy = HubSessionPolicy(
            retrySeconds = policy.millis("retry_ms") / 1_000.0,
            retryCeilingSeconds = policy.millis("retry_ceiling_ms") / 1_000.0,
            probeSeconds = policy.millis("probe_ms") / 1_000.0,
            probeDownSeconds = policy.millis("probe_down_ms") / 1_000.0,
            refusalGraceSeconds = policy.millis("refusal_grace_ms") / 1_000.0,
        )
        for (case in cases("supervise")) {
            val supervisor = LinkSupervisor(vectorPolicy)
            val produced = JsonArray(
                case.getValue("events").jsonArray.map { it.jsonObject }.map { event ->
                    // The hub rejecting this client's own key is a refusal of its own
                    // kind, carried beside LinkOutcome.REFUSED.
                    val rejected = event.text("outcome") == "client_key_rejected"
                    val outcome = if (rejected) LinkOutcome.REFUSED else LinkOutcome.entries.single { it.wireName == event.text("outcome") }
                    val decision = supervisor.after(outcome, event.millis("at_ms") / 1_000.0, clientKeyRejected = rejected)
                    buildJsonObject {
                        put("action", decision.action.wireName)
                        when (decision.action) {
                            LinkAction.RETRY -> put("wait_ms", (decision.waitSeconds * 1_000).roundToLong())
                            LinkAction.GIVE_UP -> put("reason", decision.reasonName!!)
                            LinkAction.HOLD -> Unit
                        }
                    }
                },
            )
            record(case, produced)
        }
    }

    /** Gives the client a new static key, keeping the hub pins it has. */
    private fun replaceClientKey(state: java.nio.file.Path) {
        val key = state.resolve("noise-static.key")
        java.nio.file.Files.delete(key)
        val fresh = java.nio.file.Files.createFile(
            key, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
        )
        java.nio.file.Files.write(fresh, Noise.hex(Noise.randomKey()).toByteArray())
    }

    @Test
    fun `a rejected client key names the folders and stops run at once`() {
        FakeHub().use { hub ->
            runBlocking {
                hub.connectedClient().close()
                val other = java.nio.file.Files.createTempDirectory(hub.stateDir, "another-program")
                val session = HubSession(
                    connect = { hub.connectedClient(stateDir = other) },
                    policy = HubSessionPolicy(retrySeconds = 0.05, retryCeilingSeconds = 0.1, probeSeconds = 0.05,
                        probeDownSeconds = 0.05, refusalGraceSeconds = 30.0),
                    warm = false,
                )
                try {
                    val rejected = assertFailsWith<ThalovantClientKeyRejectedException> { withTimeout(10_000) { session.run() } }
                    assertIs<ThalovantIdentityException>(rejected, "still a refusal where one is caught")
                    assertEquals(other.toString(), rejected.keyFolder)
                    assertTrue("re-pair, or share the key folder" in rejected.message.orEmpty())
                    assertEquals(2, hub.attempts.get(), "the pinning connect, then one refused XX: no retry")
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `a real close right after the handshake is read by its code`() {
        for ((code, refused) in listOf(1000 to true, 1008 to true, 1011 to false, 1001 to false)) {
            FakeHub().use { hub ->
                hub.refuseNext.set(1)
                hub.refusalCode = code
                val failure = assertFailsWith<ThalovantException> { runBlocking { hub.connectedClient() } }
                assertEquals(refused, failure is ThalovantIdentityException, "close $code: $failure")
            }
        }
    }

    @Test
    fun `run stops at once when the hub's key changed`() {
        FakeHub().use { hub ->
            runBlocking {
                hub.connectedClient().close()
                hub.serverKey = Noise.randomKey()
                val session = HubSession(
                    connect = { hub.connectedClient() },
                    policy = HubSessionPolicy(retrySeconds = 0.05, retryCeilingSeconds = 0.1, probeSeconds = 0.05,
                        probeDownSeconds = 0.05, refusalGraceSeconds = 30.0),
                    warm = false,
                )
                try {
                    // KK against the old key fails, XX follows at once and meets
                    // the pin: run() ends there rather than retrying for ever.
                    assertFailsWith<ThalovantHubIdentityChangedException> { withTimeout(10_000) { session.run() } }
                    assertEquals(listOf("KKpsk0", "XXpsk2"), hub.patternsChosen.takeLast(2))
                    assertEquals(3, hub.attempts.get(), "the pinning connect, then KK and XX")
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `a pin is never replaced by the SDK`() {
        FakeHub().use { hub ->
            runBlocking {
                hub.connectedClient().close()
                val pinned = HiveMindNoiseStore(hub.stateDir).pin("fake-hub")!!
                hub.serverKey = Noise.randomKey()
                hub.offerKk = false
                assertIs<ThalovantHubIdentityChangedException>(runCatching { hub.connectedClient() }.exceptionOrNull())
                assertTrue(pinned.contentEquals(HiveMindNoiseStore(hub.stateDir).pin("fake-hub")!!))
            }
        }
    }
}
