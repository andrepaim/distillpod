package dev.distillpod.android
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPolicyTest {
    @Test fun `credentials and private data excluded from both transfer transports`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var transport = ""
        val excluded = mutableSetOf<String>()
        while(parser.eventType != XmlPullParser.END_DOCUMENT) {
            if(parser.eventType == XmlPullParser.START_TAG) {
                if(parser.name in setOf("cloud-backup", "device-transfer")) transport = parser.name
                if(parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref" && parser.getAttributeValue(null, "path") == ".") excluded.add(transport)
            }
            parser.next()
        }
        assertEquals(setOf("cloud-backup", "device-transfer"), excluded)
    }
}
