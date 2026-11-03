package com.maukingdom.musicplayer.playback

/** What to do after a playback error. */
enum class RecoveryAction {
    /** Not a file problem: leave the error showing, as before. */
    IGNORE,

    /** Skip the unplayable song and carry on with the next one. */
    SKIP_TO_NEXT,

    /** The failed song is the last one: stop there. */
    STOP,

    /** Too many songs in a row failed: stop and tell the user. */
    GIVE_UP
}

data class RecoveryDecision(
    val action: RecoveryAction,
    /** The consecutive-skip count to keep after acting. */
    val consecutiveSkips: Int
)

/**
 * Pure decisions for recovering from unplayable files and for fast queue
 * restore. No Android or Media3 types, so it is unit-tested on the JVM.
 */
object PlaybackRecovery {

    /** At most this many songs are skipped in a row before giving up. */
    const val MAX_CONSECUTIVE_SKIPS = 5

    /** A song that plays for longer than this resets the skip count. */
    const val RESET_AFTER_PLAYED_MS = 2_000L

    /** On restore, the song that plays first and at most this many after it are checked. */
    const val RESTORE_CHECKS_AFTER_SELECTED = 2

    const val GIVE_UP_NOTICE = "Several songs could not be played — check the SD card."

    // Media3 PlaybackException codes (values, so this stays free of Media3).
    private const val IO_FIRST = 2000 // ERROR_CODE_IO_UNSPECIFIED
    private const val IO_LAST = 2999
    private const val PARSING_FIRST = 3000
    private const val PARSING_LAST = 3999
    private const val DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4003
    private const val DECODING_FORMAT_UNSUPPORTED = 4005

    /**
     * True for errors caused by the file itself: missing, no permission,
     * unreadable, malformed or an unsupported format.
     */
    fun isSourceError(errorCode: Int): Boolean =
        errorCode in IO_FIRST..IO_LAST ||
            errorCode in PARSING_FIRST..PARSING_LAST ||
            errorCode == DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
            errorCode == DECODING_FORMAT_UNSUPPORTED

    /**
     * Decides how to react to a playback error with [errorCode].
     * [consecutiveSkips] is how many songs were already skipped in a row.
     */
    fun decide(errorCode: Int, hasNext: Boolean, consecutiveSkips: Int): RecoveryDecision {
        if (!isSourceError(errorCode)) {
            return RecoveryDecision(RecoveryAction.IGNORE, consecutiveSkips)
        }

        if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
            // Start counting afresh, so a later manual retry can skip again.
            return RecoveryDecision(RecoveryAction.GIVE_UP, 0)
        }

        if (!hasNext) {
            return RecoveryDecision(RecoveryAction.STOP, consecutiveSkips)
        }

        return RecoveryDecision(RecoveryAction.SKIP_TO_NEXT, consecutiveSkips + 1)
    }

    /** The skip count after a song has played for [playedMs]. */
    fun skipsAfterPlaying(consecutiveSkips: Int, playedMs: Long): Int =
        if (playedMs > RESET_AFTER_PLAYED_MS) 0 else consecutiveSkips

    /**
     * Queue positions whose files are opened during restore: the selected
     * one and at most [RESTORE_CHECKS_AFTER_SELECTED] after it. Everything
     * else is restored without being opened.
     */
    fun restoreCheckIndices(queueSize: Int, selectedIndex: Int): Set<Int> {
        if (queueSize <= 0) return emptySet()

        val first = selectedIndex.coerceIn(0, queueSize - 1)
        val last = minOf(queueSize - 1, first + RESTORE_CHECKS_AFTER_SELECTED)
        return (first..last).toSet()
    }
}
