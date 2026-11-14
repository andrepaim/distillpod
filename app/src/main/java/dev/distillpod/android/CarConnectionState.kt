package dev.distillpod.android

import android.content.AsyncQueryHandler
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

internal fun carConnectionState(context: Context): LiveData<Int> =
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
        MutableLiveData(CarConnection.CONNECTION_TYPE_NATIVE)
    } else {
        ProjectionCarConnection(context.applicationContext)
    }

// Match AndroidX CarConnection's host contract, but close every returned cursor.
private class ProjectionCarConnection(private val context: Context) : LiveData<Int>() {
    private var activation: Any? = null

    private val query = object : AsyncQueryHandler(context.contentResolver) {
        override fun onQueryComplete(token: Int, cookie: Any?, cursor: Cursor?) {
            val connection = cursor?.use {
                val column = it.getColumnIndex(CarConnection.CAR_CONNECTION_STATE)
                if (column >= 0 && it.moveToNext()) it.getInt(column) else null
            } ?: CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            if (cookie === activation && activation != null) value = connection
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The external broadcast only triggers a refresh; never trust its payload.
            if (intent.action == CarConnection.ACTION_CAR_CONNECTION_UPDATED) refresh()
        }
    }

    override fun onActive() {
        val filter = IntentFilter(CarConnection.ACTION_CAR_CONNECTION_UPDATED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        activation = Any()
        refresh()
    }

    override fun onInactive() {
        activation = null
        context.unregisterReceiver(receiver)
        query.cancelOperation(QUERY_TOKEN)
    }

    private fun refresh() {
        val active = activation ?: return
        query.startQuery(
            QUERY_TOKEN, active, hostUri, arrayOf(CarConnection.CAR_CONNECTION_STATE),
            null, null, null,
        )
    }

    private companion object {
        const val QUERY_TOKEN = 1
        val hostUri: Uri = Uri.parse("content://androidx.car.app.connection")
    }
}
