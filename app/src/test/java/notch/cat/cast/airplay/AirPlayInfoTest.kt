package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    assertEquals(true, info.objectForKey("keepAliveSendStatsAsBody").toJavaObject())
    assertArrayEquals(publicKey, info.data("pk"))
  }

  @Test
  fun fullInfoUsesUxPlayAudioAndDisplayShape() {
    val info = AirPlayInfo.plist(
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = ByteArray(32)
    ).dict()
    val latency = info.maps("audioLatencies").first()
    val display = info.maps("displays").first()

    assertEquals(0.0, (info.objectForKey("initialVolume").toJavaObject() as Number).toDouble(), 0.000001)
    assertEquals(0, (latency["inputLatencyMicros"] as Number).toInt())
    assertEquals(false, latency["outputLatencyMicros"])
    assertEquals(1.0 / 60.0, (display["refreshRate"] as Number).toDouble(), 0.000001)
    assertEquals(0, (display["widthPhysical"] as Number).toInt())
    assertEquals(0, (display["heightPhysical"] as Number).toInt())
  }

  @Test
  fun infoQualifierReturnsAirPlayTxtRecordOnly() {
    val publicKey = ByteArray(32) { it.toByte() }
    val publicKeyHex = publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val info = AirPlayInfo.response(
      headers = mapOf("content-type" to "application/x-apple-binary-plist"),
      body = qualifierBody("txtAirPlay"),
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = publicKey,
      publicKeyHex = publicKeyHex
    ).dict()

    assertEquals(listOf("txtAirPlay"), info.allKeys().sorted())
    val txt = txtRecord(info.data("txtAirPlay"))
    assertEquals("62:6D:04:15:E7:2F", txt["deviceid"])
    assertEquals(AirPlayProfile.FEATURES_HEX, txt["features"])
    assertEquals(AirPlayProfile.FLAGS_HEX, txt["flags"])
    assertEquals("false", txt["pw"])
    assertEquals(AirPlayProfile.MODEL, txt["model"])
    assertEquals(publicKeyHex, txt["pk"])
    assertEquals("626d0415-e72f-3f1d-8d3c-7b20056358ed", txt["pi"])
    assertEquals(AirPlayProfile.SOURCE_VERSION, txt["srcvers"])
    assertEquals("2", txt["vv"])
  }

  @Test
  fun infoQualifierReturnsRaopTxtRecordOnly() {
    val publicKey = ByteArray(32) { it.toByte() }
    val publicKeyHex = publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val info = AirPlayInfo.response(
      headers = mapOf("content-type" to "application/x-apple-binary-plist"),
      body = qualifierBody("txtRAOP"),
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = publicKey,
      publicKeyHex = publicKeyHex
    ).dict()

    assertEquals(listOf("txtRAOP"), info.allKeys().sorted())
    val txt = txtRecord(info.data("txtRAOP"))
    assertEquals(AirPlayProfile.FEATURES_HEX, txt["ft"])
    assertEquals(AirPlayProfile.FLAGS_HEX, txt["sf"])
    assertEquals(AirPlayProfile.MODEL, txt["am"])
    assertEquals(publicKeyHex, txt["pk"])
    assertEquals(AirPlayProfile.SOURCE_VERSION, txt["vs"])
  }

  @Test
  fun infoQueryQualifierReturnsRequestedTxtRecordsForHeaderlessDiscovery() {
    val publicKey = ByteArray(32) { it.toByte() }
    val publicKeyHex = publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val info = AirPlayInfo.response(
      path = "/info?txtAirPlay?txtRAOP",
      headers = emptyMap(),
      body = ByteArray(0),
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = publicKey,
      publicKeyHex = publicKeyHex
    ).dict()

    assertEquals("62:6D:04:15:E7:2F", info.string("deviceID"))
    assertEquals("62:6D:04:15:E7:2F", info.string("macAddress"))
    assertEquals("莽莽投屏", info.string("name"))
    assertEquals(AirPlayProfile.FEATURES, info.long("features"))
    assertArrayEquals(publicKey, info.data("pk"))
    assertEquals(AirPlayProfile.FEATURES_HEX, txtRecord(info.data("txtAirPlay"))["features"])
    assertEquals(AirPlayProfile.FEATURES_HEX, txtRecord(info.data("txtRAOP"))["ft"])
    assertFalse(info.containsKey("displays"))
    assertFalse(info.containsKey("audioFormats"))
  }

  @Test
  fun infoBinaryQualifierRequestDoesNotFallThroughToFullInfo() {
    val publicKey = ByteArray(32) { it.toByte() }
    val info = AirPlayInfo.response(
      headers = mapOf("content-type" to "application/x-apple-binary-plist"),
      body = qualifierBody("unknown"),
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = publicKey,
      publicKeyHex = publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    ).dict()

    assertEquals(emptyList<String>(), info.allKeys().sorted())
  }

  private fun qualifierBody(vararg values: String) = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
    put("qualifier", NSArray(values.size).apply { values.forEachIndexed { index, value -> setValue(index, value) } })
  })

  private fun txtRecord(bytes: ByteArray): Map<String, String> {
    val values = linkedMapOf<String, String>()
    var index = 0
    while (index < bytes.size) {
      val size = bytes[index++].toInt() and 0xff
      val entry = String(bytes, index, size, Charsets.UTF_8)
      val split = entry.indexOf('=')
      values[entry.take(split)] = entry.substring(split + 1)
      index += size
    }
    return values
  }

  private fun ByteArray.dict() = BinaryPropertyListParser.parse(this) as NSDictionary
  private fun NSDictionary.int(key: String) = (objectForKey(key).toJavaObject() as Number).toInt()
  private fun NSDictionary.long(key: String) = (objectForKey(key).toJavaObject() as Number).toLong()
  private fun NSDictionary.string(key: String) = objectForKey(key).toJavaObject().toString()
  private fun NSDictionary.data(key: String) = (objectForKey(key) as NSData).bytes()
  private fun NSDictionary.maps(key: String) = (objectForKey(key).toJavaObject() as Array<*>).map { it as Map<*, *> }
}
