package notch.cat.cast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AirPlaySessionTest {
  @Test
  fun publicKeyIsStableForSameSeed() {
    val seed = ByteArray(32) { it.toByte() }

    assertArrayEquals(AirPlaySession(seed).publicKey, AirPlaySession(seed).publicKey)
  }

  @Test
  fun publicKeyChangesForDifferentSeed() {
    val first = AirPlaySession(ByteArray(32) { it.toByte() }).publicKeyHex
    val second = AirPlaySession(ByteArray(32) { (it + 1).toByte() }).publicKeyHex

    assertNotEquals(first, second)
  }

  @Test
  fun keySeedIsDerivedFromStableUuid() {
    val uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed"

    assertEquals(32, AirPlayKey.seed(uuid).size)
    assertArrayEquals(AirPlayKey.seed(uuid), AirPlayKey.seed(uuid))
    assertNotEquals(AirPlayKey.seed(uuid).toList(), AirPlayKey.seed("different-uuid").toList())
  }
}
