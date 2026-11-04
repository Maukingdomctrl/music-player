package com.maukingdom.musicplayer

import com.maukingdom.musicplayer.ui.AppLayout
import com.maukingdom.musicplayer.ui.v2.clampFontScale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLayoutTest {

    @Test
    fun idsRoundTrip() {
        for (layout in AppLayout.entries) {
            assertEquals(layout, AppLayout.fromId(layout.id))
        }
        assertEquals("classic", AppLayout.CLASSIC.id)
        assertEquals("modern", AppLayout.MODERN.id)
    }

    @Test
    fun unknownOrMissingIsClassic() {
        assertEquals(AppLayout.CLASSIC, AppLayout.fromId(null))
        assertEquals(AppLayout.CLASSIC, AppLayout.fromId(""))
        assertEquals(AppLayout.CLASSIC, AppLayout.fromId("MODERN"))
        assertEquals(AppLayout.CLASSIC, AppLayout.fromId("v3"))
    }
}

class FontScaleTest {

    @Test
    fun clampsToNinetyPercentUpToOneThirty() {
        assertEquals(0.9f, clampFontScale(0.8f), 0f) // this phone
        assertEquals(1.0f, clampFontScale(1.0f), 0f)
        assertEquals(1.3f, clampFontScale(1.5f), 0f)
        assertEquals(0.9f, clampFontScale(0.9f), 0f)
        assertEquals(1.15f, clampFontScale(1.15f), 0f)
        assertEquals(1.3f, clampFontScale(2.0f), 0f)
    }
}
