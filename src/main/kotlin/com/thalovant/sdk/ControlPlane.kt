package com.thalovant.sdk

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/** Default `POST /v1/auth/device/token` poll interval when the API omits `interval`. */
public const val DEFAULT_DEVICE_POLL_INTERVAL_MILLIS: Long = 5_000

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
)

public data class CreateClientIdentityOptions(
    public val name: String,
    public val siteId: String? = null,
    public val spec: JsonObject? = null,
    public val ownerId: String? = null,
    public val active: Boolean = true,
    public val preferredProtocols: List<HubProtocol>? = null,
    public val idempotencyKey: String? = null,
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
    public var accessToken: String? = null,
    public val userAgent: String = DEFAULT_USER_AGENT,
    private val httpClient: OkHttpClient = defaultHttpClient,
) {
    /** Normalized API root; a trailing `/v1` is stripped and a trailing slash added. */
    public val apiUrl: String = normalizeControlApiUrl(apiUrl)

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
        val accessToken = (token["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (accessToken.isNullOrEmpty()) {
            throw ThalovantApiException("Thalovant API token response did not include access_token.")
        }
        this.accessToken = accessToken
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
        val body = buildJsonObject {
            options.scopes?.let { scopes -> put("scopes", JsonArray(scopes.map { JsonPrimitive(it) })) }
            options.clientName?.takeIf { it.isNotEmpty() }?.let { put("client_name", it) }
        }
        val grant = request("POST", "/v1/auth/device/authorize", body = body, auth = false)

        val deviceCode = grant.optionalString("device_code")
        val userCode = grant.optionalString("user_code")
        val verificationUri = grant.optionalString("verification_uri")
        if (deviceCode == null || userCode == null || verificationUri == null) {
            throw ThalovantApiException("Thalovant API device authorization response was incomplete.")
        }
        val intervalSeconds = optionalString(grant["interval"])?.toLongOrNull()
        val intervalMillis = if (intervalSeconds != null && intervalSeconds >= 0) {
            intervalSeconds * 1_000
        } else {
            DEFAULT_DEVICE_POLL_INTERVAL_MILLIS
        }

        val prompt = options.prompt
        if (prompt != null) {
            prompt(grant)
        } else {
            println("To sign in, visit $verificationUri and enter the code $userCode")
        }
        if (options.openBrowser) {
            grant.optionalString("verification_uri_complete")?.let { openBrowserBestEffort(it) }
        }

        val token = pollDeviceToken(deviceCode, intervalMillis, options.timeoutMillis)
        val accessToken = (token["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (accessToken.isNullOrEmpty()) {
            throw ThalovantApiException("Thalovant API token response did not include access_token.")
        }
        this.accessToken = accessToken
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
        var waitMillis = intervalMillis
        while (true) {
            try {
                return request(
                    "POST",
                    "/v1/auth/device/token",
                    body = buildJsonObject { put("device_code", deviceCode) },
                    auth = false,
                )
            } catch (exception: ThalovantApiException) {
                when (deviceFlowError(exception)) {
                    "authorization_pending" -> Unit
                    "slow_down" -> waitMillis += 5_000
                    "access_denied" -> throw ThalovantDeviceLoginDeniedException(
                        "The device sign-in request was denied in the browser.",
                    )
                    "expired_token" -> throw ThalovantDeviceLoginExpiredException(
                        "The device sign-in code expired before it was approved. " +
                            "Call loginWithBrowser() again to request a new code.",
                    )
                    else -> throw exception
                }
            }
            val remainingMillis = deadline - clock()
            if (remainingMillis <= 0) {
                throw ThalovantTimeoutException("Timed out waiting for the device sign-in to be approved.")
            }
            sleep(minOf(waitMillis, remainingMillis))
        }
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
     * The request is idempotent: an `Idempotency-Key` header is always sent
     * (generated when [idempotencyKey] is null), so a create retried after a
     * timeout returns the hub that was already created instead of making a
     * second one.
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
    public suspend fun setHubRating(hubId: String, rating: Int): JsonObject =
        request("PUT", "/v1/hubs/$hubId/rating", body = buildJsonObject { put("rating", rating) })

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
        request("GET", "/v1/runtime-groups/$runtimeGroupId/config")

    /**
     * Merges runtime configuration into a runtime group via
     * `PATCH /v1/runtime-groups/{runtimeGroupId}/config`.
     *
     * The API merges [config] into the stored configuration rather than
     * replacing it, and marks the group pending so the runtime operator
     * reconciles the change. [personas] is replaced only when provided.
     *
     * Requires a paid plan and a token with the `hubs:write` scope.
     */
    public suspend fun updateRuntimeGroupConfig(
        runtimeGroupId: String,
        config: JsonObject,
        personas: JsonObject? = null,
    ): JsonObject = request(
        "PATCH",
        "/v1/runtime-groups/$runtimeGroupId/config",
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
        val client = createClient(payload, options.idempotencyKey)
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

    private suspend fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        headers: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val urlBuilder = (apiUrl + path.trimStart('/')).toHttpUrl().newBuilder()
        for ((key, value) in query) {
            urlBuilder.addQueryParameter(key, value)
        }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
        for ((key, value) in headers) {
            builder.header(key, value)
        }
        if (auth) {
            val token = accessToken
                ?: throw ThalovantApiException("Missing Thalovant API access token.")
            builder.header("Authorization", "Bearer $token")
        }
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        builder.method(method, requestBody)
        return withContext(Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ThalovantApiException(
                        apiErrorMessage(response.code, text),
                        statusCode = response.code,
                        body = text,
                    )
                }
                if (text.isBlank()) {
                    EMPTY_JSON_OBJECT
                } else {
                    ThalovantJson.parseToJsonElement(text).asObjectOrNull()
                        ?: throw ThalovantApiException("Thalovant API returned an unexpected response shape.")
                }
            }
        }
    }

    private companion object {
        val defaultHttpClient: OkHttpClient = OkHttpClient()
    }
}

