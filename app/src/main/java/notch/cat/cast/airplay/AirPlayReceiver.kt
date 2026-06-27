package notch.cat.cast.airplay

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import notch.cat.cast.CastCommand
import notch.cat.cast.R
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

private typealias RtspRequest = AirPlayRtsp.Request
private typealias RtspResponse = AirPlayRtsp.Response

class AirPlayReceiver(
  private val context: Context,
  private val uuid: String,
  publicKeySeed: ByteArray,
  private val send: (CastCommand) -> Unit
) {
  private var scope: CoroutineScope? = null
  private var server: ServerSocket? = null
  private var video: AirPlayVideoServer? = null
  private var audio: AirPlayAudio? = null
  private var discovery: AirPlayDiscovery? = null
  private var timing: AirPlayTiming? = null
  private val session = AirPlaySession(publicKeySeed)

  fun start(): Int {
    if (scope?.isActive == true) return server?.localPort ?: 0
    val socket = runCatching { ServerSocket(AirPlayProfile.PORT) }.getOrElse {
      Log.w(TAG, "AirPlay port ${AirPlayProfile.PORT} unavailable, using dynamic port", it)
      ServerSocket(0)
    }
    server = socket
    val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope = activeScope
    timing = AirPlayTiming(activeScope)
    audio = AirPlayAudio(activeScope)
    video = AirPlayVideoServer(
      scope = activeScope,
      decrypt = { session.videoDecryptor().decrypt(it) },
      event = ::handleVideoEvent,
      stopped = ::videoStopped
    )
    activeScope.launch { controlLoop(socket, activeScope) }
    discovery = AirPlayDiscovery(context, uuid, session.publicKeyHex).also { it.register(socket.localPort) }
    return socket.localPort
  }

  fun stop() {
    val activeScope = scope ?: return
    scope = null
    activeScope.cancel()
    discovery?.unregister()
    discovery = null
    runCatching { server?.close() }
    video?.stop()
    closeAudio()
    timing?.stop()
    server = null
    video = null
    audio = null
    timing = null
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
        val request = runCatching { AirPlayRtsp.read(input) }.getOrElse { error ->
          Log.e(TAG, "AirPlay request parse failed", error)
          return
        } ?: return
        val path = request.path.substringBefore("?")
        Log.i(TAG, "AirPlay ${AirPlayRequestLog.summary(request)} from=${socket.inetAddress.hostAddress}:${socket.port}")
        val response = runCatching { route(request, socket.inetAddress) }.getOrElse { error ->
          Log.e(TAG, "AirPlay handler failed", error)
          RtspResponse(500, "Internal Server Error", error.message.orEmpty().toByteArray(Charsets.UTF_8), "text/plain")
        }
        Log.i(TAG, "AirPlay ${response.status} ${request.method} $path")
        output.write(response.bytes(request))
        output.flush()
        if (response.close) return
      }
    }
  }

  private fun route(request: RtspRequest, remote: InetAddress): RtspResponse {
    val path = request.path.substringBefore("?")
    val isGet = request.method == "GET" || request.method == "HEAD"
    return when {
      isGet && path == "/info" -> RtspResponse.ok(AirPlayInfo.response(
        path = request.path,
        headers = request.headers,
        body = request.body,
        name = context.getString(R.string.application_name),
        uuid = uuid,
        deviceId = AirPlayDiscovery.deviceId(uuid),
        publicKey = session.publicKey,
        publicKeyHex = session.publicKeyHex
      ), BPLIST)
      isGet && path == "/server-info" -> RtspResponse.ok(AirPlayServerInfo.xml(AirPlayDiscovery.deviceId(uuid)), XML_PLIST)
      isGet && path == "/playback-info" -> RtspResponse.ok(playbackInfoPlist(), XML_PLIST)
      isGet && path == "/scrub" -> RtspResponse.ok("duration: 0.0\nposition: 0.0\n".toByteArray(), "text/parameters")
      request.method == "POST" && path == "/play" -> airplayUrlPlay(request)
      request.method == "POST" && path == "/rate" -> RtspResponse.empty()
      request.method == "POST" && path == "/stop" -> send(CastCommand.Stop).let { RtspResponse.empty() }
      request.method == "POST" && path == "/pair-setup" -> RtspResponse.ok(session.pairSetup(), "application/octet-stream")
      request.method == "POST" && path == "/pair-verify" -> pairVerify(request)
      request.method == "POST" && path == "/fp-setup" -> RtspResponse.ok(session.fairPlaySetup(request.body), "application/octet-stream")
      request.method == "OPTIONS" -> RtspResponse.empty(extra = mapOf("Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"))
      request.method == "SETUP" -> setup(request, remote)
      request.method == "RECORD" -> RtspResponse.empty(extra = mapOf("Session" to "1", "Audio-Latency" to "11025"))
      request.method == "GET_PARAMETER" -> RtspResponse.ok("volume: 0.000000\r\n".toByteArray(), "text/parameters")
      AirPlayControl.isNoop(request.method, path) -> RtspResponse.empty(extra = AirPlayControl.noopExtra(request.method, path))
      AirPlayControl.isMisdirected(request.method, path) -> RtspResponse(421, "Misdirected Request")
      request.method == "TEARDOWN" -> teardown(request)
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

  private fun pairVerify(request: RtspRequest): RtspResponse {
    val result = session.pairVerify(request.body)
    Log.i(TAG, "AirPlay pair-verify step=${result.step} verified=${result.verified} response=${result.response.size}")
    return RtspResponse.ok(result.response, "application/octet-stream", close = result.step == 0 && !result.verified)
  }

  private fun setup(request: RtspRequest, remote: InetAddress): RtspResponse = when (val setup = AirPlaySetup.parse(request.body)) {
    is AirPlaySetup.Session -> {
      session.ekey = setup.ekey
      session.eiv = setup.eiv
      val timingPort = setup.timingPort?.let { timing?.start(remote, it) } ?: 0
      Log.i(TAG, "AirPlay SETUP session timing=${setup.timingProtocol}:${setup.timingPort} local=$timingPort")
      RtspResponse.ok(AirPlaySetup.responseSession(eventPort = 0, timingPort = timingPort), BPLIST, extra = mapOf("Session" to "1"))
    }

    is AirPlaySetup.Streams -> {
      val unsupported = setup.unsupportedTypes
      if (unsupported.isNotEmpty()) Log.w(TAG, "AirPlay SETUP unknown stream types=$unsupported")
      val ports = setup.streams.mapNotNull { stream ->
        Log.i(TAG, "AirPlay SETUP stream type=${stream.type} connection=${stream.connectionId != null} control=${stream.controlPort} ct=${stream.compressionType} spf=${stream.samplesPerFrame} fmt=${stream.audioFormat} screen=${stream.usingScreen} media=${stream.isMedia}")
        when {
          stream.type == AirPlayStream.TYPE_MIRROR -> {
            session.streamConnectionId = stream.connectionId
            AirPlayMirrorBus.start()
            send(CastCommand.StartMirror)
            AirPlayStreamPort(type = AirPlayStream.TYPE_MIRROR, dataPort = startVideoServer())
          }

          stream.type in AirPlayStream.TYPE_AUDIO -> audioSetupPort(stream.type)
          else -> null
        }
      }
      RtspResponse.ok(AirPlaySetup.responseStreams(ports), BPLIST, extra = mapOf("Session" to "1"), close = unsupported.isNotEmpty())
    }

    AirPlaySetup.Empty -> RtspResponse.empty(extra = mapOf("Session" to "1"))
  }

  private fun teardown(request: RtspRequest): RtspResponse {
    val teardown = AirPlayTeardown.parse(request.body)
    if (teardown.stopAudio) closeAudio()
    if (teardown.stopMirror) {
      AirPlayMirrorBus.stop()
      send(CastCommand.StopMirror)
    }
    return RtspResponse(200, "OK", extra = mapOf("Session" to "1", "Connection" to "close"), close = true)
  }

  private fun closeAudio() {
    audio?.stats()?.takeIf { it.dataPackets > 0 || it.controlPackets > 0 }?.let {
      Log.i(TAG, "AirPlay audio stopped data=${it.dataPackets} control=${it.controlPackets}")
    }
    audio?.stop()
  }

  private fun startVideoServer(): Int {
    return (video ?: error("AirPlay video server unavailable")).start()
  }

  private fun handleVideoEvent(event: AirPlayVideoStreamEvent) {
    when (event) {
      AirPlayVideoStreamEvent.Suspend -> Log.i(TAG, "AirPlay video suspend")
      AirPlayVideoStreamEvent.Resume -> Log.i(TAG, "AirPlay video resume")
      is AirPlayVideoStreamEvent.Config -> {
        val config = event.config
        Log.i(TAG, "AirPlay video config codec=${config.mimeType} format=${config.width}x${config.height}")
        AirPlayMirrorBus.config(config)
      }
      is AirPlayVideoStreamEvent.Frame -> {
        if (event.count == 1 || event.count % 300 == 0) Log.i(TAG, "AirPlay video frame count=${event.count} bytes=${event.bytes.size}")
        AirPlayMirrorBus.frame(event.bytes)
      }
      AirPlayVideoStreamEvent.EmptyConfig -> Log.w(TAG, "AirPlay video codec config is empty")
      is AirPlayVideoStreamEvent.Unknown -> Log.w(TAG, "AirPlay video packet type=${event.type} bytes=${event.bytes}")
    }
  }

  private fun videoStopped() {
    AirPlayMirrorBus.stop()
    send(CastCommand.StopMirror)
  }

  private fun audioSetupPort(type: Int): AirPlayStreamPort {
    return (audio ?: error("AirPlay audio unavailable")).setup(type)
  }

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

  private fun xmlPlist(body: String) =
    """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>$body</dict></plist>""".toByteArray(Charsets.UTF_8)

  private companion object {
    const val TAG = "mang"
    const val BPLIST = "application/x-apple-binary-plist"
    const val XML_PLIST = "text/x-apple-plist+xml"
  }
}
