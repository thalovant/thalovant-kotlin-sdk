package com.thalovant.sdk

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Tag

/** Explicit opt-in loopback integration against the pinned independent Node implementation. */
@Tag("interop")
class IndependentInteropTest {
    @Test fun `independent Node accepts XX then KK and correlated query frames`() = runBlocking {
        val endpoint = requireNotNull(System.getProperty("interop.endpoint"))
        require(endpoint.startsWith("ws://127.0.0.1:")) { "Only explicit loopback endpoints are accepted." }
        val directory = Files.createTempDirectory("kotlin-interop")
        val identity = ThalovantIdentity(buildJsonObject {
            put("access_key", "fixture"); put("password", "fixture-password"); put("default_master", endpoint); put("site_id", "fixture")
        })
        val sdk = ThalovantClient(identity, noiseStore = HiveMindNoiseStore(directory))
        val received = AtomicInteger()
        val replies = sdk.on("fixture.pong") { received.incrementAndGet() }
        try {
            repeat(2) { attempt ->
                coroutineScope { (0..2).map { n -> async { sdk.connect(20000); sdk.emit("fixture.ping", buildJsonObject { put("n", n) }) } }.awaitAll() }
                withTimeout(5000) { while (received.get() != (attempt + 1) * 3) delay(10) }
                assertTrue(sdk.healthcheck().ok)
                assertEquals("query answer", sdk.query("hello").text)
                sdk.close()
                if (attempt == 0) withTimeout(5000) {
                    val http = OkHttpClient()
                    try {
                        while (true) {
                            val closed = withContext(Dispatchers.IO) {
                                http.newCall(Request.Builder().url(endpoint.replaceFirst("ws://", "http://") + "/fixture/status").build()).execute().use {
                                    ThalovantJson.parseToJsonElement(it.body!!.string()).jsonObject["closed"]!!.jsonPrimitive.int
                                }
                            }
                            if (closed == 1) break
                            delay(10)
                        }
                    } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
                }
            }
        } finally { replies.close(); sdk.close(); directory.toFile().deleteRecursively() }
    }
}
