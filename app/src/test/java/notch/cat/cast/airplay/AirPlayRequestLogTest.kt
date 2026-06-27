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
      headers = mapOf("cseq" to "4", "user-agent" to "AirPlay/670.6.2", "content-type" to "application/octet-stream"),
      body = ByteArray(16)
    )

    assertEquals("POST /fp-setup cseq=4 ua=AirPlay/670.6.2 ct=application/octet-stream len=16", AirPlayRequestLog.summary(request))
  }

  @Test
  fun summaryPreservesInfoDiscoveryQuery() {
    val request = AirPlayRtsp.Request(
      method = "GET",
      path = "/info?txtAirPlay?txtRAOP",
      protocol = "RTSP/1.0",
      headers = emptyMap(),
      body = ByteArray(0)
    )

    assertEquals("GET /info?txtAirPlay?txtRAOP cseq=- ua=- ct=- len=0", AirPlayRequestLog.summary(request))
  }
}
