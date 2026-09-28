package com.thalovant.sdk

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Durable operation lifecycle status, matching the API `OperationStatus` literal. */
@Serializable
public enum class OperationStatus {
    @SerialName("requested")
    REQUESTED,

    @SerialName("committed")
    COMMITTED,

    @SerialName("applied")
    APPLIED,

    @SerialName("ready")
    READY,

    @SerialName("failed")
    FAILED,

    @SerialName("timed_out")
    TIMED_OUT,
}

/** Typed durable operation, matching the API `OperationResource` schema exactly. */
@Serializable
public data class OperationResource(
    public val id: String,
    public val kind: String,
    @SerialName("aggregate_type") public val aggregateType: String,
    @SerialName("aggregate_id") public val aggregateId: String? = null,
    public val status: OperationStatus,
    public val details: JsonObject = EMPTY_JSON_OBJECT,
    @SerialName("git_commit_sha") public val gitCommitSha: String? = null,
    @SerialName("error_code") public val errorCode: String? = null,
    @SerialName("error_message") public val errorMessage: String? = null,
    @SerialName("created_at") public val createdAt: String,
    @SerialName("updated_at") public val updatedAt: String,
    @SerialName("committed_at") public val committedAt: String? = null,
    @SerialName("applied_at") public val appliedAt: String? = null,
    @SerialName("ready_at") public val readyAt: String? = null,
    @SerialName("terminal_at") public val terminalAt: String? = null,
    public val links: Map<String, String?> = emptyMap(),
)

public enum class MemoryScope(public val wireName: String) {
    PERSONAL("personal"),
    WORKSPACE("workspace"),
    HUB("hub"),
}

public enum class MemoryKind(public val wireName: String) {
    NOTE("note"),
    PREFERENCE("preference"),
    FACT("fact"),
}

public data class MemoryListOptions(
    public val scope: MemoryScope? = null,
    public val kind: MemoryKind? = null,
    public val ownerId: String? = null,
    public val hubId: String? = null,
    public val query: String? = null,
    public val includeDeleted: Boolean = false,
    public val includeExpired: Boolean = false,
    public val limit: Int? = null,
    public val offset: Int? = null,
)

public data class MemoryCreatePayload(
    public val content: String,
    public val scope: MemoryScope? = null,
    public val kind: MemoryKind? = null,
    public val title: String? = null,
    public val tags: List<String>? = null,
    public val ownerId: String? = null,
    public val hubId: String? = null,
    public val source: String? = null,
    public val metadata: JsonObject? = null,
    public val consentScope: String? = null,
    public val consentVersion: String? = null,
    public val retentionPolicy: String? = null,
    public val expiresAt: String? = null,
)

public data class MemoryUpdatePayload(
    public val kind: MemoryKind? = null,
    public val title: String? = null,
    public val content: String? = null,
    public val tags: List<String>? = null,
    public val metadata: JsonObject? = null,
    public val consentScope: String? = null,
    public val consentVersion: String? = null,
    public val retentionPolicy: String? = null,
    public val expiresAt: String? = null,
    public val clearExpiresAt: Boolean = false,
)

public data class AnalyticsOverviewOptions(
    public val range: String? = null,
    public val bucket: String? = null,
    public val hubId: String? = null,
    public val clientId: String? = null,
    public val country: String? = null,
    public val message: String? = null,
    public val utterance: String? = null,
    public val intent: String? = null,
    public val timeStart: String? = null,
    public val timeEnd: String? = null,
    public val weekday: Int? = null,
    public val hour: Int? = null,
)

/**
 * Body for [ThalovantControlPlane.createHub]. [name] and [spec] are required by
 * the API; every other field is omitted from the request when null.
 */
public data class HubCreatePayload(
    public val name: String,
    public val spec: JsonObject,
    public val slug: String? = null,
    public val namespace: String? = null,
    public val runtimeGroupId: String? = null,
    public val domain: String? = null,
    public val active: Boolean? = null,
    public val visibility: String? = null,
    public val capacityProfile: String? = null,
    public val ownerId: String? = null,
)

/**
 * Body for [ThalovantControlPlane.updateHub]. Every field is optional and only
 * the ones set are sent, so an unset field is left untouched by the patch.
 *
 * [name], [namespace], and [domain] are immutable after creation: the API
 * rejects a *changed* value with HTTP 400 and ignores an unchanged one. Build
 * the patch from the fields you mean to change rather than round-tripping a
 * whole hub resource back through it.
 */
public data class HubUpdatePayload(
    public val name: String? = null,
    public val slug: String? = null,
    public val namespace: String? = null,
    public val domain: String? = null,
    public val active: Boolean? = null,
    public val visibility: String? = null,
    public val capacityProfile: String? = null,
    public val runtimeGroupId: String? = null,
    public val spec: JsonObject? = null,
    /** Admin-only; the API rejects it for tenant tokens. */
    public val isLocked: Boolean? = null,
)

/**
 * Options for [ThalovantControlPlane.releaseHub] and
 * [ThalovantControlPlane.releaseRuntimeGroup]. Every option is optional;
 * omitted fields fall back to the workspace release policy. Passing [images]
 * switches to `custom` mode unless [mode] is also set.
 *
 * Unless the caller is a platform administrator, each key of [images] may name
 * only a platform image for that key: a catalog pin of the stable or alpha
 * channel, the resource's current or recommended image, its release-policy
 * image, or the platform's default image. A runtime group's `core` also takes
 * any tag or digest of `ghcr.io/thalovant/ovos-core`, and a hub's `listener`
 * any tag or digest of `ghcr.io/thalovant/hivemind-listener`; `bus` and
 * `preview_bridge` take only the listed images. The API refuses anything else
 * with HTTP 403 `platform_image_required`, and [ThalovantApiException.problem]
 * names what each refused key may be instead.
 */
public data class ReleaseOptions(
    public val channel: String? = null,
    public val mode: String? = null,
    public val version: String? = null,
    public val images: Map<String, String>? = null,
    public val reason: String? = null,
)

/** Body for [ThalovantControlPlane.createRuntimeGroup]; only [name] is required. */
public data class RuntimeGroupCreatePayload(
    public val name: String,
    public val description: String? = null,
    public val environment: String? = null,
    public val ownerId: String? = null,
    /** Seeds the new group from the workspace default group. */
    public val cloneFromDefault: Boolean? = null,
)

/**
 * Body for [ThalovantControlPlane.updateRuntimeGroup]. [spec] patches
 * `replicas` and container `resources`.
 */
public data class RuntimeGroupUpdatePayload(
    public val name: String? = null,
    public val description: String? = null,
    public val spec: JsonObject? = null,
)

/** Options for [ThalovantControlPlane.listMarketplaceSkills]. */
public data class MarketplaceSkillListOptions(
    /** Admin tokens only; silently ignored for tenant callers. */
    public val ownerId: String? = null,
    /** Admin tokens only; silently ignored for tenant callers. */
    public val includeInactive: Boolean = false,
    /** Re-syncs the global catalog from its source first, which is slower. */
    public val forceRefresh: Boolean = false,
)

/** Options for [ThalovantControlPlane.installRuntimeGroupSkill]. */
public data class InstallSkillOptions(
    public val marketplaceSkillId: String? = null,
    /** `catalog` installs a marketplace skill; `git` installs need [sourceRef]. */
    public val sourceType: String = "catalog",
    public val sourceRef: String? = null,
    public val versionPin: String? = null,
    public val active: Boolean = true,
)

/**
 * The `client_id` Home Assistant signs in with: a registered app, so the person
 * approving sees it named and verified. Pass it to
 * [ThalovantControlPlane.beginDeviceLogin] or [DeviceLoginOptions.clientId].
 */
public const val HOME_ASSISTANT_CLIENT_ID: String = "thalovant-home-assistant"

/**
 * A device sign-in as the person approving it sees it, read by
 * [ThalovantControlPlane.describeDeviceLogin]: what it asks for, and who asks.
 *
 * [clientVerified] is true only for a registered app ([clientId] set); its
 * [clientName] is then the platform's own name for it, and [deviceName]
 * whatever the device called itself. For anything else, [clientName] is what
 * the device claimed, and nothing vouches for it.
 */
