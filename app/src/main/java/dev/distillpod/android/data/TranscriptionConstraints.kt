package dev.distillpod.android.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/** The direct foreground session observes the same power/network policy as deferred work. */
internal fun transcriptionAllowed(context: Context, prefs: Preferences, needsNetwork: Boolean): Boolean {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (prefs.chargingOnly && !charging) return false
    if (!charging && level >= 0 && scale > 0 && level.toFloat() / scale <= 0.15f) return false
    if (!needsNetwork) return true
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    return network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        (!prefs.wifiOnly || !connectivity.isActiveNetworkMetered)
}
