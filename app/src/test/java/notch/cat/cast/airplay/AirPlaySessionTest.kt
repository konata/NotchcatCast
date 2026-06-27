package notch.cat.cast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

  @Test
  fun pairVerifyReportsStepAndFailedSignature() {
    val session = AirPlaySession(ByteArray(32) { it.toByte() })

    val first = ByteArray(68) { (it * 3 + 1).toByte() }.also { it[0] = 1 }
    val firstResult = session.pairVerify(first)

    assertEquals(1, firstResult.step)
    assertEquals(96, firstResult.response.size)
    assertFalse(firstResult.verified)

    val second = ByteArray(68) { (it * 5 + 7).toByte() }.also { it[0] = 0 }
    val secondResult = session.pairVerify(second)

    assertEquals(0, secondResult.step)
    assertEquals(0, secondResult.response.size)
    assertFalse(secondResult.verified)
  }
}
