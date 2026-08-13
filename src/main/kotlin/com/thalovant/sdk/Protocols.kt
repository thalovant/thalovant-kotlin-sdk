package com.thalovant.sdk

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Data-plane protocols a hub can expose. */
public enum class HubProtocol(public val wireName: String) {
    WSS("wss"),
    HTTPS("https"),
    MQTT("mqtt"),
    ;

    public companion object {
        public fun fromWireName(value: String): HubProtocol? =
            entries.firstOrNull { it.wireName == value.trim().lowercase() }
    }
}

/** Preference order used when the caller does not pass one: wss, https, mqtt. */
public val DEFAULT_PROTOCOL_PREFERENCE: List<HubProtocol> =
    listOf(HubProtocol.WSS, HubProtocol.HTTPS, HubProtocol.MQTT)

public data class SelectedHubEndpoint(
    public val protocol: HubProtocol,
    public val endpoint: String,
)

/**
 * Enablement flags parsed from `spec.protocols.{wss,http,mqtt}.enabled` on a hub
 * resource (or an identity payload). WSS defaults to enabled.
 */
public class HubProtocolSettings(
    public val wss: Boolean = true,
    public val http: Boolean = false,
    public val mqtt: Boolean = false,
) {
    public val https: Boolean get() = http

    public fun enabledProtocols(): List<HubProtocol> = buildList {
        if (wss) add(HubProtocol.WSS)
        if (http) add(HubProtocol.HTTPS)
        if (mqtt) add(HubProtocol.MQTT)
    }

    public fun isEnabled(protocol: HubProtocol): Boolean = when (protocol) {
        HubProtocol.WSS -> wss
        HubProtocol.HTTPS -> http
        HubProtocol.MQTT -> mqtt
    }

    public fun asJson(): JsonObject = buildJsonObject {
        put("wss", buildJsonObject { put("enabled", wss) })
        put("http", buildJsonObject { put("enabled", http) })
        put("mqtt", buildJsonObject { put("enabled", mqtt) })
    }

    public companion object {
        public fun from(input: JsonObject?): HubProtocolSettings {
            if (input == null) {
                return HubProtocolSettings()
            }
            val spec = input["spec"].asObjectOrNull() ?: input
            val protocols = spec["protocols"].asObjectOrNull() ?: EMPTY_JSON_OBJECT
            val network = spec["network"].asObjectOrNull() ?: EMPTY_JSON_OBJECT
            return HubProtocolSettings(
                wss = enabledValue(
                    protocols.firstElement("wss", "websocket") ?: network.firstElement("wss", "websocket"),
                    true,
                ),
                http = enabledValue(
                    protocols.firstElement("http", "https") ?: network.firstElement("http", "https"),
                    false,
                ),
                mqtt = enabledValue(
                    protocols.firstElement("mqtt") ?: network.firstElement("mqtt"),
                    false,
                ),
            )
        }
    }
}

/** Data-plane endpoints published under `data_plane_endpoints.{https,wss,mqtt}`. */
public class HubDataPlaneEndpoints(
    https: String? = null,
    wss: String? = null,
    mqtt: String? = null,
) {
    public val https: String? = normalizeEndpoint(https)
    public val wss: String? = normalizeEndpoint(wss)
    public val mqtt: String? = normalizeEndpoint(mqtt)

    public fun endpointFor(protocol: HubProtocol): String? = when (protocol) {
        HubProtocol.HTTPS -> https
        HubProtocol.WSS -> wss
        HubProtocol.MQTT -> mqtt
    }

    public fun httpBase(fallbackMaster: String, fallbackPort: Int, fallbackPath: String): String {
        https?.let { return endpointBase(it, fallbackPort, "") }
        val master = when {
            fallbackMaster.startsWith("wss://") -> "https://" + fallbackMaster.removePrefix("wss://")
            fallbackMaster.startsWith("ws://") -> "http://" + fallbackMaster.removePrefix("ws://")
            else -> fallbackMaster
        }
        return endpointBase(master, fallbackPort, fallbackPath)
    }

    public fun asJson(redactCredentials: Boolean = false): JsonObject = buildJsonObject {
        for ((key, value) in listOf("https" to https, "wss" to wss, "mqtt" to mqtt)) {
            if (value != null) {
                put(key, if (redactCredentials) redactEndpointCredentials(value) else value)
            }
        }
    }

    public companion object {
        public fun from(input: JsonObject?): HubDataPlaneEndpoints {
            if (input == null) {
                return HubDataPlaneEndpoints()
            }
            val source = input["data_plane_endpoints"].asObjectOrNull()
                ?: input["dataPlaneEndpoints"].asObjectOrNull()
                ?: input["endpoints"].asObjectOrNull()
                ?: input
            return HubDataPlaneEndpoints(
                https = optionalString(source.firstElement("https", "http")),
                wss = optionalString(source.firstElement("wss", "ws")),
                mqtt = optionalString(source.firstElement("mqtt", "mqtts")),
            )
        }

        /** Derives missing wss/https endpoints from the hub `domain` when the protocol is enabled. */
        public fun fromHub(hub: JsonObject): HubDataPlaneEndpoints {
            val endpoints = from(hub)
            val protocols = HubProtocolSettings.from(hub)
            val domain = hub.optionalString("domain") ?: return endpoints
            return HubDataPlaneEndpoints(
                https = endpoints.https
                    ?: if (protocols.http) endpointFromDomain(domain, HubProtocol.HTTPS) else null,
                wss = endpoints.wss
                    ?: if (protocols.wss) endpointFromDomain(domain, HubProtocol.WSS) else null,
                mqtt = endpoints.mqtt,
            )
        }
    }
}

