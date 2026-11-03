package com.maukingdom.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects the UI to PlaybackService without owning the player.
 *
 * Create, connect, control, and close this object on the main thread.
 * Closing the controller does not stop service-owned playback.
 */
class PlayerConnection(context: Context) : Closeable {

    companion object {
        private const val TAG = "PlayerConnection"
    }

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var closed = false

    private val mutableController = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = mutableController.asStateFlow()

    private val mutableConnectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = mutableConnectionError.asStateFlow()

    /**
     * Starts an asynchronous controller connection. Repeated calls while
     * connecting or connected do not create additional controllers.
     */
    fun connect() {
        if (closed || controllerFuture != null) return

        mutableConnectionError.value = null

        val token = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )

        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                if (closed || controllerFuture !== future) {
                    return@addListener
                }

                try {
                    mutableController.value = future.get()
                } catch (error: Exception) {
                    Log.e(TAG, "Could not connect to playback service.", error)

                    mutableConnectionError.value =
                        "Could not connect to the playback service."

                    MediaController.releaseFuture(future)
                    controllerFuture = null
                }
            },
            mainExecutor
        )
    }

    /**
     * Sets a queue and starts playback at the selected item.
     *
     * Returns false if the controller is not connected or the queue is empty.
     * Callers should construct local-file items using MediaItemMapper.
     */
    fun playQueue(
        items: List<MediaItem>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L
    ): Boolean {
        val connectedController = mutableController.value ?: return false
        if (items.isEmpty()) return false

        val safeIndex = startIndex.coerceIn(0, items.lastIndex)
        val safePosition = startPositionMs.coerceAtLeast(0L)

        connectedController.setMediaItems(items, safeIndex, safePosition)
        connectedController.prepare()
        connectedController.play()

        return true
    }

    /**
     * Submits the service's explicit queue-clear command.
     *
     * Returns true if the command was submitted, or false if there is no
     * connected controller, the command is unavailable, or submission throws.
     * This does not wait for or report the asynchronous persistence result.
     */
    fun clearQueue(): Boolean {
        val connectedController = mutableController.value ?: return false

        return try {
            if (!connectedController.isConnected) return false

            val command = SessionCommand(
                PlaybackService.ACTION_CLEAR_QUEUE,
                Bundle.EMPTY
            )

            if (!connectedController.availableSessionCommands.contains(command)) {
                return false
            }

            connectedController.sendCustomCommand(command, Bundle.EMPTY)
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not submit the queue-clear command.", error)
            false
        }
    }

    override fun close() {
        if (closed) return

        closed = true
        mutableController.value = null

        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }

        controllerFuture = null
    }
}
