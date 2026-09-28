package com.thalovant.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Base class for every exception thrown by the Thalovant SDK. */
public open class ThalovantException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Identity files or identity payloads are missing fields or are insecure. */
public open class ThalovantIdentityException(message: String, cause: Throwable? = null) : ThalovantException(message, cause)

/**
 * The hub answered with a Noise static key that is not the pinned one.
 *
 * Trust-on-first-use: the first key a hub answers with is remembered, so
 * nothing can quietly stand in for it afterwards. A refusal here is that
 * protection working -- and it is also what an honestly rebuilt hub looks
 * like, because a new deployment mints a new key and the saved pin is then
 * merely out of date. The two cannot be told apart from here, so this refuses
 * and says which question it is, rather than guessing.
 *
 * Its own type because the answer is different from every other identity
 * failure: nothing about this client's credentials is wrong, and a caller that
 * reports "your hub would not accept this connection" is pointing at the wrong
 * end of it. It extends [ThalovantIdentityException] so callers that only
 * distinguish "the identity is the problem" keep working unchanged.
 */
public class ThalovantHubIdentityChangedException(message: String, cause: Throwable? = null) :
    ThalovantIdentityException(message, cause)

/**
 * Data-plane connection or handshake failures.
 *
 * Open since 0.8.0 so the admission failures, which are connection failures
 * too, can say so: [ThalovantAdmissionTimeoutException] and
 * [ThalovantAdmissionFailedException].
 */
public open class ThalovantConnectionException(message: String, cause: Throwable? = null) : ThalovantException(message, cause)

/**
 * A wait that ran out: the thing may still happen, later.
 *
 * Implemented by [ThalovantTimeoutException] and by
 * [ThalovantAdmissionTimeoutException], which is also a
 * [ThalovantConnectionException] -- a class can extend only one of the two, so
 * `is ThalovantTimeout` is the way to ask "did this time out?" of either.
 */
public interface ThalovantTimeout

/** Deadlines exceeded while waiting on the hub. */
public class ThalovantTimeoutException(message: String) : ThalovantException(message), ThalovantTimeout

/**
 * A hub had not admitted a new connection when the wait ended.
 *
 * A connection is admitted about ninety seconds after it is created, while the
 * platform carries it to its hub. This is both a connection failure and a
 * timeout ([ThalovantTimeout]): the connection exists and may still be
 * admitted, so waiting longer, or connecting later, can succeed.
 */
public class ThalovantAdmissionTimeoutException(message: String) :
    ThalovantConnectionException(message), ThalovantTimeout

/**
 * A new connection will not be admitted.
 *
 * Either the operation that admits it failed, or the platform gave up on it --
 * [errorCode] is the operation's own code, such as `gitops_push_rejected` or
 * `convergence_timeout`, and [statusCode] is null -- or the API refused the
 * wait itself, and then [statusCode], [code], [detail] and [problem] keep what
 * it answered, as a [ThalovantApiException] does. Waiting longer will not
 * help. An authentication refusal is never this: it is thrown as the API's
 * own [ThalovantAuthException].
 */
public class ThalovantAdmissionFailedException(
    message: String,
    public val errorCode: String? = null,
    cause: Throwable? = null,
    /** The HTTP status of the API's refusal of the wait, or null when the operation itself failed. */
    public val statusCode: Int? = null,
    /** The refusal's machine-readable code, as [ThalovantApiException.errorCode] reads it. */
    public val code: String? = null,
    /** The refusal's sentence, whole, as [ThalovantApiException.detail] reads it. */
    public val detail: String? = null,
    /** The refusal's body parsed, when it was a JSON object. */
    public val problem: JsonObject? = null,
) : ThalovantConnectionException(message, cause)

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
        /** What that counter allows in its period. A hub may allow more than
         *  an Int holds, so these are the whole range the wire can carry. */
        public val limit: Long,
        /** How much of it was used. */
        public val used: Long,
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
                    limit = inner["limit"].count(),
                    used = inner["used"].count(),
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
/**
 * The largest count the wire can carry, being the largest whole number every
 * JSON decoder holds exactly. Above it a decoder backed by a double can no
 * longer tell one whole number from the next, so two SDKs would report
 * different allowances for the same denial -- and a count nobody can agree on
 * is worse than none.
 */
