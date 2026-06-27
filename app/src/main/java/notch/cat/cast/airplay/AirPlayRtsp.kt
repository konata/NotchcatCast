package notch.cat.cast.airplay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object AirPlayRtsp {
  data class Request(val method: String, val path: String, val protocol: String, val headers: Map<String, String>, val body: ByteArray)

  data class Response(
    val status: Int,
    val reason: String,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "text/plain",
    val extra: Map<String, String> = emptyMap(),
    val close: Boolean = false
  ) {
    fun bytes(request: Request): ByteArray {
      val protocol = if (request.protocol.startsWith("HTTP", ignoreCase = true)) "HTTP/1.1" else "RTSP/1.0"
      val headers = buildList {
        add("$protocol $status $reason")
        request.headers["cseq"]?.let { add("CSeq: $it") }
        add("Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC))}")
        add("Server: AirTunes/${AirPlayProfile.SOURCE_VERSION}")
        add("Audio-Jack-Status: connected; type=analog")
        add("Content-Length: ${body.size}")
        if (body.isNotEmpty()) add("Content-Type: $contentType")
        extra.forEach { (key, value) -> add("$key: $value") }
      }.joinToString("\r\n", postfix = "\r\n\r\n")
      return headers.toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
      fun ok(body: ByteArray, contentType: String, extra: Map<String, String> = emptyMap()) = Response(200, "OK", body, contentType, extra)
      fun empty(extra: Map<String, String> = emptyMap()) = Response(200, "OK", extra = extra)
    }
  }

  fun read(input: InputStream): Request? {
    val header = ByteArrayOutputStream()
    val last = ArrayDeque<Int>(4)
    while (true) {
      val b = input.read()
      if (b < 0) return null
      header.write(b)
      if (last.size == 4) last.removeFirst()
      last.addLast(b)
      if (last.size == 4 && last.toList() == listOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)) break
      require(header.size() <= 65536) { "AirPlay header too large" }
    }
    val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
    val requestLine = lines.firstOrNull() ?: return null
    val parts = requestLine.split(" ", limit = 3)
    if (parts.size < 2) return null
    val headers = lines.drop(1).mapNotNull { line ->
      val index = line.indexOf(':')
      if (index <= 0) null else line.take(index).trim().lowercase(Locale.US) to line.substring(index + 1).trim()
    }.toMap()
    val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    return Request(parts[0].uppercase(Locale.US), parts[1], parts.getOrElse(2) { "RTSP/1.0" }, headers, input.readFully(length) ?: return null)
  }
}

internal data class AirPlayStream(val type: Int, val connectionId: Long?)

internal fun InputStream.readFully(size: Int): ByteArray? {
  val bytes = ByteArray(size)
  var offset = 0
  while (offset < size) {
    val read = read(bytes, offset, size - offset)
    if (read < 0) return null
    offset += read
  }
  return bytes
}
