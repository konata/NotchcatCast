package notch.cat.cast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

internal object Http {
  data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

  data class Response(
    val statusCode: Int,
    val reason: String,
    val body: String,
    val contentType: String = """text/xml; charset="utf-8"""",
    val extraHeaders: Map<String, String> = emptyMap(),
    val omitBody: Boolean = false
  ) {
    companion object {
      fun ok(body: String, contentType: String = """text/xml; charset="utf-8"""") = Response(200, "OK", body.trimStart(), contentType)
    }
  }

  fun read(input: InputStream): Request? {
    val headerBytes = ByteArrayOutputStream()
    var tail = 0
    while (tail != Consts.Dlna.HTTP_HEADER_END) {
      val byte = input.read()
      if (byte < 0) return null
      headerBytes.write(byte)
      tail = (tail shl 8) or byte
      if (headerBytes.size() > Consts.Dlna.HTTP_HEADER_LIMIT) throw IllegalArgumentException("HTTP header too large")
    }

    val lines = String(headerBytes.toByteArray(), Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
    val request = lines.firstOrNull()?.split(" ", limit = 3)?.takeIf { it.size >= 2 } ?: return null
    val headers = parseHeaders(lines.drop(1))
    val body = input.readNBytes(headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0).toString(Charsets.UTF_8)
    return Request(request[0].uppercase(Locale.US), request[1], headers, body)
  }

  fun write(output: OutputStream, response: Response, server: String, date: String) {
    val body = response.body.toByteArray(Charsets.UTF_8)
    val headers = buildList {
      add("HTTP/1.1 ${response.statusCode} ${response.reason}")
      add("DATE: $date")
      add("SERVER: $server")
      add("CONTENT-LENGTH: ${body.size}")
      add("CONTENT-TYPE: ${response.contentType}")
      if (response.extraHeaders.keys.none { it.equals("connection", ignoreCase = true) }) add("CONNECTION: close")
      response.extraHeaders.forEach { (header, content) -> add("$header: $content") }
    }
    output.use {
      it.write(message(*headers.toTypedArray()).toByteArray(Charsets.ISO_8859_1))
      if (!response.omitBody) it.write(body)
      it.flush()
    }
  }

  fun parseHeaders(lines: List<String>) = lines.mapNotNull { line ->
    line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).lowercase(Locale.US).trim() to line.substring(it + 1).trim() }
  }.toMap()

  fun message(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n\r\n")
}

internal object Soap {
  fun action(headers: Map<String, String>, body: String): String {
    headers["soapaction"]?.trim()?.trim('"')?.substringAfter('#')?.takeIf { it.isNotBlank() }?.let { return it }
    return Regex("<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)(?:\\s|>)", RegexOption.DOT_MATCHES_ALL).find(body.substringAfter("<s:Body", body))?.groupValues?.getOrNull(1).orEmpty()
  }

  fun argument(body: String, name: String): String {
    val tag = Regex.escape(name)
    return Regex("<(?:[A-Za-z0-9_]+:)?$tag(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$tag>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.getOrNull(1)?.trim()?.unescapeXml().orEmpty()
  }

  fun ok(service: String, action: String, arguments: Map<String, String> = emptyMap()) =
    """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">${
      arguments.entries.joinToString(separator = "") { (name, content) -> "<$name>${content.escapeXml()}</$name>" }
    }</u:${action}Response></s:Body></s:Envelope>"""

  fun faultBody(code: Int, description: String) =
    """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${description.escapeXml()}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

  fun parseTime(time: String): Long {
    val parts = time.trim().substringBefore(".").split(":").mapNotNull { it.toLongOrNull() }
    return when (parts.size) {
      3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
      2 -> parts[0] * 60L + parts[1]
      1 -> parts[0]
      else -> 0L
    } * 1000L
  }

  fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600L, seconds % 3600L / 60L, seconds % 60L)
  }

}
