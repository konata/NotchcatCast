package notch.cat.cast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import android.util.Size
import android.view.Surface
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AirplayReceiver(private val context: Context, private val uuid: String, private val mirror: MirrorStream, private val send: (PlaybackCommand) -> Unit) {
  private val session = AirplaySession(uuid)
  private var running: AirplayRun? = null

  fun start(parent: CoroutineScope): Int {
    running?.takeIf { it.scope.isActive }?.let { return it.rtsp.localPort }
    val socket = runCatching { ServerSocket(Consts.Airplay.PORT) }.getOrElse {
      Log.w(Consts.Airplay.TAG, "Airplay port ${Consts.Airplay.PORT} unavailable, using dynamic port", it)
      ServerSocket(0)
    }
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    val run = AirplayRun(
      scope = scope,
      rtsp = socket,
      mirror = MirrorServer(scope, decrypt = { session.videoDecryptor().decrypt(it) }, event = ::mirrorPacket) {
        mirror.stop()
        send(PlaybackCommand.StopMirror)
      },
      audio = AirplayAudioSink(scope),
      unpublish = context.publishAirplay(uuid, session.publicKeyHex, socket.localPort),
      timing = AirplayTiming(scope)
    )
    running = run
    scope.acceptClients(socket, "Airplay accept failed", ::handle)
    return socket.localPort
  }

  fun stop() {
    val run = running ?: return
    running = null
    run.scope.cancel()
    run.unpublish()
    runCatching { run.rtsp.close() }
    run.mirror.stop()
    closeAudio(run.audio)
    run.timing.stop()
    mirror.stop()
  }

  private fun handle(socket: Socket) {
    socket.use {
      val input = it.getInputStream()
      val output = it.getOutputStream()
      while (running?.scope?.isActive == true) {
        val request = runCatching { readRtsp(input) }.getOrElse { error ->
          Log.e(Consts.Airplay.TAG, "Airplay request parse failed", error)
          return
        } ?: return
        val path = request.path.substringBefore("?")
        val response = runCatching {
          Log.i(Consts.Airplay.TAG, "Airplay ${request.summary()} from=${socket.inetAddress.hostAddress}:${socket.port}")
          route(request, socket.inetAddress)
        }.getOrElse { error ->
          Log.e(Consts.Airplay.TAG, "Airplay handler failed", error)
          RtspResponse(500, "Internal Server Error", error.message.orEmpty().toByteArray(), "text/plain")
        }
        Log.i(Consts.Airplay.TAG, "Airplay ${response.status} ${request.method} $path")
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
      isGet && path == "/info" -> RtspResponse.ok(
        airplayInfo(request, context.getString(R.string.application_name), uuid, deviceId(uuid), session.publicKey, session.publicKeyHex),
        Consts.Airplay.BPLIST
      )

      isGet && path == "/server-info" -> RtspResponse.ok(serverInfo(deviceId(uuid)), Consts.Airplay.XML_PLIST)
      isGet && path == "/playback-info" -> RtspResponse.ok(
        xmlPlist("<key>duration</key><real>0</real><key>position</key><real>0</real><key>rate</key><real>1</real><key>readyToPlay</key><true/>"),
        Consts.Airplay.XML_PLIST
      )

      isGet && path == "/scrub" -> RtspResponse.ok("duration: 0.0\nposition: 0.0\n".toByteArray(), "text/parameters")
      request.method == "POST" && path == "/reverse" -> RtspResponse.switching(mapOf("Connection" to "Upgrade", "Upgrade" to "PTTH/1.0"))
      request.method == "POST" && path == "/play" -> play(request)
      request.method == "POST" && path == "/rate" -> RtspResponse.empty()
      request.method == "POST" && path == "/stop" -> send(PlaybackCommand.Stop).let { RtspResponse.empty() }
      request.method == "POST" && path == "/pair-setup" -> RtspResponse.ok(session.pairSetup(), Consts.Airplay.OCTET)
      request.method == "POST" && path == "/pair-verify" -> pairVerify(request)
      request.method == "POST" && path == "/fp-setup" -> RtspResponse.ok(session.fairPlaySetup(request.body), Consts.Airplay.OCTET)
      request.method == "OPTIONS" -> RtspResponse.empty(extraHeaders = mapOf("Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"))
      request.method == "SETUP" -> setup(request, remote)
      request.method == "RECORD" -> RtspResponse.empty(extraHeaders = mapOf("Session" to "1", "Audio-Latency" to "11025"))
      request.method == "GET_PARAMETER" -> RtspResponse.ok("volume: 0.000000\r\n".toByteArray(), "text/parameters")
      request.method == "SET_PARAMETER" || request.method == "POST" && path in setOf("/feedback", "/audioMode") -> RtspResponse.empty(extraHeaders = mapOf("Session" to "1"))
      request.method == "FLUSH" -> RtspResponse.empty(extraHeaders = mapOf("Session" to "1", "RTP-Info" to "rtptime=0"))
      request.method == "POST" && path == "/fp-setup2" -> RtspResponse(421, "Misdirected Request")
      request.method == "TEARDOWN" -> teardown(request)
      else -> RtspResponse(404, "Not Found", "Not Found".toByteArray(), "text/plain")
    }
  }

  private fun play(request: RtspRequest): RtspResponse {
    val uri = request.parameters().let { it["Content-Location"] ?: it["content-location"] ?: "" }
    if (uri.isBlank() || runCatching { URI(uri).scheme.isNullOrBlank() }.getOrDefault(true)) {
      return RtspResponse(400, "Bad Request", "Missing Content-Location".toByteArray(), "text/plain")
    }
    Log.i(Consts.Airplay.TAG, "Airplay URL host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")}")
    send(PlaybackCommand.Load(uri, play = true))
    return RtspResponse.empty()
  }

  private fun pairVerify(request: RtspRequest): RtspResponse {
    val step = request.body.firstOrNull()?.toInt()?.and(0xff) ?: -1
    val response = session.pairVerify(request.body)
    Log.i(Consts.Airplay.TAG, "Airplay pair-verify step=$step verified=${session.verified} response=${response.size}")
    return RtspResponse.ok(response, Consts.Airplay.OCTET, close = step == 0 && !session.verified)
  }

  private fun setup(request: RtspRequest, remote: InetAddress): RtspResponse = when (val setup = parseSetup(request.body)) {
    is AirplaySetup.Session -> {
      val run = running ?: error("Airplay receiver is not running")
      session.encryptedKey = setup.encryptedKey
      val timingPort = setup.timingPort?.let { run.timing.start(remote, it) } ?: 0
      Log.i(Consts.Airplay.TAG, "Airplay SETUP session timing=${setup.timingProtocol}:${setup.timingPort} local=$timingPort")
      RtspResponse.ok(binaryPlist { put("eventPort", 0); put("timingPort", timingPort) }, Consts.Airplay.BPLIST, extraHeaders = mapOf("Session" to "1"))
    }

    is AirplaySetup.Streams -> {
      val run = running ?: error("Airplay receiver is not running")
      val unsupported = setup.streams.mapNotNull { it.type.takeUnless { type -> type == Consts.Airplay.TYPE_MIRROR || type in Consts.Airplay.TYPE_AUDIO } }
      if (unsupported.isNotEmpty()) Log.w(Consts.Airplay.TAG, "Airplay SETUP unknown stream types=$unsupported")
      val ports = setup.streams.mapNotNull { stream ->
        Log.i(
          Consts.Airplay.TAG,
          "Airplay SETUP stream type=${stream.type} connection=${stream.connectionId != null} control=${stream.controlPort} ct=${stream.compressionType} spf=${stream.samplesPerFrame} fmt=${stream.audioFormat} screen=${stream.usingScreen} media=${stream.isMedia}"
        )
        when (stream.type) {
          Consts.Airplay.TYPE_MIRROR -> {
            session.streamConnectionId = stream.connectionId
            mirror.start()
            send(PlaybackCommand.StartMirror)
            AirplayPorts(Consts.Airplay.TYPE_MIRROR, run.mirror.start())
          }

          in Consts.Airplay.TYPE_AUDIO -> run.audio.ports(stream.type)
          else -> null
        }
      }
      RtspResponse.ok(binaryPlist {
        put("streams", NSArray(*ports.map { stream -> plist("type" to stream.type, "dataPort" to stream.dataPort).apply { stream.controlPort?.let { put("controlPort", it) } } }.toTypedArray()))
      }, Consts.Airplay.BPLIST, extraHeaders = mapOf("Session" to "1"), close = unsupported.isNotEmpty())
    }

    AirplaySetup.Empty -> RtspResponse.empty(extraHeaders = mapOf("Session" to "1"))
  }

  private fun teardown(request: RtspRequest): RtspResponse {
    val types = streamTypes(request.body)
    if (types.isEmpty() || types.any { it in Consts.Airplay.TYPE_AUDIO }) closeAudio(running?.audio)
    if (types.isEmpty() || Consts.Airplay.TYPE_MIRROR in types) {
      mirror.stop()
      send(PlaybackCommand.StopMirror)
    }
    return RtspResponse(200, "OK", extraHeaders = mapOf("Session" to "1", "Connection" to "close"), close = true)
  }

  private fun closeAudio(audio: AirplayAudioSink?) {
    audio?.counts?.takeIf { (media, control) -> media > 0 || control > 0 }?.let { (media, control) ->
      Log.i(Consts.Airplay.TAG, "Airplay audio stopped media=$media control=$control")
    }
    audio?.stop()
  }

  private fun mirrorPacket(event: MirrorEvent) {
    when (event) {
      MirrorEvent.Suspend -> Log.i(Consts.Airplay.TAG, "Airplay video suspend")
      MirrorEvent.Resume -> Log.i(Consts.Airplay.TAG, "Airplay video resume")
      is MirrorEvent.Format -> {
        Log.i(Consts.Airplay.TAG, "Airplay video config codec=${event.codec.mimeType} format=${event.codec.width}x${event.codec.height}")
        mirror.format(event.codec)
      }

      is MirrorEvent.Frame -> {
        if (event.count == 1 || event.count % 300 == 0) Log.i(Consts.Airplay.TAG, "Airplay video frame count=${event.count} bytes=${event.bytes.size}")
        mirror.frame(event.bytes)
      }

      MirrorEvent.EmptyConfig -> Log.w(Consts.Airplay.TAG, "Airplay video codec config is empty")
      is MirrorEvent.Unknown -> Log.w(Consts.Airplay.TAG, "Airplay video packet type=${event.type} bytes=${event.bytes}")
    }
  }

}

