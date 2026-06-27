package notch.cat.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayDiscoveryTest {
  @Test
  fun metadataMatchesLegacyAppleTvMirrorProfile() {
    val id = AirPlayDiscovery.deviceId("626d0415-e72f-3f1d-8d3c-7b20056358ed")
    val airplay = AirPlayTxt.airplay(id, "uuid-value", "pk-value")
    val raop = AirPlayTxt.raop("pk-value")

    assertEquals("62:6D:04:15:E7:2F", id)
    assertEquals("0x5A7FFEE6,0x0", airplay["features"])
    assertEquals("0x4", airplay["flags"])
    assertEquals("AppleTV3,2", airplay["model"])
    assertEquals("uuid-value", airplay["pi"])
    assertEquals("pk-value", airplay["pk"])
    assertEquals(
      mapOf(
        "ch" to "2",
        "cn" to "0,1,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "vv" to "2",
        "ft" to "0x5A7FFEE6,0x0",
        "am" to "AppleTV3,2",
        "md" to "0,1,2",
        "rhd" to "5.6.0.0",
        "pw" to "false",
        "sf" to "0x4",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "tp" to "UDP",
        "txtvers" to "1",
        "vs" to "220.68",
        "vn" to "65537",
        "pk" to "pk-value",
      ),
      raop
    )
  }
}
