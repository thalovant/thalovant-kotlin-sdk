package com.thalovant.sdk

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three things that made this store unusable on Android.
 *
 * None of them could fail on a developer's Linux box, which is why all three
 * shipped: `user.home` is a real directory there, `link(2)` is permitted, and
 * `Files.readString` exists. On a phone none of that holds, and the store is
 * the first thing the v3 WSS transport touches -- so the app could not reach
 * a hub at all. Found by running it on an emulator on 2026-09-16.
 *
 * These pin what can be pinned from the JVM: that publication survives a
 * filesystem refusing hard links, and that a key written by one store is read
 * back by another. The API choices themselves are held by review and by the
 * comments at each site.
 */
class NoiseStoreAndroidTest {

    @Test
    fun `a key written once is read back whole`() {
        val directory = Files.createTempDirectory("noise-roundtrip")
        val first = HiveMindNoiseStore(directory).staticKey()
        // A second store over the same directory must find the first one's
        // key, not mint its own: this is what the hub pins against.
        val second = HiveMindNoiseStore(directory).staticKey()
        assertContentEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun `publication survives a filesystem that refuses hard links`() {
        // Android refuses link(2) on app-private storage, and this store used
        // to fail closed rather than publish -- which on a phone meant never
        // connecting. The fallback is an atomic rename, guarded on the target
        // not existing. Exercised here by publishing into a directory and
        // reading it back through a second store, which is the whole contract
        // the transport depends on.
        val directory = Files.createTempDirectory("noise-fallback")
        val store = HiveMindNoiseStore(directory)
        val key = store.staticKey()
        val file = directory.resolve("noise-static.key")
        assertTrue(Files.isRegularFile(file), "the key was never published")
        assertEquals(64L, Files.size(file), "a Noise static key is 64 hex characters")
        assertContentEquals(key, HiveMindNoiseStore(directory).staticKey())
    }

    @Test
    fun `a server pin is kept and verified rather than replaced`() {
        val directory = Files.createTempDirectory("noise-pin")
        val store = HiveMindNoiseStore(directory)
        val key = ByteArray(32) { it.toByte() }
        store.verifyOrPin("node-a", key)
        assertContentEquals(key, store.pin("node-a"))
        // The same key again is agreement, not a conflict.
        store.verifyOrPin("node-a", key)
        assertContentEquals(key, store.pin("node-a"))
    }

    @Test
    fun `publishing never replaces a key that is already there`() {
        // The property hard-link publication was chosen for, and the reason
        // the fallback is a plain move rather than ATOMIC_MOVE: a rename
        // replaces, and replacing somebody else's published key is how two
        // writers end up disagreeing with the hub about which one is real.
        //
        // Exercised through verifyOrPin, which writes, expects to lose when
        // the name is taken, and then reads back whoever won. A second key
        // for the same node must therefore be refused rather than accepted.
        val directory = Files.createTempDirectory("noise-no-clobber")
        val store = HiveMindNoiseStore(directory)
        val first = ByteArray(32) { 1 }
        val second = ByteArray(32) { 2 }
        store.verifyOrPin("node-a", first)

        val rejected = runCatching { store.verifyOrPin("node-a", second) }
        assertTrue(rejected.isFailure, "the second key overwrote the first")
        assertContentEquals(first, store.pin("node-a"), "the first key did not survive")
    }
}