private data class AirplayRun(
  val scope: CoroutineScope,
  val rtsp: ServerSocket,
  val mirror: MirrorServer,
  val audio: AirplayAudioSink,
  val unpublish: () -> Unit,
  val timing: AirplayTiming
)

interface MirrorSink {
  fun format(codec: MirrorCodec)
  fun frame(bytes: ByteArray)
  fun stop()
}

class MirrorStream {
  private val lock = Any()
  private var state = MirrorState()

  fun attach(sink: MirrorSink) {
    synchronized(lock) {
      state = state.copy(sink = sink)
      state.codec.takeIf { state.streaming }
    }?.let(sink::format)
  }

  fun detach(sink: MirrorSink) = synchronized(lock) {
    if (state.sink === sink) state = state.copy(sink = null)
  }

  fun start() = synchronized(lock) {
    state = state.copy(streaming = true, codec = null)
  }

  fun format(codec: MirrorCodec) {
    val sink = synchronized(lock) {
      state = state.copy(streaming = true, codec = codec)
      state.sink
    }
    sink?.format(codec)
  }

  fun frame(bytes: ByteArray) {
    val sink = synchronized(lock) { state.sink.takeIf { state.streaming } }
    sink?.frame(bytes)
  }

  fun stop() {
    val sink = synchronized(lock) {
      state.sink.takeIf { state.streaming }.also {
        state = state.copy(streaming = false, codec = null)
      }
    }
    sink?.stop()
  }

