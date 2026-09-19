package com.thalovant.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Base class for every exception thrown by the Thalovant SDK. */
public open class ThalovantException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Identity files or identity payloads are missing fields or are insecure. */
public class ThalovantIdentityException(message: String, cause: Throwable? = null) : ThalovantException(message, cause)

/** Data-plane connection or handshake failures. */
public class ThalovantConnectionException(message: String, cause: Throwable? = null) : ThalovantException(message, cause)

/** Deadlines exceeded while waiting on the hub. */
public class ThalovantTimeoutException(message: String) : ThalovantException(message)

/** The hub reported a runtime failure while handling a request. */
public open class ThalovantRuntimeException(message: String) : ThalovantException(message)

/**
 * The hub heard the question and no skill answered it.
 *
 * `ovos.intent.unmatched` (and `complete_intent_failure` from older hubs) is
 * the hub saying it understood it was asked something and has nothing
 * installed that handles it. That is a different thing from being refused, and
 * a very different thing from not answering: nothing is wrong, the question is
 * simply outside what this hub can do.
 *
 * It arrived as a bare [ThalovantRuntimeException], which a caller could only
 * render as something went wrong -- so somebody asking a hub a question it has
 * no skill for was told the hub "would not do that", as though it had refused.
 * Carried separately so a caller can say so, and point at what the hub *can*
 * be asked.
 */
public class ThalovantUnansweredException(
    /** The hub's own words, when it sent any. */
    public val spoken: String = "",
) : ThalovantRuntimeException(
    spoken.ifEmpty { "No skill on this hub answered that." },
)

/**
 * The hub refused a message, the instant it did.
 *
 * The hub sends `hive.policy.denied` as soon as it refuses, naming the type
 * ([deniedType]). Three different things arrive under that one name, and each
 * needs something different said about it:
 *
 * - an allow-list refusal (`acl_disallowed_type`) -- ask whoever manages the
 *   connection to allow the type; [allowed] lists what it may send;
 * - a spent allowance ([QUOTA_EXCEEDED]) -- wait, or raise the limit; [quota]
 *   carries the numbers;
 * - a hub whose agent bus is down ([BACKEND_UNAVAILABLE]) -- nothing the caller
 *   can fix; try again later.
 *
 * [code] is the hub's reason code and [reason] its human-readable text.
 */
