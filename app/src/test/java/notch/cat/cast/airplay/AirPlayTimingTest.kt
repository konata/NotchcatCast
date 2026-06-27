package notch.cat.cast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayTimingTest {
  @Test
  fun ntpRequestMatchesAirPlayTimingPacketShape() {
    val packet = AirPlayNtp.request(
      transmitTimeNs = 1_000_000_000L,
      clientReferenceTime = 0x0102030405060708L,
      receiveTimeNs = 2_000_000_000L
    )

    assertEquals(32, packet.size)
    assertArrayEquals(byteArrayOf(0x80.toByte(), 0xd2.toByte(), 0, 0x07), packet.copyOfRange(0, 4))
    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), packet.copyOfRange(8, 16))
    assertArrayEquals(byteArrayOf(0x83.toByte(), 0xaa.toByte(), 0x7e, 0x82.toByte(), 0, 0, 0, 0), packet.copyOfRange(16, 24))
    assertArrayEquals(byteArrayOf(0x83.toByte(), 0xaa.toByte(), 0x7e, 0x81.toByte(), 0, 0, 0, 0), packet.copyOfRange(24, 32))
  }

  @Test
  fun ntpTimestampKeepsFractionalNanoseconds() {
    val packet = AirPlayNtp.request(transmitTimeNs = 1_500_000_000L)

    assertArrayEquals(byteArrayOf(0x83.toByte(), 0xaa.toByte(), 0x7e, 0x81.toByte(), 0x80.toByte(), 0, 0, 0), packet.copyOfRange(24, 32))
  }
}
