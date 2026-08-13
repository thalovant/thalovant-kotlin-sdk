package com.thalovant.sdk

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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
    public val admin: Boolean = false,
    public val range: String? = null,
    public val bucket: String? = null,
    /** Only sent on the admin endpoint, matching the other SDKs. */
    public val ownerId: String? = null,
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

public data class CreateClientIdentityOptions(
    public val name: String,
    public val siteId: String? = null,
    public val spec: JsonObject? = null,
    public val ownerId: String? = null,
    public val active: Boolean = true,
    public val preferredProtocols: List<HubProtocol>? = null,
    public val idempotencyKey: String? = null,
)

/** Result of [ThalovantControlPlane.createClientIdentity]. Keep [identity] secret. */
public class BootstrapIdentityResult internal constructor(
    public val identity: ThalovantIdentity,
    public val hub: JsonObject,
    public val client: JsonObject,
    public val endpoint: SelectedHubEndpoint?,
) {
    public val selectedProtocol: HubProtocol? get() = endpoint?.protocol

    public fun asJson(includeSecrets: Boolean = false): JsonObject = buildJsonObject {
        put("identity", identity.asJson(includeSecrets))
        put("hub", hub)
        put("client", client)
        endpoint?.let {
            put("selected_protocol", it.protocol.wireName)
            put("selected_endpoint", it.endpoint)
        }
    }
}

/**
 * Thalovant control-plane API client. Discovers hubs, manages memory and
 * analytics, and provisions client identities.
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
     * Workspace analytics overview; `admin = true` switches to
     * `/v1/admin/analytics/overview` and enables the `owner_id` filter.
     */
    public suspend fun getAnalyticsOverview(options: AnalyticsOverviewOptions = AnalyticsOverviewOptions()): JsonObject {
        val endpoint = if (options.admin) "/v1/admin/analytics/overview" else "/v1/analytics/overview"
        val query = LinkedHashMap<String, String>()
        options.range?.takeIf { it.isNotBlank() }?.let { query["range"] = it }
        options.bucket?.takeIf { it.isNotBlank() }?.let { query["bucket"] = it }
        if (options.admin) {
            options.ownerId?.takeIf { it.isNotBlank() }?.let { query["owner_id"] = it }
        }
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
        return request("GET", endpoint, query = query)
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
                        "Thalovant API request failed with HTTP ${response.code}: $text",
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