public class ThalovantPolicyDeniedException(
    public val deniedType: String,
    public val code: String = "",
    public val reason: String = "",
    public val allowed: List<String> = emptyList(),
    quota: Quota? = null,
) : ThalovantRuntimeException(policyDeniedMessage(deniedType, code, reason, quota)) {
    /**
     * What the hub said about the quota, when the refusal was a quota.
     *
     * The intent-quota policy denies with `intent_quota_exceeded` and sends
     * the numbers with it: which counter ran out, what it allows, how much of
     * it was used, and how long until it resets. Without these a caller can
     * only say "refused", which is what an app showed a person who had simply
     * used up the day.
     */
    public data class Quota(
        /** The counter that ran out, as the hub names it: `daily`, `monthly`. */
        public val period: String,
        /** What that counter allows in its period. */
        public val limit: Int,
        /** How much of it was used. */
        public val used: Int,
        /** Seconds until the counter resets, or 0 when the hub did not say. */
        public val resetAfterSeconds: Long,
    )

    /** The quota detail when [code] is `intent_quota_exceeded`, else null. */
    public val quota: Quota? = quota

    public companion object {
        /** The hub's code for a refusal that is a spent quota, not a policy. */
        public const val QUOTA_EXCEEDED: String = "intent_quota_exceeded"

        /** The hub's code for a refusal because its own agent bus is down. */
        public const val BACKEND_UNAVAILABLE: String = "backend_unavailable"

        /** Builds the exception from a `hive.policy.denied` bus event. */
        public fun fromEvent(event: ThalovantEvent): ThalovantPolicyDeniedException {
            // The policy's own detail rides nested under data.data
            // (hivemind-core _send_policy_denied: "data": verdict.data).
            // Missing reads as empty, so a quota refusal whose numbers were
            // lost is still a quota, with zeros -- as the shared vectors say.
            val inner = event.data["data"].asObjectOrNull() ?: JsonObject(emptyMap())
            // Only non-blank strings, trimmed: a number, a null or a blank in
            // the list is not a message type, and stringifying one would put
            // "3" or "null" in front of an operator reading what to allow.
            val allowed = (inner["allowed"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content?.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val code = event.data.optionalString("code") ?: ""
            val quota = if (code == QUOTA_EXCEEDED) {
                Quota(
                    period = (inner["period"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    limit = inner["limit"].count().toInt(),
                    used = inner["used"].count().toInt(),
                    resetAfterSeconds = inner["reset_after"].count(),
                )
            } else {
                null
            }
            return ThalovantPolicyDeniedException(
                deniedType = event.data.optionalString("denied_type") ?: "",
                code = code,
                reason = event.data.optionalString("reason") ?: "",
                allowed = allowed,
                quota = quota,
            )
        }
    }
}

/**
 * A whole, non-negative count from the wire, or 0: never a boolean, never a
 * guess. A negative limit, usage or reset time is not something a policy can
 * mean, and passing one through would have an app say "-1 of -5 questions used".
 */
private fun kotlinx.serialization.json.JsonElement?.count(): Long {
    val primitive = this as? JsonPrimitive ?: return 0
    if (!primitive.isString && (primitive.content == "true" || primitive.content == "false")) return 0
    return (primitive.content.trim().toLongOrNull() ?: 0).coerceAtLeast(0)
}

private fun policyDeniedMessage(
    deniedType: String,
    code: String,
    reason: String,
    quota: ThalovantPolicyDeniedException.Quota?,
): String {
    // Advice follows the kind of refusal. Telling somebody who used up their
    // day to "allow this connection to publish recognizer_loop:utterance" sent
    // them to a settings page that could not help.
    if (quota != null) {
        val used = if (quota.limit > 0) "${quota.used} of ${quota.limit}" else "all"
        val period = if (quota.period.isNotEmpty()) " ${quota.period}" else ""
        val resets = if (quota.resetAfterSeconds > 0) "; it resets in ${quota.resetAfterSeconds}s" else ""
        return "The hub refused '$deniedType': $used$period questions used$resets."
    }
    if (code == ThalovantPolicyDeniedException.BACKEND_UNAVAILABLE) {
        val detail = if (reason.isNotEmpty()) ": $reason" else ""
        return "The hub could not reach its assistant$detail. Try again shortly."
    }
    val detail = reason.ifEmpty { code.ifEmpty { "refused by the hub's policy" } }
    return "The hub refused '$deniedType': $detail. Allow this connection to publish " +
        "'$deniedType' in the dashboard's connection settings."
}

/**
 * The typed error an ask throws for the failure event it ended on.
 *
 * A refusal, a question the hub has nothing for, and a fault need three
 * different sentences, and a bare runtime error allowed only one.
 */
internal fun failureError(event: ThalovantEvent): ThalovantException = when (event.name) {
    ThalovantEvents.POLICY_DENIED -> ThalovantPolicyDeniedException.fromEvent(event)
    ThalovantEvents.INTENT_UNMATCHED, ThalovantEvents.INTENT_FAILURE -> ThalovantUnansweredException(event.text)
    else -> ThalovantRuntimeException(event.text.ifEmpty { "Hub reported ${event.name}." })
}

/**
 * Whether a `hive.policy.denied` is this ask's to throw.
 *
 * A denial carrying a request id is judged by it, like any reply. The hub
 * builds its denials with source and destination context only, so the usual
 * one carries none and names the refused type instead: enough when this ask is
 * the only utterance the client has out, a guess otherwise -- and a wrong
 * guess ends a question the hub never refused. [sendsInFlight] is
 * fire-and-forget utterances still inside [UNTRACKED_UTTERANCE_GRACE_MS]: they
 * have nothing to wait on, but a refusal of one could land while this ask is
 * waiting. The shared refusal vectors pin every case.
 */
internal fun refusalBelongsToAsk(
    requestId: String?,
    ownRequestId: String,
    deniedType: String?,
    asksInFlight: Int,
    queriesInFlight: Int,
    sendsInFlight: Int = 0,
): Boolean {
    if (!requestId.isNullOrEmpty()) return requestId == ownRequestId
    return deniedType == ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE &&
        asksInFlight == 1 && queriesInFlight == 0 && sendsInFlight == 0
}

/**
 * How long a fire-and-forget utterance counts as possibly still being refused.
 *
 * Denials come back as fast as the hub admits a message -- milliseconds -- so
 * this is generous on purpose: a wrong "in flight" only costs an ask the
 * deadline it always had, where a wrong "not in flight" ends a question the
 * hub never refused. The shared refusal vectors name it
 * (`untracked_grace_seconds`), so every SDK uses the same window.
 */
internal const val UNTRACKED_UTTERANCE_GRACE_MS: Long = 10_000

/**
 * Control-plane API failures. [statusCode] and [body] are set when the API
 * responded with a non-2xx status; both are null for local failures such as a
 * missing access token or an unexpected response shape.
 */
public class ThalovantApiException(
    message: String,
    public val statusCode: Int? = null,
    public val body: String? = null,
) : ThalovantException(message) {

    /**
     * The sentence the API wrote for a person, out of [body], or null.
     *
     * The control plane answers RFC 7807 and writes its refusals to be read:
     * "Free plan allows up to 1 client." [message] is not that -- it is the
     * whole body, whitespace-collapsed and truncated, behind "Thalovant API
     * request failed with HTTP 403:" -- so a caller that wants to show
     * somebody why gets a JSON blob or writes this itself.
     *
     * thalovant-android wrote it itself, which is why a phone said "Thalovant
     * could not answer just now. Try again in a moment." to somebody whose
     * plan was full: not a moment, and trying again would not have helped.
     *
     * Both shapes the API emits are handled: `{"detail": "..."}` and the
     * validation wrapper `{"detail": {"detail": "...", "errors": [...]}}`.
     */
    public val detail: String?
        get() {
            val raw = body?.takeIf { it.isNotBlank() } ?: return null
            val root = runCatching { ThalovantJson.parseToJsonElement(raw).asObjectOrNull() }
                .getOrNull() ?: return null
            val detail = root["detail"] ?: return null
            (detail as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
            return ((detail as? JsonObject)?.get("detail") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
        }
}

/** The requested data-plane protocol is unavailable or unsupported. */
public class ThalovantUnsupportedProtocolException(message: String) : ThalovantException(message)

/** The device sign-in request was denied in the browser. */
public class ThalovantDeviceLoginDeniedException(message: String) : ThalovantException(message)

/** The device sign-in code expired before it was approved. */
public class ThalovantDeviceLoginExpiredException(message: String) : ThalovantException(message)

/** An automatic skill wait failed after acceptance. Resume with [accepted]; never replay the write. */
public class HubSkillOperationException(
    public val accepted: kotlinx.serialization.json.JsonObject,
    public val timedOut: Boolean,
    message: String,
) : ThalovantException(message)
