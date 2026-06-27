package notch.cat.cast

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.UUID

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

internal data class DlnaEndpoint(val uuid: String, val ipAddress: String, val httpPort: Int)

private typealias HttpRequest = Http.Request
private typealias HttpResponse = Http.Response

internal class DlnaRenderer(private val context: Context, private val send: (PlaybackCommand) -> Unit) {
  private var scope: CoroutineScope? = null
  private var httpServer: ServerSocket? = null
  private var ssdpSocket: MulticastSocket? = null

  private lateinit var uuid: String
  private var ipAddress = "0.0.0.0"
  private var httpPort = 0

  @SuppressLint("HardwareIds")
  fun start(parent: CoroutineScope): DlnaEndpoint {
    if (scope?.isActive == true) return endpoint()

    val preferences = context.getSharedPreferences("notch_cat_cast", Context.MODE_PRIVATE)
    uuid =
      preferences.getString("uuid", null) ?: UUID.nameUUIDFromBytes("NotchCatCast:${Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()}".toByteArray(Charsets.UTF_8))
        .toString().also { preferences.edit { putString("uuid", it) } }
    val addresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
      .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    ipAddress = addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: addresses.firstOrNull()?.hostAddress ?: "127.0.0.1"
    val serverSocket = ServerSocket(0)
    httpServer = serverSocket
    httpPort = serverSocket.localPort

    val serverScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    scope = serverScope
    serverScope.launch { httpLoop(serverSocket, serverScope) }
    serverScope.launch { ssdpLoop(serverScope) }
    serverScope.launch {
      delay(300)
      while (isActive) {
        ssdpTargets().forEach { notify(it, "ssdp:alive") }
        delay(Consts.Dlna.SSDP_ALIVE_INTERVAL_MS)
      }
    }
    return endpoint()
  }

  fun stop() {
    val rendererScope = scope ?: return
    if (::uuid.isInitialized) ssdpTargets().forEach { notify(it, "ssdp:byebye") }
    scope = null
    rendererScope.cancel()
    runCatching { ssdpSocket?.close() }
    ssdpSocket = null
    runCatching { httpServer?.close() }
    httpServer = null
  }

  private fun endpoint() = DlnaEndpoint(uuid, ipAddress, httpPort)

  private fun httpLoop(serverSocket: ServerSocket, serverScope: CoroutineScope) {
    while (serverScope.isActive) {
      val socket = try {
        serverSocket.accept().apply { soTimeout = Consts.Dlna.SOCKET_TIMEOUT_MS }
      } catch (error: Exception) {
        if (serverScope.isActive) Runtime.error("HTTP server accept failed", error)
        break
      }
      serverScope.launch { http(socket) }
    }
  }

