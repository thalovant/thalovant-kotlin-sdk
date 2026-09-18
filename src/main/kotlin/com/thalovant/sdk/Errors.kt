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
 * The hub refused a message type this connection may not publish.
 *
 * The hub answers `hive.policy.denied` at once, naming the type ([deniedType])
 * and the types it does allow ([allowed]); throwing here saves the caller a
 * timeout and tells the operator exactly what to add to the connection's
 * allow-list. [code] is the hub's reason code (`acl_disallowed_type`) and
 * [reason] its human-readable text.
 */
public class ThalovantPolicyDeniedException(
    public val deniedType: String,
    public val code: String = "",
    public val reason: String = "",
    public val allowed: List<String> = emptyList(),
) : ThalovantRuntimeException(policyDeniedMessage(deniedType, code, reason)) {
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
    public val quota: Quota? get() = quotaDetail

    internal var quotaDetail: Quota? = null

    public companion object {
        /** The hub's code for a refusal that is a spent quota, not a policy. */
        public const val QUOTA_EXCEEDED: String = "intent_quota_exceeded"

        /** Builds the exception from a `hive.policy.denied` bus event. */
        public fun fromEvent(event: ThalovantEvent): ThalovantPolicyDeniedException {
            val inner = event.data["data"].asObjectOrNull()
            // Only strings: a number or a null in the list is not a message
            // type, and stringifying one would put "3" or "null" in front of
            // an operator reading which types to allow.
            val allowed = (inner?.get("allowed") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content }
                ?: emptyList()
            val code = event.data.optionalString("code") ?: ""
            val denial = ThalovantPolicyDeniedException(
                deniedType = event.data.optionalString("denied_type") ?: "",
                code = code,
                reason = event.data.optionalString("reason") ?: "",
                allowed = allowed,
            )
            if (code == QUOTA_EXCEEDED && inner != null) {
                denial.quotaDetail = Quota(
                    period = (inner["period"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    limit = (inner["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                    used = (inner["used"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                    resetAfterSeconds = (inner["reset_after"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                )
            }
            return denial
        }
    }
}

private fun policyDeniedMessage(deniedType: String, code: String, reason: String): String {
    val detail = reason.ifEmpty { code.ifEmpty { "refused by the hub's policy" } }
    return "The hub refused '$deniedType': $detail. Allow this connection to publish " +
        "'$deniedType' in the dashboard's connection settings."
}

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
