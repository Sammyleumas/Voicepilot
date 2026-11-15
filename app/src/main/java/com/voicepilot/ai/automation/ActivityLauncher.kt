package com.voicepilot.ai.automation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts other apps' activities. VoicePilot only runs actions while one of its
 * own screens is visible, which Android requires for starting activities.
 */
@Singleton
class ActivityLauncher @Inject constructor(@ApplicationContext private val context: Context) {

    @Suppress("DEPRECATION")
    fun canResolve(intent: Intent): Boolean {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        return info != null
    }

    /** Returns false when no app can handle [intent]. */
    fun start(intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    fun launchIntentFor(packageName: String): Intent? = context.packageManager.getLaunchIntentForPackage(packageName)
}
