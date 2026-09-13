package com.thalovant.sdk


import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private class SessionTransport : HiveMindRuntimeTransport {
    override var connected = false
    override val handshakeComplete get() = connected
    override val connectionInfo get() = ThalovantConnectionInfo(if(connected)"ready" else "closed")
    var closes=0;var emissions=0;var fail=false;var rejectListener=false
    var block: (suspend ()->Unit)? = null
    val listeners=CopyOnWriteArrayList<(ThalovantEvent)->Unit>()
    override suspend fun connect(timeoutMs: Long) { connected=true }
    override suspend fun disconnect() { connected=false;closes++ }
    override fun addBusListener(listener: (ThalovantEvent)->Unit): ThalovantSubscription {
        if(rejectListener)throw IllegalStateException("listener refused")
        listeners.add(listener);return ThalovantSubscription {listeners.remove(listener)}
    }
    override suspend fun emitBus(eventType: String,data: JsonObject,context: JsonObject) {
        emissions++;block?.invoke()
        if(fail)throw ThalovantConnectionException("response lost")
        if(eventType==ThalovantEvents.RECOGNIZER_LOOP_UTTERANCE)listeners.forEach {it(ThalovantEvent(ThalovantEvents.SPEAK,buildJsonObject {put("utterance","ok")},context))}
    }
    fun deliver(name: String) {listeners.forEach {it(ThalovantEvent(name))}}
}
class SessionInventoryTest {
    @Test fun `cancelled preferred origin does not fall back after an IO failure`() = runBlocking {
        val preference = OriginPreference("10.0.0.2")
        var attempts = 0
        launch {
            preference.connect(OriginAttempt("hub.example",12000)) {
                attempts++
                currentCoroutineContext().cancel()
                throw java.io.IOException("interrupted dial")
            }
        }.join()
        assertEquals(1,attempts)
        assertFalse(preference.coolingDown)
    }

    @Test fun `shared Python reference survives sorted JSON`() {
        val raw = javaClass.getResourceAsStream("/thalovant/inventory-vectors.json")!!.bufferedReader().use { it.readText() }
        val data = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val inventory = Inventory.fromJson(data.getValue("inventory").toString())
        for (row in data.getValue("examples").jsonArray.map { it.jsonObject }) {
            assertEquals(row.getValue("expected").jsonArray.map { it.jsonPrimitive.content },inventory.intents.first().examples(row["language"]?.jsonPrimitive?.contentOrNull,row.getValue("limit").jsonPrimitive.int))
        }
        for (row in data.getValue("speaks").jsonArray.map { it.jsonObject }) assertEquals(row.getValue("expected").jsonPrimitive.boolean,inventory.skills.first().speaks(row.getValue("language").jsonPrimitive.content))
        assertNull(inventory.skills[1].speaks("en"))
    }

