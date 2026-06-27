package notch.cat.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DlnaHttpTest {
  @Test
  fun readParsesRequestLineHeadersAndUtf8Body() {
    val raw = "POST /upnp/control/AVTransport HTTP/1.1\r\nHOST: tv\r\nContent-Length: 6\r\nSOAPAction: \"#Play\"\r\n\r\n莽莽".toByteArray(Charsets.UTF_8)

    val request = DlnaHttp.read(ByteArrayInputStream(raw))!!

    assertEquals("POST", request.method)
    assertEquals("/upnp/control/AVTransport", request.path)
    assertEquals("tv", request.headers["host"])
    assertEquals("\"#Play\"", request.headers["soapaction"])
    assertEquals("莽莽", request.body)
    assertEquals("莽莽", request.bodyBytes.toString(Charsets.UTF_8))
  }

  @Test
  fun readRejectsOversizedHeaders() {
    val raw = ("GET / HTTP/1.1\r\nX: " + "a".repeat(70_000) + "\r\n\r\n").toByteArray()

    val error = runCatching { DlnaHttp.read(ByteArrayInputStream(raw)) }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
  }

  @Test
  fun writeSerializesResponseHeadersAndBody() {
    val output = ByteArrayOutputStream()
    val response = DlnaHttp.Response.ok("<xml/>", contentType = "text/xml")

    DlnaHttp.write(output, response, server = "Server/1", date = "Sat, 27 Jun 2026 10:00:00 GMT")
    val text = output.toString(Charsets.ISO_8859_1.name())

    assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
    assertTrue(text.contains("DATE: Sat, 27 Jun 2026 10:00:00 GMT\r\n"))
    assertTrue(text.contains("SERVER: Server/1\r\n"))
    assertTrue(text.contains("CONTENT-LENGTH: 6\r\n"))
    assertTrue(text.contains("CONTENT-TYPE: text/xml\r\n"))
    assertTrue(text.contains("CONNECTION: close\r\n"))
    assertTrue(text.endsWith("\r\n\r\n<xml/>"))
  }

  @Test
  fun writeCanOmitBodyWithoutChangingContentLength() {
    val output = ByteArrayOutputStream()
    val response = DlnaHttp.Response.ok("body").copy(omitBody = true)

    DlnaHttp.write(output, response, server = "Server/1", date = "Sat, 27 Jun 2026 10:00:00 GMT")
    val text = output.toString(Charsets.ISO_8859_1.name())

    assertTrue(text.contains("CONTENT-LENGTH: 4\r\n"))
    assertFalse(text.endsWith("body"))
  }
}