internal const val MAX_COUNT: Long = (1L shl 53) - 1

private fun kotlinx.serialization.json.JsonElement?.count(): Long {
    val primitive = this as? JsonPrimitive ?: return 0
    if (!primitive.isString && (primitive.content == "true" || primitive.content == "false")) return 0
    val whole = primitive.content.trim().toLongOrNull() ?: 0
    return if (whole in 0..MAX_COUNT) whole else 0
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
        if (quota.limit == 0L && quota.used == 0L && quota.resetAfterSeconds == 0L && quota.period.isEmpty()) {
            // Refused on a quota, with none of the numbers. "All questions
            // used" would be inventing one.
            return "The hub refused '$deniedType': a quota has run out."
        }
        val used = if (quota.limit > 0L) "${quota.used} of ${quota.limit}" else "all"
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
 * A failed control-plane request.
 *
 * [statusCode] and [body] are set when the API answered with a non-2xx status.
 * Everything else the API said rides beside the message rather than inside it,
 * read out of [body] once, when the exception is built:
 *
 * - [problem] is the whole body parsed, when it is a JSON object -- the
 *   Problem+JSON document every Thalovant API refusal is. A structured field
 *   the API adds is reachable here without a new SDK release:
 *   `refused_images`, `allowed_images` and `allowed_repositories` on a
 *   `platform_image_required` refusal; `resource`, `limit`, `used` and `plan`
 *   on a `plan_limit` one.
 * - [errorCode] is the body's machine-readable code, for branching without
 *   reading the prose.
 * - [detail] is the API's own sentence, whole, exactly as sent.
 *
 * The message is not where to read any of that. It is one line for display:
 * "Thalovant API request failed with HTTP <status>", followed, when the body
 * has one, by a short known field -- the body's `detail` when it is a string;
 * else the `message` or `code` of a `detail` object, the `msg` of each entry
 * in a validation list, or the body's own `message`, `error_description`,
 * `error`, `title` or `code` -- with its whitespace collapsed and cut at 200
 * characters, so a long sentence is shortened there and whole in [detail].
 * The message never repeats what the request sent: a validation input is
 * never read into it, and the bearer token and every string of 8 characters
 * or more in the request body are replaced with `[redacted]` before it is
 * cut. [body], [problem] and [detail] are exactly what the API sent, and
 * [toString] is the class name and the message.
 *
 * Every field but the message is null for a local failure, such as a missing
 * access token or an unexpected response shape.
 */
public open class ThalovantApiException(
    message: String,
    public val statusCode: Int? = null,
    /** The response text as the API sent it, read as UTF-8, or null for a local failure. */
    public val body: String? = null,
) : ThalovantException(message) {

    /**
     * [body] parsed, when it is a JSON object; null for a body that is empty,
     * is not JSON, or is JSON that is not an object.
     */
    public val problem: JsonObject? = problemOf(body)

    /**
     * The body's machine-readable code, such as `platform_image_required` or
     * `plan_limit`, or null.
     *
     * [problem]'s `code` when it is a string with a non-whitespace character;
     * else, when its `detail` is itself an object -- FastAPI's own envelope,
     * which the API's Problem+JSON handler normally lifts -- that object's
     * `code` under the same rule. Returned exactly as sent.
     */
    public val errorCode: String? = problemMember(problem, "code")

    /**
     * The sentence the API wrote for a person, whole, or null.
     *
     * The control plane writes its refusals to be read: "Free plan allows up to
     * 1 connection." A caller that shows somebody why reads it here rather than
     * writing its own -- which is how a phone once told somebody whose plan was
     * full to try again in a moment.
     *
     * [problem]'s `detail` when it is a string with a non-whitespace character;
     * else, when `detail` is itself an object, that object's `detail` under the
     * same rule. Never trimmed, collapsed or shortened: a refusal that lists
     * every image a caller may pin instead runs past any display limit.
     */
    public val detail: String? = problemMember(problem, "detail")

    /**
     * How long the API asked to wait before trying again, in seconds, when it
     * said: a 429's `retry_after_seconds` -- at the top of [problem], or inside
     * its `detail` object, where the API's per-token limit puts it -- else its
     * `Retry-After` header, else its `RateLimit-Reset`, which is all the API's
     * own rate limiter sends with its plain-text "Too Many Requests". Null
     * otherwise.
     */
    public var retryAfterSeconds: Double? = retryAfterOf(problem)
        internal set
}

/** A problem's `retry_after_seconds`, at its top or inside a `detail` object: a number, not negative. */
private fun retryAfterOf(problem: JsonObject?): Double? {
    if (problem == null) return null
    for (source in listOfNotNull(problem, problem["detail"] as? JsonObject)) {
        val value = source["retry_after_seconds"] as? JsonPrimitive ?: continue
        if (value.isString || value.content == "true" || value.content == "false") continue
        value.content.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { return it }
    }
    return null
}

/**
 * The exception for a failed API answer, of the kind a caller can branch on.
 *
 * The same rules every SDK keeps (`connection-kinds-vectors.json`): 401, 423
 * and 403 `Insufficient scopes` are [ThalovantAuthException]; 402 and 403
 * `plan_limit` are [ThalovantPlanException]; 409
 * `home_assistant_already_linked` is [ThalovantAlreadyLinkedException]; every
 * other refusal is a plain [ThalovantApiException]. Each keeps [statusCode],
 * [body] and what is read out of it.
 */
internal fun apiException(message: String, statusCode: Int, body: String): ThalovantApiException {
    val problem = problemOf(body)
    val code = problemMember(problem, "code")
    val detail = problemMember(problem, "detail")
    return when {
        statusCode == 401 || statusCode == 423 || (statusCode == 403 && detail == "Insufficient scopes") ->
            ThalovantAuthException(message, statusCode, body)
        statusCode == 402 || (statusCode == 403 && code == "plan_limit") ->
            ThalovantPlanException(message, statusCode, body)
        statusCode == 409 && code == "home_assistant_already_linked" ->
            ThalovantAlreadyLinkedException(message, statusCode, body)
        else -> ThalovantApiException(message, statusCode, body)
    }
}

/**
 * The connection a 409 `home_assistant_already_linked` names: `client_id`, or
 * `existing_client_id` or `connection_id`, on the problem or on a `detail`
 * object inside it.
 */
internal fun linkedClientId(problem: JsonObject?): String? {
    if (problem == null) return null
    val nested = problem["detail"] as? JsonObject
    for (source in listOfNotNull(problem, nested)) {
        for (key in listOf("client_id", "existing_client_id", "connection_id")) {
            val value = (source[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!value.isNullOrEmpty()) return value
        }
    }
    return null
}

/** The body of a failed request as a JSON object, or null when it is not one. */
internal fun problemOf(body: String?): JsonObject? {
    if (body.isNullOrEmpty()) return null
    return try {
        ThalovantJson.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

/**
 * [problem]'s [name] member, or the same member of a `detail` that is itself an
 * object. The same rule reads `code` and `detail`, as every SDK reads them.
 */
internal fun problemMember(problem: JsonObject?, name: String): String? {
    if (problem == null) return null
    return problemText(problem[name]) ?: problemText((problem["detail"] as? JsonObject)?.get(name))
}

/**
 * A JSON string with something in it, exactly as sent; anything else is absent.
 * "Something" is a character the Python reference's `str.strip()` would keep
 * ([isListingSpace] is its whitespace), so a sentence of only spaces is no
 * sentence here either.
 */
internal fun problemText(value: JsonElement?): String? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    val text = primitive.content
    return text.takeIf { content -> content.any { !isListingSpace(it.code) } }
}

/**
 * The control plane refused the API token itself: sign in again.
 *
 * A 401 (the token is unknown, expired or revoked), a 423 (the account is
 * locked), or a 403 whose detail is `Insufficient scopes` (the token was never
 * given what this call needs). Signing in again, with the right scopes, is the
 * way out of each, which is not true of any other refusal.
 */
public class ThalovantAuthException(
    message: String,
    statusCode: Int? = null,
    body: String? = null,
) : ThalovantApiException(message, statusCode, body)

/**
 * The account's plan does not allow the request.
 *
 * A 402, or a 403 whose code is `plan_limit`; [problem] carries the `resource`,
 * `limit`, `used` and `plan` the API reported, and [detail] the sentence to
 * show somebody -- "Free plan allows up to 1 connection."
 */
public class ThalovantPlanException(
    message: String,
    statusCode: Int? = null,
    body: String? = null,
) : ThalovantApiException(message, statusCode, body)

/**
 * The hub already has the one connection of this kind it allows.
 *
 * A 409 `home_assistant_already_linked`: a hub takes one Home Assistant
 * connection. [clientId] names the connection that holds the link when the API
 * said which, so a caller can offer to replace it.
 */
public class ThalovantAlreadyLinkedException(
    message: String,
    statusCode: Int? = null,
    body: String? = null,
    public val clientId: String? = linkedClientId(problemOf(body)),
) : ThalovantApiException(message, statusCode, body)

/**
 * The API cannot make a connection of the kind asked for.
 *
 * A 422 naming `connection_type` (an API that does not know the kind yet), or
 * a created connection whose kind did not come back as asked. An API that
 * silently ignored the field would have handed out an ordinary satellite with
 * a satellite's grants, so the SDK deletes that connection before throwing;
 * [statusCode] is null then, because the API itself answered 201.
 */
public class ThalovantUnsupportedConnectionTypeException(
    message: String,
    statusCode: Int? = null,
    body: String? = null,
) : ThalovantApiException(message, statusCode, body)

/**
 * One device-login poll found nobody has decided yet: poll again later.
 *
 * [intervalMillis] is how long to wait before the next poll, already
 * lengthened when the API asked this code to slow down (RFC 8628 §3.5: five
 * seconds more for every `slow_down`, and they stay added).
 */
public class ThalovantDeviceLoginPendingException(
    message: String,
    public val intervalMillis: Long,
    statusCode: Int? = null,
    body: String? = null,
) : ThalovantApiException(message, statusCode, body)

/** The requested data-plane protocol is unavailable or unsupported. */
public class ThalovantUnsupportedProtocolException(message: String) : ThalovantException(message)

/**
 * The device sign-in request was denied in the browser.
 *
 * [statusCode] and [body] are the API's answer (a 400 `access_denied`). This
 * stays a [ThalovantException] rather than a [ThalovantApiException], as it
 * has been since the device flow arrived, so a `catch` written against it
 * keeps catching exactly what it caught.
 */
public class ThalovantDeviceLoginDeniedException @JvmOverloads constructor(
    message: String,
    public val statusCode: Int? = null,
    public val body: String? = null,
) : ThalovantException(message)

/**
 * The device sign-in code expired before it was approved.
 *
 * [statusCode] and [body] are the API's answer (a 400 `expired_token`); see
 * [ThalovantDeviceLoginDeniedException] for why this is not a
 * [ThalovantApiException].
 */
public class ThalovantDeviceLoginExpiredException @JvmOverloads constructor(
    message: String,
    public val statusCode: Int? = null,
    public val body: String? = null,
) : ThalovantException(message)

/** An automatic skill wait failed after acceptance. Resume with [accepted]; never replay the write. */
public class HubSkillOperationException(
    public val accepted: kotlinx.serialization.json.JsonObject,
    public val timedOut: Boolean,
    message: String,
) : ThalovantException(message)