  private fun http(socket: Socket) {
    socket.use {
      val request = runCatching { Http.read(it.getInputStream()) }.getOrElse { error ->
        Runtime.error("HTTP request parse failed", error)
        return
      } ?: return

      val route = request.path.substringBefore("?")
      val action = if (request.method == "POST") Soap.action(request.headers, request.body) else ""
      val agent = request.headers["user-agent"]?.takeIf { it.isNotBlank() }?.take(Consts.Dlna.MAX_LOG_BODY)
      Log.i(Consts.App.TAG, "HTTP ${socket.inetAddress.hostAddress} ${request.method} $route${if (action.isBlank()) "" else " action=$action"}${if (agent == null) "" else " ua=$agent"}")

      val isGetLike = request.method == "GET" || request.method == "HEAD"
      val response = when {
        isGetLike && (route == "/" || route == "/description.xml") -> HttpResponse.ok(description())
        isGetLike && (route == "/upnp/AVTransport/scpd.xml" || route == "/_urn:schemas-upnp-org:service:AVTransport_scpd.xml") -> HttpResponse.ok(asset("AVTransport.scpd.xml"))
        isGetLike && (route == "/upnp/RenderingControl/scpd.xml" || route == "/_urn:schemas-upnp-org:service:RenderingControl_scpd.xml") -> HttpResponse.ok(asset("RenderingControl.scpd.xml"))
        isGetLike && (route == "/upnp/ConnectionManager/scpd.xml" || route == "/_urn:schemas-upnp-org:service:ConnectionManager_scpd.xml") -> HttpResponse.ok(asset("ConnectionManager.scpd.xml"))
        request.method == "POST" && (route == "/upnp/control/AVTransport" || route == "/_urn:schemas-upnp-org:service:AVTransport_control") -> av(request)
        request.method == "POST" && (route == "/upnp/control/RenderingControl" || route == "/_urn:schemas-upnp-org:service:RenderingControl_control") -> render(request)
        request.method == "POST" && (route == "/upnp/control/ConnectionManager" || route == "/_urn:schemas-upnp-org:service:ConnectionManager_control") -> connection(request)
        request.method == "SUBSCRIBE" && (route.startsWith("/upnp/event/") || route.endsWith("_event")) -> HttpResponse(200, "OK", "", extraHeaders = mapOf("SID" to "uuid:${UUID.randomUUID()}", "TIMEOUT" to "Second-1800"))
        request.method == "UNSUBSCRIBE" && (route.startsWith("/upnp/event/") || route.endsWith("_event")) -> HttpResponse(statusCode = 200, reason = "OK", body = "")
        else -> HttpResponse(statusCode = 404, reason = "Not Found", body = "Not Found", contentType = "text/plain; charset=\"utf-8\"")
      }
      Log.i(Consts.App.TAG, "HTTP response ${response.statusCode} ${request.method} $route")
      Http.write(it.getOutputStream(), if (request.method == "HEAD") response.copy(omitBody = true) else response, Consts.Dlna.SERVER_HEADER, httpDate())
    }
  }

