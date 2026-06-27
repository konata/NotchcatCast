package notch.cat.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AirPlayMirrorDecoderTest {
  @Test
  fun inputWriterWritesWholeFrameWhenBufferHasCapacity() {
    val frame = byteArrayOf(1, 2, 3)
    val buffer = ByteBuffer.allocate(3)

    val result = AirPlayCodecInput.writeFrame(buffer, frame)

    assertTrue(result.queued)
    assertEquals(3, result.size)
    assertEquals(3, buffer.position())
  }

  @Test
  fun inputWriterRejectsOversizedFrameWithoutTruncating() {
    val frame = byteArrayOf(1, 2, 3, 4)
    val buffer = ByteBuffer.allocate(3)

    val result = AirPlayCodecInput.writeFrame(buffer, frame)

    assertFalse(result.queued)
    assertEquals(0, result.size)
    assertEquals(0, buffer.position())
  }
}
