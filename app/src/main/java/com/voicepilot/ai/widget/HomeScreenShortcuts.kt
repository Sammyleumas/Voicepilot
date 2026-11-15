package com.voicepilot.ai.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.voicepilot.ai.R
import com.voicepilot.ai.assist.AssistActivity

/** 1x1 home screen widget: a round mic button that opens SL listening. */
class TalkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_talk).apply {
            setOnClickPendingIntent(R.id.widget_root, HomeScreenShortcuts.talkPendingIntent(context))
        }
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }
}

/** Adds SL to the launcher's home screen, with the launcher's own confirmation. */
object HomeScreenShortcuts {
    private const val SHORTCUT_ID = "talk_to_sl"

    fun talkIntent(context: Context): Intent =
        AssistActivity.intent(context, null).setAction(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun talkPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 7, talkIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun canPinShortcut(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to add a "Talk to SL" icon. Returns false if the launcher doesn't support it. */
    fun pinShortcut(context: Context): Boolean {
        if (!canPinShortcut(context)) return false
        val info = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.shortcut_talk_short))
            .setLongLabel(context.getString(R.string.shortcut_talk_long))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_shortcut_talk))
            .setIntent(talkIntent(context))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    fun canPinWidget(context: Context): Boolean =
        context.getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

    /** Asks the launcher to place the 1x1 widget. Returns false if the launcher doesn't support it. */
    fun pinWidget(context: Context): Boolean {
        val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        return manager.requestPinAppWidget(ComponentName(context, TalkWidgetProvider::class.java), null, null)
    }
}
