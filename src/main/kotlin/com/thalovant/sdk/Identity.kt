package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client-scoped MQTT broker credentials, matching the API
 * `ClientMqttCredentials` schema: endpoint, username, password, topic_prefix, tls.
 *
 * Security: this is intentionally a plain `class`, not a `data class`. A
 * generated `data class` `toString()` would print [username]/[password]/
 * [topicPrefix] and leak them into logs and stack traces. Do not convert it to
 * a `data class`; a test asserts `toString()` leaks no secret values. Use
 * [asJson] for a redaction-aware serialization instead.
 */
public class MqttBrokerCredentials internal constructor(
    public val endpoint: String,
    public val username: String,
    public val password: String,
    public val topicPrefix: String?,
    public val tls: Boolean,
) {
    public fun asJson(includeSecrets: Boolean = false): JsonObject = buildJsonObject {
        put("endpoint", endpoint)
        put("tls", tls)
        if (includeSecrets) {
            put("username", username)
            put("password", password)
            topicPrefix?.let { put("topic_prefix", it) }
        }
    }

    public companion object {
        public fun from(input: JsonObject?): MqttBrokerCredentials? {
            if (input == null) {
                return null
            }
            val endpoint = input.optionalString("endpoint", "broker_url", "brokerUrl") ?: return null
            val username = input.optionalString("username", "broker_username", "brokerUsername") ?: return null
            val password = input.optionalString("password", "broker_password", "brokerPassword") ?: return null
            return MqttBrokerCredentials(
                endpoint = endpoint,
                username = username,
                password = password,
                topicPrefix = input.optionalString("topic_prefix", "topicPrefix"),
                tls = when (val flag = input.firstElement("tls")) {
                    null -> endpoint.startsWith("mqtts://")
                    else -> enabledValue(flag, endpoint.startsWith("mqtts://"))
                },
            )
        }
    }
}

/**
 * A hub client identity, matching the API `ClientIdentifyResource` schema:
 * access_key, password, crypto_key, site_id, default_port, default_master, mqtt.
 *
 * Security: this is intentionally a plain `class`, not a `data class`. A
 * generated `data class` `toString()` would print [accessKey]/[password]/
 * [cryptoKey] (and the [mqtt] credentials) and leak them into logs and stack
 * traces. Do not convert it to a `data class`; a test asserts `toString()`
 * leaks no secret values. Use [asJson] for a redaction-aware serialization.
 */
public class ThalovantIdentity(input: JsonObject) {
    public val accessKey: String = requiredString(input, "access_key", "accessKey", "access_key", "api_key", "key")
    public val password: String = requiredString(input, "password", "password")
    public val defaultMaster: String = requiredString(
        input,
        "default_master",
        "defaultMaster",
        "default_master",
        "hub_http_host",
        "host",
        "master",
    ).trimEnd('/')
    public val siteId: String = requiredString(input, "site_id", "siteId", "site_id", "site")
    public val defaultPort: Int = positiveIntValue(
        input.firstElement("defaultPort", "default_port", "hub_http_port", "port"),
        5679,
    )
    public val defaultPath: String = normalizePath(
        input.optionalString("defaultPath", "default_path", "hub_http_path", "path", "uri_path"),
    )
    public val cryptoKey: String? = input.optionalString("cryptoKey", "crypto_key")
    public val dataPlaneEndpoints: HubDataPlaneEndpoints = HubDataPlaneEndpoints.from(input)
    public val protocols: HubProtocolSettings = HubProtocolSettings.from(input)
    public val publicKey: String? = input.optionalString("publicKey", "public_key")
    public val metadata: JsonObject = input["metadata"].asObjectOrNull() ?: EMPTY_JSON_OBJECT
    public val mqtt: MqttBrokerCredentials? = MqttBrokerCredentials.from(input["mqtt"].asObjectOrNull())

