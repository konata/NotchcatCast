package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlaySetupTest {
  @Test
  fun parserExtractsSessionKeysAndTimingPort() {
    val ekey = ByteArray(72) { it.toByte() }
    val eiv = ByteArray(16) { (it + 1).toByte() }
    val body = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
      put("ekey", ekey)
      put("eiv", eiv)
      put("timingProtocol", "NTP")
      put("timingPort", 54911)
    })

    val setup = AirPlaySetup.parse(body) as AirPlaySetup.Session

    assertArrayEquals(ekey, setup.ekey)
    assertArrayEquals(eiv, setup.eiv)
    assertEquals("NTP", setup.timingProtocol)
    assertEquals(54911, setup.timingPort)
  }

  @Test
  fun parserExtractsMirrorStreamConnectionIdAsUnsignedDecimal() {
    val body = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
      put("streams", NSArray(NSDictionary().apply {
        put("type", 110)
        put("streamConnectionID", -1L)
      }))
    })

    val setup = AirPlaySetup.parse(body) as AirPlaySetup.Streams

    assertEquals(1, setup.streams.size)
    assertEquals(110, setup.streams[0].type)
    assertEquals("18446744073709551615", setup.streams[0].connectionId)
  }

  @Test
  fun parserKeepsEveryRequestedStream() {
    val body = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
      put("streams", NSArray(
        NSDictionary().apply {
          put("type", 110)
          put("streamConnectionID", 7L)
        },
        NSDictionary().apply {
          put("type", 96)
          put("controlPort", 62989)
        }
      ))
    })

    val setup = AirPlaySetup.parse(body) as AirPlaySetup.Streams

    assertEquals(listOf(110, 96), setup.streams.map { it.type })
    assertEquals("7", setup.streams[0].connectionId)
  }

  @Test
  fun setupResponsesMatchAirPlayMirrorShape() {
    val session = AirPlaySetup.responseSession(eventPort = 0, timingPort = 43210).dict()
    val video = AirPlaySetup.responseStreams(listOf(AirPlayStreamPort(type = 110, dataPort = 41000))).dict()

    assertEquals(0, session.int("eventPort"))
    assertEquals(43210, session.int("timingPort"))
    assertTrue(video.containsKey("streams"))
    assertFalse(video.containsKey("eventPort"))
    assertFalse(video.containsKey("timingPort"))
  }

  @Test
  fun streamResponseIncludesEveryStartedStream() {
    val response = AirPlaySetup.responseStreams(listOf(
      AirPlayStreamPort(type = 110, dataPort = 41000),
      AirPlayStreamPort(type = 96, dataPort = 42000, controlPort = 42001)
    )).dict()
    val streams = response.objectForKey("streams").toJavaObject() as Array<*>

    assertEquals(2, streams.size)
    assertEquals(110, (streams[0] as Map<*, *>)["type"])
    assertEquals(41000, (streams[0] as Map<*, *>)["dataPort"])
    assertEquals(96, (streams[1] as Map<*, *>)["type"])
    assertEquals(42000, (streams[1] as Map<*, *>)["dataPort"])
    assertEquals(42001, (streams[1] as Map<*, *>)["controlPort"])
  }

  private fun ByteArray.dict() = BinaryPropertyListParser.parse(this) as NSDictionary

  private fun NSDictionary.int(key: String) = (objectForKey(key).toJavaObject() as Number).toInt()
}