  private data class MirrorState(
    val sink: MirrorSink? = null,
    val streaming: Boolean = false,
    val codec: MirrorCodec? = null
  )
}

class MirrorDecoder(private val surface: Surface) : MirrorSink {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val events = Channel<DecoderEvent>(capacity = 32)
  private var codec: MediaCodec? = null
  private var timestampUs = 0L

  init {
    scope.launch {
      for (event in events) {
        when (event) {
          is DecoderEvent.Format -> configure(event.codec)
          is DecoderEvent.Frame -> decode(event.frame)
          DecoderEvent.Stop -> releaseCodec()
        }
      }
    }
  }

  override fun format(codec: MirrorCodec) {
    events.trySend(DecoderEvent.Format(codec))
  }

  override fun frame(bytes: ByteArray) {
    events.trySend(DecoderEvent.Frame(bytes.copyOf()))
  }

  override fun stop() {
    events.trySend(DecoderEvent.Stop)
  }

  fun release() {
    events.close()
    releaseCodec()
    scope.cancel()
  }

  private fun configure(codec: MirrorCodec) {
    releaseCodec()
    timestampUs = 0L
    this.codec = MediaCodec.createDecoderByType(codec.mimeType).apply {
      val format = MediaFormat.createVideoFormat(codec.mimeType, codec.width, codec.height)
      codec.csd.forEachIndexed { index, bytes -> format.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
      configure(format, surface, null, 0)
      start()
    }
  }

  private fun decode(frame: ByteArray) {
    val decoder = codec ?: return
    val inputIndex = decoder.dequeueInputBuffer(10_000)
    if (inputIndex >= 0) {
      val buffer = decoder.getInputBuffer(inputIndex)
      if (buffer == null || frame.size > buffer.capacity()) {
        Log.w(Consts.Airplay.TAG, "Airplay frame too large for decoder input: frame=${frame.size}")
        decoder.queueInputBuffer(inputIndex, 0, 0, timestampUs, 0)
        return
      }
      buffer.clear()
      buffer.put(frame)
      decoder.queueInputBuffer(inputIndex, 0, frame.size, timestampUs, 0)
      timestampUs += 33_333L
    }

    val info = MediaCodec.BufferInfo()
    generateSequence(decoder.dequeueOutputBuffer(info, 0)) { decoder.dequeueOutputBuffer(info, 0) }
      .takeWhile { it >= 0 }
      .forEach { decoder.releaseOutputBuffer(it, true) }
  }

  private fun releaseCodec() {
    val decoder = codec ?: return
    codec = null
    runCatching { decoder.stop() }
    runCatching { decoder.release() }
  }

  private sealed interface DecoderEvent {
    data class Format(val codec: MirrorCodec) : DecoderEvent
    data class Frame(val frame: ByteArray) : DecoderEvent
    data object Stop : DecoderEvent
  }
}

interface MirrorCodec {
  val mimeType: String
  val width: Int
  val height: Int
  val csd: List<ByteArray>
}

private class AirplaySession(uuid: String) {
  private val pairing = Pairing(MessageDigest.getInstance("SHA-512").digest("NotchCatCastAirPlay:$uuid".toByteArray(Charsets.UTF_8)).copyOf(32))
  private val fairPlay = FairPlay()
  private var videoDecryptor: FairPlayVideoDecryptor? = null