/** Upper bound on the server detail echoed into a [ThalovantApiException] message. */
private const val API_ERROR_DETAIL_MAX_LENGTH: Int = 200

private val API_ERROR_WHITESPACE: Regex = Regex("\\s+")

/**
 * Builds the human-facing message for a failed API request. The full response
 * body stays on [ThalovantApiException.body] for programmatic inspection (the
 * device flow parses its `error` code from it), but the message keeps only the
 * status and a short, single-line, bounded slice of the server detail. This
 * keeps a raw body — which for `POST /v1/clients`, `POST /v1/auth/token`, and
 * `POST /v1/auth/device/token` can echo the credentials that were sent — from
 * being interpolated wholesale into logs and stack traces.
 */
private fun apiErrorMessage(statusCode: Int, body: String): String {
    val detail = body.replace(API_ERROR_WHITESPACE, " ").trim().take(API_ERROR_DETAIL_MAX_LENGTH)
    return if (detail.isEmpty()) {
        "Thalovant API request failed with HTTP $statusCode."
    } else {
        "Thalovant API request failed with HTTP $statusCode: $detail"
    }
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
private suspend fun openBrowserBestEffort(uri: String) {
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
                desktopClass.getMethod("browse", java.net.URI::class.java).invoke(desktop, java.net.URI(uri))
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
 * (snake_case). Matched by exact key name, so reference shapes such as
 * `apiKeyRef` are untouched.
 */
private val BOOTSTRAP_SECRET_KEYS = setOf(
    "initial_identify",
    "initial_identify_token",
    "access_key",
    "api_key",
    "apiKey",
    "password",
    "crypto_key",
    "cryptoKey",
)

/**
 * Recursively removes [BOOTSTRAP_SECRET_KEYS] and strips URL userinfo
 * credentials from string values, mirroring the `includeSecrets = false`
 * behavior of [ThalovantIdentity.asJson] for raw API resources. Used only for
 * the redacted [BootstrapIdentityResult.asJson] view — never for request
 * bodies or identity persistence.
 */
private fun redactBootstrapSecrets(value: JsonObject): JsonObject = JsonObject(
    value.filterKeys { it !in BOOTSTRAP_SECRET_KEYS }
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