public class DeviceLoginRequest(
    public val scopes: List<String>,
    public val clientName: String?,
    public val clientId: String?,
    public val clientVerified: Boolean,
    public val deviceName: String?,
    /** When the code stops working, in ISO 8601 exactly as the API sent it, or null. */
    public val expiresAt: String?,
) {
    override fun equals(other: Any?): Boolean = other is DeviceLoginRequest && scopes == other.scopes &&
        clientName == other.clientName && clientId == other.clientId && clientVerified == other.clientVerified &&
        deviceName == other.deviceName && expiresAt == other.expiresAt

    override fun hashCode(): Int = listOf(scopes, clientName, clientId, clientVerified, deviceName, expiresAt).hashCode()

    override fun toString(): String =
        "DeviceLoginRequest(scopes=$scopes, clientName=$clientName, clientId=$clientId, " +
            "clientVerified=$clientVerified, deviceName=$deviceName, expiresAt=$expiresAt)"

    public companion object {
        /** Reads `GET /v1/auth/device/codes/{user_code}`. */
        public fun fromJson(body: JsonObject): DeviceLoginRequest {
            val clientId = jsonText(body["client_id"])
            return DeviceLoginRequest(
                scopes = (body["scopes"] as? JsonArray)?.mapNotNull(::jsonText) ?: emptyList(),
                clientName = jsonText(body["client_name"]),
                clientId = clientId,
                clientVerified = clientId != null && (body["client_verified"] as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.content == "true",
                deviceName = jsonText(body["device_name"]),
                expiresAt = jsonText(body["expires_at"]),
            )
        }
    }
}

/** Default `POST /v1/auth/device/token` poll interval when the API omits `interval`. */
public const val DEFAULT_DEVICE_POLL_INTERVAL_MILLIS: Long = 5_000

/**
 * The scopes a Home Assistant link asks for when it signs in: find the hubs,
 * then create, find and delete its own connection. Also all that a Free plan
 * can approve, so asking for more breaks sign-in for Free accounts.
 */
public val HOME_ASSISTANT_SCOPES: List<String> = listOf("hubs:read", "clients:read", "clients:write")

/** `spec.connection_type` of a Home Assistant link; see [CreateClientIdentityOptions.connectionType]. */
public const val CONNECTION_TYPE_HOME_ASSISTANT: String = "home_assistant"

/**
 * How long [ThalovantControlPlane.waitForAdmission] waits by default. A hub
 * admits a new connection about ninety seconds after it is created.
 */
public const val DEFAULT_ADMISSION_TIMEOUT_MS: Long = 180_000

/** How often [ThalovantControlPlane.waitForAdmission] reads the operation by default. */
public const val DEFAULT_OPERATION_POLL_INTERVAL_MS: Long = 2_000

/**
 * A started device sign-in: what to show a person, and what to poll with.
 *
 * Returned by [ThalovantControlPlane.beginDeviceLogin]. Show [verificationUri]
 * and [userCode] (or [verificationUriComplete], which carries the code), then
 * call [ThalovantControlPlane.pollDeviceLogin] every [intervalMillis] until it
 * returns a token or throws something other than
 * [ThalovantDeviceLoginPendingException].
 *
 * [deviceCode] is the secret half and never needs showing: this is a plain
 * class whose [toString] leaves it out. [asJson] keeps it, so a sign-in can be
 * resumed in another process with [fromJson]; store that like a secret.
 */
public class DeviceAuthorization(
    public val deviceCode: String,
    public val userCode: String,
    public val verificationUri: String,
    public val verificationUriComplete: String? = null,
    /** How long to wait between two polls, as the API asked. */
    public val intervalMillis: Long = DEFAULT_DEVICE_POLL_INTERVAL_MILLIS,
    /** How long the code is valid for from when it was issued. */
    public val expiresInMillis: Long = 900_000,
) {
    /** A JSON form, [deviceCode] included, in the API's own field names. */
    public fun asJson(): JsonObject = buildJsonObject {
        put("device_code", deviceCode)
        put("user_code", userCode)
        put("verification_uri", verificationUri)
        put("verification_uri_complete", verificationUriComplete)
        put("interval", intervalMillis / 1_000.0)
        put("expires_in", expiresInMillis / 1_000)
    }

    override fun toString(): String =
        "DeviceAuthorization(userCode=$userCode, verificationUri=$verificationUri, " +
            "intervalMillis=$intervalMillis, expiresInMillis=$expiresInMillis)"

    public companion object {
        /**
         * Reads `POST /v1/auth/device/authorize` (or [asJson]).
         *
         * Refuses a grant without its codes, and a verification URL that is
         * not http(s), has no host, or carries credentials or whitespace: it
         * is about to be opened in a browser, or read out to somebody.
         */
        public fun fromJson(grant: JsonObject): DeviceAuthorization {
            val deviceCode = jsonText(grant["device_code"])
            val userCode = jsonText(grant["user_code"])
            val verificationUri = jsonText(grant["verification_uri"])
            if (deviceCode == null || userCode == null || verificationUri == null) {
                throw ThalovantApiException("Thalovant API device authorization response was incomplete.")
            }
            val completeValue = grant["verification_uri_complete"]
            val complete = jsonText(completeValue)
            if (deviceVerificationUri(verificationUri) == null ||
                (completeValue != null && completeValue != JsonNull && (complete == null || deviceVerificationUri(complete) == null))
            ) {
                throw ThalovantApiException("Thalovant API device authorization returned an invalid verification URI.")
            }
            val interval = jsonNumber(grant["interval"])?.takeIf { it >= 0 }
            val expires = jsonNumber(grant["expires_in"])?.takeIf { it >= 0 }
            return DeviceAuthorization(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                verificationUriComplete = complete,
                intervalMillis = interval?.let { (it * 1_000).roundToLong() } ?: DEFAULT_DEVICE_POLL_INTERVAL_MILLIS,
                expiresInMillis = expires?.let { (it * 1_000).roundToLong() } ?: 900_000,
            )
        }
    }
}

/**
 * An API token the API minted for a device sign-in: the credential and what it may do.
 *
 * There is no refresh token; a device-login token lives 365 days. [tokenId] is
 * what [ThalovantControlPlane.revokeApiToken] revokes. A plain class, so
 * [toString] never prints [accessToken].
 */
public class ApiToken(
    public val accessToken: String,
    public val tokenType: String = "bearer",
    public val scopes: List<String> = emptyList(),
    /** When the token stops working, in ISO 8601 exactly as the API sent it, or null. */
    public val expiresAt: String? = null,
    public val tokenId: String? = null,
) {
    /** A JSON form in the API's own field names, [accessToken] included: store it as a secret. */
    public fun asJson(): JsonObject = buildJsonObject {
        put("access_token", accessToken)
        put("token_type", tokenType)
        put("scopes", JsonArray(scopes.map(::JsonPrimitive)))
        put("expires_at", expiresAt)
        put("token_id", tokenId)
    }

    override fun toString(): String =
        "ApiToken(tokenType=$tokenType, scopes=$scopes, expiresAt=$expiresAt, tokenId=$tokenId)"

    public companion object {
        /** Reads a token answer; one without an `access_token` is refused. */
        public fun fromJson(token: JsonObject): ApiToken {
            val accessToken = jsonText(token["access_token"])
                ?: throw ThalovantApiException("Thalovant API token response did not include access_token.")
            return ApiToken(
                accessToken = accessToken,
                tokenType = jsonText(token["token_type"]) ?: "bearer",
                scopes = (token["scopes"] as? JsonArray)?.mapNotNull(::jsonText) ?: emptyList(),
                expiresAt = jsonText(token["expires_at"]),
                tokenId = jsonText(token["token_id"]),
            )
        }
    }
}

/** Options for [ThalovantControlPlane.loginWithBrowser]. */
public data class DeviceLoginOptions(
    /** Scopes to request for the durable API token; omitted from the request when null. */
    public val scopes: List<String>? = null,
    /** Human-readable name shown on the browser approval page; omitted when null. */
    public val clientName: String? = null,
    /**
     * Best-effort open of `verification_uri_complete` in the system browser via a
     * reflective `java.awt.Desktop` lookup. Safely skipped on Android and headless
     * JVMs, where the class or the browse action is unavailable; never throws.
     */
    public val openBrowser: Boolean = true,
    /**
     * Receives the raw device authorization payload to present the code yourself.
     * When null, the SDK prints `verification_uri` and `user_code` to stdout.
     */
    public val prompt: ((JsonObject) -> Unit)? = null,
    /** How long to keep polling before [ThalovantTimeoutException]; default 900 s. */
    public val timeoutMillis: Long = 900_000,
    /**
     * The registered app signing in, sent as `client_id` -- such as
     * [HOME_ASSISTANT_CLIENT_ID] -- so the approval page names it as verified;
     * left out when null. A registered app may ask only for its own scopes,
     * and an id the API does not know is refused with 400 `unknown_client`.
     */
    public val clientId: String? = null,
) {
    /** The shape of 0.8.0, kept so code compiled against it still links. */
    @Deprecated("Kept for binary compatibility; pass clientId as well.", level = DeprecationLevel.HIDDEN)
    public constructor(
        scopes: List<String>? = null,
        clientName: String? = null,
        openBrowser: Boolean = true,
        prompt: ((JsonObject) -> Unit)? = null,
        timeoutMillis: Long = 900_000,
    ) : this(scopes, clientName, openBrowser, prompt, timeoutMillis, null)

    /** The `copy` of 0.8.0, kept so code compiled against it still links; keeps [clientId]. */
    @Deprecated("Kept for binary compatibility; copy with clientId as well.", level = DeprecationLevel.HIDDEN)
    public fun copy(
        scopes: List<String>? = this.scopes,
        clientName: String? = this.clientName,
        openBrowser: Boolean = this.openBrowser,
        prompt: ((JsonObject) -> Unit)? = this.prompt,
        timeoutMillis: Long = this.timeoutMillis,
    ): DeviceLoginOptions = DeviceLoginOptions(scopes, clientName, openBrowser, prompt, timeoutMillis, clientId)
}

public data class CreateClientIdentityOptions(
    public val name: String,
    public val siteId: String? = null,
    public val spec: JsonObject? = null,
    public val ownerId: String? = null,
    public val active: Boolean = true,
    public val preferredProtocols: List<HubProtocol>? = null,
    public val idempotencyKey: String? = null,
    /**
     * The kind of connection to create, sent as `spec.connection_type`:
     * `voice_satellite`, `web_chat`, `developer`, `embedded`, or
     * [CONNECTION_TYPE_HOME_ASSISTANT]. The kind decides what the connection
     * may send and receive. Null sends none, and the API makes its default.
     *
     * When set, the API must say the connection is of that kind; see
     * [ThalovantControlPlane.createClientIdentity].
     */
    public val connectionType: String? = null,
)

/**
 * Result of [ThalovantControlPlane.createClientIdentity]. Keep [identity]
 * secret; the raw [hub] and [client] API resources carry the same bootstrap
 * credentials (`initial_identify`, `initial_identify_token`, and the secret
 * `spec` fields), so treat them the same way.
 *
 * Security: this is intentionally a plain `class`, not a `data class`. A
 * generated `data class` `toString()` would dump [identity]/[hub]/[client] and
 * leak those secrets into logs and stack traces. Do not convert it to a
 * `data class`; a test asserts `toString()` leaks no secret values.
 */
public class BootstrapIdentityResult internal constructor(
    public val identity: ThalovantIdentity,
    public val hub: JsonObject,
    public val client: JsonObject,
    public val endpoint: SelectedHubEndpoint?,
) {
    public val selectedProtocol: HubProtocol? get() = endpoint?.protocol

    /** The new connection's id. */
    public val clientId: String? get() = jsonText(client["id"])

    /** The kind of connection the API recorded, `spec.connection_type`, or null when it named none. */
    public val connectionType: String? get() = jsonText((client["spec"] as? JsonObject)?.get("connection_type"))

    /**
     * The operation that carries the new connection to its hub, when the API
     * returned one it could read; [ThalovantControlPlane.waitForAdmission]
     * waits for it.
     */
    public val operation: OperationResource? get() = operationOrNull(client["operation"])

    /**
     * The id of the operation the create answered with, even when [operation]
     * is null because its status is one this SDK does not know yet: what
     * [ThalovantControlPlane.waitForAdmission] follows, keeping polling through
     * such a status.
     */
    public val operationId: String? get() = jsonText((client["operation"] as? JsonObject)?.get("id"))

    /** The operation's status exactly as the API sent it, known to this SDK or not. */
    public val operationStatus: String? get() = jsonText((client["operation"] as? JsonObject)?.get("status"))

    /**
     * Serializes the result. Without [includeSecrets] the hub/client secret
     * subkeys are gated the same way [ThalovantIdentity.asJson] gates the
     * identity secrets: `initial_identify`, `initial_identify_token`, the
     * secret `spec` fields (`apiKey`/`password`/`cryptoKey`), and URL
     * userinfo credentials are all omitted. Pass `includeSecrets = true` to
     * get the raw API resources back unchanged.
     */
    public fun asJson(includeSecrets: Boolean = false): JsonObject = buildJsonObject {
        put("identity", identity.asJson(includeSecrets))
        put("hub", if (includeSecrets) hub else redactBootstrapSecrets(hub))
        put("client", if (includeSecrets) client else redactBootstrapSecrets(client))
        endpoint?.let {
            put("selected_protocol", it.protocol.wireName)
            put("selected_endpoint", if (includeSecrets) it.endpoint else redactEndpointCredentials(it.endpoint))
        }
    }
}

/**
 * Thalovant control-plane API client. Discovers hubs, manages memory and
 * analytics, and provisions client identities.
 *
 * Security: this is intentionally a plain `class`, not a `data class`. It holds
 * the bearer [accessToken]; a generated `data class` `toString()` would print
 * it and leak it into logs and stack traces. Do not convert it to a
 * `data class`; a test asserts `toString()` does not contain the token.
 */
public class ThalovantControlPlane(
    apiUrl: String = DEFAULT_CONTROL_API_URL,
    @Volatile public var accessToken: String? = null,
    public val userAgent: String = DEFAULT_USER_AGENT,
    httpClient: OkHttpClient = defaultHttpClient,
) {
    /** Normalized API root; a trailing `/v1` is stripped and a trailing slash added. */
    public val apiUrl: String = normalizeControlApiUrl(apiUrl)

    /**
     * The id of the API token in [accessToken], when the sign-in that minted
     * it said (a device login, a native sign-in); what [revokeApiToken]
     * revokes by default. Every sign-in on this client sets it, to null when
     * its answer carried no id.
     */
    @Volatile
    public var tokenId: String? = null

    /**
     * Each device code's poll interval, lengthened by every `slow_down` and
     * kept that way (RFC 8628 §3.5). Bounded: a caller that begins sign-ins
     * and abandons them must not grow this for the life of the client.
     */
    /**
     * Keeps a sign-in's token and its id together against a revoke finishing at
     * the same time, on another thread: a sign-in writes both under it, and a
     * revoke checks and clears both under it -- never across its DELETE -- so
     * one can never land between the other's two writes.
     */
    private val tokenLock = Any()

    /** Whether the token this client signed in with was revoked and forgotten; see [revokeApiToken]. */
    @Volatile
    private var revokedOwn = false

    private val deviceIntervals = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 32
    }
    private val httpClient = httpClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val secretHeaders = listOf("Authorization", "Proxy-Authorization", "Cookie")
            if (!request.url.isHttps && request.url.host !in setOf("localhost", "127.0.0.1", "::1") &&
                (request.body != null || secretHeaders.any { request.header(it) != null })) {
                throw java.io.IOException("Credential-bearing Thalovant API requests require HTTPS.")
            }
            chain.proceed(request)
        }.build()

    /**
     * Exchanges credentials for an access token via `POST /v1/auth/token` and
     * stores it for subsequent requests. `otp_code` / `recovery_code` are sent
     * only when provided; accounts with MFA enabled receive HTTP 401
     * `mfa_required` without one.
     */
    public suspend fun login(
        email: String,
        password: String,
        scope: String? = null,
        otpCode: String? = null,
        recoveryCode: String? = null,
    ): JsonObject {
        val body = buildJsonObject {
            put("email", email)
            put("password", password)
            if (!scope.isNullOrEmpty()) put("scope", scope)
            if (otpCode != null) put("otp_code", otpCode)
            if (recoveryCode != null) put("recovery_code", recoveryCode)
        }
        val token = request("POST", "/v1/auth/token", body = body, auth = false)
        acceptToken(token)
        return token
    }

    /**
     * Signs in through the browser device flow and stores the API token.
     *
     * This is the sign-in path for accounts without a password (for example
     * Google sign-in). It requests a device authorization via
     * `POST /v1/auth/device/authorize`, tells the user to visit
     * `verification_uri` and enter the short `user_code` (pass
     * [DeviceLoginOptions.prompt] to present the authorization payload
     * yourself), optionally opens the browser at `verification_uri_complete`,
     * and polls `POST /v1/auth/device/token` until the request is approved,
     * denied ([ThalovantDeviceLoginDeniedException]), expired
     * ([ThalovantDeviceLoginExpiredException]), or
     * [DeviceLoginOptions.timeoutMillis] elapses
     * ([ThalovantTimeoutException]). A `slow_down` response grows the poll
     * interval by 5 seconds.
     *
     * On approval the returned `access_token` is a durable scoped API token
     * and is stored on [accessToken] exactly like [login]. The server may
     * normalize and expand the echoed `scopes`.
     */
    public suspend fun loginWithBrowser(options: DeviceLoginOptions = DeviceLoginOptions()): JsonObject {
        val grant = request("POST", "/v1/auth/device/authorize", body = deviceAuthorizeBody(options.scopes, options.clientName, options.clientId), auth = false)
        val authorization = DeviceAuthorization.fromJson(grant)

        val prompt = options.prompt
        if (prompt != null) {
            prompt(grant)
        } else {
            println("To sign in, visit ${authorization.verificationUri} and enter the code ${authorization.userCode}")
        }
        if (options.openBrowser) {
            authorization.verificationUriComplete?.let { openBrowserBestEffort(it) }
        }

        val token = pollDeviceToken(authorization.deviceCode, authorization.intervalMillis, options.timeoutMillis)
        acceptToken(token)
        return token
    }

    /**
     * Polls the device token endpoint until approval or a terminal state.
     * [sleep] and [clock] are injectable so tests can drive the loop without
     * real waiting; the defaults use coroutine [delay] and a monotonic clock.
     */
    internal suspend fun pollDeviceToken(
        deviceCode: String,
        intervalMillis: Long,
        timeoutMillis: Long,
        sleep: suspend (Long) -> Unit = { delay(it) },
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): JsonObject {
        val deadline = clock() + timeoutMillis
        synchronized(deviceIntervals) { deviceIntervals[deviceCode] = intervalMillis }
        while (true) {
            val waitMillis = try {
                // Each POST is bounded by what is left: one the API is slow to
                // answer must not carry the sign-in past its deadline.
                val left = deadline - clock()
                return (if (left > 0) withTimeoutOrNull(left) { deviceTokenOnce(deviceCode) } else null)
                    ?: run {
                        synchronized(deviceIntervals) { deviceIntervals.remove(deviceCode) }
                        throw ThalovantTimeoutException("Timed out waiting for the device sign-in to be approved.")
                    }
            } catch (pending: ThalovantDeviceLoginPendingException) {
                pending.intervalMillis
            }
            val remainingMillis = deadline - clock()
            if (remainingMillis <= 0) {
                synchronized(deviceIntervals) { deviceIntervals.remove(deviceCode) }
                throw ThalovantTimeoutException("Timed out waiting for the device sign-in to be approved.")
            }
            sleep(minOf(waitMillis, remainingMillis))
        }
    }

    /**
     * Starts a device sign-in (RFC 8628): a code for a person to approve in a
     * browser, for a caller that runs its own loop.
     *
     * A Home Assistant config flow, a TV, a CLI: anything that shows a code
     * and cannot wait inside one call for a person to act. Show the person
     * [DeviceAuthorization.verificationUri] and [DeviceAuthorization.userCode]
     * (or [DeviceAuthorization.verificationUriComplete], which carries the
     * code), then call [pollDeviceLogin] every
     * [DeviceAuthorization.intervalMillis]. [loginWithBrowser] is the same
     * flow with the loop run for you.
     *
     * [scopes] are what the token will carry; none, or an empty list, asks for
     * the API's default, `hubs:read` and `clients:write`. A Free plan can approve only
     * [HOME_ASSISTANT_SCOPES]. [clientName] is shown on the approval page, and
     * [clientId], when given, names the registered app signing in
     * ([HOME_ASSISTANT_CLIENT_ID]); it is left out when null.
     *
     * Throws [ThalovantApiException] when the API refuses, and when its answer
     * is incomplete or names a verification URL that is not http(s), has no
     * host, or carries credentials.
     */
    public suspend fun beginDeviceLogin(
        scopes: List<String>? = null,
        clientName: String? = null,
        clientId: String? = null,
    ): DeviceAuthorization {
        val grant = request("POST", "/v1/auth/device/authorize", body = deviceAuthorizeBody(scopes, clientName, clientId), auth = false)
        val authorization = DeviceAuthorization.fromJson(grant)
        synchronized(deviceIntervals) { deviceIntervals[authorization.deviceCode] = authorization.intervalMillis }
        return authorization
    }

    /** The shape of 0.8.0, kept so code compiled against it still links. */
    @Deprecated("Kept for binary compatibility; call with clientId as well.", level = DeprecationLevel.HIDDEN)
    public suspend fun beginDeviceLogin(scopes: List<String>? = null, clientName: String? = null): DeviceAuthorization =
        beginDeviceLogin(scopes, clientName, null)

    /**
     * Reads a device sign-in as the person approving it sees it, via
     * `GET /v1/auth/device/codes/{userCode}`: what it asks for, and which app
     * asks. Signed in as that person. [DeviceLoginRequest.clientVerified] is
     * true only for a registered app.
     *
     * A code that is unknown, expired or already answered is a
     * [ThalovantApiException] with status 404.
     */
    public suspend fun describeDeviceLogin(userCode: String): DeviceLoginRequest =
        DeviceLoginRequest.fromJson(request("GET", "/v1/auth/device/codes/${encodePathSegment(userCode)}"))

    /**
     * Asks once whether the device sign-in was approved.
     *
     * Returns the token and stores it on this client, as [accessToken] and
     * [tokenId]. Otherwise throws:
     *
     * - [ThalovantDeviceLoginPendingException] while nobody has decided --
     *   poll again after its [ThalovantDeviceLoginPendingException.intervalMillis],
     *   which a `slow_down` has already lengthened, for good;
     * - [ThalovantDeviceLoginExpiredException] when the code ran out, and
     *   [ThalovantDeviceLoginDeniedException] when the person said no -- start
     *   again with [beginDeviceLogin];
     * - [ThalovantApiException] for anything else the API answered, carrying
     *   its status, code and detail.
     *
     * Neither the device code nor the token ever appears in an error message.
     */
    public suspend fun pollDeviceLogin(authorization: DeviceAuthorization): ApiToken {
        synchronized(deviceIntervals) { deviceIntervals.putIfAbsent(authorization.deviceCode, authorization.intervalMillis) }
        return pollDeviceLogin(authorization.deviceCode)
    }

    /** [pollDeviceLogin] for a device code kept on its own, as `device_code` in [DeviceAuthorization.asJson]. */
    public suspend fun pollDeviceLogin(deviceCode: String): ApiToken {
        val token = deviceTokenOnce(deviceCode)
        val parsed = ApiToken.fromJson(token)
        acceptToken(token)
        return parsed
    }

    /**
     * Revokes an API token via `DELETE /v1/auth/api-tokens/{tokenId}`; by
     * default the one a device login on this client signed in with.
     *
     * A token may always revoke itself, whatever its scopes, so a Home
     * Assistant integration can undo its own sign-in when it is removed.
     * Revoking the token in use forgets it here too -- [accessToken] and
     * [tokenId] become null -- so a later call fails locally rather than with
     * a 401.
     *
     * Revoking the token in use is idempotent. A token already revoked, or
     * expired, cannot authenticate its own revoke, so the API answers 401;
     * the token is dead either way, so that counts as revoked and it is
     * forgotten, and revoking again then sends nothing until the next
     * sign-in. Revoking another token by id is not: the API's own answer --
     * a 404 for one it does not know -- is thrown as usual.
     */
    public suspend fun revokeApiToken(tokenId: String? = null) {
        val target = tokenId?.takeIf { it.isNotEmpty() } ?: this.tokenId
        if (target == null) {
            // Revoked and forgotten already: revoking again changes nothing.
            if (revokedOwn && accessToken == null) return
            throw ThalovantApiException("No API token id to revoke: pass tokenId, or sign in with a device login first.")
        }
        val own = target == this.tokenId
        try {
            request("DELETE", "/v1/auth/api-tokens/${encodePathSegment(target)}")
        } catch (error: ThalovantApiException) {
            // Revoking the token in use with a token that no longer works: it
            // is revoked already, which is what was asked.
            if (!(own && error.statusCode == 401)) throw error
        }
        if (own) {
            // Only the token that was revoked is forgotten: a sign-in that
            // completed while the DELETE was on its way installed another,
            // and that one is still good.
            synchronized(tokenLock) {
                if (this.tokenId == target) {
                    accessToken = null
                    this.tokenId = null
                    revokedOwn = true
                }
            }
        }
    }

    /** One `POST /v1/auth/device/token`: the token, or why there is none yet. */
    private suspend fun deviceTokenOnce(deviceCode: String): JsonObject {
        val token = try {
            request(
                "POST",
                "/v1/auth/device/token",
                body = buildJsonObject { put("device_code", deviceCode) },
                auth = false,
            )
        } catch (exception: ThalovantApiException) {
            val status = exception.statusCode ?: throw exception
            val error = deviceFlowError(exception)
            val interval = synchronized(deviceIntervals) {
                val current = deviceIntervals[deviceCode] ?: DEFAULT_DEVICE_POLL_INTERVAL_MILLIS
                when (error) {
                    // RFC 8628 §3.5: every slow_down adds five seconds, for good.
                    "slow_down" -> (current + 5_000).also { deviceIntervals[deviceCode] = it }
                    "access_denied", "expired_token" -> current.also { deviceIntervals.remove(deviceCode) }
                    else -> current
                }
            }
            when (error) {
                "authorization_pending", "slow_down" -> throw ThalovantDeviceLoginPendingException(
                    "The device sign-in has not been approved yet.",
                    intervalMillis = interval,
                    statusCode = status,
                    body = exception.body,
                )
                "access_denied" -> throw ThalovantDeviceLoginDeniedException(
                    "The device sign-in request was denied in the browser.",
                    statusCode = status,
                    body = exception.body,
                )
                "expired_token" -> throw ThalovantDeviceLoginExpiredException(
                    "The device sign-in code expired before it was approved. " +
                        "Call beginDeviceLogin() or loginWithBrowser() again to request a new code.",
                    statusCode = status,
                    body = exception.body,
                )
                else -> throw exception
            }
        }
        synchronized(deviceIntervals) { deviceIntervals.remove(deviceCode) }
        return token
    }

    /**
     * Keeps a sign-in's token, and its id, for later calls.
     *
     * Every sign-in goes through here, so [tokenId] always describes the
     * token in [accessToken]: the id the answer carried, or null. A device
     * token's id left behind by a later password sign-in would have had
     * [revokeApiToken] revoke the wrong token.
     */
    private fun acceptToken(token: JsonObject) {
        val accessToken = jsonText(token["access_token"])
            ?: throw ThalovantApiException("Thalovant API token response did not include access_token.")
        synchronized(tokenLock) {
            this.accessToken = accessToken
            tokenId = jsonText(token["token_id"])
            revokedOwn = false
        }
    }

    /**
     * Exchange an authorization code for a scoped access token and store it.
     *
     * The other half of [NativeSignIn]. The verifier is sent here and nowhere
     * else; it never entered the browser, which is what makes an intercepted
     * code useless to whoever intercepted it.
     *
     * A code presented twice revokes the token the first exchange minted
     * (RFC 9700), so retrying a failed exchange with the same code destroys
     * the token it is trying to obtain. Start again from [NativeSignIn.begin].
     */
    public suspend fun completeNativeSignIn(
        code: String,
        verifier: String,
        clientId: String,
        redirectUri: String,
    ): JsonObject {
        requireSecureTokenExchange()
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("client_id", clientId)
            put("redirect_uri", redirectUri)
        }
        val token = request("POST", "/v1/auth/native/token", body = body, auth = false)
        acceptToken(token)
        return token
    }

    /**
     * Refuse to put an authorization code and its PKCE verifier on the wire in
     * cleartext.
     *
     * [apiUrl] accepts an `http` scheme -- a self-hosted or local control
     * plane may legitimately be served that way -- and `request()` hands
     * whatever it is given to OkHttp without looking. Every other call that
     * would leak over http leaks a bearer token the caller already holds; this
     * one leaks the two secrets that are about to become one, and a code is
     * exchangeable by whoever sees it first.
     *
     * Loopback is allowed, because a request that never leaves the machine has
     * no cleartext to observe, and that is how the control plane is run while
     * somebody is working on it.
     */
    private fun requireSecureTokenExchange() {
        val uri = runCatching { java.net.URI(apiUrl) }.getOrNull()
            ?: throw ThalovantApiException("Thalovant API URL could not be read: $apiUrl")
        if (uri.scheme.equals("https", ignoreCase = true)) return
        if (NativeSignIn.isLoopback(uri.host)) return
        throw ThalovantApiException(
            "Refusing to send an authorization code and PKCE verifier in cleartext to " +
                "${uri.host ?: apiUrl}. Use https, or a loopback address while developing.",
        )
    }

    public suspend fun listHubs(limit: Int = 100, cursor: String? = null, ownerId: String? = null): JsonObject {
        val query = linkedMapOf("limit" to limit.toString())
        cursor?.takeIf { it.isNotEmpty() }?.let { query["cursor"] = it }
        ownerId?.takeIf { it.isNotEmpty() }?.let { query["owner_id"] = it }
        return request("GET", "/v1/hubs", query = query)
    }

    public suspend fun getHub(hubId: String): JsonObject = request("GET", "/v1/hubs/$hubId")

    public suspend fun listPublicHubs(limit: Int = 24, cursor: String? = null): JsonObject {
        val query = linkedMapOf("limit" to limit.toString())
        cursor?.takeIf { it.isNotEmpty() }?.let { query["cursor"] = it }
        return request("GET", "/v1/public/hubs", query = query, auth = false)
    }

    public suspend fun getPublicHub(hubRef: String): JsonObject =
        request("GET", "/v1/public/hubs/$hubRef", auth = false)

    public suspend fun getOperation(operationId: String): OperationResource {
        val body = request("GET", "/v1/operations/$operationId")
        return ThalovantJson.decodeFromJsonElement(OperationResource.serializer(), body)
    }

    public suspend fun listMemoryItems(options: MemoryListOptions = MemoryListOptions()): JsonObject {
        val query = LinkedHashMap<String, String>()
        options.scope?.let { query["scope"] = it.wireName }
        options.kind?.let { query["kind"] = it.wireName }
        options.ownerId?.takeIf { it.isNotBlank() }?.let { query["owner_id"] = it }
        options.hubId?.takeIf { it.isNotBlank() }?.let { query["hub_id"] = it }
        options.query?.takeIf { it.isNotBlank() }?.let { query["q"] = it }
        if (options.includeDeleted) query["include_deleted"] = "true"
        if (options.includeExpired) query["include_expired"] = "true"
        options.limit?.let { query["limit"] = it.toString() }
        options.offset?.let { query["offset"] = it.toString() }
        return request("GET", "/v1/memory", query = query)
    }

    public suspend fun getMemorySummary(ownerId: String? = null): JsonObject {
        val query = LinkedHashMap<String, String>()
        ownerId?.takeIf { it.isNotBlank() }?.let { query["owner_id"] = it }
        return request("GET", "/v1/memory/summary", query = query)
    }

    public suspend fun createMemoryItem(payload: MemoryCreatePayload): JsonObject =
        request("POST", "/v1/memory", body = memoryCreateBody(payload))

    public suspend fun getMemoryItem(memoryId: String): JsonObject = request("GET", "/v1/memory/$memoryId")

    public suspend fun updateMemoryItem(memoryId: String, payload: MemoryUpdatePayload): JsonObject =
        request("PATCH", "/v1/memory/$memoryId", body = memoryUpdateBody(payload))

    public suspend fun deleteMemoryItem(memoryId: String) {
        request("DELETE", "/v1/memory/$memoryId")
    }

    /**
     * Reads the workspace analytics overview from `GET /v1/analytics/overview`.
     */
    public suspend fun getAnalyticsOverview(options: AnalyticsOverviewOptions = AnalyticsOverviewOptions()): JsonObject {
        val query = LinkedHashMap<String, String>()
        options.range?.takeIf { it.isNotBlank() }?.let { query["range"] = it }
        options.bucket?.takeIf { it.isNotBlank() }?.let { query["bucket"] = it }
        options.hubId?.takeIf { it.isNotBlank() }?.let { query["hub_id"] = it }
        options.clientId?.takeIf { it.isNotBlank() }?.let { query["client_id"] = it }
        options.country?.takeIf { it.isNotBlank() }?.let { query["country"] = it }
        options.message?.takeIf { it.isNotBlank() }?.let { query["message"] = it }
        options.utterance?.takeIf { it.isNotBlank() }?.let { query["utterance"] = it }
        options.intent?.takeIf { it.isNotBlank() }?.let { query["intent"] = it }
        options.timeStart?.takeIf { it.isNotBlank() }?.let { query["time_start"] = it }
        options.timeEnd?.takeIf { it.isNotBlank() }?.let { query["time_end"] = it }
        options.weekday?.let { query["weekday"] = it.toString() }
        options.hour?.let { query["hour"] = it.toString() }
        return request("GET", "/v1/analytics/overview", query = query)
    }

    /**
     * Creates a hub via `POST /v1/hubs`.
     *
     * An `Idempotency-Key` header is always sent. When [idempotencyKey] is null,
     * this call generates a new key; a separate call generates another key.
     * To retry the same logical create after a timeout, retain and pass the same
     * explicit key and payload on every attempt. The SDK does not retry for you.
     *
     * Requires a paid plan and a token with the `hubs:write` scope; a free-plan
     * token fails with HTTP 402 and a token without the scope with HTTP 403.
     */
    public suspend fun createHub(payload: HubCreatePayload, idempotencyKey: String? = null): JsonObject =
        request(
            "POST",
            "/v1/hubs",
            body = hubCreateBody(payload),
            headers = mapOf("Idempotency-Key" to (idempotencyKey ?: UUID.randomUUID().toString())),
        )

    /**
     * Partially updates a hub via `PATCH /v1/hubs/{hubId}`.
     *
     * The route enforces optimistic locking, so [etag] is required rather than
     * optional: pass the `etag` from the hub resource you read and the SDK
     * sends it as `If-Match`. A stale — or missing — value fails the request
     * with HTTP 412 and changes nothing; re-read the hub with [getHub] and
     * retry with the fresh `etag`.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun updateHub(hubId: String, payload: HubUpdatePayload, etag: String): JsonObject =
        request(
            "PATCH",
            "/v1/hubs/$hubId",
            body = hubUpdateBody(payload),
            headers = mapOf("If-Match" to etag),
        )

    /**
     * The clients on this account, newest page first, via `GET /v1/clients`.
     *
     * The `clients:read` scope existed with nothing to spend it on: an app
     * could create a connection and delete one it had just made, but could
     * not *find* one. That gap is why a phone whose app was reinstalled had
     * no way to recognise the connection it had made before, and a plan
     * allowing one connection then refused it -- with the only way out being
     * to find the row in a dashboard and delete it by hand.
     *
     * [hubId] filters to one hub. Each row carries `id`, `name`, `hub_id` and
     * the `etag` that [deleteClient] requires, so a caller can go straight
     * from finding a connection to replacing it.
     *
     * Requires a token with `clients:read` or `clients:write`.
     */
    public suspend fun listClients(
        hubId: String? = null,
        limit: Int = 100,
        cursor: String? = null,
    ): JsonObject {
        val query = linkedMapOf("limit" to limit.toString())
        hubId?.takeIf { it.isNotEmpty() }?.let { query["hub_id"] = it }
        cursor?.takeIf { it.isNotEmpty() }?.let { query["cursor"] = it }
        return request("GET", "/v1/clients", query = query)
    }

    /**
     * Reads one client (a hub connection) via `GET /v1/clients/{clientId}`,
     * with the `etag` a change to it needs.
     *
     * Requires a token with `clients:read` or `clients:write`.
     */
    public suspend fun getClient(clientId: String): JsonObject =
        request("GET", "/v1/clients/${encodePathSegment(clientId)}")

    /**
     * Deletes one client via `DELETE /v1/clients/{clientId}`.
     *
     * A client is a connection, and connections are counted against a plan. An
     * app that mints a new client every time somebody pairs, without removing
     * the one it made last time, spends its own allowance: on a plan that
     * allows a single connection, the second attempt is refused because of the
     * first. That is what this exists to prevent.
     *
     * The route requires the client's current [etag], sent as `If-Match`; it
     * comes back from the call that created the client, and on every row of
     * [listClients] and [getClient]. Without one this reads it first. When
     * another writer changed the client in between -- HTTP 412 -- it reads the
     * etag once more and retries, once. A client that is already gone (HTTP
     * 404, on either request) counts as deleted, so deleting twice is safe.
     *
     * Requires a token with the `clients:write` scope.
     */
    public suspend fun deleteClient(clientId: String, etag: String? = null) {
        val path = "/v1/clients/${encodePathSegment(clientId)}"
        var current = etag
        for (attempt in 0..1) {
            try {
                val match = current ?: jsonText(getClient(clientId)["etag"])
                    ?: throw ThalovantApiException("Thalovant API client response did not include etag.")
                request("DELETE", path, headers = mapOf("If-Match" to match))
                return
            } catch (error: ThalovantApiException) {
                if (error.statusCode == 404) return
                if (error.statusCode != 412 || attempt == 1) throw error
                current = null
            }
        }
    }

    /**
     * Deletes a hub and its dependent clients and ACLs via
     * `DELETE /v1/hubs/{hubId}`.
     *
     * Like [updateHub] this route requires the hub's current [etag], sent as
     * `If-Match`; a stale or missing value fails with HTTP 412.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun deleteHub(hubId: String, etag: String) {
        request("DELETE", "/v1/hubs/$hubId", headers = mapOf("If-Match" to etag))
    }

    /**
     * Applies a hub release policy via `POST /v1/hubs/{hubId}/release` and
     * returns the updated hub.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun releaseHub(hubId: String, options: ReleaseOptions = ReleaseOptions()): JsonObject =
        request("POST", "/v1/hubs/$hubId/release", body = releaseBody(options))

    /**
     * Rates a public hub from 1 to 5 via `PUT /v1/hubs/{hubId}/rating` and
     * returns the updated hub.
     *
     * Only public hubs can be rated, and owners cannot rate their own hub.
     * Requires a token with the `hubs:write` scope; unlike the provisioning
     * routes this one is **not** paid-gated.
     */
    public suspend fun setHubRating(hubId: String, rating: Int): JsonObject {
        require(rating in 1..5) { "rating must be between 1 and 5." }
        return request("PUT", "/v1/hubs/$hubId/rating", body = buildJsonObject { put("rating", rating) })
    }

    /**
     * Removes the caller's rating from a public hub via
     * `DELETE /v1/hubs/{hubId}/rating` and returns the updated hub.
     *
     * Requires a token with the `hubs:write` scope; no paid plan is needed.
     */
    public suspend fun clearHubRating(hubId: String): JsonObject =
        request("DELETE", "/v1/hubs/$hubId/rating")

    /**
     * Reads the live skill and intent inventory a hub runtime exposes via
     * `GET /v1/hubs/{hubId}/runtime-capabilities`.
     *
     * Requires a token with the `hubs:inspect` scope; no paid plan is needed.
     *
     * This is the one discovery read that can fail with HTTP 409 rather than
     * answering with empty data. The 409 is conditional: with no connected
     * client the API first falls back to the hub's runtime-group snapshot and
     * answers HTTP 200 with `source` reporting that the inventory is not live,
     * and only raises 409 when there is no snapshot to fall back on either.
     * Branch on `source` rather than assuming a disconnected hub means 409.
     * The route is also rate-limited and may answer HTTP 429 with a
     * `Retry-After` header, which the SDK surfaces without retrying.
     */
    public suspend fun getHubRuntimeCapabilities(hubId: String): JsonObject =
        request("GET", "/v1/hubs/$hubId/runtime-capabilities")

    /**
     * Lists runtime groups visible to the caller via `GET /v1/runtime-groups`.
     *
     * The response is a single unpaginated `data` list; this route takes no
     * limit, offset, or cursor. [ownerId] reads another tenant's groups for an
     * admin token and is rejected with HTTP 403 for anyone else — it is not
     * silently ignored the way the marketplace catalog's `owner_id` is.
     *
     * Requires a token with the `hubs:read` scope.
     */
    public suspend fun listRuntimeGroups(ownerId: String? = null): JsonObject {
        val query = LinkedHashMap<String, String>()
        ownerId?.takeIf { it.isNotBlank() }?.let { query["owner_id"] = it }
        return request("GET", "/v1/runtime-groups", query = query)
    }

    /**
     * Fetches one runtime group via `GET /v1/runtime-groups/{runtimeGroupId}`.
     *
     * Requires a token with the `hubs:read` scope.
     */
    public suspend fun getRuntimeGroup(runtimeGroupId: String): JsonObject =
        request("GET", "/v1/runtime-groups/$runtimeGroupId")

    /**
     * Creates a runtime group via `POST /v1/runtime-groups`.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun createRuntimeGroup(payload: RuntimeGroupCreatePayload): JsonObject =
        request("POST", "/v1/runtime-groups", body = runtimeGroupCreateBody(payload))

    /**
     * Updates a runtime group via `PATCH /v1/runtime-groups/{runtimeGroupId}`.
     *
     * Unlike the hub routes this one does not use `If-Match`: no runtime-group
     * route reads an `If-Match` or `Idempotency-Key` header, and the resources
     * carry no `etag` to round-trip.
     *
     * Note that the API does not echo `spec` back on runtime-group resources —
     * a patched `spec` reads as `{}` in the response even when it applied.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun updateRuntimeGroup(
        runtimeGroupId: String,
        payload: RuntimeGroupUpdatePayload,
    ): JsonObject = request(
        "PATCH",
        "/v1/runtime-groups/$runtimeGroupId",
        body = runtimeGroupUpdateBody(payload),
    )

    /**
     * Reads a runtime group's runtime configuration and personas via
     * `GET /v1/runtime-groups/{runtimeGroupId}/config`.
     *
     * Requires a token with the `hubs:read` scope.
     */
    public suspend fun getRuntimeGroupConfig(runtimeGroupId: String): JsonObject =
        request("GET", "/v1/runtime-groups/${encodePathSegment(runtimeGroupId)}/config")

    /** Deep merge with a revision precondition. Only 412 retries, at most three attempts.
     * Older servers fail before a write. Requires hubs:read and paid hubs:write. */
    public suspend fun updateRuntimeGroupConfig(runtimeGroupId: String, config: JsonObject, personas: JsonObject? = null): JsonObject {
        val delta = ThalovantJson.parseToJsonElement(config.toString()) as JsonObject
        val stablePersonas = personas?.let { ThalovantJson.parseToJsonElement(it.toString()) as JsonObject }
        repeat(3) { attempt ->
            val snapshot = getRuntimeGroupConfig(runtimeGroupId)
            val revision = (snapshot["revision"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val base = snapshot["config"] as? JsonObject
            if (revision == null || !Regex("^[0-9a-f]{64}$").matches(revision) || base == null)
                throw ThalovantApiException("Safe configuration merge requires a valid config and revision from the API.")
            try {
                return request("PUT", "/v1/runtime-groups/${encodePathSegment(runtimeGroupId)}/config", body = buildJsonObject {
                    put("config", mergeRuntimeConfig(base, delta)); put("expected_revision", revision)
                    stablePersonas?.let { put("personas", it) }
                })
            } catch (error: ThalovantApiException) { if (error.statusCode != 412 || attempt == 2) throw error }
        }
        error("Last attempt always returns")
    }

    /** Explicit unconditional configuration replacement. Personas replace only when supplied. */
    public suspend fun replaceRuntimeGroupConfig(
        runtimeGroupId: String,
        config: JsonObject,
        personas: JsonObject? = null,
    ): JsonObject = request(
        "PATCH",
        "/v1/runtime-groups/${encodePathSegment(runtimeGroupId)}/config",
        body = buildJsonObject {
            put("config", config)
            personas?.let { put("personas", it) }
        },
    )

    /**
     * Applies a runtime image policy via
     * `POST /v1/runtime-groups/{runtimeGroupId}/release` and returns the
     * updated runtime group. Options behave like [releaseHub].
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun releaseRuntimeGroup(
        runtimeGroupId: String,
        options: ReleaseOptions = ReleaseOptions(),
    ): JsonObject = request(
        "POST",
        "/v1/runtime-groups/$runtimeGroupId/release",
        body = releaseBody(options),
    )

    /**
     * Deletes a runtime group via `DELETE /v1/runtime-groups/{runtimeGroupId}`.
     *
     * The API answers HTTP 409 for the workspace default group and for a group
     * that still has hubs attached.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun deleteRuntimeGroup(runtimeGroupId: String) {
        request("DELETE", "/v1/runtime-groups/$runtimeGroupId")
    }

    /**
     * Installs (or re-installs) a skill in a runtime group via
     * `POST /v1/runtime-groups/{runtimeGroupId}/skills`.
     *
     * The default [InstallSkillOptions.sourceType] of `catalog` installs a
     * marketplace skill and fails with HTTP 404 when the catalog has no
     * matching entry; `git` installs need a [InstallSkillOptions.sourceRef]
     * repository URL and fail with HTTP 422 without one. Installing a skill
     * that is already present updates the existing entry.
     *
     * Requires a paid plan and a token with the `hubs:write` scope. A skill
     * whose `access_tier` is `paid` additionally needs marketplace access on
     * the tenant plan, which is a second, separate HTTP 402.
     */
    public suspend fun installRuntimeGroupSkill(
        runtimeGroupId: String,
        skillId: String,
        options: InstallSkillOptions = InstallSkillOptions(),
    ): JsonObject = request(
        "POST",
        "/v1/runtime-groups/$runtimeGroupId/skills",
        body = installSkillBody(skillId, options),
    )

    /**
     * Removes a skill from a runtime group via
     * `DELETE /v1/runtime-groups/{runtimeGroupId}/skills/{skillId}`.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun uninstallRuntimeGroupSkill(runtimeGroupId: String, skillId: String) {
        request("DELETE", "/v1/runtime-groups/$runtimeGroupId/skills/${encodePathSegment(skillId)}")
    }

    /**
     * Lists the marketplace skill catalog via `GET /v1/marketplace/skills`.
     *
     * Requires a token with the `hubs:read` scope. Unlike the provisioning
     * routes this catalog is **not** paid-gated, so free-plan callers can
     * browse the marketplace before upgrading — only the install itself needs
     * a paid plan.
     */
    public suspend fun listMarketplaceSkills(
        options: MarketplaceSkillListOptions = MarketplaceSkillListOptions(),
    ): JsonObject {
        val query = LinkedHashMap<String, String>()
        options.ownerId?.takeIf { it.isNotBlank() }?.let { query["owner_id"] = it }
        if (options.includeInactive) query["include_inactive"] = "true"
        if (options.forceRefresh) query["force_refresh"] = "true"
        return request("GET", "/v1/marketplace/skills", query = query)
    }

    /**
     * Lists the marketplace catalog resolved against one runtime group via
     * `GET /v1/runtime-groups/{runtimeGroupId}/marketplace`.
     *
     * This is the discovery view to use before installing: each catalog entry
     * carries the group's own state, whether it was observed running, and the
     * access verdict for the tenant plan. [refreshInventory] forces a live read
     * from the runtime operator instead of the cached snapshot.
     *
     * Like [listRuntimeGroupInventory] this never fails with HTTP 409 when
     * nothing is reporting; the envelope's `source` says how fresh the
     * observation is — `ovos-runtime-operator`, `runtime-group-cache`,
     * `runtime-group-cache-empty`, or, only when [refreshInventory] is set and
     * the operator has yet to answer, `ovos-runtime-operator-pending`.
     *
     * Requires a token with the `hubs:inspect` scope; no paid plan is needed.
     */
    public suspend fun listRuntimeGroupMarketplace(
        runtimeGroupId: String,
        refreshInventory: Boolean = false,
    ): JsonObject {
        val query = LinkedHashMap<String, String>()
        if (refreshInventory) query["refresh_inventory"] = "true"
        return request("GET", "/v1/runtime-groups/$runtimeGroupId/marketplace", query = query)
    }

    /**
     * Lists the skills a runtime group is observed running via
     * `GET /v1/runtime-groups/{runtimeGroupId}/inventory`.
     *
     * Where [listRuntimeGroupMarketplace] answers "what could be installed
     * here", this answers "what is loaded right now". [refresh] forces a live
     * operator read. Unlike [getHubRuntimeCapabilities] this route does not
     * answer HTTP 409 when nothing is reporting — it returns an empty `data`
     * list with a pending `source` instead.
     *
     * Requires a token with the `hubs:inspect` scope; no paid plan is needed.
     */
    public suspend fun listRuntimeGroupInventory(
        runtimeGroupId: String,
        refresh: Boolean = false,
    ): JsonObject {
        val query = LinkedHashMap<String, String>()
        if (refresh) query["refresh"] = "true"
        return request("GET", "/v1/runtime-groups/$runtimeGroupId/inventory", query = query)
    }

    /** Creates a client via `POST /v1/clients` with an `Idempotency-Key` header. */
    public suspend fun createClient(payload: JsonObject, idempotencyKey: String? = null): JsonObject =
        request(
            "POST",
            "/v1/clients",
            body = payload,
            headers = mapOf("Idempotency-Key" to (idempotencyKey ?: UUID.randomUUID().toString())),
        )

    public suspend fun createClientIdentity(hubId: String, options: CreateClientIdentityOptions): BootstrapIdentityResult =
        createClientIdentity(getHub(hubId), options)

    /**
     * Provisions a client on [hub] and derives a runtime [ThalovantIdentity].
     * Secrets are generated locally; when the API returns `initial_identify`, that
     * payload wins and is merged with the hub protocol/endpoint settings.
     *
     * With [CreateClientIdentityOptions.connectionType] set, the kind is sent
     * as `spec.connection_type` and the API must answer with the same kind. A
     * 422 about the field, or a connection whose kind came back different,
     * throws [ThalovantUnsupportedConnectionTypeException] -- after deleting
     * that connection, which would otherwise be an ordinary satellite nobody
     * asked for. A plan that does not allow it throws [ThalovantPlanException];
     * a hub that already holds the one link of its kind,
     * [ThalovantAlreadyLinkedException] naming it; a token that cannot do this,
     * [ThalovantAuthException].
     *
     * The new connection is admitted by its hub about ninety seconds later;
     * [BootstrapIdentityResult.operation] tracks that, and [waitForAdmission]
     * waits for it.
     */
    public suspend fun createClientIdentity(hub: JsonObject, options: CreateClientIdentityOptions): BootstrapIdentityResult {
        val hubId = hub.optionalString("id")
            ?: throw ThalovantApiException("Hub resource is missing id.")
        val siteId = cleanSiteId(options.siteId ?: options.name)
        val apiKey = newSecret()
        val password = newSecret()
        val cryptoKey = newSecret()
        val spec = buildJsonObject {
            options.spec?.forEach { (key, value) -> put(key, value) }
            put("version", optionalString(options.spec?.get("version")) ?: "1")
            options.connectionType?.let { put("connection_type", it) }
            put("apiKey", apiKey)
            put("password", password)
            put("cryptoKey", cryptoKey)
            put("siteId", siteId)
        }
        val payload = buildJsonObject {
            put("hub_id", hubId)
            put("name", options.name)
            put("spec", spec)
            put("active", options.active)
            options.ownerId?.let { put("owner_id", it) }
        }
        val kind = options.connectionType
        val client = try {
            createClient(payload, options.idempotencyKey)
        } catch (error: ThalovantApiException) {
            if (kind != null && refusesConnectionType(error)) {
                throw ThalovantUnsupportedConnectionTypeException(
                    "The Thalovant API cannot create a '$kind' connection yet.",
                    statusCode = error.statusCode,
                    body = error.body,
                )
            }
            throw error
        }
        if (kind != null) requireConnectionType(client, kind)
        val protocols = HubProtocolSettings.from(hub)
        val endpoints = HubDataPlaneEndpoints.fromHub(hub)
        val endpoint = selectDataPlaneEndpoint(
            endpoints,
            protocols,
            options.preferredProtocols ?: DEFAULT_PROTOCOL_PREFERENCE,
        )
        val initialIdentify = client["initial_identify"].asObjectOrNull()
        val identityInput = buildJsonObject {
            if (initialIdentify != null) {
                initialIdentify.forEach { (key, value) -> put(key, value) }
            } else {
                put("access_key", apiKey)
                put("password", password)
                put("crypto_key", cryptoKey)
                put("site_id", siteId)
                put("default_master", defaultMaster(hub, endpoints, endpoint))
                put("default_port", 443)
            }
            put("data_plane_endpoints", endpoints.asJson())
            put("protocols", protocols.asJson())
        }
        return BootstrapIdentityResult(ThalovantIdentity(identityInput), hub, client, endpoint)
    }

    /** Deletes and refuses a connection the API did not make of the kind asked for. */
    private suspend fun requireConnectionType(client: JsonObject, kind: String) {
        val echoed = jsonText((client["spec"] as? JsonObject)?.get("connection_type"))
        if (echoed == kind) return
        val clientId = jsonText(client["id"])
        var note = ""
        if (clientId != null) {
            try {
                deleteClient(clientId, jsonText(client["etag"]))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: ThalovantApiException) {
                note = " Deleting the connection it made instead ($clientId) failed; remove it in the dashboard."
            } catch (_: IOException) {
                note = " Deleting the connection it made instead ($clientId) failed; remove it in the dashboard."
            }
        }
        throw ThalovantUnsupportedConnectionTypeException(
            "The Thalovant API did not make a '$kind' connection (it answered ${echoed?.let { "'$it'" } ?: "no type"}).$note",
        )
    }

    /**
     * Waits until the hub has admitted a new connection, about ninety seconds
     * after [createClientIdentity] made it.
     *
     * Follows the operation the create answered with, reading
     * `GET /v1/operations/{id}` every [pollIntervalMs]:
     *
     * - `ready` returns: the hub knows the connection, and connecting works;
     * - no operation at all, or one the API no longer tracks (HTTP 404),
     *   returns at once -- there is nothing to wait on;
     * - `failed` and `timed_out` throw [ThalovantAdmissionFailedException] with
     *   the operation's own code; the connection has to be created again;
     * - an answer in the 5xx range is ridden out, and so is a 429, waiting
     *   what it names -- [ThalovantApiException.retryAfterSeconds], read from
     *   its body, else its `Retry-After`, else its `RateLimit-Reset` -- or the
     *   poll interval, when that is longer; a timeout at once when that is
     *   more than is left;
     * - a 401 or 403 is thrown as it came -- a token revoked while waiting is
     *   [ThalovantAuthException]: sign in again, not a failed admission;
     * - any other refusal of the wait is [ThalovantAdmissionFailedException]
     *   keeping the API's `statusCode`, `code`, `detail` and `problem`;
     * - an API out of reach throws the `IOException` it is, never a failed
     *   admission;
     * - [timeoutMs] passing first throws [ThalovantAdmissionTimeoutException],
     *   which is a [ThalovantConnectionException] and a [ThalovantTimeout] at
     *   once: the connection may still be admitted later. No read runs past
     *   [timeoutMs].
     *
     * A connection a hub has not admitted yet is refused by it, the way a
     * wrong credential is; this is how to tell the two apart. An operation
     * whose `links.self` names another origin than [apiUrl] -- scheme, host
     * and port, the scheme's default port spelled out -- is refused before
     * anything is fetched, with [ThalovantApiException]: the token goes to the
     * API and nowhere else.
     */
    public suspend fun waitForAdmission(
        result: BootstrapIdentityResult,
        timeoutMs: Long = DEFAULT_ADMISSION_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_OPERATION_POLL_INTERVAL_MS,
    ) {
        val operation = result.client["operation"] as? JsonObject
        awaitAdmission(
            jsonText(operation?.get("id")),
            jsonText((operation?.get("links") as? JsonObject)?.get("self")),
            timeoutMs,
            pollIntervalMs,
        )
    }

    /** [waitForAdmission] for an operation held on its own; null returns at once. */
    public suspend fun waitForAdmission(
        operation: OperationResource?,
        timeoutMs: Long = DEFAULT_ADMISSION_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_OPERATION_POLL_INTERVAL_MS,
    ) {
        awaitAdmission(operation?.id, operation?.links?.get("self"), timeoutMs, pollIntervalMs)
    }

    private suspend fun awaitAdmission(id: String?, link: String?, timeoutMs: Long, pollIntervalMs: Long) {
        require(timeoutMs > 0 && pollIntervalMs > 0) { "timeoutMs and pollIntervalMs must be positive." }
        if (id.isNullOrEmpty() && link.isNullOrEmpty()) return
        if (link != null && "://" in link && origin(link).let { it == null || it != origin(apiUrl) }) {
            // The token goes to the API's own origin -- scheme, host and port
            // -- and nowhere else.
            throw ThalovantApiException("The admission operation points outside the Thalovant API.")
        }
        val operationId = id?.takeIf { it.isNotEmpty() } ?: operationIdFromLink(link.orEmpty())
            ?: throw ThalovantApiException("An operation needs an id to wait on.")
        val started = System.nanoTime()
        fun remaining(): Long = timeoutMs - (System.nanoTime() - started) / 1_000_000
        fun timedOut(why: String = "") = ThalovantAdmissionTimeoutException(
            "The hub did not admit the connection within ${timeoutMs / 1_000.0}s$why; it may still admit it later.",
        )
        while (true) {
            var wait = pollIntervalMs
            val current = try {
                // Every read is bounded by what is left of the wait: a read the
                // API is slow to answer must not carry the wait past it.
                withTimeoutOrNull(remaining().coerceAtLeast(1)) {
                    request("GET", "/v1/operations/${encodePathSegment(operationId)}")
                } ?: throw timedOut()
            } catch (error: ThalovantApiException) {
                val status = error.statusCode
                when {
                    status == 404 -> return
                    status != null && status >= 500 -> null
                    // A rate limit is ridden out too, for as long as it says --
                    // unless that is longer than is left: waiting it out would
                    // only end in the same timeout, later.
                    status == 429 -> {
                        wait = maxOf(pollIntervalMs, error.retryAfterSeconds?.let { (it * 1_000).roundToLong() } ?: 0)
                        if (wait > remaining()) throw timedOut(" (the API asked to slow down)")
                        null
                    }
                    // The token, not the connection: signing in again fixes it.
                    status == 401 || status == 403 -> throw error
                    else -> throw ThalovantAdmissionFailedException(
                        "The hub could not admit the connection: ${error.message}",
                        errorCode = if (status == null) error.errorCode else null,
                        cause = error,
                        statusCode = status,
                        code = error.errorCode,
                        detail = error.detail,
                        problem = error.problem,
                    )
                }
            }
            if (current != null) {
                when (jsonText(current["status"])) {
                    "ready" -> return
                    "failed", "timed_out" -> {
                        val code = jsonText(current["error_code"])
                        throw ThalovantAdmissionFailedException(
                            "The hub could not admit the connection: operation $operationId ended with status " +
                                "${jsonText(current["status"])}: ${jsonText(current["error_message"]) ?: code ?: "no detail"}",
                            errorCode = code,
                        )
                    }
                }
            }
            val left = remaining()
            if (left <= 0) throw timedOut()
            sleepAtLeast(minOf(wait, left))
        }
    }

    /** Resolves the runtime endpoint for [protocol] (or the selected one), or throws. */
    public fun requireRuntimeProtocol(
        result: BootstrapIdentityResult,
        protocol: HubProtocol? = null,
    ): SelectedHubEndpoint {
        val effective = protocol ?: result.selectedProtocol ?: DEFAULT_PROTOCOL_PREFERENCE.first()
        if (effective == HubProtocol.MQTT && result.identity.mqtt == null) {
            throw ThalovantUnsupportedProtocolException(
                "MQTT is enabled, but the API did not return client-scoped MQTT broker credentials.",
            )
        }
        val endpoint = result.identity.endpointFor(effective)
            ?: throw ThalovantUnsupportedProtocolException(
                "This hub does not expose a ${effective.wireName.uppercase()} endpoint for the SDK runtime.",
            )
        return SelectedHubEndpoint(effective, endpoint)
    }

    /** Skills on the shared runtime behind this hub; requires hubs:inspect. */
    public suspend fun listHubSkills(hubId: String): JsonObject =
        request("GET", hubSkillsPath(hubId))

    /** Newest-first shared-runtime events and operations. Limit must be 1–200. */
    public suspend fun listHubSkillHistory(hubId: String, limit: Int = 50): JsonObject {
        require(limit in 1..200) { "limit must be from 1 to 200" }
        return request("GET", "${hubSkillsPath(hubId)}/history", query = mapOf("limit" to limit.toString()))
    }

    /** Install on the shared runtime, affecting all served hubs. Requires hubs:write and a paid plan. */
    public suspend fun installHubSkill(hubId: String, skill: String, version: String = "latest",
        options: HubSkillWaitOptions = HubSkillWaitOptions()): JsonObject =
        changeHubSkill("POST", hubSkillsPath(hubId), buildJsonObject {
            put("skill", JsonPrimitive(skill)); put("version", JsonPrimitive(version))
        }, options)

    /** Move a shared-runtime skill to an exact version or latest. */
    public suspend fun updateHubSkill(hubId: String, skill: String, version: String,
        options: HubSkillWaitOptions = HubSkillWaitOptions()): JsonObject =
        changeHubSkill("PATCH", "${hubSkillsPath(hubId)}/${encodePathSegment(skill)}",
            buildJsonObject { put("version", JsonPrimitive(version)) }, options)

    /** Remove a shared-runtime attachment, affecting all served hubs. */
    public suspend fun removeHubSkill(hubId: String, skill: String,
        options: HubSkillWaitOptions = HubSkillWaitOptions()): JsonObject =
        changeHubSkill("DELETE", "${hubSkillsPath(hubId)}/${encodePathSegment(skill)}", null, options)

    private fun hubSkillsPath(hubId: String): String = "/v1/hubs/${encodePathSegment(hubId)}/skills"

    private suspend fun changeHubSkill(method: String, path: String, body: JsonObject?, options: HubSkillWaitOptions): JsonObject {
        options.validate()
        val accepted = request(method, path, body)
        if (!options.wait) return accepted
        return try { waitForHubSkillOperation(accepted, options) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: ThalovantException) {
                throw HubSkillOperationException(accepted, error is ThalovantTimeoutException,
                    error.message ?: "Accepted skill operation could not be observed.")
            }
    }

    /** Resume polling without repeating the write. Retain the complete accepted response, including state, before waiting when cancellation is possible. */
    public suspend fun waitForHubSkillOperation(accepted: JsonObject, options: HubSkillWaitOptions = HubSkillWaitOptions()): JsonObject {
        options.validate()
        val id = (accepted["operation_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: throw ThalovantApiException("Missing accepted operation_id.")
        val state = (accepted["state"] as? JsonPrimitive)?.content
        val converged = if (state == "removing" || state == "removed") "removed" else "installed"
        val start = System.nanoTime()
        fun remaining(): Long = options.timeoutMs - (System.nanoTime() - start) / 1_000_000
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (remaining() <= 0) throw ThalovantTimeoutException("Timed out waiting for accepted operation $id")
            val operation = try { request("GET", "/v1/operations/${encodePathSegment(id)}") }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { throw ThalovantApiException("Could not read accepted operation $id; inspect the operation by ID, or resume with the complete accepted response.") }
            when ((operation["status"] as? JsonPrimitive)?.content) {
                "ready" -> return JsonObject(accepted + mapOf("state" to JsonPrimitive(converged), "operation" to operation))
                "failed", "timed_out" -> throw ThalovantApiException("Accepted operation $id failed; inspect getOperation for details.")
            }
            val left = remaining()
            if (left <= 0) throw ThalovantTimeoutException("Timed out waiting for accepted operation $id")
            delay(minOf(options.pollIntervalMs, left))
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val urlBuilder = try { (apiUrl + path.trimStart('/')).toHttpUrl().newBuilder() }
            catch (_: IllegalArgumentException) { throw ThalovantApiException("Invalid Thalovant API URL.") }
        for ((key, value) in query) {
            urlBuilder.addQueryParameter(key, value)
        }
        val url = urlBuilder.build()
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*@").containsMatchIn(apiUrl)) {
            throw ThalovantApiException("Thalovant API URLs must not contain userinfo credentials.")
        }
        val carriesCredentials = auth || body != null || headers.keys.any { key ->
            listOf("Authorization", "Proxy-Authorization", "Cookie").any { key.equals(it, ignoreCase = true) }
        } || httpClient.authenticator !== okhttp3.Authenticator.NONE || httpClient.proxyAuthenticator !== okhttp3.Authenticator.NONE ||
            httpClient.cookieJar.loadForRequest(url).isNotEmpty()
        val explicitLoopback = Regex("^http://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]+)?(?:/|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(apiUrl)
        if (carriesCredentials && !url.isHttps && !explicitLoopback) {
            throw ThalovantApiException("Credential-bearing Thalovant API requests require HTTPS except explicit loopback development endpoints.")
        }
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        val token = if (auth) {
            accessToken ?: throw ThalovantApiException("Missing Thalovant API access token.")
        } else {
            null
        }
        token?.let { builder.header("Authorization", "Bearer $it") }
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        builder.method(method, requestBody)
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (!response.isSuccessful) throw apiError(response, sentSecrets(token, body))
                            val text = response.body?.string().orEmpty()
                            // A 2xx this SDK cannot use is a local failure,
                            // with no status: the API did not refuse. A body
                            // that is not JSON reads the same as one that is
                            // JSON and not an object -- device-login-vectors
                            // records a 2xx with no token that way too.
                            if (text.isBlank()) EMPTY_JSON_OBJECT
                            else runCatching { ThalovantJson.parseToJsonElement(text) }.getOrNull()?.asObjectOrNull()
                                ?: throw ThalovantApiException("Thalovant API returned an unexpected response shape.")
                        }
                    })
                }
            })
        }
    }

    private companion object {
        val defaultHttpClient: OkHttpClient = OkHttpClient()
    }
}

