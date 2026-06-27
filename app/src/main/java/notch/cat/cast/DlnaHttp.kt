package notch.cat.cast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal object DlnaHttp {
  data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String, val bodyBytes: ByteArray)

  data class Response(
    val statusCode: Int,
    val reason: String,
    val body: String,
    val contentType: String = """text/xml; charset="utf-8"""",
    val extraHeaders: Map<String, String> = emptyMap(),
    val omitBody: Boolean = false
  ) {
    companion object {
      fun ok(body: String, contentType: String = """text/xml; charset="utf-8"""") = Response(statusCode = 200, reason = "OK", body = body.trimStart(), contentType = contentType)
      fun empty() = Response(statusCode = 200, reason = "OK", body = "")
    }
  }

  fun read(input: InputStream): Request? {
    val headerBytes = ByteArrayOutputStream()
    val last = ArrayDeque<Int>(4)
    while (true) {
      val b = input.read()
      if (b == -1) return null
      headerBytes.write(b)
      if (last.size == 4) last.removeFirst()
      last.addLast(b)
      if (last.size == 4 && last.toList() == listOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)) break
      if (headerBytes.size() > 65536) throw IllegalArgumentException("HTTP header too large")
    }

    val lines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
    val requestLine = lines.firstOrNull() ?: return null
    val parts = requestLine.split(" ", limit = 3)
    if (parts.size < 2) return null
    val headers = parseHeaders(lines.drop(1))
    val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val bodyBytes = ByteArray(contentLength)
    var offset = 0
    while (offset < contentLength) {
      val read = input.read(bodyBytes, offset, contentLength - offset)
      if (read == -1) break
      offset += read
    }
    return Request(parts[0].uppercase(Locale.US), parts[1], headers, String(bodyBytes, 0, offset, Charsets.UTF_8), bodyBytes.copyOf(offset))
  }

  fun write(output: OutputStream, response: Response, server: String, date: String) {
    val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
    val hasConnection = response.extraHeaders.keys.any { it.equals("connection", ignoreCase = true) }
    val headers = message(
      *(listOf(
        "HTTP/1.1 ${response.statusCode} ${response.reason}",
        "DATE: $date",
        "SERVER: $server",
        "CONTENT-LENGTH: ${bodyBytes.size}",
        "CONTENT-TYPE: ${response.contentType}",
        if (hasConnection) null else "CONNECTION: close"
      ).filterNotNull() + response.extraHeaders.map { (key, value) -> "$key: $value" }).toTypedArray()
    )
    output.use {
      it.write(headers.toByteArray(Charsets.ISO_8859_1))
      if (!response.omitBody) it.write(bodyBytes)
      it.flush()
    }
  }

  fun parseHeaders(lines: List<String>): Map<String, String> =
    lines.mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).lowercase(Locale.US).trim() to line.substring(it + 1).trim() } }.toMap()

  fun message(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n\r\n")
}
