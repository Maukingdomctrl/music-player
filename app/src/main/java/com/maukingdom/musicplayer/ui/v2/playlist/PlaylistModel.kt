package com.maukingdom.musicplayer.ui.v2.playlist

import com.maukingdom.musicplayer.ui.v2.blendOver
import com.maukingdom.musicplayer.ui.v2.home.songCountLabel
import com.maukingdom.musicplayer.ui.v2.hslToRgb
import com.maukingdom.musicplayer.ui.v2.relativeLuminance
import com.maukingdom.musicplayer.ui.v2.rgbToHsl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Labels, ordering and colour for the Modern Playlist screen. Pure (no
 * Android or Compose runtime), so it is unit tested.
 */

/** "3 h 05 min", "45 min", "1 min"; "0 min" when empty or unknown. Negative durations are ignored. */
fun formatTotalDuration(durationsMs: List<Long>): String {
    val totalMs = durationsMs.sumOf { it.coerceAtLeast(0L) }
    var minutes = (totalMs + 30_000L) / 60_000L
    // Anything playable counts as at least a minute.
    if (totalMs > 0L && minutes == 0L) minutes = 1L
    val hours = minutes / 60L
    val rest = minutes % 60L
    return if (hours > 0L) "$hours h ${rest.toString().padStart(2, '0')} min" else "$minutes min"
}

/** "SD folder · 42 songs · 3 h 05 min" or "Playlist · 1 song · 4 min"; no duration while empty. */
fun playlistHeaderMeta(isFolder: Boolean, durationsMs: List<Long>): String {
    val kind = if (isFolder) "SD folder" else "Playlist"
    val count = songCountLabel(durationsMs.size)
    return if (durationsMs.isEmpty()) "$kind · $count" else "$kind · $count · ${formatTotalDuration(durationsMs)}"
}

const val NEVER_SYNCED = "Never synced"

/**
 * "Synced just now / 5 min ago / 2 h ago / yesterday / 12 Oct" for a sync at
 * [syncedAtMs] seen at [nowMs]. Older than this year adds the year. A time
 * in the future (clock change) counts as just now.
 */
fun syncedLabel(
    syncedAtMs: Long?,
    nowMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault()
): String {
    if (syncedAtMs == null) return NEVER_SYNCED
    val elapsed = nowMs - syncedAtMs
    if (elapsed < 60_000L) return "Synced just now"
    if (elapsed < 60 * 60_000L) return "Synced ${elapsed / 60_000L} min ago"

    val synced = Calendar.getInstance(timeZone, locale).apply { timeInMillis = syncedAtMs }
    val now = Calendar.getInstance(timeZone, locale).apply { timeInMillis = nowMs }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    fun sameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    return when {
        sameDay(synced, now) -> "Synced ${elapsed / (60 * 60_000L)} h ago"
        sameDay(synced, yesterday) -> "Synced yesterday"
        else -> {
            val pattern = if (synced.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "d MMM" else "d MMM yyyy"
            val format = SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }
            "Synced ${format.format(Date(syncedAtMs))}"
        }
    }
}

/**
 * [songIds] with the song at [index] moved by [delta] (-1 up, +1 down), or
 * null when that would leave the list (so the menu item is disabled).
 */
fun moveSong(songIds: List<String>, index: Int, delta: Int): List<String>? {
    val target = index + delta
    if (index !in songIds.indices || target !in songIds.indices || delta == 0) return null
    return songIds.toMutableList().apply { add(target, removeAt(index)) }
}

/** Above this luminance the wash is mixed toward the background. */
const val WASH_MAX_LUMINANCE = 0.35

/**
 * The top colour of the Playlist wash for a cover whose average colour is
 * [averageArgb]: visibly coloured (saturation 45–75 % unless the cover is
 * grey, lightness 28–50 %), then mixed toward [bgArgb] until its luminance
 * is at most [WASH_MAX_LUMINANCE].
 */
fun playlistWashColor(averageArgb: Int, bgArgb: Int): Int {
    val (h, s, l) = rgbToHsl(averageArgb)
    val sat = if (s < 0.08f) s else s.coerceIn(0.45f, 0.75f)
    val start = hslToRgb(h, sat, l.coerceIn(0.28f, 0.5f))
    var colour = start
    var towardBg = 0f
    while (relativeLuminance(colour) > WASH_MAX_LUMINANCE && towardBg < 1f) {
        towardBg = (towardBg + 0.05f).coerceAtMost(1f)
        colour = blendOver(start, 1f - towardBg, bgArgb)
    }
    return colour
}

/** The darker middle stop of the wash (~30 % down the screen), where the header text starts. */
fun playlistWashMid(washArgb: Int, bgArgb: Int): Int = blendOver(washArgb, 0.45f, bgArgb)

/** No cover colour yet: the skin accent mixed 30 % into the background. */
fun playlistWashFallback(accentArgb: Int, bgArgb: Int): Int = blendOver(accentArgb, 0.30f, bgArgb)
