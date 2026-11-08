package dev.distillpod.android.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.io.File

internal fun transcriptionNeedsNetwork(episode: EpisodeEntity?, row: TranscriptEntity): Boolean =
    row.lookupOnline || row.source == "publisher" || episode?.localFile?.let { File(it).isFile } != true

/** The direct foreground session observes the same power/network policy as deferred work. */
internal fun transcriptionAllowed(context: Context, prefs: Preferences, needsNetwork: Boolean): Boolean = transcriptionWaitReason(context,prefs,needsNetwork)==null

internal fun transcriptionWaitReason(context: Context, prefs: Preferences, needsNetwork: Boolean): String? {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (prefs.chargingOnly && !charging) return "charging_required"
    if (!charging && level >= 0 && scale > 0 && level.toFloat() / scale <= 0.15f) return "battery_low"
    if (!needsNetwork) return null
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    return when {
        network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)!=true -> "offline"
        prefs.wifiOnly && connectivity.isActiveNetworkMetered -> "wifi_required"
        else -> null
    }
}
