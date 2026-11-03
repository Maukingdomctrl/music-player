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
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

private val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "playback_preferences"
)

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
    val trebleDb: Int = 0
)

/**
 * Stores playback and app preferences in a single DataStore.
 *
 * Queue restoration and validation against available Room records and file
 * access are performed by the playback layer.
 */
class PlaybackPreferences(context: Context) {

    private val dataStore = context.applicationContext.playbackDataStore

    private object Keys {
        val queue = stringPreferencesKey("queue_song_ids")
        val selectedIndex = intPreferencesKey("selected_index")
        val positionMs = longPreferencesKey("position_ms")
        val shuffleEnabled = booleanPreferencesKey("shuffle_enabled")
        val repeatMode = intPreferencesKey("repeat_mode")
        val shuffleOrder = stringPreferencesKey("shuffle_order")
        val carModeEnabled = booleanPreferencesKey("car_mode_enabled")
        val playerSkin = stringPreferencesKey("player_skin")
        val bassDb = intPreferencesKey("tone_bass_db")
        val trebleDb = intPreferencesKey("tone_treble_db")
    }

    private val preferences: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) {
            emit(emptyPreferences())
        } else {
            throw error
        }
    }

    val playbackSnapshot: Flow<PlaybackSnapshot> = preferences.map { values ->
        val queue = decodeStringList(values[Keys.queue])
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
            shuffleOrder = decodeStringList(values[Keys.shuffleOrder])
        )
    }

    val appPreferences: Flow<AppPreferences> = preferences.map { values ->
        AppPreferences(
            carModeEnabled = values[Keys.carModeEnabled] ?: false,
            playerSkinId = values[Keys.playerSkin],
            bassDb = (values[Keys.bassDb] ?: 0).coerceIn(TONE_MIN_DB, TONE_MAX_DB),
            trebleDb = (values[Keys.trebleDb] ?: 0).coerceIn(TONE_MIN_DB, TONE_MAX_DB)
        )
    }

    suspend fun getPlaybackSnapshot(): PlaybackSnapshot {
        return playbackSnapshot.first()
    }

    /**
     * Writes all playback fields in one DataStore transaction, preventing
     * readers from observing a partially updated snapshot.
     */
    suspend fun savePlaybackSnapshot(snapshot: PlaybackSnapshot) {
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

        dataStore.edit { values ->
            values[Keys.queue] = encodeStringList(queue)
            values[Keys.selectedIndex] = safeIndex
            values[Keys.positionMs] = snapshot.positionMs.coerceAtLeast(0L)
            values[Keys.shuffleEnabled] = snapshot.shuffleEnabled
            values[Keys.repeatMode] = safeRepeatMode
            values[Keys.shuffleOrder] = encodeStringList(snapshot.shuffleOrder)
        }
    }

    suspend fun setCarModeEnabled(enabled: Boolean) {
        dataStore.edit { values ->
            values[Keys.carModeEnabled] = enabled
        }
    }

    suspend fun setPlayerSkin(id: String) {
        dataStore.edit { values ->
            values[Keys.playerSkin] = id
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
