package com.thalovant.sdk

import kotlinx.serialization.json.JsonArray
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
    public companion object {
        /** Builds the exception from a `hive.policy.denied` bus event. */
        public fun fromEvent(event: ThalovantEvent): ThalovantPolicyDeniedException {
            val inner = event.data["data"].asObjectOrNull()
            // Only strings: a number or a null in the list is not a message
            // type, and stringifying one would put "3" or "null" in front of
            // an operator reading which types to allow.
            val allowed = (inner?.get("allowed") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { entry -> entry.isString }?.content }
                ?: emptyList()
            return ThalovantPolicyDeniedException(
                deniedType = event.data.optionalString("denied_type") ?: "",
                code = event.data.optionalString("code") ?: "",
                reason = event.data.optionalString("reason") ?: "",
                allowed = allowed,
            )
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
) : ThalovantException(message)

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