  val publicKey: ByteArray get() = pairing.publicKey()

  @OptIn(ExperimentalStdlibApi::class)
  val publicKeyHex: String get() = publicKey.toHexString()
  val verified: Boolean get() = pairing.isPairVerified
  var encryptedKey: ByteArray? = null
  var streamConnectionId: String? = null
    set(connectionId) {
      field = connectionId
      videoDecryptor = null
    }

  fun pairSetup() = ByteArrayOutputStream().also { pairing.pairSetup(it) }.toByteArray()
  fun pairVerify(body: ByteArray) = ByteArrayOutputStream().also { pairing.pairVerify(ByteArrayInputStream(body), it) }.toByteArray()
  fun fairPlaySetup(body: ByteArray) = ByteArrayOutputStream().also { fairPlay.fairPlaySetup(ByteArrayInputStream(body), it) }.toByteArray()

  fun videoDecryptor(): FairPlayVideoDecryptor =
    videoDecryptor ?: FairPlayVideoDecryptor(
      fairPlay.decryptAesKey(encryptedKey ?: error("Airplay encrypted key missing")),
      pairing.sharedSecret,
      streamConnectionId ?: error("Airplay streamConnectionID missing")
    ).also { videoDecryptor = it }
}

private class AirplayAudioSink(private val scope: CoroutineScope) {
  private var sockets: Pair<DatagramSocket, DatagramSocket>? = null
  private val mediaPackets = AtomicLong()
  private val controlPackets = AtomicLong()
  val counts get() = mediaPackets.get() to controlPackets.get()

  fun ports(type: Int): AirplayPorts {
    val (media, control) = sockets ?: (DatagramSocket(0) to DatagramSocket(0)).also {
      sockets = it
      drain(it.first, mediaPackets)
      drain(it.second, controlPackets)
    }
    return AirplayPorts(type, media.localPort, control.localPort)
  }

  fun stop() {
    sockets?.let { (media, control) ->
      runCatching { media.close() }
      runCatching { control.close() }
    }
    sockets = null
  }

  private fun drain(socket: DatagramSocket, counter: AtomicLong) {
    scope.launch {
      val packet = DatagramPacket(ByteArray(2048), 2048)
      while (isActive && !socket.isClosed) {
        try {
          packet.length = packet.data.size
          socket.receive(packet)
          counter.incrementAndGet()
        } catch (_: SocketException) {
          return@launch
        }
      }
    }
  }
}

private class AirplayTiming(private val scope: CoroutineScope) {
  private var job: Job? = null

  fun start(remote: InetAddress, remotePort: Int): Int {
    if (remotePort <= 0) return 0
    stop()
    val socket = DatagramSocket(0).apply { soTimeout = 300 }
    job = scope.launch {
      try {
        var clientReferenceTime = 0L
        var receiveTimeNs = 0L
        while (scope.isActive && !socket.isClosed) {
          runCatching {
            val request = ntpRequest(System.currentTimeMillis() * 1_000_000L, clientReferenceTime, receiveTimeNs)
            socket.send(DatagramPacket(request, request.size, InetSocketAddress(remote, remotePort)))
            val response = ByteArray(128)
            val packet = DatagramPacket(response, response.size)
            try {
              socket.receive(packet)
              receiveTimeNs = System.currentTimeMillis() * 1_000_000L
              clientReferenceTime = ByteBuffer.wrap(response, 24, Long.SIZE_BYTES).long
            } catch (_: SocketTimeoutException) {
            }
          }.onFailure {
            if (scope.isActive && !socket.isClosed) Log.w(Consts.Airplay.TAG, "Airplay timing tick failed", it)
          }
          delay(3_000)
        }
      } finally {
        socket.close()
      }
    }
    Log.i(Consts.Airplay.TAG, "Airplay timing local=${socket.localPort} remote=${remote.hostAddress}:$remotePort")
    return socket.localPort
  }

