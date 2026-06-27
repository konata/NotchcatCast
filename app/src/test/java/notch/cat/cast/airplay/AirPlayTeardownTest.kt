package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayTeardownTest {
  @Test
  fun emptyBodyStopsAllStreams() {
    val teardown = AirPlayTeardown.parse(ByteArray(0))

    assertTrue(teardown.stopMirror)
    assertTrue(teardown.stopAudio)
  }

  @Test
  fun audioOnlyTeardownKeepsMirrorRunning() {
    val teardown = AirPlayTeardown.parse(bodyWithStreams(96))

    assertFalse(teardown.stopMirror)
    assertTrue(teardown.stopAudio)
  }

  @Test
  fun mirrorTeardownStopsMirrorOnly() {
    val teardown = AirPlayTeardown.parse(bodyWithStreams(110))

    assertTrue(teardown.stopMirror)
    assertFalse(teardown.stopAudio)
  }

  @Test
  fun malformedBodyFallsBackToStoppingEverything() {
    val teardown = AirPlayTeardown.parse("not a plist".toByteArray())

    assertTrue(teardown.stopMirror)
    assertTrue(teardown.stopAudio)
  }

  private fun bodyWithStreams(vararg types: Int) = BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
    put("streams", NSArray(*types.map { type ->
      NSDictionary().apply { put("type", type) }
    }.toTypedArray()))
  })
}
