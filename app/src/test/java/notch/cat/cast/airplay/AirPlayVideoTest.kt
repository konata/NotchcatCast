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
  fun packetClassifiesSuspendAndResumeControlsSeparatelyFromPayload() {
    val suspend = videoPacket(type = 1, option = 0x0156, payload = ByteArray(0))
    val resume = videoPacket(type = 1, option = 0x0116, payload = byteArrayOf(1, 2, 3))

    assertEquals(AirPlayVideoControl.SUSPEND, suspend.control)
    assertEquals(AirPlayVideoPayload.EMPTY_CONFIG, suspend.payloadType)
    assertEquals(AirPlayVideoControl.RESUME, resume.control)
    assertEquals(AirPlayVideoPayload.CODEC_CONFIG, resume.payloadType)
  }

  @Test
  fun packetClassifiesStreamingReportsAndKeepAlivesAsIgnorablePayloads() {
    val report = videoPacket(type = 5, payload = byteArrayOf('b'.code.toByte(), 'p'.code.toByte()))
    val keepAlive = videoPacket(type = 2, payload = ByteArray(0))

    assertEquals(AirPlayVideoPayload.REPORT, report.payloadType)
    assertEquals(AirPlayVideoPayload.KEEP_ALIVE, keepAlive.payloadType)
  }

  @Test
  fun codecConfigCarriesMirrorDimensionsFromTypeOneHeader() {
    val packet = videoPacket(type = 1, option = 0x0116, width = 334f, height = 720f, payload = avcConfigPayload())
    val config = AirPlayH264.parseConfig(packet.payload, packet.format)

    assertEquals(AirPlayVideoFormat(sourceWidth = 334, sourceHeight = 720, width = 334, height = 720), packet.format)
    assertEquals(334, config.width)
    assertEquals(720, config.height)
  }

  @Test
  fun avcConfigExtractsSpsAndPpsAsAnnexB() {
    val sps = byteArrayOf(0x67, 0x64, 0x00, 0x20)
    val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
    val payload = avcConfigPayload(sps, pps)

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

  private fun videoPacket(type: Int, option: Int = 0, width: Float = 0f, height: Float = 0f, payload: ByteArray): AirPlayVideoPacket {
    val buffer = ByteBuffer.allocate(128 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(payload.size)
    buffer.put(type.toByte())
    buffer.put(0)
    buffer.putShort(option.toShort())
    buffer.putLong(42L)
    buffer.putFloat(16, width)
    buffer.putFloat(20, height)
    buffer.putFloat(40, width)
    buffer.putFloat(44, height)
    buffer.putFloat(56, width)
    buffer.putFloat(60, height)
    buffer.position(128)
    buffer.put(payload)
    return AirPlayVideoPacket.parse(buffer.array())
  }

  private fun avcConfigPayload(
    sps: ByteArray = byteArrayOf(0x67, 0x64, 0x00, 0x20),
    pps: ByteArray = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
  ) = byteArrayOf(
    1, 0x64, 0, 0x20, -1, -31,
    0, sps.size.toByte(),
    *sps,
    1,
    0, pps.size.toByte(),
    *pps
  )
}