  fun stop() {
    job?.cancel()
    job = null
  }
}

private class MirrorServer(
  private val scope: CoroutineScope,
  private val decrypt: (ByteArray) -> Unit,
  private val event: (MirrorEvent) -> Unit,
  private val stopped: () -> Unit,
) {
  private var server: ServerSocket? = null

  fun start(): Int {
    server?.let { return it.localPort }
    val socket = ServerSocket(0)
    server = socket
    Log.i(Consts.Airplay.TAG, "Airplay video listening on ${socket.localPort}")
    scope.acceptClients(socket, null, ::handle)
    return socket.localPort
  }

  fun stop() {
    runCatching { server?.close() }
    server = null
  }

  private fun handle(socket: Socket) {
    runCatching {
      socket.use {
        val input = it.getInputStream()
        var frames = 0
        while (scope.isActive) {
          val packet = readMirrorPacket(input) ?: break
          when (packet.option) {
            0x0156, 0x015e -> event(MirrorEvent.Suspend)
            0x0116, 0x011e -> event(MirrorEvent.Resume)
          }
          when (packet.type) {
            0 -> if (packet.payload.isNotEmpty()) {
              decrypt(packet.payload)
              packet.payload.samplesToAnnexB()
              event(MirrorEvent.Frame(packet.payload, ++frames))
            }

            1 -> event(if (packet.payload.isEmpty()) MirrorEvent.EmptyConfig else MirrorEvent.Format(mirrorCodec(packet.payload, packet.format)))
            2, 5 -> Unit
            else -> event(MirrorEvent.Unknown(packet.type, packet.payload.size))
          }
        }
      }
    }.onFailure {
      if (scope.isActive) Log.e(Consts.Airplay.TAG, "Airplay video stream failed", it)
    }
    stopped()
  }
}

private data class RtspRequest(val method: String, val path: String, val protocol: String, val headers: Map<String, String>, val body: ByteArray) {
  fun summary(): String {
    val shown = if (path.startsWith("/info?")) path else path.substringBefore("?")
    return "$method $shown cseq=${headers["cseq"] ?: "-"} ua=${headers["user-agent"] ?: "-"} ct=${headers["content-type"] ?: "-"} len=${body.size}"
  }
}

private data class RtspResponse(
  val status: Int,
  val reason: String,
  val body: ByteArray = ByteArray(0),
  val contentType: String? = null,
  val extraHeaders: Map<String, String> = emptyMap(),
  val close: Boolean = false,
  val includeContentLength: Boolean = true
) {
  fun bytes(request: RtspRequest): ByteArray {
    val protocol = if (request.protocol.startsWith("HTTP", ignoreCase = true)) "HTTP/1.1" else "RTSP/1.0"
    val headers = buildList {
      add("$protocol $status $reason")
      request.headers["cseq"]?.let { add("CSeq: $it") }
      add("Date: ${httpDate()}")
      add("Server: AirTunes/${Consts.Airplay.SOURCE_VERSION}")
      add("Audio-Jack-Status: connected; type=analog")
      if (includeContentLength) add("Content-Length: ${body.size}")
      contentType?.let { add("Content-Type: $it") }
      extraHeaders.forEach { (header, content) -> add("$header: $content") }
    }.joinToString("\r\n", postfix = "\r\n\r\n")
    return headers.toByteArray(Charsets.ISO_8859_1) + body
  }

  companion object {
    fun ok(body: ByteArray, contentType: String, extraHeaders: Map<String, String> = emptyMap(), close: Boolean = false) = RtspResponse(200, "OK", body, contentType, extraHeaders, close)
    fun empty(extraHeaders: Map<String, String> = emptyMap()) = RtspResponse(200, "OK", extraHeaders = extraHeaders)
    fun switching(extraHeaders: Map<String, String>) = RtspResponse(101, "Switching Protocols", extraHeaders = extraHeaders, includeContentLength = false)
  }
}

private sealed interface AirplaySetup {
  data class Session(val encryptedKey: ByteArray, val timingProtocol: String?, val timingPort: Int?) : AirplaySetup
  data class Streams(val streams: List<AirplayStream>) : AirplaySetup
  data object Empty : AirplaySetup
}

private data class AirplayStream(
  val type: Int,
  val connectionId: String?,
  val controlPort: Int? = null,
  val compressionType: Int? = null,
  val samplesPerFrame: Int? = null,
  val audioFormat: Long? = null,
  val usingScreen: Boolean? = null,
  val isMedia: Boolean? = null
)

private data class AirplayPorts(val type: Int, val dataPort: Int, val controlPort: Int? = null)

private sealed interface MirrorEvent {
  data object Suspend : MirrorEvent
  data object Resume : MirrorEvent
  data class Format(val codec: MirrorCodec) : MirrorEvent
  data class Frame(val bytes: ByteArray, val count: Int) : MirrorEvent
  data object EmptyConfig : MirrorEvent
  data class Unknown(val type: Int, val bytes: Int) : MirrorEvent
}

private data class MirrorPacket(val type: Int, val option: Int, val payload: ByteArray, val format: Size?)

private data class MirrorCodecInfo(override val mimeType: String, override val csd: List<ByteArray>, override val width: Int = 1920, override val height: Int = 1080) : MirrorCodec

private fun Context.publishAirplay(uuid: String, publicKeyHex: String, port: Int): () -> Unit {
  val nsd = getSystemService(NsdManager::class.java) ?: return {}
  fun registrationListener(service: String) = object : NsdManager.RegistrationListener {
    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
      Log.i(Consts.Airplay.TAG, "$service registered ${serviceInfo.serviceName}:${serviceInfo.port}")
    }

    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
      Log.e(Consts.Airplay.TAG, "$service registration failed: $errorCode")
    }

    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
  }

  val device = deviceId(uuid)
  val receiver = getString(R.string.application_name)
  val registrations = listOf(
    NsdServiceInfo().apply {
      serviceName = receiver
      serviceType = "_airplay._tcp."
      this.port = port
      airplayTxt(device, uuid, publicKeyHex).forEach { (attribute, content) -> setAttribute(attribute, content) }
    } to registrationListener("Airplay"),
    NsdServiceInfo().apply {
      serviceName = "${device.replace(":", "")}@$receiver"
      serviceType = "_raop._tcp."
      this.port = port
      raopTxt(publicKeyHex).forEach { (attribute, content) -> setAttribute(attribute, content) }
    } to registrationListener("RAOP")
  )
  runCatching {
    registrations.forEach { (service, registration) -> nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration) }
  }.onFailure { Log.e(Consts.Airplay.TAG, "Airplay registration failed", it) }
  return { registrations.forEach { (_, registration) -> runCatching { nsd.unregisterService(registration) } } }
}

