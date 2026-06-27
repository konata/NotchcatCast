package notch.cat.cast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AirPlayVideoStreamTest {
  @Test
  fun streamTurnsCodecAndEncryptedSamplesIntoMirrorEvents() {
    var decrypted = false
    val stream = AirPlayVideoStream { bytes ->
      decrypted = true
      bytes[4] = 0x65
    }
    val configPacket = videoPacket(type = 1, option = 0x0116, width = 720f, height = 1280f, payload = avcConfigPayload())
    val framePayload = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN).putInt(3).put(byteArrayOf(0x41, 1, 2)).array()

    val configEvents = stream.handle(configPacket)
    val frameEvents = stream.handle(videoPacket(type = 0, payload = framePayload))

    assertEquals(AirPlayVideoStreamEvent.Resume, configEvents.first())
    val config = (configEvents.last() as AirPlayVideoStreamEvent.Config).config
    assertEquals("video/avc", config.mimeType)
    assertEquals(720, config.width)
    assertEquals(1280, config.height)
    assertTrue(decrypted)
    assertEquals(1, (frameEvents.single() as AirPlayVideoStreamEvent.Frame).count)
    assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), (frameEvents.single() as AirPlayVideoStreamEvent.Frame).bytes)
  }

  @Test
  fun streamReportsEmptyConfigAndUnknownPacketsWithoutTouchingMediaSink() {
    val stream = AirPlayVideoStream { error("decrypt should not be called") }

    assertEquals(listOf(AirPlayVideoStreamEvent.EmptyConfig), stream.handle(videoPacket(type = 1, payload = ByteArray(0))))
    assertEquals(listOf(AirPlayVideoStreamEvent.Unknown(type = 99, bytes = 3)), stream.handle(videoPacket(type = 99, payload = ByteArray(3))))
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

  private fun avcConfigPayload() = byteArrayOf(
    1, 0x64, 0, 0x20, -1, -31,
    0, 4, 0x67, 0x64, 0, 0x20,
    1,
    0, 4, 0x68, 0xee.toByte(), 0x3c, 0x80.toByte()
  )
}