/** Picks the first enabled protocol (in [preferredProtocols] order) that has an endpoint. */
public fun selectDataPlaneEndpoint(
    endpoints: HubDataPlaneEndpoints,
    protocols: HubProtocolSettings,
    preferredProtocols: List<HubProtocol> = DEFAULT_PROTOCOL_PREFERENCE,
): SelectedHubEndpoint? {
    val preference = preferredProtocols.ifEmpty { DEFAULT_PROTOCOL_PREFERENCE }
    for (protocol in preference) {
        if (!protocols.isEnabled(protocol)) continue
        val endpoint = endpoints.endpointFor(protocol) ?: continue
        return SelectedHubEndpoint(protocol, endpoint)
    }
    return null
}

public fun endpointFromDomain(domain: String, protocol: HubProtocol): String? {
    val normalized = domain.trim().trimEnd('/')
    return when (protocol) {
        HubProtocol.WSS -> when {
            normalized.startsWith("wss://") || normalized.startsWith("ws://") -> normalizeEndpoint(normalized)
            normalized.startsWith("https://") -> normalizeEndpoint("wss://" + normalized.removePrefix("https://"))
            normalized.startsWith("http://") -> normalizeEndpoint("wss://" + normalized.removePrefix("http://"))
            else -> normalizeEndpoint("wss://$normalized")
        }
        HubProtocol.HTTPS -> when {
            normalized.startsWith("https://") -> normalizeEndpoint(normalized)
            normalized.startsWith("http://") -> normalizeEndpoint("https://" + normalized.removePrefix("http://"))
            normalized.startsWith("wss://") -> normalizeEndpoint("https://" + normalized.removePrefix("wss://"))
            normalized.startsWith("ws://") -> normalizeEndpoint("https://" + normalized.removePrefix("ws://"))
            else -> normalizeEndpoint("https://$normalized")
        }
        HubProtocol.MQTT -> null
    }
}

private val ALLOWED_ENDPOINT_SCHEMES = setOf("http", "https", "ws", "wss", "mqtt", "mqtts")

internal fun normalizeEndpoint(raw: String?): String? {
    val trimmed = raw?.trim()?.trimEnd('/')?.ifEmpty { null } ?: return null
    return try {
        val uri = URI(trimmed)
        if (uri.scheme?.lowercase() in ALLOWED_ENDPOINT_SCHEMES && !uri.host.isNullOrEmpty()) trimmed else null
    } catch (_: Exception) {
        null
    }
}

/**
 * Builds `scheme://host[:port][/path]` from a master URL, filling in the default
 * port and path when missing. Scheme-default ports (80/443) are omitted, matching
 * the Node SDK URL normalization.
 */
internal fun endpointBase(master: String, defaultPort: Int, defaultPath: String): String {
    val uri = try {
        URI(master).takeIf { it.scheme != null && !it.host.isNullOrEmpty() }
    } catch (_: Exception) {
        null
    } ?: return master.trimEnd('/') + ":" + defaultPort + defaultPath

    val scheme = uri.scheme
    val port = if (uri.port == -1) defaultPort else uri.port
    val schemeDefaultPort = when (scheme.lowercase()) {
        "http", "ws" -> 80
        "https", "wss" -> 443
        else -> -1
    }
    val portPart = if (port == schemeDefaultPort) "" else ":$port"
    val userInfoPart = uri.userInfo?.let { "$it@" } ?: ""
    val path = listOf(uri.rawPath.orEmpty(), defaultPath)
        .map { it.trim('/') }
        .filter { it.isNotEmpty() }
        .joinToString("/")
    val pathPart = if (path.isEmpty()) "" else "/$path"
    return "$scheme://$userInfoPart${uri.host}$portPart$pathPart"
}

internal fun redactEndpointCredentials(endpoint: String): String {
    return try {
        val uri = URI(endpoint)
        if (uri.userInfo == null) {
            endpoint
        } else {
            URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment,
            ).toString().trimEnd('/')
        }
    } catch (_: Exception) {
        endpoint
    }
}
