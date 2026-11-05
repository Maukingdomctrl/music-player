package com.maukingdom.musicplayer.playback.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.maukingdom.musicplayer.MainActivity
import com.maukingdom.musicplayer.R
import com.maukingdom.musicplayer.playback.PlaybackService

/** What the home-screen widget shows. [hasSong] false = "Tap to start MyCar Music". */
data class WidgetState(
    val hasSong: Boolean = false,
    val songId: String? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false
)

/** Pure widget rules. JVM-tested. */
object WidgetModel {
    /** Covers are decoded to at most this many pixels on the long side. */
    const val COVER_MAX_PX = 256

    /** Below this height (dp) the 4x1 layout is used, else 4x2. */
    const val LARGE_MIN_HEIGHT_DP = 100

    /** The 4x2 layout needs about this much width (dp); narrower falls back to 4x1. */
    const val LARGE_MIN_WIDTH_DP = 290

    fun useLarge(widthDp: Int, heightDp: Int): Boolean =
        heightDp >= LARGE_MIN_HEIGHT_DP && widthDp >= LARGE_MIN_WIDTH_DP

    /** Title and artist lines; the empty state has a single line. */
    fun texts(state: WidgetState, emptyText: String): Pair<String, String?> =
        if (!state.hasSong) {
            emptyText to null
        } else {
            state.title.ifBlank { "Unknown title" } to state.artist.ifBlank { "Unknown artist" }
        }

    /** Play while paused cold-starts playback, so it asks for a foreground service start. */
    fun playNeedsForeground(state: WidgetState): Boolean = state.hasSong && !state.isPlaying
}

/**
 * Renders the widget from [WidgetState] and the last decoded cover. Called
 * by PlaybackService on changes and by the provider when a widget is added
 * or resized. Main thread. Holds only process memory: after process death
 * the launcher keeps showing the last views (with working buttons).
 */
object PlayerWidget {
    private const val TAG = "PlayerWidget"

    const val ACTION_PLAY_PAUSE = "com.maukingdom.musicplayer.widget.PLAY_PAUSE"
    const val ACTION_NEXT = "com.maukingdom.musicplayer.widget.NEXT"
    const val ACTION_PREVIOUS = "com.maukingdom.musicplayer.widget.PREVIOUS"

    /** Set on an intent sent with getForegroundService. */
    const val EXTRA_FOREGROUND = "com.maukingdom.musicplayer.widget.FOREGROUND"

    var state: WidgetState = WidgetState()
        private set

    private var cover: Bitmap? = null
    private var coverSongId: String? = null

    /** Updates every placed widget if [next] (or the cover) differs from what is shown. */
    fun update(context: Context, next: WidgetState, nextCover: Bitmap? = cover, nextCoverSongId: String? = coverSongId) {
        if (next == state && nextCover === cover && nextCoverSongId == coverSongId) return
        state = next
        cover = nextCover
        coverSongId = nextCoverSongId
        renderAll(context)
    }

    /** The cover only if it belongs to the song shown. */
    private fun shownCover(): Bitmap? = cover?.takeIf { state.hasSong && coverSongId == state.songId }

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = try {
            manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not list widgets.", error)
            return
        }
        for (id in ids) render(context, manager, id)
    }

    fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        try {
            manager.updateAppWidget(widgetId, views(context, manager.getAppWidgetOptions(widgetId)))
        } catch (error: RuntimeException) {
            // A launcher that went away, or a too-large bitmap: keep the old views.
            Log.w(TAG, "Could not update widget $widgetId.", error)
        }
    }

    private fun views(context: Context, options: Bundle?): RemoteViews {
        val small = build(context, R.layout.widget_player_small)
        val large = build(context, R.layout.widget_player_large)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launcher picks the layout that fits the widget's current size.
            return RemoteViews(
                mapOf(
                    SizeF(180f, 40f) to small,
                    SizeF(WidgetModel.LARGE_MIN_WIDTH_DP.toFloat(), WidgetModel.LARGE_MIN_HEIGHT_DP.toFloat()) to large
                )
            )
        }

        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        return if (WidgetModel.useLarge(width, height)) large else small
    }

    private fun build(context: Context, layout: Int): RemoteViews {
        val current = state
        val views = RemoteViews(context.packageName, layout)
        val (title, artist) = WidgetModel.texts(current, context.getString(R.string.widget_empty))

        views.setTextViewText(R.id.widget_title, title)
        if (artist == null) {
            views.setViewVisibility(R.id.widget_artist, View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_artist, View.VISIBLE)
            views.setTextViewText(R.id.widget_artist, artist)
        }

        val art = shownCover()
        if (art != null) {
            views.setImageViewBitmap(R.id.widget_cover, art)
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
        }

        val open = openAppIntent(context)
        views.setOnClickPendingIntent(R.id.widget_cover, open)
        views.setOnClickPendingIntent(R.id.widget_text, open)

        val buttons = if (current.hasSong) View.VISIBLE else View.GONE
        views.setViewVisibility(R.id.widget_previous, buttons)
        views.setViewVisibility(R.id.widget_play, buttons)
        views.setViewVisibility(R.id.widget_next, buttons)

        if (current.hasSong) {
            views.setImageViewResource(
                R.id.widget_play,
                if (current.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            )
            views.setContentDescription(
                R.id.widget_play,
                context.getString(if (current.isPlaying) R.string.widget_cd_pause else R.string.widget_cd_play)
            )
            views.setOnClickPendingIntent(
                R.id.widget_play,
                serviceIntent(context, ACTION_PLAY_PAUSE, 1, WidgetModel.playNeedsForeground(current))
            )
            views.setOnClickPendingIntent(R.id.widget_previous, serviceIntent(context, ACTION_PREVIOUS, 2, false))
            views.setOnClickPendingIntent(R.id.widget_next, serviceIntent(context, ACTION_NEXT, 3, false))
        } else {
            // Empty: the whole widget opens the app.
            views.setOnClickPendingIntent(android.R.id.background, open)
        }

        return views
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * A media command for PlaybackService. Play-while-paused uses a foreground
     * start (as Media3's own notification does), since it starts playback.
     */
    private fun serviceIntent(context: Context, action: String, requestCode: Int, foreground: Boolean): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java)
            .setAction(action)
            .putExtra(EXTRA_FOREGROUND, foreground)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (foreground) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    /** [source] scaled to fit [maxPx] and cut to rounded corners ([radiusFraction] of the side). Off the main thread. */
    fun roundedCover(source: Bitmap, maxPx: Int = WidgetModel.COVER_MAX_PX, radiusFraction: Float = 0.12f): Bitmap {
        val side = minOf(source.width, source.height).coerceAtLeast(1)
        val target = minOf(side, maxPx)
        val output = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val scale = target.toFloat() / side
        val matrix = android.graphics.Matrix().apply {
            postTranslate(-(source.width - side) / 2f, -(source.height - side) / 2f)
            postScale(scale, scale)
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        val radius = target * radiusFraction
        canvas.drawRoundRect(RectF(0f, 0f, target.toFloat(), target.toFloat()), radius, radius, paint)
        return output
    }
}
