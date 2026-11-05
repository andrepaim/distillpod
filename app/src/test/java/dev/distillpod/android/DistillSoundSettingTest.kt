package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.distillpod.android.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DistillSoundSettingTest {
    @Test fun `sound defaults on and its mute setting survives repository recreation`()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File(context.cacheDir,"sound-${UUID.randomUUID()}.preferences_pb")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val store=PreferenceDataStoreFactory.create(scope=scope){file}
            val settings=AppSettings(context,store)
            assertTrue(settings.flow.first().distillSound)
            settings.flag("distillSound",false)
            assertFalse(AppSettings(context,store).flow.first().distillSound)
        } finally {scope.cancel();file.delete()}
    }
}