    /** HTTPS base endpoint derived from the data-plane endpoints or the default master. */
    public fun endpointBase(): String = dataPlaneEndpoints.httpBase(defaultMaster, defaultPort, defaultPath)

    public fun endpointFor(protocol: HubProtocol): String? {
        if (protocol == HubProtocol.HTTPS) {
            return endpointBase()
        }
        dataPlaneEndpoints.endpointFor(protocol)?.let { return it }
        if (protocol == HubProtocol.WSS &&
            (defaultMaster.startsWith("wss://") || defaultMaster.startsWith("ws://"))
        ) {
            return defaultMaster
        }
        return null
    }

    public fun enabledProtocols(): List<HubProtocol> = protocols.enabledProtocols()

    public fun supportsProtocol(protocol: HubProtocol): Boolean = protocols.isEnabled(protocol)

    public fun asJson(includeSecrets: Boolean = false): JsonObject = buildJsonObject {
        put("site_id", siteId)
        put("default_master", defaultMaster)
        put("default_port", defaultPort)
        put("default_path", defaultPath)
        val endpoints = dataPlaneEndpoints.asJson(redactCredentials = !includeSecrets)
        if (endpoints.isNotEmpty()) {
            put("data_plane_endpoints", endpoints)
        }
        if (metadata.isNotEmpty()) {
            put("metadata", metadata)
        }
        if (includeSecrets) {
            put("access_key", accessKey)
            put("password", password)
            cryptoKey?.let { put("crypto_key", it) }
        }
        mqtt?.let { put("mqtt", it.asJson(includeSecrets)) }
    }

    public companion object {
        /** Parses an identity from a raw JSON string, e.g. an `initial_identify` payload. */
        public fun fromJson(json: String): ThalovantIdentity {
            val parsed = try {
                ThalovantJson.parseToJsonElement(json)
            } catch (error: Exception) {
                throw ThalovantIdentityException("Identity payload is not valid JSON.", error)
            }
            val body = parsed.asObjectOrNull()
                ?: throw ThalovantIdentityException("Identity payload must be a JSON object.")
            return ThalovantIdentity(body)
        }

        /**
         * Loads an identity from a JSON file. On POSIX systems the file must not be
         * readable or writable by group/other (`chmod 600`).
         */
        public fun fromFile(path: Path): ThalovantIdentity {
            assertSecureIdentityFile(path)
            val content = try {
                Files.readString(path)
            } catch (error: Exception) {
                throw ThalovantIdentityException("Unable to read identity file: $path", error)
            }
            return try {
                fromJson(content)
            } catch (_: ThalovantIdentityException) {
                throw ThalovantIdentityException("Identity file is not valid JSON: $path")
            }
        }

        public fun fromFile(path: String): ThalovantIdentity = fromFile(Path.of(path))

        private fun assertSecureIdentityFile(path: Path) {
            if (!Files.exists(path)) {
                throw ThalovantIdentityException("Unable to read identity file: $path")
            }
            val permissions = try {
                Files.getPosixFilePermissions(path)
            } catch (_: UnsupportedOperationException) {
                return // Non-POSIX platform (e.g. Windows); skip the check.
            } catch (error: Exception) {
                throw ThalovantIdentityException("Unable to read identity file: $path", error)
            }
            val tooPermissive = permissions.any {
                it != PosixFilePermission.OWNER_READ &&
                    it != PosixFilePermission.OWNER_WRITE &&
                    it != PosixFilePermission.OWNER_EXECUTE
            }
            if (tooPermissive) {
                throw ThalovantIdentityException(
                    "Identity file is too permissive: $path. Run `chmod 600 $path`.",
                )
            }
        }
    }
}

private fun requiredString(input: JsonObject, field: String, vararg keys: String): String {
    return input.optionalString(*keys)
        ?: throw ThalovantIdentityException("Missing required identity field: $field")
}

private fun normalizePath(value: String?): String {
    val normalized = value?.trim('/')?.ifEmpty { null } ?: return ""
    return "/$normalized"
}
