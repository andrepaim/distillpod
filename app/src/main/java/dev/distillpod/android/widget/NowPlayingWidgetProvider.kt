package dev.distillpod.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * Placement, reboot and app updates. Without a running session in this process the launcher
 * may hold views from a process that died, so this draws the idle state rather than guessing.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, NowPlayingWidget.views(context, NowPlayingWidget.live ?: NowPlayingWidgetState.IDLE))
    }
}