/** Upper bound on the server detail echoed into a [ThalovantApiException] message. */
private const val API_ERROR_DETAIL_MAX_LENGTH: Int = 200

private val API_ERROR_WHITESPACE: Regex = Regex("\\s+")

/** What a value this call sent is replaced with when an error message would repeat it. */
private const val API_ERROR_REDACTED: String = "[redacted]"

/**
 * The shortest value treated as a credential. An email or an enum such as
 * `stable` is left alone; a password, a key, or a generated secret is not.
 */
private const val API_ERROR_SECRET_MIN_LENGTH: Int = 8

/**
 * What an error message must never repeat: the bearer token and every string
 * value in this call's JSON body, at least [API_ERROR_SECRET_MIN_LENGTH]
 * characters long. Longest first, so a value that contains another is replaced
 * whole rather than leaving its remainder behind.
 */
private fun sentSecrets(token: String?, body: JsonObject?): List<String> {
    val sent = linkedSetOf<String>()
    token?.let { sent += it }
    fun collect(element: JsonElement) {
        when (element) {
            is JsonObject -> element.values.forEach(::collect)
            is JsonArray -> element.forEach(::collect)
            is JsonPrimitive -> if (element.isString) sent += element.content
        }
    }
    body?.let(::collect)
    return sent.filter { it.length >= API_ERROR_SECRET_MIN_LENGTH }.sortedByDescending { it.length }
}

