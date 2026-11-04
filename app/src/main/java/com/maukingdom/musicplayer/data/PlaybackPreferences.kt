package com.maukingdom.musicplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_preferences"
)

/**
 * The saved queue lives in its own small file. DataStore rewrites a whole
 * file on every edit, so keeping a long queue apart means the frequent
 * position checkpoints no longer rewrite it.
 */
private val Context.playbackQueueDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_queue"
)

/**
 * Reading saved queues across storage versions. Pure, for JVM tests.
 *
 * Older versions kept the queue in the main preferences file. The queue
 * file wins once it has been written; until then the old value is used.
 */
internal object SnapshotCompat {
    fun pick(queueFileValue: String?, legacyValue: String?): String? =
        queueFileValue ?: legacyValue
}

/**
 * The queue contains SongEntity IDs in normal queue order.
 *
 * shuffleOrder contains IDs in the actual shuffled traversal order when that
 * order is available. A repeated ID in the normal queue is permitted; the
 * playback layer is responsible for validating any saved traversal order
 * against the queue it can restore.
 */
data class PlaybackSnapshot(
    val queueSongIds: List<String> = emptyList(),
    val selectedIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = REPEAT_OFF,
    val shuffleOrder: List<String> = emptyList()
) {
    companion object {
        const val REPEAT_OFF = 0
        const val REPEAT_ONE = 1
        const val REPEAT_ALL = 2
    }
}

data class AppPreferences(
    val carModeEnabled: Boolean = false,
    /** Stored skin ID; null when the user has never chosen one. */
    val playerSkinId: String? = null,
    /** Bass and treble in whole dB, -6..+6. */
    val bassDb: Int = 0,
    val trebleDb: Int = 0,
    /** Stored layout ID ("classic"/"modern"); null means Classic. */
    val appLayoutId: String? = null,
    /** Modern only: tint the Studio player with the cover's colour instead of the skin's. */
    val useCoverColour: Boolean = true
)

/**
 * Stores playback and app preferences in a single DataStore.
 *
 * Queue restoration and validation against available Room records and file
 * access are performed by the playback layer.
 */
class PlaybackPreferences(context: Context) {

    private val dataStore = context.applicationContext.playbackDataStore
    private val queueStore = context.applicationContext.playbackQueueDataStore

    private object Keys {
        val queue = stringPreferencesKey("queue_song_ids")
        val selectedIndex = intPreferencesKey("selected_index")
        val positionMs = longPreferencesKey("position_ms")
        val shuffleEnabled = booleanPreferencesKey("shuffle_enabled")
        val repeatMode = intPreferencesKey("repeat_mode")
        val shuffleOrder = stringPreferencesKey("shuffle_order")
        val carModeEnabled = booleanPreferencesKey("car_mode_enabled")
        val playerSkin = stringPreferencesKey("player_skin")
        val removedSongIds = stringSetPreferencesKey("removed_song_ids")
        val bassDb = intPreferencesKey("tone_bass_db")
        val trebleDb = intPreferencesKey("tone_treble_db")
        val appLayout = stringPreferencesKey("app_layout")
        val modernUseCoverColour = booleanPreferencesKey("modern_use_cover_colour")
    }

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    private val queueValues: Flow<Preferences> = queueStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    val playbackSnapshot: Flow<PlaybackSnapshot> = combine(preferences, queueValues) { values, queued ->
        val queue = decodeStringList(SnapshotCompat.pick(queued[Keys.queue], values[Keys.queue]))
        val savedShuffleOrder = SnapshotCompat.pick(queued[Keys.shuffleOrder], values[Keys.shuffleOrder])
        val rawIndex = values[Keys.selectedIndex] ?: 0

        PlaybackSnapshot(
            queueSongIds = queue,
            selectedIndex = if (queue.isEmpty()) {
                0
            } else {
                rawIndex.coerceIn(0, queue.lastIndex)
            },
            positionMs = (values[Keys.positionMs] ?: 0L).coerceAtLeast(0L),
            shuffleEnabled = values[Keys.shuffleEnabled] ?: false,
            repeatMode = (values[Keys.repeatMode] ?: PlaybackSnapshot.REPEAT_OFF)
                .coerceIn(
                    PlaybackSnapshot.REPEAT_OFF,
                    PlaybackSnapshot.REPEAT_ALL
                ),
            shuffleOrder = decodeStringList(savedShuffleOrder)
        )
    }

