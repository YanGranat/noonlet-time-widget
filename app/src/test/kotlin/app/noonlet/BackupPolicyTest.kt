package app.noonlet

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class BackupPolicyTest {
    @Test fun automaticBackupAndBothTransferModesExcludeAppData() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val exclusions = mutableMapOf<String, MutableSet<String>>()
        context.resources.getXml(R.xml.data_extraction_rules).use { xml ->
            var mode = ""
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                    "cloud-backup", "device-transfer" -> mode = xml.name
                    "exclude" -> {
                        assertEquals(".", xml.getAttributeValue(null, "path"))
                        exclusions.getOrPut(mode) { mutableSetOf() }.add(xml.getAttributeValue(null, "domain"))
                    }
                }
            }
        }
        val domains = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        assertEquals(mapOf("cloud-backup" to domains, "device-transfer" to domains), exclusions)
    }
}
