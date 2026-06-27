package notch.cat.cast.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class AirPlayRtspTest {
  @Test
  fun readRequestConsumesHeadersAndDeclaredBody() {
    val body = "volume: -12.0\r\n".toByteArray()
    val raw = "SET_PARAMETER rtsp://example.local/stream RTSP/1.0\r\nCSeq: 7\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body

    val request = AirPlayRtsp.read(ByteArrayInputStream(raw))

    assertEquals("SET_PARAMETER", request?.method)
    assertEquals("rtsp://example.local/stream", request?.path)
    assertEquals("RTSP/1.0", request?.protocol)
    assertEquals("7", request?.headers?.get("cseq"))
    assertArrayEquals(body, request!!.body)
  }

  @Test
  fun readRequestAcceptsLfOnlyHeadersSeenInAirPlayCaptures() {
    val body = "volume: -20.000000\n".toByteArray()
    val raw = (
      "SET_PARAMETER rtsp://example.local/stream RTSP/1.0\n" +
        "Content-Length: ${body.size}\n" +
        "Content-Type: text/parameters\n" +
        "CSeq: 9\n" +
        "\n"
      ).toByteArray() + body

    val request = AirPlayRtsp.read(ByteArrayInputStream(raw))

    assertEquals("SET_PARAMETER", request?.method)
    assertEquals("rtsp://example.local/stream", request?.path)
    assertEquals("RTSP/1.0", request?.protocol)
    assertEquals("9", request?.headers?.get("cseq"))
    assertEquals("text/parameters", request?.headers?.get("content-type"))
    assertArrayEquals(body, request!!.body)
  }

  @Test
  fun responseMirrorsRequestProtocolAndCSeq() {
    val request = AirPlayRtsp.Request("OPTIONS", "*", "RTSP/1.0", mapOf("cseq" to "3"), ByteArray(0))

    val text = String(AirPlayRtsp.Response.empty(extra = mapOf("Public" to "OPTIONS")).bytes(request), Charsets.ISO_8859_1)

    assert(text.startsWith("RTSP/1.0 200 OK\r\n"))
    assert(text.contains("\r\nCSeq: 3\r\n"))
    assert(text.contains("\r\nPublic: OPTIONS\r\n"))
    assert(text.endsWith("\r\n\r\n"))
  }

  @Test
  fun explicitEmptyTypedResponseKeepsContentType() {
    val request = AirPlayRtsp.Request("POST", "/pair-verify", "RTSP/1.0", mapOf("cseq" to "2"), ByteArray(0))

    val typed = String(AirPlayRtsp.Response.ok(ByteArray(0), "application/octet-stream").bytes(request), Charsets.ISO_8859_1)
    val empty = String(AirPlayRtsp.Response.empty().bytes(request), Charsets.ISO_8859_1)

    assert(typed.contains("\r\nContent-Type: application/octet-stream\r\n"))
    assert(typed.contains("\r\nContent-Length: 0\r\n"))
    assert(!empty.contains("\r\nContent-Type:"))
  }

  @Test
  fun switchingProtocolsResponseDoesNotAddBodyFramingHeaders() {
    val request = AirPlayRtsp.Request("POST", "/reverse", "HTTP/1.1", emptyMap(), ByteArray(0))

    val text = String(AirPlayRtsp.Response.switchingProtocols(mapOf("Connection" to "Upgrade", "Upgrade" to "PTTH/1.0")).bytes(request), Charsets.ISO_8859_1)

    assert(text.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))
    assert(!text.contains("\r\nContent-Length:"))
    assert(!text.contains("\r\nContent-Type:"))
    assert(text.contains("\r\nConnection: Upgrade\r\n"))
    assert(text.contains("\r\nUpgrade: PTTH/1.0\r\n"))
  }
}
