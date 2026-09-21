package com.thalovant.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The tag to retry a listing with, when the hub had nothing under the one
 * that was asked for.
 *
 * The answers here are CLDR's, not this SDK's, and they must be the same ones
 * the Python reference gives: a managed port that disagrees about which
 * language to retry would list a different hub.
 */
class UsualFormTest {

    @Test
    fun `a regional tag becomes the form skills actually register`() {
        assertEquals("en-us", usualForm("en-CA"))
        assertEquals("en-us", usualForm("en-AT"))
        assertEquals("fr-fr", usualForm("fr-BE"))
        assertEquals("pt-br", usualForm("pt-AO"))
        assertEquals("de-de", usualForm("de-AT"))
    }

    @Test
    fun `a tag already in its usual form has nothing to retry with`() {
        // Null rather than the same tag, so a caller can tell "already right"
        // from "no idea" -- and so a hub that answered is never asked twice.
        // Only byte-for-byte the usual form. The CAPITAL spelling is a
        // different string to a manifest keyed `en-us`, and suppressing its
        // retry was the bug.
        assertEquals("en-us", usualForm("en-US"))
        assertEquals("fr-fr", usualForm("fr-FR"))
        assertNull(usualForm("en-us"))
        assertNull(usualForm("fr-fr"))
    }

    @Test
    fun `a language nobody has heard of is a null and not a guess`() {
        // `maximize` does not fail on an unknown language, it answers out of
        // the root locale. Left ungeared, "zzz" comes back a confident
        // United States for a language that does not exist, and the retry is
        // a wasted round trip at best.
        assertNull(usualForm("zzz"))
        assertNull(usualForm(""))
        assertNull(usualForm("xx-YY"))
    }

    @Test
    fun `it answers in lower case, because that is how a manifest is keyed`() {
        // Skills register `en-us`. The manifest lookup is exact, so a retry
        // in BCP47's `en-US` finds nothing, which is the failure this ends.
        assertEquals(usualForm("en-CA"), usualForm("en-CA")?.lowercase())
        assertEquals("pt-br", usualForm("pt-PT"))
    }
}
