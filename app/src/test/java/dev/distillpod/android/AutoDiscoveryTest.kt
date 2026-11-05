package dev.distillpod.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AutoDiscoveryTest {
    @Test fun `both modern and legacy browsers can discover our single playback service`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(action in listOf("androidx.media3.session.MediaLibraryService","android.media.browse.MediaBrowserService")) {
            val matches=context.packageManager.queryIntentServices(Intent(action).setPackage(context.packageName),0)
            assertEquals(action,1,matches.size)
            assertEquals("dev.distillpod.android.PlaybackService",matches.single().serviceInfo.name)
            assertTrue(matches.single().serviceInfo.exported)
        }
    }
    @Test fun `app declares Android Auto media capability`() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val info=context.packageManager.getApplicationInfo(context.packageName,android.content.pm.PackageManager.GET_META_DATA)
        val resource=info.metaData?.getInt("com.google.android.gms.car.application")?:0
        assertNotEquals(0,resource)
        val xml=context.resources.getXml(resource)
        var media=false
        while(xml.eventType!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if(xml.eventType==org.xmlpull.v1.XmlPullParser.START_TAG && xml.name=="uses" && xml.getAttributeValue(null,"name")=="media")media=true
            xml.next()
        }
        xml.close();assertTrue(media)
    }
}
