package notch.cat.cast.airplay

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayServerInfoTest {
  @Test
  fun serverInfoUsesLegacyHttpFeatureSetAndStableDeviceId() {
    val deviceId = "62:6D:04:15:E7:2F"
    val info = AirPlayServerInfo.xml(deviceId).dict()

    assertEquals(0x27f, info.int("features"))
    assertEquals(deviceId, info.string("deviceid"))
    assertEquals(deviceId, info.string("macAddress"))
    assertEquals(AirPlayProfile.MODEL, info.string("model"))
    assertEquals(AirPlayProfile.SOURCE_VERSION, info.string("srcvers"))
    assertEquals(2, info.int("vv"))
    assertEquals("1.0", info.string("protovers"))
  }

  private fun ByteArray.dict() = PropertyListParser.parse(this) as NSDictionary
  private fun NSDictionary.int(key: String) = (objectForKey(key).toJavaObject() as Number).toInt()
  private fun NSDictionary.string(key: String) = objectForKey(key).toJavaObject().toString()
}