private fun CoroutineScope.acceptClients(socket: ServerSocket, error: String?, handle: (Socket) -> Unit): Job = launch {
  while (isActive) {
    val client = try {
      socket.accept()
    } catch (cause: Exception) {
      if (isActive && error != null) Log.e(Consts.Airplay.TAG, error, cause)
      return@launch
    }
    launch { handle(client) }
  }
}

private fun readRtsp(input: InputStream): RtspRequest? {
  val header = ByteArrayOutputStream()
  var tail = 0
  while (true) {
    val byte = input.read()
    if (byte < 0) return null
    header.write(byte)
    tail = (tail shl 8) or byte
    if ((tail and 0xffff) == 0x0a0a || tail == 0x0d0a0d0a) break
    require(header.size() <= 65536) { "Airplay header too large" }
  }
  val lines = header.toString(Charsets.ISO_8859_1.name()).trimEnd('\r', '\n').split(Regex("\r?\n")).filter { it.isNotEmpty() }
  val parts = (lines.firstOrNull() ?: return null).split(" ", limit = 3)
  if (parts.size < 2) return null
  val headers = lines.drop(1).mapNotNull { line ->
    val index = line.indexOf(':')
    if (index <= 0) null else line.take(index).trim().lowercase(Locale.US) to line.substring(index + 1).trim()
  }.toMap()
  val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
  return RtspRequest(parts[0].uppercase(Locale.US), parts[1], parts.getOrElse(2) { "RTSP/1.0" }, headers, input.bytes(length) ?: return null)
}

private fun RtspRequest.parameters(): Map<String, String> =
  runCatching {
    (PropertyListParser.parse(body) as? NSDictionary)?.let { dict -> dict.allKeys().associateWith { key -> dict.objectForKey(key).toJavaObject().toString() } }
  }.getOrNull() ?: String(body, Charsets.UTF_8).lineSequence().mapNotNull { line ->
    line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).trim() to line.substring(it + 1).trim() }
  }.toMap()

private fun parseSetup(body: ByteArray): AirplaySetup {
  val dict = BinaryPropertyListParser.parse(body) as NSDictionary
  val encryptedKey = dict.bytes("ekey")
  return if (encryptedKey != null) AirplaySetup.Session(encryptedKey, dict.text("timingProtocol"), dict.number("timingPort")) else dict.streams()?.let(AirplaySetup::Streams) ?: AirplaySetup.Empty
}

private fun streamTypes(body: ByteArray): Set<Int> =
  if (body.isEmpty()) emptySet() else runCatching {
    val streams = ((BinaryPropertyListParser.parse(body) as NSDictionary).objectForKey("streams")?.toJavaObject() as? Array<*>) ?: return@runCatching emptySet()
    streams.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Number)?.toInt() }.toSet()
  }.getOrElse { emptySet() }

private fun airplayInfo(request: RtspRequest, receiverName: String, uuid: String, deviceId: String, publicKey: ByteArray, publicKeyHex: String): ByteArray {
  val binary = request.headers["content-type"]?.contains(Consts.Airplay.BPLIST, ignoreCase = true) == true
  val query = request.path.substringAfter("?", "").split("?").filter { it.isNotBlank() }.toSet()
  if (binary) return BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
    putTxtRecords(qualifiers(request.body), deviceId, uuid, publicKeyHex)
  })
  if (query.isNotEmpty()) return BinaryPropertyListWriter.writeToArray(receiverInfo(receiverName, uuid, deviceId, publicKey, full = false).apply {
    putTxtRecords(query, deviceId, uuid, publicKeyHex)
  })
  return BinaryPropertyListWriter.writeToArray(receiverInfo(receiverName, uuid, deviceId, publicKey, full = true))
}

