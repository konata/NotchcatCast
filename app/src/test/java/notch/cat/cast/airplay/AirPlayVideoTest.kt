package notch.cat.cast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirPlayVideoTest {
  @Test
  fun packetParserReadsAirPlayVideoHeader() {
    val payload = byteArrayOf(1, 2, 3, 4, 5)
    val buffer = ByteBuffer.allocate(128 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(payload.size)
    buffer.put(1)
    buffer.put(0x10)
    buffer.put(0x16)
    buffer.put(1)
    buffer.putLong(123456789L)
    buffer.position(128)
    buffer.put(payload)
    val bytes = buffer.array()

    val packet = AirPlayVideoPacket.parse(bytes)

    assertEquals(1, packet.type)
    assertEquals(0x0116, packet.option)
    assertEquals(123456789L, packet.timestamp)
    assertArrayEquals(payload, packet.payload)
  }

  @Test
  fun avcConfigExtractsSpsAndPpsAsAnnexB() {
    val sps = byteArrayOf(0x67, 0x64, 0x00, 0x20)
    val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
    val payload = byteArrayOf(
      1, 0x64, 0, 0x20, -1, -31,
      0, sps.size.toByte(),
      *sps,
      1,
      0, pps.size.toByte(),
      *pps
    )

    assertArrayEquals(
      byteArrayOf(0, 0, 0, 1, *sps, 0, 0, 0, 1, *pps),
      AirPlayH264.configToAnnexB(payload)
    )
  }

  @Test
  fun samplesReplaceLengthPrefixesWithAnnexBStartCodes() {
    val first = byteArrayOf(0x65, 1, 2)
    val second = byteArrayOf(0x41, 3)
    val samples = ByteBuffer.allocate(4 + first.size + 4 + second.size).order(ByteOrder.BIG_ENDIAN)
      .putInt(first.size)
      .put(first)
      .putInt(second.size)
      .put(second)
      .array()

    AirPlayH264.samplesToAnnexB(samples)

    assertArrayEquals(byteArrayOf(0, 0, 0, 1, *first, 0, 0, 0, 1, *second), samples)
  }
}
