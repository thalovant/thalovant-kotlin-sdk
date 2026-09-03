package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A hub rewrites a declared session id; replies must still be recognised.
 *
 * hivemind-core derives a Layer-1 identity for every client-declared session as
 * `{conn_nonce}:{declared}` (HIVEMIND-BRIDGE-1 §4). Comparing the returned id
 * to the sent one for equality rejected every reply: ask() timed out while the
 * hub had already answered. Reproduced against a live hub on 2026-09-03.
 */
class SessionNatTest {
    @Test
    fun `a NAT rewritten reply is recognised`() {
        assertTrue(sessionIdsMatch("my-session", "d41d8cd98f00b204:my-session"))
    }

    @Test
    fun `an unrewritten reply is still recognised`() {
        assertTrue(sessionIdsMatch("my-session", "my-session"))
    }

    @Test
    fun `a reply for a different session is rejected`() {
        assertFalse(sessionIdsMatch("my-session", "nonce:other"))
        assertFalse(sessionIdsMatch("my-session", "other"))
    }

    @Test
    fun `only the declared half after the first colon matches`() {
        // a bare endsWith would wrongly accept these
        assertFalse(sessionIdsMatch("abc", "nonce:xabc"))
        assertFalse(sessionIdsMatch("abc", "nonce:abc:def"))
        // a declared id containing a colon still matches as a whole
        assertTrue(sessionIdsMatch("a:b", "nonce:a:b"))
        assertFalse(sessionIdsMatch("abc", ""))
        assertFalse(sessionIdsMatch("abc", "nonce:"))
    }
}