  private fun ssdpLoop(serverScope: CoroutineScope) {
    val socket = MulticastSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(Consts.Dlna.SSDP_PORT))
      soTimeout = 0
    }
    ssdpSocket = socket

    val group = InetAddress.getByName(Consts.Dlna.SSDP_ADDRESS)
    val targetAddress = runCatching { InetAddress.getByName(ipAddress) }.getOrNull()
    val networkInterface = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { candidate -> candidate.inetAddresses.toList().any { it == targetAddress } }
    runCatching {
      if (networkInterface != null) {
        socket.networkInterface = networkInterface
        socket.joinGroup(InetSocketAddress(group, Consts.Dlna.SSDP_PORT), networkInterface)
      } else {
        @Suppress("DEPRECATION") socket.joinGroup(group)
      }
    }.onFailure { Runtime.error("Failed to join SSDP multicast group", it) }

    val buffer = ByteArray(8192)
    while (serverScope.isActive) {
      val packet = DatagramPacket(buffer, buffer.size)
      runCatching { socket.receive(packet) }.onSuccess {
        val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        if (message.startsWith("M-SEARCH", ignoreCase = true)) {
          val headers = Http.parseHeaders(message.lines())
          if (!headers["man"].orEmpty().contains("ssdp:discover", ignoreCase = true)) return@onSuccess
          val searchTarget = headers["st"] ?: return@onSuccess
          Log.i(Consts.App.TAG, "SSDP search from=${packet.address.hostAddress}:${packet.port} st=$searchTarget")
          val target = searchTarget.trim()
          ssdpTargets().filter { target.equals("ssdp:all", ignoreCase = true) || it.equals(target, ignoreCase = true) }.forEach {
            udp(
              Http.message(
                "HTTP/1.1 200 OK", "CACHE-CONTROL: max-age=1800", "DATE: ${httpDate()}", "EXT:", "LOCATION: ${location()}", "SERVER: ${Consts.Dlna.SERVER_HEADER}", "ST: $it", "USN: ${usn(it)}"
              ), packet.address, packet.port, socket
            )
          }
        }
      }.onFailure {
        if (serverScope.isActive) Runtime.error("SSDP receive failed", it)
      }
    }
  }

  private fun notify(target: String, nts: String) {
    val headers = mutableListOf("NOTIFY * HTTP/1.1", "HOST: ${Consts.Dlna.SSDP_ADDRESS}:${Consts.Dlna.SSDP_PORT}")
    if (nts == "ssdp:alive") headers += listOf("CACHE-CONTROL: max-age=1800", "LOCATION: ${location()}", "SERVER: ${Consts.Dlna.SERVER_HEADER}")
    headers += listOf("NT: $target", "NTS: $nts", "USN: ${usn(target)}")
    udp(Http.message(*headers.toTypedArray()), InetAddress.getByName(Consts.Dlna.SSDP_ADDRESS), Consts.Dlna.SSDP_PORT, ssdpSocket)
  }

  private fun udp(message: String, address: InetAddress, port: Int, socket: MulticastSocket?) {
    val bytes = message.toByteArray(Charsets.UTF_8)
    val packet = DatagramPacket(bytes, bytes.size, address, port)
    runCatching { socket?.send(packet) ?: MulticastSocket().use { it.send(packet) } }.onFailure { Log.w(Consts.App.TAG, "SSDP send failed: ${it.message}") }
  }

  private fun av(request: HttpRequest): HttpResponse {
    val action = runCatching { enumValueOf<AvTransportAction>(Soap.action(request.headers, request.body)) }.getOrNull() ?: return fault("AVTransport", 401, "Invalid Action")
    return when (action) {
      AvTransportAction.SetAVTransportURI -> uri(request, action)
      AvTransportAction.SetNextAVTransportURI, AvTransportAction.SetPlayMode, AvTransportAction.Next, AvTransportAction.Previous -> ok(action)
      AvTransportAction.Play -> send(PlaybackCommand.Play).let { ok(action) }
      AvTransportAction.Pause -> send(PlaybackCommand.Pause).let { ok(action) }
      AvTransportAction.Stop -> send(PlaybackCommand.Stop).let { ok(action) }
      AvTransportAction.Seek -> send(PlaybackCommand.Seek(Soap.parseTime(Soap.argument(request.body, "Target")))).let { ok(action) }
      AvTransportAction.GetTransportInfo -> ok(action, transport(Runtime.state.value))
      AvTransportAction.GetPositionInfo -> ok(action, position(Runtime.state.value))
      AvTransportAction.GetMediaInfo -> ok(action, media(Runtime.state.value))
      AvTransportAction.GetTransportSettings -> ok(action, mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
      AvTransportAction.GetDeviceCapabilities -> ok(action, mapOf("PlayMedia" to "NETWORK", "RecMedia" to "", "RecQualityModes" to ""))
      AvTransportAction.GetCurrentTransportActions -> ok(action, mapOf("Actions" to "Play,Stop,Pause,Seek"))
    }
  }

  private enum class AvTransportAction {
    SetAVTransportURI, SetNextAVTransportURI, SetPlayMode, Next, Previous, Play, Pause, Stop, Seek, GetTransportInfo, GetPositionInfo, GetMediaInfo, GetTransportSettings, GetDeviceCapabilities, GetCurrentTransportActions,
  }

  private fun uri(request: HttpRequest, action: AvTransportAction): HttpResponse {
    val uri = Soap.argument(request.body, "CurrentURI")
    if (uri.isBlank()) return fault("AVTransport", 714, "Illegal MIME-type")
    Log.i(Consts.App.TAG, "SetAVTransportURI host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")} uri=$uri")
    return send(PlaybackCommand.Load(uri, play = false)).let { ok(action) }
  }

  private fun ok(action: AvTransportAction, arguments: Map<String, String> = emptyMap()) = soap("AVTransport", action.name, arguments)

  private fun render(request: HttpRequest) = when (val action = Soap.action(request.headers, request.body)) {
    "GetVolume" -> soap("RenderingControl", action, mapOf("CurrentVolume" to Runtime.state.value.volume.toString()))
    "SetVolume" -> Runtime.volume(Soap.argument(request.body, "DesiredVolume").toIntOrNull() ?: Runtime.state.value.volume).let { soap("RenderingControl", action) }
    "GetMute" -> soap("RenderingControl", action, mapOf("CurrentMute" to if (Runtime.state.value.muted) "1" else "0"))
    "SetMute" -> Runtime.muted(Soap.argument(request.body, "DesiredMute").let { it == "1" || it.equals("true", ignoreCase = true) }).let { soap("RenderingControl", action) }
    else -> fault("RenderingControl", 401, "Invalid Action")
  }

  private fun connection(request: HttpRequest) = when (val action = Soap.action(request.headers, request.body)) {
    "GetProtocolInfo" -> soap("ConnectionManager", action, mapOf("Source" to "", "Sink" to Consts.Dlna.SINK_PROTOCOL_INFO))
    "GetCurrentConnectionIDs" -> soap("ConnectionManager", action, mapOf("ConnectionIDs" to "0"))
    "GetCurrentConnectionInfo" -> soap(
      "ConnectionManager",
      action,
      mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to Consts.Dlna.SINK_PROTOCOL_INFO, "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
    )

    else -> fault("ConnectionManager", 401, "Invalid Action")
  }

  private fun soap(service: String, action: String, arguments: Map<String, String> = emptyMap()) = HttpResponse.ok(Soap.ok(service, action, arguments))

  private fun fault(service: String, code: Int, description: String) = HttpResponse(statusCode = 500, reason = "Internal Server Error", body = Soap.faultBody(code, description))
    .also { Log.w(Consts.App.TAG, "SOAP fault service=$service code=$code description=$description") }

  private fun description() =
    """<?xml version="1.0" encoding="utf-8"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://$ipAddress:$httpPort</URLBase><device><deviceType>${Consts.Dlna.MEDIA_RENDERER}</deviceType><presentationURL>/</presentationURL><friendlyName>${
      context.getString(R.string.application_name).escapeXml()
    }</friendlyName><manufacturer>Microsoft Corporation</manufacturer><manufacturerURL>http://www.microsoft.com</manufacturerURL><modelDescription>Media Renderer</modelDescription><modelName>Windows Media Player</modelName><modelURL>http://go.microsoft.com/fwlink/Linkld=105927</modelURL><dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC><UDN>uuid:$uuid</UDN><serviceList>${
      service(Consts.Dlna.AV_TRANSPORT, "AVTransport")
    }${service(Consts.Dlna.CONNECTION_MANAGER, "ConnectionManager")}${service(Consts.Dlna.RENDERING_CONTROL, "RenderingControl")}</serviceList></device></root>"""

  private fun service(type: String, name: String) =
    """<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$name</serviceId><SCPDURL>_urn:schemas-upnp-org:service:${name}_scpd.xml</SCPDURL><controlURL>_urn:schemas-upnp-org:service:${name}_control</controlURL><eventSubURL>_urn:schemas-upnp-org:service:${name}_event</eventSubURL></service>"""

  private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

  private fun ssdpTargets() = listOf("upnp:rootdevice", "uuid:$uuid", Consts.Dlna.MEDIA_RENDERER, Consts.Dlna.AV_TRANSPORT, Consts.Dlna.CONNECTION_MANAGER, Consts.Dlna.RENDERING_CONTROL)
  private fun usn(target: String) = when (target) {
    "uuid:$uuid" -> "uuid:$uuid"
    "upnp:rootdevice" -> "uuid:$uuid::upnp:rootdevice"
    else -> "uuid:$uuid::$target"
  }

  private fun location() = "http://$ipAddress:$httpPort/description.xml"
  private fun transport(state: Snapshot) = mapOf("CurrentTransportState" to state.transportState.protocol, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
  private fun position(state: Snapshot) = mapOf(
    "Track" to if (state.currentUri.isBlank()) "0" else "1",
    "TrackDuration" to Soap.formatTime(state.durationMs),
    "TrackMetaData" to "",
    "TrackURI" to state.currentUri,
    "RelTime" to Soap.formatTime(state.positionMs),
    "AbsTime" to Soap.formatTime(state.positionMs),
    "RelCount" to "2147483647",
    "AbsCount" to "2147483647",
  )

  private fun media(state: Snapshot) = mapOf(
    "NrTracks" to if (state.currentUri.isBlank()) "0" else "1",
    "MediaDuration" to Soap.formatTime(state.durationMs),
    "CurrentURI" to state.currentUri,
    "CurrentURIMetaData" to "",
    "NextURI" to "",
    "NextURIMetaData" to "",
    "PlayMedium" to "NETWORK",
    "RecordMedium" to "NOT_IMPLEMENTED",
    "WriteStatus" to "NOT_IMPLEMENTED",
  )
}
