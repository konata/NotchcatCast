package notch.cat.cast.airplay

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import java.net.InetAddress
import java.net.URI

internal data class AirPlayIdentity(
  val name: String,
  val uuid: String,
  val deviceId: String,
  val publicKey: ByteArray,
  val publicKeyHex: String
)

internal class AirPlayRouter(
  private val identity: AirPlayIdentity,
  private val callbacks: Callbacks
) {
  data class Callbacks(
    val playUrl: (String) -> Unit,
    val stop: () -> Unit,
    val pairSetup: () -> ByteArray,
    val pairVerify: (AirPlayRtsp.Request) -> AirPlayRtsp.Response,
    val fairPlaySetup: (ByteArray) -> ByteArray,
    val setup: (AirPlayRtsp.Request, InetAddress) -> AirPlayRtsp.Response,
    val teardown: (AirPlayRtsp.Request) -> AirPlayRtsp.Response
  )

  fun route(request: AirPlayRtsp.Request, remote: InetAddress): AirPlayRtsp.Response {
    val path = request.path.substringBefore("?")
    val isGet = request.method == "GET" || request.method == "HEAD"
    return when {
      isGet && path == "/info" -> AirPlayRtsp.Response.ok(AirPlayInfo.response(
        path = request.path,
        headers = request.headers,
        body = request.body,
        name = identity.name,
        uuid = identity.uuid,
        deviceId = identity.deviceId,
        publicKey = identity.publicKey,
        publicKeyHex = identity.publicKeyHex
      ), BPLIST)
      isGet && path == "/server-info" -> AirPlayRtsp.Response.ok(AirPlayServerInfo.xml(identity.deviceId), XML_PLIST)
      isGet && path == "/playback-info" -> AirPlayRtsp.Response.ok(playbackInfoPlist(), XML_PLIST)
      isGet && path == "/scrub" -> AirPlayRtsp.Response.ok("duration: 0.0\nposition: 0.0\n".toByteArray(), "text/parameters")
      request.method == "POST" && path == "/reverse" -> AirPlayRtsp.Response.switchingProtocols(mapOf("Connection" to "Upgrade", "Upgrade" to "PTTH/1.0"))
      request.method == "POST" && path == "/play" -> airplayUrlPlay(request)
      request.method == "POST" && path == "/rate" -> AirPlayRtsp.Response.empty()
      request.method == "POST" && path == "/stop" -> callbacks.stop().let { AirPlayRtsp.Response.empty() }
      request.method == "POST" && path == "/pair-setup" -> AirPlayRtsp.Response.ok(callbacks.pairSetup(), "application/octet-stream")
      request.method == "POST" && path == "/pair-verify" -> callbacks.pairVerify(request)
      request.method == "POST" && path == "/fp-setup" -> AirPlayRtsp.Response.ok(callbacks.fairPlaySetup(request.body), "application/octet-stream")
      request.method == "OPTIONS" -> AirPlayRtsp.Response.empty(extra = mapOf("Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"))
      request.method == "SETUP" -> callbacks.setup(request, remote)
      request.method == "RECORD" -> AirPlayRtsp.Response.empty(extra = mapOf("Session" to "1", "Audio-Latency" to "11025"))
      request.method == "GET_PARAMETER" -> AirPlayRtsp.Response.ok("volume: 0.000000\r\n".toByteArray(), "text/parameters")
      AirPlayControl.isNoop(request.method, path) -> AirPlayRtsp.Response.empty(extra = AirPlayControl.noopExtra(request.method, path))
      AirPlayControl.isMisdirected(request.method, path) -> AirPlayRtsp.Response(421, "Misdirected Request")
      request.method == "TEARDOWN" -> callbacks.teardown(request)
      else -> AirPlayRtsp.Response(404, "Not Found", "Not Found".toByteArray(), "text/plain")
    }
  }

  private fun airplayUrlPlay(request: AirPlayRtsp.Request): AirPlayRtsp.Response {
    val uri = airplayParams(request).let { it["Content-Location"] ?: it["content-location"] ?: "" }
    if (uri.isBlank() || runCatching { URI(uri).scheme.isNullOrBlank() }.getOrDefault(true)) return AirPlayRtsp.Response(400, "Bad Request", "Missing Content-Location".toByteArray(), "text/plain")
    callbacks.playUrl(uri)
    return AirPlayRtsp.Response.empty()
  }

  private fun airplayParams(request: AirPlayRtsp.Request): Map<String, String> {
    runCatching {
      (PropertyListParser.parse(request.body) as? NSDictionary)?.let { dict ->
        return dict.allKeys().associateWith { key -> dict.objectForKey(key).toJavaObject().toString() }
      }
    }
    return String(request.body, Charsets.UTF_8).lineSequence().mapNotNull { line ->
      line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).trim() to line.substring(it + 1).trim() }
    }.toMap()
  }

  private fun playbackInfoPlist() = xmlPlist(
    """
    <key>duration</key><real>0</real>
    <key>position</key><real>0</real>
    <key>rate</key><real>1</real>
    <key>readyToPlay</key><true/>
    """.trimIndent()
  )

  private fun xmlPlist(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>$body</dict></plist>""".toByteArray(Charsets.UTF_8)

  private companion object {
    const val BPLIST = "application/x-apple-binary-plist"
    const val XML_PLIST = "text/x-apple-plist+xml"
  }
}
