package com.thalovant.sdk

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PythonParityTest {
    @Test fun `configuration snapshots caller backed JSON maps before reads`() = runBlocking {
        val configMap = mutableMapOf<String, JsonElement>("value" to JsonPrimitive("original"))
        val personaMap = mutableMapOf<String, JsonElement>("name" to JsonPrimitive("original"))
        val config = buildJsonObject { put("nested", JsonObject(configMap)) }
        val personas = buildJsonObject { put("default", JsonObject(personaMap)) }
        MockWebServer().use { server ->
            server.start()
            val http = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().method == "GET") {
                    configMap["value"] = JsonPrimitive("changed")
                    personaMap["name"] = JsonPrimitive("changed")
                }
                chain.proceed(chain.request())
            }.build()
            val api = ThalovantControlPlane(server.url("/").toString(), accessToken="test", httpClient=http)
            server.enqueue(reply("{\"config\":{},\"revision\":\"${"a".repeat(64)}\"}"))
            server.enqueue(reply("{}",412))
            server.enqueue(reply("{\"config\":{},\"revision\":\"${"b".repeat(64)}\"}"))
            server.enqueue(reply("{}"))
            try {
                api.updateRuntimeGroupConfig("g",config,personas)
                repeat(2) {
                    assertEquals("GET",server.takeRequest().method)
                    val put=server.takeRequest(); assertEquals("PUT",put.method)
                    val body=obj(put.body.readUtf8())
                    assertEquals("original",body["config"]!!.jsonObject["nested"]!!.jsonObject["value"]!!.jsonPrimitive.content)
                    assertEquals("original",body["personas"]!!.jsonObject["default"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                }
            } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        }
    }
    @Test fun `maximum size audio avoids recursive matching`() {
        val clip = "a5".repeat(MAX_AUDIO_CLIP_BYTES)
        val bytes = ThalovantEvent(ThalovantEvents.AUDIO_QUEUE, buildJsonObject { put("binary_data", clip) }).audioBytes()
        assertEquals(MAX_AUDIO_CLIP_BYTES, bytes.size)
        assertTrue(bytes.all { it == 0xa5.toByte() })
        assertFailsWith<IllegalArgumentException> {
            ThalovantEvent(ThalovantEvents.AUDIO_QUEUE, buildJsonObject { put("binary_data", clip.dropLast(1) + "g") }).audioBytes()
        }
    }
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun reply(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setBody(body).setHeader("Content-Type", "application/json")
    @Test fun `request hints and location preserve callers`() {
        val base = obj("""{"session":{"pipeline":["old"],"session_id":"kept"}}""")
        val location = buildLocation(city=" Montréal ",country=" ca ",latitude=45.5,longitude=-73.5)!!
        val result = requestContext(base, " fr ",listOf(" ","intent"),location)!!
        assertEquals("fr",result["stt_lang"]!!.jsonPrimitive.content)
        assertEquals("CA",location["country_code"]!!.jsonPrimitive.content)
        assertEquals("old",base["session"]!!.jsonObject["pipeline"]!!.jsonArray[0].jsonPrimitive.content)
        assertNull(requestContext()); assertNull(buildLocation())
        for ((lat,lon) in listOf(0.0 to 0.0,91.0 to 0.0,0.0 to 181.0,Double.NaN to 1.0)) assertNull(buildLocation(city="Toronto",latitude=lat,longitude=lon)!!["coordinate"])
    }
    @Test fun `speakable examples retain source priority and duplicate best rank`() {
        assertEquals("did i ask about thing", speakable("did i (already |)ask (about|for|to|) {thing}"))
        val intent = HubIntent("x","x","padatious",mapOf("en-us" to listOf("{x}","a complete sentence","[please]","(x|y)","x")))
        assertEquals(listOf("a complete sentence","x"),intent.examplesWithOptions("en-us",2,true))
    }
    @Test fun `audio admission preserves Python encoded budgets and delivery identity`() {
        val first=ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,obj("{\"binary_data\":\"00\"}"))
        val second=ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,obj("{\"binary_data\":\"00\"}"))
        val budget=ReplyMediaBudget()
        assertTrue(budget.accept(first));assertTrue(budget.accept(second));assertFalse(budget.accept(first));assertEquals(0,budget.dropped)
        val malformed=ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,obj("{\"binary_data\":\"gg\"}"))
        assertTrue(budget.accept(malformed)) // Bounded event metadata remains inspectable.
        assertFailsWith<IllegalArgumentException> { malformed.audioBytes() }
        assertTrue(ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,buildJsonObject { put("binary_data"," \t") }).audioBytes().isEmpty())
        assertFailsWith<IllegalArgumentException> { ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,buildJsonObject { put("binary_data","00 ") }).audioBytes(1) }
    }
    @Test fun `embedded audio is strict bounded and ordered`() {
        val event = ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,obj("""{"binary_data":"00 ff\n10","lang":"fr"}"""))
        assertContentEquals(byteArrayOf(0,-1,16),event.audioBytes());assertEquals("fr",event.lang)
        for (encoded in listOf("","0","0 0","gg","https://example.com","00\u00a0ff")) assertFailsWith<IllegalArgumentException> { ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,buildJsonObject { put("binary_data",encoded) }).audioBytes() }
        val budget=ReplyMediaBudget(); assertTrue(budget.accept(event));assertFalse(budget.accept(event))
        val clip="00".repeat(MAX_AUDIO_CLIP_BYTES)
        repeat(4) { budget.accept(ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,buildJsonObject { put("binary_data",clip) })) }
        budget.accept(ThalovantEvent(ThalovantEvents.AUDIO_QUEUE,buildJsonObject { put("binary_data",clip+"00") }))
        assertEquals(2,budget.dropped)
        val result=ThalovantReply("hello",listOf("hello"),true,true,null,null,listOf(event,ThalovantEvent(ThalovantEvents.SPEAK)),null,budget.dropped)
        assertTrue(result.hasAudio);assertEquals("fr",result.lang);assertEquals(2,result.mediaEvents.size)
    }
    @Test fun `configuration conflicts re-read and preserve concurrent keys`() = runBlocking {
        MockWebServer().use { server ->
            server.start();val api=ThalovantControlPlane(server.url("/").toString(),accessToken="test")
            server.enqueue(reply("""{"config":{"nested":{"original":true}},"revision":"${"a".repeat(64)}"}"""))
            server.enqueue(reply("{}",412))
            server.enqueue(reply("""{"config":{"nested":{"original":true,"concurrent":true}},"revision":"${"b".repeat(64)}"}"""))
            server.enqueue(reply("{}"))
            val delta=obj("""{"nested":{"caller":true},"array":[1]}""")
            api.updateRuntimeGroupConfig("x/y",delta,EMPTY_JSON_OBJECT)
            val requests=(1..4).map { server.takeRequest() }
            assertEquals(listOf("GET","PUT","GET","PUT"),requests.map{it.method})
            assertEquals("/v1/runtime-groups/x%2Fy/config",requests[0].path)
            val body=obj(requests[3].body.readUtf8())
            assertEquals(obj("""{"nested":{"original":true,"concurrent":true,"caller":true},"array":[1]}"""),body["config"])
            assertEquals(EMPTY_JSON_OBJECT,body["personas"]);assertEquals(1,delta["nested"]!!.jsonObject.size)
        }
    }
    @Test fun `configuration retries only preconditions and fails closed on older servers`() = runBlocking {
        for(status in listOf(400,401,403,405,409,412,429,500)) MockWebServer().use { server ->
            server.start();val api=ThalovantControlPlane(server.url("/").toString(),accessToken="test")
            val attempts=if(status==412)3 else 1
            repeat(attempts){ server.enqueue(reply("""{"config":{},"revision":"${"a".repeat(64)}"}"""));server.enqueue(reply("{}",status)) }
            assertEquals(status,assertFailsWith<ThalovantApiException>{api.updateRuntimeGroupConfig("x",EMPTY_JSON_OBJECT)}.statusCode)
            assertEquals(attempts*2,server.requestCount)
        }
        for(body in listOf("""{"config":{}}""","""{"config":{},"revision":"bad"}""","""{"config":[],"revision":"${"a".repeat(64)}"}""")) MockWebServer().use { server ->
            server.start();server.enqueue(reply(body));val api=ThalovantControlPlane(server.url("/").toString(),accessToken="test")
            assertFailsWith<ThalovantApiException>{api.updateRuntimeGroupConfig("x",EMPTY_JSON_OBJECT)}
            assertEquals(1,server.requestCount)
        }
    }
}
