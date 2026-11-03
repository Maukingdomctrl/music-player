package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.playback.ArtworkAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkAccessTest {

    private val myUid = 10_106
    private val otherUid = 10_250

    @Test
    fun thisAppAndTheSystemAreAllowed() {
        assertTrue(ArtworkAccess.isAllowed(myUid, myUid, emptyList()))
        assertTrue(ArtworkAccess.isAllowed(1000, myUid, emptyList()))
    }

    @Test
    fun eachListedMediaClientIsAllowed() {
        for (pkg in listOf(
            "com.google.android.projection.gearhead",
            "com.android.systemui",
            "com.android.bluetooth",
            "com.samsung.android.app.aodservice",
            "com.sec.android.app.launcher"
        )) {
            assertTrue(pkg, ArtworkAccess.isAllowed(otherUid, myUid, listOf(pkg)))
        }

        assertEquals(5, ArtworkAccess.ALLOWED_PACKAGES.size)
    }

    @Test
    fun aSharedUidIsAllowedWhenAnyOfItsPackagesIs() {
        assertTrue(
            ArtworkAccess.isAllowed(1002, myUid, listOf("com.android.nfc", "com.android.bluetooth"))
        )
    }

    @Test
    fun otherAppsAreRefused() {
        assertFalse(ArtworkAccess.isAllowed(otherUid, myUid, listOf("com.example.prober")))
        assertFalse(ArtworkAccess.isAllowed(otherUid, myUid, emptyList()))
        // Exact names only: no prefix or look-alike matches.
        assertFalse(ArtworkAccess.isAllowed(otherUid, myUid, listOf("com.android.systemui.evil")))
        assertFalse(ArtworkAccess.isAllowed(otherUid, myUid, listOf("com.google.android.projection")))
        assertFalse(ArtworkAccess.isAllowed(otherUid, myUid, listOf("COM.ANDROID.SYSTEMUI")))
    }

    @Test
    fun pipeWriteDeadline() {
        assertEquals(10_000L, ArtworkAccess.remainingMs(nowMs = 500L, startedAtMs = 500L, timeoutMs = 10_000L))
        assertEquals(1L, ArtworkAccess.remainingMs(nowMs = 10_499L, startedAtMs = 500L, timeoutMs = 10_000L))
        assertEquals(0L, ArtworkAccess.remainingMs(nowMs = 10_500L, startedAtMs = 500L, timeoutMs = 10_000L))
        assertEquals(0L, ArtworkAccess.remainingMs(nowMs = 99_999L, startedAtMs = 500L, timeoutMs = 10_000L))
    }
}
