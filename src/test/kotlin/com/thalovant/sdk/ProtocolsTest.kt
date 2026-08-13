package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ProtocolsTest {
    @Test
    fun `wss is enabled by default`() {
        val settings = HubProtocolSettings.from(null)
        assertTrue(settings.wss)
        assertFalse(settings.http)
        assertFalse(settings.mqtt)
        assertEquals(listOf(HubProtocol.WSS), settings.enabledProtocols())
    }

    @Test
    fun `parses spec protocols enabled flags`() {
        val settings = HubProtocolSettings.from(
            buildJsonObject {
                putJsonObject("spec") {
                    putJsonObject("protocols") {
                        putJsonObject("wss") { put("enabled", false) }
                        putJsonObject("http") { put("enabled", true) }
                        putJsonObject("mqtt") { put("enabled", "yes") }
                    }
                }
            },
        )
        assertFalse(settings.wss)
        assertTrue(settings.http)
        assertTrue(settings.https)
        assertTrue(settings.mqtt)
    }

    @Test
    fun `derives data plane endpoints from a hub resource`() {
        val endpoints = HubDataPlaneEndpoints.fromHub(
            buildJsonObject {
                put("domain", "jokes.thalovant.io")
                putJsonObject("spec") {
                    putJsonObject("protocols") {
                        putJsonObject("wss") { put("enabled", true) }
                        putJsonObject("http") { put("enabled", true) }
                        putJsonObject("mqtt") { put("enabled", false) }
                    }
                }
            },
        )

        assertEquals("wss://jokes.thalovant.io", endpoints.wss)
        assertEquals("https://jokes.thalovant.io", endpoints.https)
        assertNull(endpoints.mqtt)
    }

    @Test
    fun `selectDataPlaneEndpoint honors the preference order`() {
        val selected = selectDataPlaneEndpoint(
            HubDataPlaneEndpoints(
                https = "https://hub.example.com/public",
                wss = "wss://hub.example.com/public",
            ),
            HubProtocolSettings(wss = true, http = true),
            listOf(HubProtocol.MQTT, HubProtocol.WSS, HubProtocol.HTTPS),
        )

        assertEquals(SelectedHubEndpoint(HubProtocol.WSS, "wss://hub.example.com/public"), selected)
    }

    @Test
    fun `default preference is wss then https then mqtt`() {
        assertEquals(
            listOf(HubProtocol.WSS, HubProtocol.HTTPS, HubProtocol.MQTT),
            DEFAULT_PROTOCOL_PREFERENCE,
        )
        val selected = selectDataPlaneEndpoint(
            HubDataPlaneEndpoints(
                https = "https://hub.example.com",
                wss = "wss://hub.example.com",
                mqtt = "mqtts://mqtt.example.com:8883",
            ),
            HubProtocolSettings(wss = true, http = true, mqtt = true),
        )
        assertEquals(HubProtocol.WSS, selected?.protocol)
    }

    @Test
    fun `skips disabled protocols during selection`() {
        val selected = selectDataPlaneEndpoint(
            HubDataPlaneEndpoints(
                https = "https://hub.example.com",
                wss = "wss://hub.example.com",
            ),
            HubProtocolSettings(wss = false, http = true),
        )
        assertEquals(SelectedHubEndpoint(HubProtocol.HTTPS, "https://hub.example.com"), selected)
    }

    @Test
    fun `endpointFromDomain converts schemes`() {
        assertEquals("wss://jokes.thalovant.io", endpointFromDomain("jokes.thalovant.io", HubProtocol.WSS))
        assertEquals("wss://jokes.thalovant.io", endpointFromDomain("https://jokes.thalovant.io/", HubProtocol.WSS))
        assertEquals("https://jokes.thalovant.io", endpointFromDomain("wss://jokes.thalovant.io", HubProtocol.HTTPS))
        assertEquals("https://jokes.thalovant.io", endpointFromDomain("http://jokes.thalovant.io", HubProtocol.HTTPS))
        assertNull(endpointFromDomain("jokes.thalovant.io", HubProtocol.MQTT))
    }
}