/**
 * Builds the human-facing message for a failed API request: the status and a
 * bounded, known JSON error field. The full response body stays on
 * [ThalovantApiException.body] for programmatic inspection (the device flow
 * parses its `error` code from it), and what the API said in full is on the
 * exception itself: its `problem`, `errorCode` and whole `detail`.
 *
 * The field is the body's own `detail` sentence when it is a string; otherwise
 * the `message` or `code` of a `detail` object, the `msg` of each validation
 * entry, or the body's `message`, `error_description`, `error`, `title` or
 * `code`. A body can repeat what the request sent -- a validation error echoes
 * its input, which for `POST /v1/auth/token` or `POST /v1/clients` is a
 * credential -- so a raw body or a validation input never becomes the message,
 * and every value in [secrets] is replaced with `[redacted]` before the field
 * is collapsed and bounded, so a cut cannot leave part of one behind.
 */
private fun apiErrorMessage(statusCode: Int, body: String, secrets: List<String> = emptyList()): String {
    val envelope = runCatching { ThalovantJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    fun text(value: JsonElement?): String? = (value as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    val rawDetail = envelope?.get("detail")
    val summary = problemText(rawDetail)
        ?: (rawDetail as? JsonObject)?.let { text(it["message"]) ?: text(it["code"]) }
        ?: (rawDetail as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { row -> text(row["msg"]) } }
            ?.takeIf { it.isNotEmpty() }?.joinToString("; ")
        ?: listOf("message", "error_description", "error", "title", "code")
            .firstNotNullOfOrNull { envelope?.get(it)?.let(::text) }
        ?: ""
    val redacted = secrets.fold(summary) { text, secret -> text.replace(secret, API_ERROR_REDACTED) }
    val detail = redacted.replace(API_ERROR_WHITESPACE, " ").trim().take(API_ERROR_DETAIL_MAX_LENGTH)
    return if (detail.isEmpty()) {
        "Thalovant API request failed with HTTP $statusCode."
    } else {
        "Thalovant API request failed with HTTP $statusCode: $detail"
    }
}

/**
 * The error for a response the API answered with a failure status. Every
 * non-2xx control-plane response becomes one here.
 *
 * The body is read as UTF-8 whatever the Content-Type claims: it is JSON, which
 * is UTF-8, and the API declares no charset on `application/problem+json`, so a
 * charset some proxy declares must not re-spell the API's sentence. A byte
 * order mark is dropped, as OkHttp drops it. The exception reads its `problem`,
 * `errorCode` and `detail` out of that text once, exactly as sent; only the
 * message, the bounded line [apiErrorMessage] builds, has [secrets] redacted.
 */
private fun apiError(response: Response, secrets: List<String>): ThalovantApiException {
    val text = response.body?.bytes()?.decodeToString()?.removePrefix("\uFEFF").orEmpty()
    return apiException(apiErrorMessage(response.code, text, secrets), statusCode = response.code, body = text).also {
        if (it.retryAfterSeconds == null) it.retryAfterSeconds = retryAfterHeader(response)
    }
}

/**
 * `Retry-After` in whole seconds, else `RateLimit-Reset`. The API's own rate
 * limiter answers a 429 in plain text with only `RateLimit-Reset` to say how
 * long. An HTTP-date `Retry-After` is not read.
 */
private fun retryAfterHeader(response: Response): Double? {
    for (name in listOf("Retry-After", "RateLimit-Reset")) {
        val value = response.header(name)?.trim() ?: continue
        if (value.isNotEmpty() && value.all { it in '0'..'9' }) return value.toBigInteger().toDouble()
    }
    return null
}

/**
 * A 422 whose problem is about `connection_type`: the API does not know the kind asked for.
 *
 * Read from the problem's `detail` and code, and from each validation error's
 * `loc` and `msg` (under `errors`, or under `detail` when that is a list) --
 * never from the rest of the body. A validation error echoes what was sent as
 * `input`, and the request always carries `spec.connection_type`, so a 422
 * about any other field would otherwise read as "this kind is not supported".
 */
private fun refusesConnectionType(error: ThalovantApiException): Boolean {
    if (error.statusCode != 422) return false
    val said = mutableListOf<String>()
    error.detail?.let(said::add)
    error.errorCode?.let(said::add)
    val problem = error.problem ?: EMPTY_JSON_OBJECT
    for (key in listOf("errors", "detail")) {
        val entries = problem[key] as? JsonArray ?: continue
        for (entry in entries) {
            val row = entry as? JsonObject ?: continue
            when (val location = row["loc"]) {
                is JsonArray -> said += location.joinToString(".") { part ->
                    (part as? JsonPrimitive)?.takeIf { it.isString }?.content ?: part.toString()
                }
                is JsonPrimitive -> if (location.isString) said += location.content
                else -> Unit
            }
            (row["msg"] as? JsonPrimitive)?.takeIf { it.isString }?.let { said += it.content }
        }
    }
    return said.any { "connection_type" in it || "connectionType" in it }
}

/** A URL's origin: scheme, host and port, the scheme's default port spelled out; null when unreadable. */
private fun origin(url: String): Triple<String, String, Int>? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = uri.port.takeIf { it >= 0 } ?: when (scheme) { "https" -> 443; "http" -> 80; else -> -1 }
    return Triple(scheme, host, port)
}

/**
 * Sleeps the whole of [millis] on the monotonic clock, never less: a wait the
 * API asked for must not end before it is up, whatever the timer's resolution.
 */
private suspend fun sleepAtLeast(millis: Long) {
    val end = System.nanoTime() + millis * 1_000_000
    while (true) {
        val left = end - System.nanoTime()
        if (left <= 0) return
        delay((left + 999_999) / 1_000_000)
    }
}

/** The body of `POST /v1/auth/device/authorize`: scopes when there are any, a client name when not empty. */
private fun deviceAuthorizeBody(scopes: List<String>?, clientName: String?, clientId: String?): JsonObject = buildJsonObject {
    // An empty list is left out rather than sent: the API requires at least
    // one scope and answers [] with a 422, and a missing field asks for its
    // default.
    scopes?.takeIf { it.isNotEmpty() }?.let { put("scopes", JsonArray(it.map(::JsonPrimitive))) }
    clientName?.takeIf { it.isNotEmpty() }?.let { put("client_name", it) }
    clientId?.takeIf { it.isNotEmpty() }?.let { put("client_id", it) }
}

/** A JSON string with something in it, exactly as sent; anything else, including a number, is null. */
internal fun jsonText(value: JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

/** A JSON number that is finite -- never a string, never a boolean -- or null. */
internal fun jsonNumber(value: JsonElement?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull || primitive.content == "true" || primitive.content == "false") return null
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** The operation a create answered with, when it reads as one; else null. */
internal fun operationOrNull(value: JsonElement?): OperationResource? {
    val operation = value as? JsonObject ?: return null
    return try {
        ThalovantJson.decodeFromJsonElement(OperationResource.serializer(), operation)
    } catch (_: IllegalArgumentException) {
        // kotlinx.serialization reports a missing field or an unknown status
        // as a SerializationException, which is an IllegalArgumentException.
        null
    }
}

/** The id at the end of an operation's `links.self`, `/v1/operations/{id}`, or null. */
private fun operationIdFromLink(link: String): String? {
    val marker = "/v1/operations/"
    val at = link.lastIndexOf(marker).takeIf { it >= 0 } ?: return null
    val id = link.substring(at + marker.length).substringBefore('?').substringBefore('#').trim('/')
    return id.takeIf { it.isNotEmpty() }?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
}

/** Extracts the device-flow `error` code from an HTTP 400 body, or null. */
private fun deviceFlowError(exception: ThalovantApiException): String? {
    if (exception.statusCode != 400) {
        return null
    }
    val body = exception.body?.takeIf { it.isNotBlank() } ?: return null
    val parsed = try {
        ThalovantJson.parseToJsonElement(body).asObjectOrNull()
    } catch (_: Exception) {
        null
    }
    return parsed?.optionalString("error")
}

/**
 * Best-effort browser open through a reflective `java.awt.Desktop` lookup.
 * `java.awt` does not exist on Android, and headless JVMs report the browse
 * action as unsupported; both paths simply do nothing. Never throws.
 */
internal fun deviceVerificationUri(uri: String): java.net.URI? {
    val target = runCatching { java.net.URI(uri) }.getOrNull() ?: return null
    if (uri.any { it.isISOControl() || it.isWhitespace() } || target.scheme?.lowercase() !in setOf("http", "https") ||
        target.host.isNullOrEmpty() || target.rawUserInfo != null) return null
    return target
}

internal suspend fun openBrowserBestEffort(uri: String, launch: ((java.net.URI) -> Unit)? = null) {
    val target = deviceVerificationUri(uri) ?: return
    if (launch != null) { runCatching { launch(target) }; return }
    withContext(Dispatchers.IO) {
        try {
            val desktopClass = Class.forName("java.awt.Desktop")
            val desktopSupported =
                desktopClass.getMethod("isDesktopSupported").invoke(null) as? Boolean ?: false
            if (!desktopSupported) {
                return@withContext
            }
            val desktop = desktopClass.getMethod("getDesktop").invoke(null)
            val actionClass = Class.forName("java.awt.Desktop\$Action")
            val browseAction = actionClass.enumConstants?.firstOrNull { it.toString() == "BROWSE" }
                ?: return@withContext
            val browseSupported =
                desktopClass.getMethod("isSupported", actionClass).invoke(desktop, browseAction) as? Boolean ?: false
            if (browseSupported) {
                desktopClass.getMethod("browse", java.net.URI::class.java).invoke(desktop, target)
            }
        } catch (_: Throwable) {
            // Browser availability is best-effort; the prompt already carries the URI and code.
        }
    }
}

internal fun normalizeControlApiUrl(apiUrl: String): String {
    var normalized = apiUrl.trim().ifEmpty { DEFAULT_CONTROL_API_URL }.trimEnd('/')
    if (normalized.endsWith("/v1")) {
        normalized = normalized.dropLast(3).trimEnd('/')
    }
    return "$normalized/"
}

private fun memoryCreateBody(payload: MemoryCreatePayload): JsonObject = buildJsonObject {
    payload.scope?.let { put("scope", it.wireName) }
    payload.kind?.let { put("kind", it.wireName) }
    payload.title?.let { put("title", it) }
    put("content", payload.content)
    payload.tags?.let { tags -> put("tags", JsonArray(tags.map { JsonPrimitive(it) })) }
    payload.ownerId?.let { put("owner_id", it) }
    payload.hubId?.let { put("hub_id", it) }
    payload.source?.let { put("source", it) }
    payload.metadata?.let { put("metadata", it) }
    payload.consentScope?.let { put("consent_scope", it) }
    payload.consentVersion?.let { put("consent_version", it) }
    payload.retentionPolicy?.let { put("retention_policy", it) }
    payload.expiresAt?.let { put("expires_at", it) }
}

private fun memoryUpdateBody(payload: MemoryUpdatePayload): JsonObject = buildJsonObject {
    payload.kind?.let { put("kind", it.wireName) }
    payload.title?.let { put("title", it) }
    payload.content?.let { put("content", it) }
    payload.tags?.let { tags -> put("tags", JsonArray(tags.map { JsonPrimitive(it) })) }
    payload.metadata?.let { put("metadata", it) }
    payload.consentScope?.let { put("consent_scope", it) }
    payload.consentVersion?.let { put("consent_version", it) }
    payload.retentionPolicy?.let { put("retention_policy", it) }
    payload.expiresAt?.let { put("expires_at", it) }
    if (payload.clearExpiresAt) put("clear_expires_at", true)
}

/**
 * Percent-encodes one path segment. Used for the runtime-group skill id, which
 * is the only free-form string the SDK interpolates into a path — every other
 * path parameter is a server-issued UUID. Skill ids are slugs that may carry
 * characters (`/` above all) that would otherwise change the request path.
 */
private fun encodePathSegment(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

private fun hubCreateBody(payload: HubCreatePayload): JsonObject = buildJsonObject {
    put("name", payload.name)
    put("spec", payload.spec)
    payload.slug?.let { put("slug", it) }
    payload.namespace?.let { put("namespace", it) }
    payload.runtimeGroupId?.let { put("runtime_group_id", it) }
    payload.domain?.let { put("domain", it) }
    payload.active?.let { put("active", it) }
    payload.visibility?.let { put("visibility", it) }
    payload.capacityProfile?.let { put("capacity_profile", it) }
    payload.ownerId?.let { put("owner_id", it) }
}

private fun hubUpdateBody(payload: HubUpdatePayload): JsonObject = buildJsonObject {
    payload.name?.let { put("name", it) }
    payload.slug?.let { put("slug", it) }
    payload.namespace?.let { put("namespace", it) }
    payload.domain?.let { put("domain", it) }
    payload.active?.let { put("active", it) }
    payload.visibility?.let { put("visibility", it) }
    payload.capacityProfile?.let { put("capacity_profile", it) }
    payload.runtimeGroupId?.let { put("runtime_group_id", it) }
    payload.spec?.let { put("spec", it) }
    payload.isLocked?.let { put("is_locked", it) }
}

private fun releaseBody(options: ReleaseOptions): JsonObject = buildJsonObject {
    options.channel?.let { put("channel", it) }
    options.mode?.let { put("mode", it) }
    options.version?.let { put("version", it) }
    options.images?.let { images ->
        put("images", JsonObject(images.mapValues { (_, value) -> JsonPrimitive(value) }))
    }
    options.reason?.let { put("reason", it) }
}

private fun runtimeGroupCreateBody(payload: RuntimeGroupCreatePayload): JsonObject = buildJsonObject {
    put("name", payload.name)
    payload.description?.let { put("description", it) }
    payload.environment?.let { put("environment", it) }
    payload.ownerId?.let { put("owner_id", it) }
    payload.cloneFromDefault?.let { put("clone_from_default", it) }
}

private fun runtimeGroupUpdateBody(payload: RuntimeGroupUpdatePayload): JsonObject = buildJsonObject {
    payload.name?.let { put("name", it) }
    payload.description?.let { put("description", it) }
    payload.spec?.let { put("spec", it) }
}

private fun installSkillBody(skillId: String, options: InstallSkillOptions): JsonObject = buildJsonObject {
    put("skill_id", skillId)
    put("source_type", options.sourceType)
    put("active", options.active)
    options.marketplaceSkillId?.let { put("marketplace_skill_id", it) }
    options.sourceRef?.let { put("source_ref", it) }
    options.versionPin?.let { put("version_pin", it) }
}

/**
 * Keys that carry credential material in the raw hub/client resources returned
 * by [ThalovantControlPlane.createClientIdentity]: the `POST /v1/clients`
 * bootstrap payloads (`initial_identify`, `initial_identify_token`) plus the
 * identity secrets echoed inside `spec` (camelCase) and `initial_identify`
 * (snake_case), plus known credential fields in arbitrary metadata. Compared
 * case-insensitively without underscores or hyphens; reference shapes such as
 * `apiKeyRef` are untouched.
 */
private val BOOTSTRAP_SECRET_KEYS = setOf(
    "initialidentify", "initialidentifytoken", "accesskey", "apikey", "password",
    "cryptokey", "username", "brokerusername", "brokerpassword", "authorization",
    "clientsecret", "privatekey", "apisecret", "secretkey", "credentials",
    "token", "accesstoken", "refreshtoken", "authtoken",
)

/**
 * Recursively removes [BOOTSTRAP_SECRET_KEYS] and strips URL userinfo
 * credentials from string values, mirroring the `includeSecrets = false`
 * behavior of [ThalovantIdentity.asJson] for raw API resources. Used only for
 * the redacted identity metadata and [BootstrapIdentityResult.asJson] view — never for request
 * bodies or identity persistence.
 */
internal fun redactBootstrapSecrets(value: JsonObject): JsonObject = JsonObject(
    value.filterKeys { it.replace("_", "").replace("-", "").lowercase() !in BOOTSTRAP_SECRET_KEYS }
        .mapValues { (_, child) -> redactBootstrapElement(child) },
)

private fun redactBootstrapElement(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> redactBootstrapSecrets(value)
    is JsonArray -> JsonArray(value.map { redactBootstrapElement(it) })
    is JsonPrimitive -> if (value.isString) JsonPrimitive(redactEndpointCredentials(value.content)) else value
}

private val secretRandom = SecureRandom()

private fun newSecret(): String {
    val bytes = ByteArray(32).also { secretRandom.nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun cleanSiteId(value: String): String {
    val cleaned = value.trim().replace(Regex("_+"), "-").replace(Regex("\\s+"), "-")
    if (cleaned.isNotEmpty()) {
        return cleaned
    }
    val suffix = ByteArray(4).also { secretRandom.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    return "thalovant-client-$suffix"
}

private fun defaultMaster(
    hub: JsonObject,
    endpoints: HubDataPlaneEndpoints,
    selected: SelectedHubEndpoint?,
): String {
    endpoints.https?.let { return stripPath(it) }
    hub.optionalString("domain")?.let { domain ->
        endpointFromDomain(domain, HubProtocol.HTTPS)?.let { return it }
    }
    selected?.let { return stripPath(it.endpoint) }
    throw ThalovantApiException("Hub resource does not expose a usable data-plane endpoint.")
}

private fun stripPath(endpoint: String): String {
    return try {
        val uri = java.net.URI(endpoint)
        if (uri.scheme == null || uri.host.isNullOrEmpty()) {
            endpoint.trimEnd('/')
        } else {
            val portPart = if (uri.port == -1) "" else ":${uri.port}"
            "${uri.scheme}://${uri.host}$portPart"
        }
    } catch (_: Exception) {
        endpoint.trimEnd('/')
    }
}

/** Optional waiting after a skill mutation; polling never repeats the accepted write. */
public data class HubSkillWaitOptions(
    public val wait: Boolean = false,
    public val timeoutMs: Long = 120_000,
    public val pollIntervalMs: Long = 2_000,
) {
    internal fun validate() {
        require(timeoutMs > 0 && pollIntervalMs > 0) { "hub skill wait durations must be positive" }
    }
}

private fun mergeRuntimeConfig(base: JsonObject, delta: JsonObject): JsonObject {
    val result = base.toMutableMap()
    for ((key, value) in delta) {
        val old = base[key]
        result[key] = if (old is JsonObject && value is JsonObject) mergeRuntimeConfig(old, value) else value
    }
    return JsonObject(result)
}