    private fun client(fake: SessionTransport) = ThalovantClient(ThalovantIdentity(Json.parseToJsonElement("""{"access_key":"access","password":"password","crypto_key":"0123456789abcdef","site_id":"site","default_master":"wss://hub.example"}""").jsonObject),transport=fake,replySettleMs=0,emptyReplyWaitMs=0)
    @Test fun `ambiguous calls never replay and subscriptions follow rebuilds`() = runBlocking {
        val first=SessionTransport().also {it.fail=true};val second=SessionTransport();var attempts=0;var received=0
        val session=HubSession(connect={client(if(++attempts==1)first else second).also {it.connect()}},warm=false)
        val subscription=session.on("event") {received++}
        assertFailsWith<ThalovantConnectionException> {session.ask("action")}
        assertEquals(1,attempts);assertEquals(1,first.emissions);assertFalse(session.held)
        assertEquals("ok",session.ask("status").text);second.deliver("event");assertEquals(1,received)
        subscription.close();second.deliver("event");assertEquals(1,received)
        session.close();assertFailsWith<IllegalStateException> {session.ask("again")}
    }
    @Test fun `shutdown waits for admitted publication`() = runBlocking {
        val fake=SessionTransport();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        fake.block={entered.complete(Unit);release.await();assertEquals(0,fake.closes)}
        val session=HubSession(connect={client(fake).also {it.connect()}},warm=false)
        val call=async {session.emit("event")};entered.await();val close=async {session.close()}
        yield();assertFalse(close.isCompleted);release.complete(Unit);call.await();close.await();assertEquals(1,fake.closes)
    }
    @Test fun `unattended attempts back off but foreground calls do not`() = runBlocking {
        var now=100.0;var attempts=0
        val session=HubSession(connect={attempts++;throw ThalovantConnectionException("offline")},clock={now},warm=false)
        session.warm()!!.join();assertEquals(110.0,session.retryAt);assertEquals(20.0,session.retryWait)
        now=105.0;assertNull(session.warm());assertEquals(1,attempts)
        assertFailsWith<ThalovantConnectionException> {session.ask("hi")};assertEquals(2,attempts);session.close()
    }
    @Test fun `failed replay retires the fresh client`() = runBlocking {
        val fake=SessionTransport().also {it.rejectListener=true}
        val session=HubSession(connect={client(fake).also {it.connect()}},clock={100.0},warm=false)
        session.on("event") {};assertFailsWith<IllegalStateException> {session.ask("hi")}
        assertEquals(1,fake.closes);assertFalse(session.held);assertEquals(110.0,session.retryAt);session.close()
    }
    private fun inventory()=Inventory("hub-1","Example","hub","2026-09-13",listOf(Skill("weather","Weather",listOf("en-US","fr-FR"),listOf(Intent("a","current.weather","weather","padatious",linkedMapOf("fr-FR" to listOf("quel temps fait-il"),"en-US" to listOf("weather in","what is the weather")))))),listOf("from the hub"))
    @Test fun `inventory preserves phrase order locale knowledge and helpers`() {
        val value=inventory();assertEquals(value,Inventory.fromJson(value.asJson()))
        assertEquals(listOf("quel temps fait-il"),Inventory.fromJson(value.asJson()).intents[0].examples(limit=1))
        assertEquals(listOf("what is the weather"),value.intents[0].examples("en-GB",1));assertEquals(true,value.skills[0].speaks("fr-CA"));assertNull(Skill("x","X").speaks("en"))
        assertEquals(listOf("en-US","fr-FR"),languagesPresent(value));assertTrue(value.live&&value.hasPhrases)
        assertEquals("Custos Query",friendlyTitle("thalovant-skill-custos-query.thalovant"));assertEquals("suffix" to "weather",commonAffix(listOf("current.weather","high_low.weather")));assertEquals("high low",stripAffix("high_low.weather","suffix","weather"))
        assertEquals(listOf("Intent1","intent2","intent10"),listOf("intent10","intent2","Intent1").sortedWith(::compareNames))
    }
    @Test fun `cache concurrent writes remain complete private and contained`() {
        val directory=Files.createTempDirectory("thalovant-inventory-");val cache=InventoryCache(directory)
        try {
            val pool=Executors.newFixedThreadPool(4)
            try {pool.invokeAll((1..16).map {Callable {cache.store("safe",inventory())}}).forEach {it.get()}} finally {pool.shutdownNow()}
            assertEquals(inventory(),cache.load("safe"));assertNull(cache.load("x/../../../outside"))
            cache.store("x/../../../outside",inventory());assertNull(cache.load("x/../../../outside"))
            Files.setLastModifiedTime(cache.path("safe"),FileTime.fromMillis(System.currentTimeMillis()-7200000));assertNull(cache.load("safe"))
        } finally {directory.toFile().deleteRecursively()}
    }
    @Test fun `origin cools down after a failed preferred attempt`() = runBlocking {
        var now=0.0;val origin=OriginPreference("127.0.0.2",clock={now});val attempts=mutableListOf<String?>()
        suspend fun build(attempt: OriginAttempt): String {attempts.add(attempt.address);if(attempt.address!=null)throw ThalovantConnectionException("offline");return "public"}
        val options=OriginAttempt("hub.example",6000)
        assertEquals("public",origin.connect(options,::build));origin.connect(options,::build);assertEquals(listOf("127.0.0.2",null,null),attempts);now=301.0;assertFalse(origin.coolingDown)
    }
}