private fun receiverInfo(receiverName: String, uuid: String, deviceId: String, publicKey: ByteArray, full: Boolean) = plist(
  "deviceID" to deviceId,
  "features" to Consts.Airplay.FEATURES,
  "keepAliveLowPower" to 1,
  "keepAliveSendStatsAsBody" to true,
  "macAddress" to deviceId,
  "model" to Consts.Airplay.MODEL,
  "name" to receiverName,
  "pi" to uuid,
  "pk" to NSData(publicKey),
  "sourceVersion" to Consts.Airplay.SOURCE_VERSION,
  "statusFlags" to 68,
  "vv" to 2
).apply {
  if (full) {
    put("initialVolume", 0.0)
    put(
      "audioFormats", NSArray(
        plist("type" to 100, "audioInputFormats" to 67108860, "audioOutputFormats" to 67108860),
        plist("type" to 101, "audioInputFormats" to 67108860, "audioOutputFormats" to 67108860),
      )
    )
    put(
      "audioLatencies", NSArray(
        plist("type" to 100, "audioType" to "default", "inputLatencyMicros" to 0, "outputLatencyMicros" to false),
        plist("type" to 101, "audioType" to "default", "inputLatencyMicros" to 0, "outputLatencyMicros" to false),
      )
    )
    put(
      "displays", NSArray(
        plist(
          "features" to 14, "height" to 1080, "heightPixels" to 1080, "heightPhysical" to 0,
          "width" to 1920, "widthPixels" to 1920, "widthPhysical" to 0, "maxFPS" to 60,
          "overscanned" to false, "refreshRate" to 1.0 / 60.0, "rotation" to false, "uuid" to uuid
        )
      )
    )
  }
}

private fun serverInfo(deviceId: String) = """<?xml version="1.0" encoding="UTF-8"?>
  <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
  <plist version="1.0"><dict>
  <key>deviceid</key><string>$deviceId</string>
  <key>features</key><integer>639</integer>
  <key>macAddress</key><string>$deviceId</string>
  <key>model</key><string>${Consts.Airplay.MODEL}</string>
  <key>osBuildVersion</key><string>12B435</string>
  <key>protovers</key><string>1.0</string>
  <key>srcvers</key><string>${Consts.Airplay.SOURCE_VERSION}</string>
  <key>vv</key><integer>2</integer>
  </dict></plist>""".trimIndent().toByteArray(Charsets.UTF_8)

private fun xmlPlist(body: String) =
  """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>$body</dict></plist>""".toByteArray(
    Charsets.UTF_8
  )

private fun binaryPlist(block: NSDictionary.() -> Unit) = BinaryPropertyListWriter.writeToArray(NSDictionary().apply(block))
private fun plist(vararg pairs: Pair<String, Any?>) = NSDictionary().apply { pairs.forEach { (name, entry) -> put(name, entry) } }

private fun NSDictionary.putTxtRecords(qualifiers: Set<String>, deviceId: String, uuid: String, publicKeyHex: String) {
  if ("txtAirPlay" in qualifiers) put("txtAirPlay", NSData(dnsTxt(airplayTxt(deviceId, uuid, publicKeyHex))))
  if ("txtRAOP" in qualifiers) put("txtRAOP", NSData(dnsTxt(raopTxt(publicKeyHex))))
}

private fun qualifiers(body: ByteArray) = runCatching {
  ((BinaryPropertyListParser.parse(body) as? NSDictionary)?.objectForKey("qualifier") as? NSArray)?.array?.map { it.toJavaObject().toString() }?.toSet().orEmpty()
}.getOrDefault(emptySet())

private fun NSDictionary.bytes(key: String): ByteArray? = objectForKey(key)?.toJavaObject() as? ByteArray
private fun NSDictionary.text(key: String): String? = objectForKey(key)?.toJavaObject()?.toString()
private fun NSDictionary.number(key: String): Int? = (objectForKey(key)?.toJavaObject() as? Number)?.toInt()

private fun NSDictionary.streams(): List<AirplayStream>? {
  val streams = objectForKey("streams")?.toJavaObject() as? Array<*> ?: return null
  return streams.mapNotNull { record ->
    val stream = record as? Map<*, *> ?: return@mapNotNull null
    val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
    AirplayStream(
      type = type,
      connectionId = when (val connection = stream["streamConnectionID"]) {
        is Number -> java.lang.Long.toUnsignedString(connection.toLong())
        is String -> connection
        else -> null
      },
      controlPort = (stream["controlPort"] as? Number)?.toInt(),
      compressionType = (stream["ct"] as? Number)?.toInt(),
      samplesPerFrame = (stream["spf"] as? Number)?.toInt(),
      audioFormat = (stream["audioFormat"] as? Number)?.toLong(),
      usingScreen = stream["usingScreen"] as? Boolean,
      isMedia = stream["isMedia"] as? Boolean
    )
  }.takeIf { it.isNotEmpty() }
}

private fun airplayTxt(deviceId: String, identifier: String, publicKey: String) = linkedMapOf(
  "deviceid" to deviceId,
  "features" to Consts.Airplay.FEATURES_HEX,
  "srcvers" to Consts.Airplay.SOURCE_VERSION,
  "flags" to Consts.Airplay.FLAGS_HEX,
  "vv" to "2",
  "model" to Consts.Airplay.MODEL,
  "pi" to identifier,
  "pw" to "false",
  "pk" to publicKey,
)