    val appPreferences: Flow<AppPreferences> = preferences.map { values ->
        AppPreferences(
            carModeEnabled = values[Keys.carModeEnabled] ?: false,
            playerSkinId = values[Keys.playerSkin],
            bassDb = (values[Keys.bassDb] ?: 0).coerceIn(TONE_MIN_DB, TONE_MAX_DB),
            trebleDb = (values[Keys.trebleDb] ?: 0).coerceIn(TONE_MIN_DB, TONE_MAX_DB),
            appLayoutId = values[Keys.appLayout],
            useCoverColour = values[Keys.modernUseCoverColour] ?: true
        )
    }

    suspend fun getPlaybackSnapshot(): PlaybackSnapshot {
        return playbackSnapshot.first()
    }

    /**
     * Saves a snapshot. The queue and shuffle order are written to the queue
     * file only when [writeQueue] is true (the queue changed); the small
     * fields (index, position, shuffle, repeat) are always written.
     *
     * The queue is written first, so a crash between the two writes can
     * only leave an index that is clamped into the new queue on restore.
     */
    suspend fun savePlaybackSnapshot(snapshot: PlaybackSnapshot, writeQueue: Boolean = true) {
        val queue = snapshot.queueSongIds

        val safeIndex = if (queue.isEmpty()) {
            0
        } else {
            snapshot.selectedIndex.coerceIn(0, queue.lastIndex)
        }

        val safeRepeatMode = snapshot.repeatMode.coerceIn(
            PlaybackSnapshot.REPEAT_OFF,
            PlaybackSnapshot.REPEAT_ALL
        )

        if (writeQueue) {
            queueStore.edit { values ->
                values[Keys.queue] = encodeStringList(queue)
                values[Keys.shuffleOrder] = encodeStringList(snapshot.shuffleOrder)
            }
        }

        dataStore.edit { values ->
            if (writeQueue) {
                // The queue file now holds them; drop the old copies.
                values.remove(Keys.queue)
                values.remove(Keys.shuffleOrder)
            }
            values[Keys.selectedIndex] = safeIndex
            values[Keys.positionMs] = snapshot.positionMs.coerceAtLeast(0L)
            values[Keys.shuffleEnabled] = snapshot.shuffleEnabled
            values[Keys.repeatMode] = safeRepeatMode
        }
    }

    suspend fun setCarModeEnabled(enabled: Boolean) {
        dataStore.edit { values ->
            values[Keys.carModeEnabled] = enabled
        }
    }

    suspend fun setUseCoverColour(enabled: Boolean) {
        dataStore.edit { values ->
            values[Keys.modernUseCoverColour] = enabled
        }
    }

    suspend fun setAppLayout(id: String) {
        dataStore.edit { values ->
            values[Keys.appLayout] = id
        }
    }

    suspend fun setPlayerSkin(id: String) {
        dataStore.edit { values ->
            values[Keys.playerSkin] = id
        }
    }

    /**
     * IDs of songs the user removed from the library. "Scan this phone"
     * does not add these back; an explicit import of the same file does,
     * and takes it off this list.
     */
    suspend fun getRemovedSongIds(): Set<String> =
        preferences.first()[Keys.removedSongIds].orEmpty()

    suspend fun addRemovedSongId(songId: String) {
        dataStore.edit { values ->
            values[Keys.removedSongIds] = values[Keys.removedSongIds].orEmpty() + songId
        }
    }

    suspend fun forgetRemovedSongIds(songIds: Collection<String>) {
        if (songIds.isEmpty()) return

        dataStore.edit { values ->
            val current = values[Keys.removedSongIds] ?: return@edit
            val remaining = current - songIds.toSet()

            if (remaining.size != current.size) {
                values[Keys.removedSongIds] = remaining
            }
        }
    }

    /** Stored clamped to -6..+6 dB. */
    suspend fun setBassDb(db: Int) {
        dataStore.edit { values ->
            values[Keys.bassDb] = db.coerceIn(TONE_MIN_DB, TONE_MAX_DB)
        }
    }

    /** Stored clamped to -6..+6 dB. */
    suspend fun setTrebleDb(db: Int) {
        dataStore.edit { values ->
            values[Keys.trebleDb] = db.coerceIn(TONE_MIN_DB, TONE_MAX_DB)
        }
    }

    private companion object {
        const val TONE_MIN_DB = -6
        const val TONE_MAX_DB = 6
    }

    private fun encodeStringList(items: List<String>): String {
        val array = JSONArray()

        items.forEach { item ->
            array.put(item)
        }

        return array.toString()
    }

    private fun decodeStringList(encoded: String?): List<String> {
        if (encoded.isNullOrBlank()) return emptyList()

        return try {
            val array = JSONArray(encoded)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.opt(index)
                    if (value is String && value.isNotBlank()) {
                        add(value)
                    }
                }
            }
        } catch (_: JSONException) {
            emptyList()
        }
    }
}
