package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A hub substitutes its own session id; the request id is what correlates.
 *
 * Observed against a live hub on 2026-09-03: a client declaring
 * `session_id="observe-me"` gets every reply back carrying the hub's own uuid.
 * Comparing session ids rejected replies the request id had already identified
 * as ours, so ask() timed out while the hub had answered.
 *
 * The filter under test lives inline in Client.addBusListener; this pins the
 * decision table it implements.
 */
class SessionNatTest {
    private fun accepts(
        askedSession: String?, askedRequest: String?,
        replySession: String?, replyRequest: String?,
    ): Boolean =
        if (askedRequest != null && replyRequest != null) replyRequest == askedRequest
        else !(askedSession != null && replySession != null && replySession != askedSession)

    @Test
    fun `a matching request id wins over a substituted session`() {
        assertEquals(true, accepts("observe-me", "req-1", "71048b7f-e7b0", "req-1"))
    }

    @Test
    fun `a wrong request id is rejected even if sessions agree`() {
        assertEquals(false, accepts("same", "req-1", "same", "req-2"))
    }

    @Test
    fun `without request ids the session still decides`() {
        assertEquals(true, accepts("s1", null, "s1", null))
        assertEquals(false, accepts("s1", null, "s2", null))
    }

    @Test
    fun `a reply without a request id falls back to the session`() {
        assertEquals(true, accepts("s1", "req-1", "s1", null))
        assertEquals(false, accepts("s1", "req-1", "other", null))
    }
}
