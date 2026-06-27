package notch.cat.cast.airplay

import android.content.Context
import android.net.Uri
import android.util.Log
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
  private val router by lazy {
    AirPlayRouter(
      identity = AirPlayIdentity(
        name = context.getString(R.string.application_name),
        uuid = uuid,
        deviceId = AirPlayDiscovery.deviceId(uuid),
        publicKey = session.publicKey,
        publicKeyHex = session.publicKeyHex
      ),
      callbacks = AirPlayRouter.Callbacks(
        playUrl = ::playUrl,
        stop = { send(CastCommand.Stop) },
        pairSetup = session::pairSetup,
        pairVerify = ::pairVerify,
        fairPlaySetup = session::fairPlaySetup,
        setup = ::setup,
        teardown = ::teardown
      )
    )
  }

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
    return router.route(request, remote)
  }

  private fun playUrl(uri: String) {
    Log.i(TAG, "AirPlay URL host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")}")
    send(CastCommand.Load(uri, play = true))
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

  private companion object {
    const val TAG = "mang"
    const val BPLIST = "application/x-apple-binary-plist"
  }
}
