package notch.cat.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AirPlayRouterTest {
  @Test
  fun reverseRequestUpgradesHttpConnectionForAirPlayEvents() {
    val router = router()
    val request = AirPlayRtsp.Request(
      method = "POST",
      path = "/reverse",
      protocol = "HTTP/1.1",
      headers = mapOf("cseq" to "4", "connection" to "Upgrade", "upgrade" to "PTTH/1.0"),
      body = ByteArray(0)
    )

    val response = router.route(request, InetAddress.getLoopbackAddress())
    val text = String(response.bytes(request), Charsets.ISO_8859_1)

    assertEquals(101, response.status)
    assertEquals("Switching Protocols", response.reason)
    assertFalse(response.close)
    assertTrue(text.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))
    assertTrue(text.contains("\r\nConnection: Upgrade\r\n"))
    assertTrue(text.contains("\r\nUpgrade: PTTH/1.0\r\n"))
  }

  @Test
  fun playRequestParsesTextParametersAndDelegatesUrl() {
    var playedUrl = ""
    val router = router(playUrl = { playedUrl = it })
    val request = AirPlayRtsp.Request(
      method = "POST",
      path = "/play",
      protocol = "HTTP/1.1",
      headers = mapOf("cseq" to "5", "content-type" to "text/parameters"),
      body = "Content-Location: https://example.test/video.m3u8\r\nStart-Position: 0\r\n".toByteArray()
    )

    val response = router.route(request, InetAddress.getLoopbackAddress())

    assertEquals(200, response.status)
    assertEquals("https://example.test/video.m3u8", playedUrl)
  }

  private fun router(playUrl: (String) -> Unit = {}) = AirPlayRouter(
    identity = AirPlayIdentity(
      name = "莽莽投屏",
      uuid = "626d0415-e72f-3f1d-8d3c-7b20056358ed",
      deviceId = "62:6D:04:15:E7:2F",
      publicKey = ByteArray(32) { it.toByte() },
      publicKeyHex = "00"
    ),
    callbacks = AirPlayRouter.Callbacks(
      playUrl = playUrl,
      stop = {},
      pairSetup = { ByteArray(0) },
      pairVerify = { AirPlayRtsp.Response.empty() },
      fairPlaySetup = { ByteArray(0) },
      setup = { _, _ -> AirPlayRtsp.Response.empty() },
      teardown = { AirPlayRtsp.Response.empty() }
    )
  )
}
