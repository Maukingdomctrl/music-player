package com.maukingdom.musicplayer.ui

/**
 * Which app layout is shown. [id] is stored in preferences and must never
 * change once released. Both layouts share the same library, playlists,
 * playback and settings.
 */
enum class AppLayout(val id: String, val displayName: String, val description: String) {
    CLASSIC("classic", "Classic", "Retro hi-fi player"),
    MODERN("modern", "Modern", "Clean streaming-style app");

    companion object {
        /** Unknown or missing IDs fall back to [CLASSIC]. */
        fun fromId(id: String?): AppLayout = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}
