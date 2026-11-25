package dev.distillpod.android

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33])
class CarConnectionStateTest {
    @Test fun closesProviderCursorsAndRequeriesOnBroadcast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = ConnectionProvider()
        ShadowContentResolver.registerProviderInternal("androidx.car.app.connection", provider)
        val projection = CarConnection.CONNECTION_TYPE_PROJECTION
        val disconnected = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        val connected = stateCursor(projection)
        provider.response = connected
        val data = carConnectionState(context)
        val values = mutableListOf<Int>()
        val observer = Observer<Int> { values += it }
        data.observeForever(observer)
        try {
            await { values.size == 1 }
            assertEquals(projection, values.last())
            assertTrue("Connected cursor must close", connected.isClosed)
            val responses = listOf(
                stateCursor(disconnected),
                MatrixCursor(arrayOf("other")).apply { addRow(arrayOf(0)) },
                MatrixCursor(arrayOf(CarConnection.CAR_CONNECTION_STATE)),
                null,
            )
            responses.forEachIndexed { index, cursor ->
                provider.response = cursor
                context.sendBroadcast(Intent(CarConnection.ACTION_CAR_CONNECTION_UPDATED))
                await { values.size == index + 2 }
                assertEquals(disconnected, values.last())
                if (cursor != null) assertTrue("Cursor for response $index must close", cursor.isClosed)
            }
        } finally {
            data.removeObserver(observer)
        }
    }

    @Test fun automotiveReportsNativeWithoutQueryingAProjectionHost() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_AUTOMOTIVE, true)
        assertEquals(CarConnection.CONNECTION_TYPE_NATIVE, carConnectionState(context).value)
    }

    private fun stateCursor(value: Int) =
        MatrixCursor(arrayOf(CarConnection.CAR_CONNECTION_STATE)).apply { addRow(arrayOf(value)) }

    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Connection query did not publish a result", done())
    }

    private class ConnectionProvider : ContentProvider() {
        @Volatile var response: MatrixCursor? = null
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor? {
            assertEquals(Uri.parse("content://androidx.car.app.connection"), uri)
            assertEquals(listOf(CarConnection.CAR_CONNECTION_STATE), projection?.toList())
            return response
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?): Int = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            error("read only")
    }
}
