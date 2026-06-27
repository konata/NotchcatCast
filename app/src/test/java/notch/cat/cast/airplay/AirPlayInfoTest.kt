package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayInfoTest {
  @Test
  fun infoUsesAirPlayFeatureMaskAndInfoStatusFlags() {
    val publicKey = ByteArray(32) { it.toByte() }
    val info = AirPlayInfo.plist(
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = publicKey
    ).dict()

    assertEquals(AirPlayProfile.FEATURES, info.long("features"))
    assertEquals(68, info.int("statusFlags"))
    assertEquals("62:6D:04:15:E7:2F", info.string("deviceID"))
    assertEquals("62:6D:04:15:E7:2F", info.string("macAddress"))
    assertEquals(1, info.int("keepAliveLowPower"))
    assertArrayEquals(publicKey, info.data("pk"))
  }

  private fun ByteArray.dict() = BinaryPropertyListParser.parse(this) as NSDictionary
  private fun NSDictionary.int(key: String) = (objectForKey(key).toJavaObject() as Number).toInt()
  private fun NSDictionary.long(key: String) = (objectForKey(key).toJavaObject() as Number).toLong()
  private fun NSDictionary.string(key: String) = objectForKey(key).toJavaObject().toString()
  private fun NSDictionary.data(key: String) = (objectForKey(key) as NSData).bytes()
}
