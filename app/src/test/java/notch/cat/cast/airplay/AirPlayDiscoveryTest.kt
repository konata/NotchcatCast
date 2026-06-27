package notch.cat.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayDiscoveryTest {
  @Test
  fun metadataMatchesLegacyAppleTvMirrorProfile() {
    val id = AirPlayDiscovery.deviceId("626d0415-e72f-3f1d-8d3c-7b20056358ed")
    val airplay = AirPlayDiscovery.airplayTxt(id, "uuid-value", "pk-value")
    val raop = AirPlayDiscovery.raopTxt("pk-value")

    assertEquals("62:6D:04:15:E7:2F", id)
    assertEquals("0x5A7FFEE6,0x400", airplay["features"])
    assertEquals("0x4", airplay["flags"])
    assertEquals("AppleTV3,2", airplay["model"])
    assertEquals("uuid-value", airplay["pi"])
    assertEquals("pk-value", airplay["pk"])
    assertEquals("0x5A7FFEE6,0x400", raop["ft"])
    assertEquals("0x4", raop["sf"])
    assertEquals("AppleTV3,2", raop["am"])
  }
}
