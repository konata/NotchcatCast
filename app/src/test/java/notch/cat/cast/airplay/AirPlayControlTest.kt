package notch.cat.cast.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayControlTest {
  @Test
  fun acceptsNoopControlRequestsSeenDuringMirroring() {
    assertTrue(AirPlayControl.isNoop("SET_PARAMETER", "rtsp://receiver/session"))
    assertTrue(AirPlayControl.isNoop("FLUSH", "rtsp://receiver/session"))
    assertTrue(AirPlayControl.isNoop("POST", "/feedback"))
    assertTrue(AirPlayControl.isNoop("POST", "/audioMode"))
  }

  @Test
  fun doesNotTreatUnknownPostAsNoop() {
    assertFalse(AirPlayControl.isNoop("POST", "/unknown"))
  }
}
