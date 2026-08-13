package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class IdentityTest {
    @Test
    fun `normalizes aliases`() {
        val identity = ThalovantIdentity(
            buildJsonObject {
                put("key", "access")
                put("password", "secret")
                put("cryptoKey", "0123456789abcdef-extra")
                put("site", "site")
                put("host", "https://hub.example.com/")
                put("port", "443")
                put("path", "/hivemind/public")
                putJsonObject("metadata") { put("thalovant_owner_id", "owner-1") }
            },
        )

        assertEquals("access", identity.accessKey)
        assertEquals("https://hub.example.com", identity.defaultMaster)
        assertEquals(443, identity.defaultPort)
        assertEquals("/hivemind/public", identity.defaultPath)
        assertEquals("https://hub.example.com/hivemind/public", identity.endpointBase())
        assertEquals("owner-1", identity.metadata["thalovant_owner_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `uses protocol aware data plane endpoints`() {
        val identity = ThalovantIdentity(
            buildJsonObject {
                put("key", "access")
                put("password", "secret")
                put("site", "site")
                put("host", "wss://hub.example.com")
                put("port", 443)
                put("path", "/hivemind/public")
                putJsonObject("data_plane_endpoints") {
                    put("https", "https://api.example.com/hivemind/public")
                    put("wss", "wss://socket.example.com/hivemind/public")
                    put("mqtt", "mqtts://mqtt.example.com:8883")
                }
                putJsonObject("protocols") {
                    putJsonObject("wss") { put("enabled", true) }
                    putJsonObject("http") { put("enabled", true) }
                    putJsonObject("mqtt") { put("enabled", true) }
                }
            },
        )

        assertEquals("https://api.example.com/hivemind/public", identity.endpointBase())
        assertEquals("wss://socket.example.com/hivemind/public", identity.endpointFor(HubProtocol.WSS))
        assertEquals("mqtts://mqtt.example.com:8883", identity.endpointFor(HubProtocol.MQTT))
        assertEquals(
            listOf(HubProtocol.WSS, HubProtocol.HTTPS, HubProtocol.MQTT),
            identity.enabledProtocols(),
        )
        assertTrue(identity.supportsProtocol(HubProtocol.HTTPS))
    }

    @Test
    fun `uses WSS default master from setup claims`() {
        val identity = ThalovantIdentity(
            buildJsonObject {
                put("access_key", "access")
                put("password", "secret")
                put("crypto_key", "0123456789abcdef")
                put("site_id", "site")
                put("default_master", "wss://daily-desk.thalovant.io")
                put("default_port", 443)
            },
        )

        assertEquals("wss://daily-desk.thalovant.io", identity.endpointFor(HubProtocol.WSS))
        assertEquals("https://daily-desk.thalovant.io", identity.endpointBase())
    }

    @Test
    fun `loads MQTT credentials and redacts them by default`() {
        val identity = ThalovantIdentity(
            buildJsonObject {
                put("key", "access")
                put("password", "secret")
                put("site", "site")
                put("host", "wss://hub.example.com")
                putJsonObject("mqtt") {
                    put("endpoint", "mqtts://mqtt.example.com:8883")
                    put("username", "access")
                    put("password", "broker-password")
                    put("topic_prefix", "hivemind/hub/access")
                }
            },
        )

        val mqtt = identity.mqtt
        assertNotNull(mqtt)
        assertEquals("mqtts://mqtt.example.com:8883", mqtt.endpoint)
        assertEquals("access", mqtt.username)
        assertTrue(mqtt.tls)

        val redacted = identity.asJson()["mqtt"]?.jsonObject
        assertNotNull(redacted)
        assertEquals(setOf("endpoint", "tls"), redacted.keys)
        val withSecrets = identity.asJson(includeSecrets = true)["mqtt"]?.jsonObject
        assertNotNull(withSecrets)
        assertEquals("broker-password", withSecrets["password"]?.jsonPrimitive?.content)
        assertEquals("hivemind/hub/access", withSecrets["topic_prefix"]?.jsonPrimitive?.content)
    }

    @Test
    fun `asJson hides secrets unless requested`() {
        val identity = ThalovantIdentity(
            buildJsonObject {
                put("access_key", "access")
                put("password", "secret")
                put("crypto_key", "crypto")
                put("site_id", "site")
                put("default_master", "https://hub.example.com")
            },
        )

        val redacted = identity.asJson()
        assertNull(redacted["access_key"])
        assertNull(redacted["password"])
        assertNull(redacted["crypto_key"])
        val withSecrets = identity.asJson(includeSecrets = true)
        assertEquals("access", withSecrets["access_key"]?.jsonPrimitive?.content)
        assertEquals("secret", withSecrets["password"]?.jsonPrimitive?.content)
        assertEquals("crypto", withSecrets["crypto_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `rejects payloads missing required fields`() {
        val error = assertFailsWith<ThalovantIdentityException> {
            ThalovantIdentity(buildJsonObject { put("access_key", "access") })
        }
        assertTrue("password" in error.message.orEmpty())
    }

    @Test
    fun `fromJson parses initial_identify payloads`() {
        val identity = ThalovantIdentity.fromJson(
            """
            {
              "access_key": "access",
              "password": "secret",
              "crypto_key": "0123456789abcdef",
              "site_id": "site",
              "default_port": 443,
              "default_master": "wss://hub.example.com",
              "mqtt": {
                "endpoint": "mqtts://mqtt.example.com:8883",
                "username": "access",
                "password": "broker-password",
                "topic_prefix": "hivemind/hub/access",
                "tls": true
              }
            }
            """.trimIndent(),
        )

        assertEquals("access", identity.accessKey)
        assertEquals(443, identity.defaultPort)
        assertNotNull(identity.mqtt)
    }

    @Test
    fun `loads private identity files`() {
        val dir = Files.createTempDirectory("thalovant-sdk-")
        val path = dir.resolve("_identity.json")
        try {
            Files.writeString(
                path,
                """{"access_key":"access","password":"secret","site_id":"site","default_master":"https://hub.example.com","default_port":443}""",
            )
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))

            val identity = ThalovantIdentity.fromFile(path)

            assertEquals("access", identity.accessKey)
            assertEquals("https://hub.example.com", identity.defaultMaster)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `rejects permissive identity files`() {
        val dir = Files.createTempDirectory("thalovant-sdk-")
        val path = dir.resolve("_identity.json")
        try {
            Files.writeString(
                path,
                """{"access_key":"access","password":"secret","site_id":"site","default_master":"https://hub.example.com"}""",
            )
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))

            val error = assertFailsWith<ThalovantIdentityException> { ThalovantIdentity.fromFile(path) }
            assertTrue("too permissive" in error.message.orEmpty())
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }
}
