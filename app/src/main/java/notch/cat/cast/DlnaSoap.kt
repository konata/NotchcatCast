package notch.cat.cast

import java.util.Locale

internal object DlnaSoap {
  fun action(headers: Map<String, String>, body: String): String {
    headers["soapaction"]?.trim()?.trim('"')?.substringAfter('#')?.takeIf { it.isNotBlank() }?.let { return it }
    val actionRegex = Regex("<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)(?:\\s|>)", RegexOption.DOT_MATCHES_ALL)
    return actionRegex.find(body.substringAfter("<s:Body", body))?.groupValues?.getOrNull(1).orEmpty()
  }

  fun arg(body: String, name: String) =
    Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.getOrNull(1)?.trim()?.unescapeXml().orEmpty()

  fun ok(service: String, action: String, values: Map<String, String> = emptyMap()) =
    """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">${
      values.entries.joinToString(separator = "") { (key, value) -> "<$key>${xml(value)}</$key>" }
    }</u:${action}Response></s:Body></s:Envelope>"""

  fun faultBody(code: Int, description: String) =
    """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${xml(description)}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

  fun parseTime(value: String): Long {
    val parts = value.trim().substringBefore(".").split(":").mapNotNull { it.toLongOrNull() }
    return when (parts.size) {
      3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
      2 -> parts[0] * 60L + parts[1]
      1 -> parts[0]
      else -> 0L
    } * 1000L
  }

  fun formatTime(milliseconds: Long): String {
    val s = milliseconds.coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600L, s % 3600L / 60L, s % 60L)
  }

  fun escapeXml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

  private fun xml(value: String) = escapeXml(value)
  private fun String.unescapeXml() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
