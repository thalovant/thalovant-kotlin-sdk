package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pins finding F8: the secret-bearing SDK types are intentionally plain classes
 * (not `data class`) and carry no secret-dumping `@Serializable`, so their
 * default `toString()` cannot leak secrets. Converting any of them to a
 * `data class` would generate a `toString()` that prints the constructor
 * properties (or, for the identity, the raw input JSON) and fail these tests.
 */
class SecretToStringTest {
    private val secrets = listOf(
        "access-key-SECRET",
        "password-SECRET",
        "crypto-key-SECRET",
        "broker-password-SECRET",
        "broker-username-SECRET",
        "topic-prefix-SECRET",
        "access-token-SECRET",
    )

    private fun assertNoSecrets(label: String, rendered: String) {
        for (secret in secrets) {
            assertFalse(secret in rendered, "$label toString() leaked $secret")
        }
    }

    private fun identityWithSecrets(): ThalovantIdentity = ThalovantIdentity(
        buildJsonObject {
            put("access_key", "access-key-SECRET")
            put("password", "password-SECRET")
            put("crypto_key", "crypto-key-SECRET")
            put("site_id", "site")
            put("default_master", "wss://hub.example.com")
            put("default_port", 443)
            putJsonObject("mqtt") {
                put("endpoint", "mqtts://broker.example.com:8883")
                put("username", "broker-username-SECRET")
                put("password", "broker-password-SECRET")
                put("topic_prefix", "topic-prefix-SECRET")
                put("tls", true)
            }
        },
    )

    @Test
    fun `ThalovantIdentity toString does not leak secrets`() {
        assertNoSecrets("ThalovantIdentity", identityWithSecrets().toString())
    }

    @Test
    fun `MqttBrokerCredentials toString does not leak secrets`() {
        val mqtt = identityWithSecrets().mqtt
        assertNotNull(mqtt)
        assertNoSecrets("MqttBrokerCredentials", mqtt.toString())
    }

    @Test
    fun `BootstrapIdentityResult toString does not leak secrets`() {
        val client = buildJsonObject {
            put("id", "client-1")
            putJsonObject("initial_identify") {
                put("access_key", "access-key-SECRET")
                put("password", "password-SECRET")
                put("crypto_key", "crypto-key-SECRET")
            }
            put("initial_identify_token", "password-SECRET")
        }
        val hub = buildJsonObject { put("id", "hub-1") }
        val result = BootstrapIdentityResult(identityWithSecrets(), hub, client, endpoint = null)
        assertNoSecrets("BootstrapIdentityResult", result.toString())
    }

    @Test
    fun `ThalovantControlPlane toString does not leak the access token`() {
        val api = ThalovantControlPlane(accessToken = "access-token-SECRET")
        assertNoSecrets("ThalovantControlPlane", api.toString())
    }
}