private fun raopTxt(publicKey: String) = linkedMapOf(
  "ch" to "2",
  "cn" to "0,1,2,3",
  "da" to "true",
  "et" to "0,3,5",
  "vv" to "2",
  "ft" to Consts.Airplay.FEATURES_HEX,
  "am" to Consts.Airplay.MODEL,
  "md" to "0,1,2",
  "rhd" to "5.6.0.0",
  "pw" to "false",
  "sf" to Consts.Airplay.FLAGS_HEX,
  "sr" to "44100",
  "ss" to "16",
  "sv" to "false",
  "tp" to "UDP",
  "txtvers" to "1",
  "vs" to Consts.Airplay.SOURCE_VERSION,
  "vn" to "65537",
  "pk" to publicKey,
)

private fun readMirrorPacket(input: InputStream): MirrorPacket? {
  val header = input.bytes(Consts.Airplay.VIDEO_HEADER_BYTES) ?: return null
  val payloadSize = header.intLe(0)
  if (payloadSize < 0 || payloadSize > Consts.Airplay.MAX_VIDEO_PAYLOAD_BYTES) {
    Log.w(Consts.Airplay.TAG, "Airplay video payload has invalid size: $payloadSize")
    return null
  }
  val payload = input.bytes(payloadSize) ?: return null
  val type = header[4].toInt() and 0xff
  val option = header.shortLe(6)
  return MirrorPacket(type, option, payload, mirrorSourceSize(type, header))
}

private fun mirrorCodec(payload: ByteArray, size: Size?): MirrorCodec =
  if (payload.size >= 0x79 && payload[4] == 'h'.code.toByte() && payload[5] == 'v'.code.toByte() && payload[6] == 'c'.code.toByte() && payload[7] == '1'.code.toByte()) {
    val (vps, afterVps) = payload.hevcArray(0x75, 0xa0.toByte())
    val (sps, afterSps) = payload.hevcArray(afterVps, 0xa1.toByte())
    val (pps) = payload.hevcArray(afterSps, 0xa2.toByte())
    MirrorCodecInfo("video/hevc", listOf(Consts.Airplay.START_CODE + vps + Consts.Airplay.START_CODE + sps + Consts.Airplay.START_CODE + pps), width = size?.width ?: 1920, height = size?.height ?: 1080)
  } else {
    require(payload.size >= 8) { "AVC config is too short" }
    val spsLength = payload.shortBe(6)
    val spsStart = 8
    val ppsLengthOffset = spsStart + spsLength + 1
    require(payload.size >= ppsLengthOffset + 3) { "AVC config has truncated SPS" }
    val ppsLength = payload.shortBe(ppsLengthOffset)
    val ppsStart = ppsLengthOffset + 2
    require(payload.size >= ppsStart + ppsLength) { "AVC config has truncated PPS" }
    MirrorCodecInfo(
      "video/avc",
      listOf(Consts.Airplay.START_CODE + payload.copyOfRange(spsStart, spsStart + spsLength), Consts.Airplay.START_CODE + payload.copyOfRange(ppsStart, ppsStart + ppsLength)),
      width = size?.width ?: 1920,
      height = size?.height ?: 1080
    )
  }

private fun ByteArray.hevcArray(offset: Int, nalType: Byte): Pair<ByteArray, Int> {
  require(size >= offset + 5) { "HEVC config array is truncated" }
  require(this[offset] == nalType && this[offset + 1] == 0.toByte() && this[offset + 2] == 1.toByte()) { "HEVC config has unexpected array type" }
  val length = shortBe(offset + 3)
  val start = offset + 5
  require(length > 0 && size >= start + length) { "HEVC config NAL is truncated" }
  return copyOfRange(start, start + length) to start + length
}

private fun ByteArray.samplesToAnnexB() {
  fun write(offset: Int) {
    if (offset + 4 > size) return
    val naluSize = intBe(offset)
    if (naluSize == 1) return
    require(naluSize > 0 && offset + 4 + naluSize <= size) { "Corrupt H.264 sample length: $naluSize" }
    this[offset] = 0
    this[offset + 1] = 0
    this[offset + 2] = 0
    this[offset + 3] = 1
    write(offset + 4 + naluSize)
  }
  write(0)
}

private fun ntpRequest(transmitTimeNs: Long, clientReferenceTime: Long, receiveTimeNs: Long): ByteArray {
  val bytes = ByteArray(32)
  bytes[0] = 0x80.toByte()
  bytes[1] = 0xd2.toByte()
  bytes[3] = 0x07
  if (clientReferenceTime != 0L) ByteBuffer.wrap(bytes, 8, Long.SIZE_BYTES).putLong(clientReferenceTime)
  if (receiveTimeNs != 0L) bytes.putTimestamp(16, receiveTimeNs)
  bytes.putTimestamp(24, transmitTimeNs)
  return bytes
}

private fun ByteArray.putTimestamp(offset: Int, nsSince1970: Long) {
  val seconds = nsSince1970 / 1_000_000_000L + 2_208_988_800L
  val fraction = ((nsSince1970 % 1_000_000_000L) shl 32) / 1_000_000_000L
  ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).putInt(seconds.toInt())
  ByteBuffer.wrap(this, offset + 4, Int.SIZE_BYTES).putInt(fraction.toInt())
}
