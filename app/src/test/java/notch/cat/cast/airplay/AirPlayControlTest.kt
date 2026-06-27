package notch.cat.cast.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
  fun flushResponseCarriesRtptimeHeaderSeenInMirroringCaptures() {
    assertEquals(mapOf("Session" to "1", "RTP-Info" to "rtptime=0"), AirPlayControl.noopExtra("FLUSH", "rtsp://receiver/session"))
    assertEquals(mapOf("Session" to "1"), AirPlayControl.noopExtra("SET_PARAMETER", "rtsp://receiver/session"))
  }

  @Test
  fun doesNotTreatUnknownPostAsNoop() {
    assertFalse(AirPlayControl.isNoop("POST", "/unknown"))
  }

  @Test
  fun marksUnsupportedFairPlayVariantAsMisdirected() {
    assertTrue(AirPlayControl.isMisdirected("POST", "/fp-setup2"))
    assertFalse(AirPlayControl.isMisdirected("POST", "/fp-setup"))
  }
}
