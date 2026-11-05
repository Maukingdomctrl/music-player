package com.maukingdom.musicplayer.playback.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * The home-screen player widget (4x1 to 4x2). It holds no state of its
 * own: PlaybackService pushes updates through [PlayerWidget]; this only
 * draws the current state when a widget is added or resized.
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        for (id in widgetIds) PlayerWidget.render(context, manager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        // Below Android 12 the layout is chosen from the size; 12+ picks it itself.
        PlayerWidget.render(context, manager, widgetId)
    }
}
