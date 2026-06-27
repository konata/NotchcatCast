package notch.cat.cast.airplay

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.github.serezhka.airplay.lib.internal.FairPlay
import com.github.serezhka.airplay.lib.internal.FairPlayVideoDecryptor
import com.github.serezhka.airplay.lib.internal.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import notch.cat.cast.CastCommand
import notch.cat.cast.R
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class AirPlayReceiver(
  private val context: Context,
  private val uuid: String,
  private val send: (CastCommand) -> Unit
) {
  private var scope: CoroutineScope? = null
  private var server: ServerSocket? = null
  private var videoServer: ServerSocket? = null
  private var audioDataSocket: DatagramSocket? = null
  private var audioControlSocket: DatagramSocket? = null
  private var airplayRegistration: NsdManager.RegistrationListener? = null
  private var raopRegistration: NsdManager.RegistrationListener? = null
  private val session = AirPlaySession()

  fun start(): Int {
    if (scope?.isActive == true) return server?.localPort ?: 0
    val socket = ServerSocket(0)
    server = socket
    val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope = activeScope
    activeScope.launch { controlLoop(socket, activeScope) }
    register(socket.localPort)
    return socket.localPort
  }

  fun stop() {
    val activeScope = scope ?: return
    scope = null
    activeScope.cancel()
    unregister()
    runCatching { server?.close() }
    runCatching { videoServer?.close() }
    runCatching { audioDataSocket?.close() }
    runCatching { audioControlSocket?.close() }
    server = null
    videoServer = null
    audioDataSocket = null
    audioControlSocket = null
    AirPlayMirrorBus.stop()
  }

  private fun controlLoop(socket: ServerSocket, activeScope: CoroutineScope) {
    while (activeScope.isActive) {
      val client = try {
        socket.accept()
      } catch (error: Exception) {
        if (activeScope.isActive) Log.e(TAG, "AirPlay accept failed", error)
        break
      }
      activeScope.launch { handleControl(client) }
    }
  }

  private fun handleControl(socket: Socket) {
    socket.use {
      val input = it.getInputStream()
      val output = it.getOutputStream()
      while (scope?.isActive == true) {
        val request = runCatching { readRequest(input) }.getOrElse { error ->
          Log.e(TAG, "AirPlay request parse failed", error)
          return
        } ?: return
        Log.i(TAG, "AirPlay ${request.method} ${request.path.substringBefore('?')} len=${request.body.size}")
        val response = runCatching { route(request) }.getOrElse { error ->
          Log.e(TAG, "AirPlay handler failed", error)
          RtspResponse(500, "Internal Server Error", error.message.orEmpty().toByteArray(Charsets.UTF_8), "text/plain")
        }
        output.write(response.bytes(request))
        output.flush()
        if (response.close) return
      }
    }
  }

  private fun route(request: RtspRequest): RtspResponse {
    val path = request.path.substringBefore("?")
    val isGet = request.method == "GET" || request.method == "HEAD"
    return when {
      isGet && path == "/info" -> RtspResponse.ok(infoPlist(), BPLIST)
      isGet && path == "/server-info" -> RtspResponse.ok(serverInfoPlist(), XML_PLIST)
      isGet && path == "/playback-info" -> RtspResponse.ok(playbackInfoPlist(), XML_PLIST)
      isGet && path == "/scrub" -> RtspResponse.ok("duration: 0.0\nposition: 0.0\n".toByteArray(), "text/parameters")
      request.method == "POST" && path == "/play" -> airplayUrlPlay(request)
      request.method == "POST" && path == "/rate" -> RtspResponse.empty()
      request.method == "POST" && path == "/stop" -> send(CastCommand.Stop).let { RtspResponse.empty() }
      request.method == "POST" && path == "/pair-setup" -> RtspResponse.ok(session.pairSetup(), "application/octet-stream")
      request.method == "POST" && path == "/pair-verify" -> RtspResponse.ok(session.pairVerify(request.body), "application/octet-stream")
      request.method == "POST" && path == "/fp-setup" -> RtspResponse.ok(session.fairPlaySetup(request.body), "application/octet-stream")
      request.method == "OPTIONS" -> RtspResponse.empty(extra = mapOf("Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"))
      request.method == "SETUP" -> setup(request)
      request.method == "RECORD" -> RtspResponse.empty(extra = mapOf("Session" to "1", "Audio-Latency" to "11025"))
      request.method == "GET_PARAMETER" -> RtspResponse.ok("volume: 0.000000\r\n".toByteArray(), "text/parameters")
      request.method == "SET_PARAMETER" || request.method == "FLUSH" || path == "/feedback" -> RtspResponse.empty(extra = mapOf("Session" to "1"))
      request.method == "TEARDOWN" -> teardown()
      else -> RtspResponse(404, "Not Found", "Not Found".toByteArray(), "text/plain")
    }
  }

  private fun airplayUrlPlay(request: RtspRequest): RtspResponse {
    val params = airplayParams(request)
    val uri = params["Content-Location"] ?: params["content-location"] ?: ""
    if (uri.isBlank()) return RtspResponse(400, "Bad Request", "Missing Content-Location".toByteArray(), "text/plain")
    Log.i(TAG, "AirPlay URL host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")}")
    send(CastCommand.Load(uri, play = true))
    return RtspResponse.empty()
  }

  private fun setup(request: RtspRequest): RtspResponse {
    val plist = request.body.dictionary()
    plist.bytes("ekey")?.let {
      session.ekey = it
      session.eiv = plist.bytes("eiv")
      return RtspResponse.ok(setupPlist(eventPort = server?.localPort ?: 0, timingPort = 0), BPLIST, extra = mapOf("Session" to "1"))
    }

    val stream = plist.stream() ?: return RtspResponse.empty(extra = mapOf("Session" to "1"))
    return when (stream.type) {
      110 -> {
        session.streamConnectionId = java.lang.Long.toUnsignedString(stream.connectionId ?: 0L)
        AirPlayMirrorBus.start()
        send(CastCommand.StartMirror)
        RtspResponse.ok(videoSetupPlist(startVideoServer()), BPLIST, extra = mapOf("Session" to "1"))
      }

      96, 100, 101 -> RtspResponse.ok(audioSetupPlist(stream.type), BPLIST, extra = mapOf("Session" to "1"))
      else -> RtspResponse.empty(extra = mapOf("Session" to "1"))
    }
  }

  private fun teardown(): RtspResponse {
    AirPlayMirrorBus.stop()
    send(CastCommand.StopMirror)
    return RtspResponse.empty(extra = mapOf("Session" to "1"))
  }

  private fun startVideoServer(): Int {
    videoServer?.let { return it.localPort }
    val socket = ServerSocket(0)
    videoServer = socket
    scope?.launch { videoLoop(socket) }
    return socket.localPort
  }

  private fun videoLoop(socket: ServerSocket) {
    while (scope?.isActive == true) {
      val client = runCatching { socket.accept() }.getOrElse { return }
      handleVideo(client)
    }
  }

  private fun handleVideo(socket: Socket) {
    socket.use {
      val input = it.getInputStream()
      val decryptor by lazy { session.videoDecryptor() }
      while (scope?.isActive == true) {
        val header = input.readFully(VIDEO_HEADER_BYTES) ?: break
        val payloadSize = header.leInt(0)
        if (payloadSize <= 0 || payloadSize > MAX_VIDEO_PAYLOAD_BYTES) {
          Log.w(TAG, "AirPlay video payload has invalid size: $payloadSize")
          break
        }
        val packetBytes = ByteArray(VIDEO_HEADER_BYTES + payloadSize)
        System.arraycopy(header, 0, packetBytes, 0, header.size)
        val payload = input.readFully(payloadSize) ?: break
        System.arraycopy(payload, 0, packetBytes, VIDEO_HEADER_BYTES, payloadSize)
        val packet = AirPlayVideoPacket.parse(packetBytes)
        when (packet.type) {
          1 -> AirPlayMirrorBus.config(AirPlayH264.parseConfig(packet.payload))
          0 -> {
            decryptor.decrypt(packet.payload)
            AirPlayH264.samplesToAnnexB(packet.payload)
            AirPlayMirrorBus.frame(packet.payload)
          }
        }
      }
    }
    AirPlayMirrorBus.stop()
    send(CastCommand.StopMirror)
  }

  private fun audioSetupPlist(type: Int): ByteArray {
    val data = audioDataSocket ?: DatagramSocket(0).also { audioDataSocket = it }
    val control = audioControlSocket ?: DatagramSocket(0).also { audioControlSocket = it }
    return plist {
      put("streams", NSArray(NSDictionary().apply {
        put("type", type)
        put("dataPort", data.localPort)
        put("controlPort", control.localPort)
      }))
    }
  }

  private fun videoSetupPlist(videoPort: Int) = plist {
    put("streams", NSArray(NSDictionary().apply {
      put("type", 110)
      put("dataPort", videoPort)
    }))
    put("eventPort", server?.localPort ?: 0)
    put("timingPort", 0)
  }

  private fun setupPlist(eventPort: Int, timingPort: Int) = plist {
    put("eventPort", eventPort)
    put("timingPort", timingPort)
  }

  private fun infoPlist() = plist {
    put("audioFormats", NSArray(
      NSDictionary().apply { put("type", 100); put("audioInputFormats", 67108860); put("audioOutputFormats", 67108860) },
      NSDictionary().apply { put("type", 101); put("audioInputFormats", 67108860); put("audioOutputFormats", 67108860) },
    ))
    put("audioLatencies", NSArray(
      NSDictionary().apply { put("type", 100); put("audioType", "default"); put("inputLatencyMicros", false) },
      NSDictionary().apply { put("type", 101); put("audioType", "default"); put("inputLatencyMicros", false) },
    ))
    put("displays", NSArray(NSDictionary().apply {
      put("features", 14)
      put("height", 1080)
      put("heightPixels", 1080)
      put("heightPhysical", false)
      put("width", 1920)
      put("widthPixels", 1920)
      put("widthPhysical", false)
      put("maxFPS", 60)
      put("overscanned", false)
      put("refreshRate", 60)
      put("rotation", false)
      put("uuid", uuid)
    }))
    put("features", AIRPLAY_FEATURES)
    put("keepAliveSendStatsAsBody", 1)
    put("model", AIRPLAY_INFO_MODEL)
    put("name", context.getString(R.string.application_name))
    put("pi", uuid)
    put("pk", NSData(session.publicKey))
    put("sourceVersion", AIRPLAY_SOURCE_VERSION)
    put("statusFlags", 68)
    put("vv", 2)
  }

  private fun serverInfoPlist() = xmlPlist(
    """
    <key>deviceid</key><string>${deviceId()}</string>
    <key>features</key><integer>119</integer>
    <key>model</key><string>$AIRPLAY_INFO_MODEL</string>
    <key>protovers</key><string>1.0</string>
    <key>srcvers</key><string>$AIRPLAY_SOURCE_VERSION</string>
    """.trimIndent()
  )

  private fun playbackInfoPlist() = xmlPlist(
    """
    <key>duration</key><real>0</real>
    <key>position</key><real>0</real>
    <key>rate</key><real>1</real>
    <key>readyToPlay</key><true/>
    """.trimIndent()
  )

  private fun airplayParams(request: RtspRequest): Map<String, String> {
    runCatching {
      (PropertyListParser.parse(request.body) as? NSDictionary)?.let { dict ->
        return dict.allKeys().associateWith { key -> dict.objectForKey(key).toJavaObject().toString() }
      }
    }
    return String(request.body, Charsets.UTF_8).lineSequence().mapNotNull { line ->
      line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).trim() to line.substring(it + 1).trim() }
    }.toMap()
  }

  private fun register(port: Int) {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return
    val airplay = listener("AirPlay")
    val raop = listener("RAOP")
    airplayRegistration = airplay
    raopRegistration = raop
    val id = deviceId()
    runCatching {
      nsd.registerService(NsdServiceInfo().apply {
        serviceName = context.getString(R.string.application_name)
        serviceType = "_airplay._tcp."
        this.port = port
        setAttribute("deviceid", id)
        setAttribute("features", AIRPLAY_FEATURES_HEX)
        setAttribute("srcvers", AIRPLAY_SOURCE_VERSION)
        setAttribute("flags", "0x44")
        setAttribute("vv", "2")
        setAttribute("model", AIRPLAY_SERVICE_MODEL)
        setAttribute("rhd", "5.6.0.0")
        setAttribute("pw", "false")
        setAttribute("pk", session.publicKeyHex)
      }, NsdManager.PROTOCOL_DNS_SD, airplay)
      nsd.registerService(NsdServiceInfo().apply {
        serviceName = "${id.replace(":", "")}@${context.getString(R.string.application_name)}"
        serviceType = "_raop._tcp."
        this.port = port
        setAttribute("txtvers", "1")
        setAttribute("am", AIRPLAY_SERVICE_MODEL)
        setAttribute("ch", "2")
        setAttribute("cn", "1,3")
        setAttribute("da", "true")
        setAttribute("et", "0,3,5")
        setAttribute("ek", "1")
        setAttribute("ft", AIRPLAY_FEATURES_HEX)
        setAttribute("md", "0,1,2")
        setAttribute("pk", session.publicKeyHex)
        setAttribute("sr", "44100")
        setAttribute("ss", "16")
        setAttribute("sv", "false")
        setAttribute("sm", "false")
        setAttribute("tp", "UDP")
        setAttribute("sf", "0x44")
        setAttribute("vs", AIRPLAY_SOURCE_VERSION)
        setAttribute("vn", "65537")
      }, NsdManager.PROTOCOL_DNS_SD, raop)
    }.onFailure { Log.e(TAG, "AirPlay registration failed", it) }
  }

  private fun unregister() {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return
    listOfNotNull(airplayRegistration, raopRegistration).forEach { listener ->
      runCatching { nsd.unregisterService(listener) }
    }
    airplayRegistration = null
    raopRegistration = null
  }

  private fun listener(label: String) = object : NsdManager.RegistrationListener {
    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
      Log.i(TAG, "$label registered ${serviceInfo.serviceName}:${serviceInfo.port}")
    }

    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
      Log.e(TAG, "$label registration failed: $errorCode")
    }

    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
  }

  private fun deviceId() = uuid.replace("-", "").take(12).chunked(2).joinToString(":") { it.uppercase(Locale.US) }

  private fun plist(block: NSDictionary.() -> Unit): ByteArray = BinaryPropertyListWriter.writeToArray(NSDictionary().apply(block))
  private fun xmlPlist(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>$body</dict></plist>""".toByteArray(Charsets.UTF_8)

  private class AirPlaySession {
    private val pairing = Pairing()
    private val fairPlay = FairPlay()
    private var videoDecryptor: FairPlayVideoDecryptor? = null

    val publicKey: ByteArray get() = pairing.publicKey()
    val publicKeyHex: String get() = publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    var ekey: ByteArray? = null
    var eiv: ByteArray? = null
    var streamConnectionId: String? = null
      set(value) {
        field = value
        videoDecryptor = null
      }

    fun pairSetup() = ByteArrayOutputStream().also { pairing.pairSetup(it) }.toByteArray()
    fun pairVerify(body: ByteArray) = ByteArrayOutputStream().also { pairing.pairVerify(ByteArrayInputStream(body), it) }.toByteArray()
    fun fairPlaySetup(body: ByteArray) = ByteArrayOutputStream().also { fairPlay.fairPlaySetup(ByteArrayInputStream(body), it) }.toByteArray()

    fun videoDecryptor(): FairPlayVideoDecryptor {
      val active = videoDecryptor
      if (active != null) return active
      val key = ekey ?: error("AirPlay ekey missing")
      val secret = pairing.sharedSecret ?: error("AirPlay shared secret missing")
      val connectionId = streamConnectionId ?: error("AirPlay streamConnectionID missing")
      return FairPlayVideoDecryptor(fairPlay.decryptAesKey(key), secret, connectionId).also { videoDecryptor = it }
    }
  }

  internal data class AirPlayStream(val type: Int, val connectionId: Long?)

  internal data class RtspRequest(val method: String, val path: String, val protocol: String, val headers: Map<String, String>, val body: ByteArray)

  private data class RtspResponse(
    val status: Int,
    val reason: String,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "text/plain",
    val extra: Map<String, String> = emptyMap(),
    val close: Boolean = false
  ) {
    fun bytes(request: RtspRequest): ByteArray {
      val protocol = if (request.protocol.startsWith("HTTP", ignoreCase = true)) "HTTP/1.1" else "RTSP/1.0"
      val headers = buildList {
        add("$protocol $status $reason")
        request.headers["cseq"]?.let { add("CSeq: $it") }
        add("Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC))}")
        add("Server: AirTunes/$AIRPLAY_SOURCE_VERSION")
        add("Audio-Jack-Status: connected; type=analog")
        add("Content-Length: ${body.size}")
        if (body.isNotEmpty()) add("Content-Type: $contentType")
        extra.forEach { (key, value) -> add("$key: $value") }
      }.joinToString("\r\n", postfix = "\r\n\r\n")
      return headers.toByteArray(Charsets.ISO_8859_1) + body
    }

    companion object {
      fun ok(body: ByteArray, contentType: String, extra: Map<String, String> = emptyMap()) = RtspResponse(200, "OK", body, contentType, extra)
      fun empty(extra: Map<String, String> = emptyMap()) = RtspResponse(200, "OK", extra = extra)
    }
  }

  private companion object {
    const val TAG = "mang"
    const val BPLIST = "application/x-apple-binary-plist"
    const val XML_PLIST = "text/x-apple-plist+xml"
    const val AIRPLAY_FEATURES_HEX = "0x5A7FFFF7,0x1E"
    const val AIRPLAY_FEATURES = 130367356919L
    const val AIRPLAY_SOURCE_VERSION = "220.68"
    const val AIRPLAY_INFO_MODEL = "AppleTV3,2"
    const val AIRPLAY_SERVICE_MODEL = "AppleTV3,2C"
    const val VIDEO_HEADER_BYTES = 128
    const val MAX_VIDEO_PAYLOAD_BYTES = 8 * 1024 * 1024
  }
}

private fun java.io.InputStream.readFully(size: Int): ByteArray? {
  val bytes = ByteArray(size)
  var offset = 0
  while (offset < size) {
    val read = read(bytes, offset, size - offset)
    if (read < 0) return null
    offset += read
  }
  return bytes
}

private fun readRequest(input: java.io.InputStream): AirPlayReceiver.RtspRequest? {
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
  return AirPlayReceiver.RtspRequest(parts[0].uppercase(Locale.US), parts[1], parts.getOrElse(2) { "RTSP/1.0" }, headers, input.readFully(length) ?: return null)
}

private fun ByteArray.leInt(offset: Int) = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8) or ((this[offset + 2].toInt() and 0xff) shl 16) or ((this[offset + 3].toInt() and 0xff) shl 24)

private fun ByteArray.dictionary() = BinaryPropertyListParser.parse(this) as NSDictionary

private fun NSDictionary.bytes(key: String): ByteArray? = objectForKey(key)?.toJavaObject() as? ByteArray

private fun NSDictionary.stream(): AirPlayReceiver.AirPlayStream? {
  val streams = objectForKey("streams")?.toJavaObject() as? Array<*> ?: return null
  val stream = streams.firstOrNull() as? Map<*, *> ?: return null
  val type = (stream["type"] as? Number)?.toInt() ?: return null
  val connectionId = (stream["streamConnectionID"] as? Number)?.toLong()
  return AirPlayReceiver.AirPlayStream(type, connectionId)
}
