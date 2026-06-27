package notch.cat.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayRequestLogTest {
  @Test
  fun summaryIncludesHandshakeFieldsWithoutQueryNoise() {
    val request = AirPlayRtsp.Request(
      method = "POST",
      path = "/fp-setup?ignored=1",
      protocol = "RTSP/1.0",
      headers = mapOf("cseq" to "4", "user-agent" to "AirPlay/670.6.2"),
      body = ByteArray(16)
    )

    assertEquals("POST /fp-setup cseq=4 ua=AirPlay/670.6.2 len=16", AirPlayRequestLog.summary(request))
  }
}
