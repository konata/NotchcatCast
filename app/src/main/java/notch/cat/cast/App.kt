@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package notch.cat.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import notch.cat.cast.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private const val TAG = "mang"

private fun Context.playerIntent() = Intent(this, IndexActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
private fun Context.startCastService() = runCatching { startForegroundService(Intent(this, CastService::class.java)) }.onFailure { Log.e(TAG, "start cast service failed", it) }
private fun Context.openPlayerPage() = runCatching { startActivity(playerIntent()) }.onFailure { Log.e(TAG, "open player page failed", it) }
private val START_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED)

class StartReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in START_ACTIONS) return
    Log.i(TAG, "start receiver action=${intent.action}")
    context.startCastService()
  }
}

private sealed interface CastCommand {
  data class Load(val uri: String, val play: Boolean) : CastCommand
  data class Seek(val positionMs: Long) : CastCommand
  data object Play : CastCommand
  data object Pause : CastCommand
  data object Stop : CastCommand
}

private object CastSession {
  private val pending = ArrayDeque<CastCommand>()

  @Volatile
  private var surface: ((CastCommand) -> Unit)? = null

  fun attach(surface: (CastCommand) -> Unit) = synchronized(this) {
    this.surface = surface
    pending.toList().also { pending.clear() }
  }.forEach { surface(it) }

  fun detach(surface: (CastCommand) -> Unit) = synchronized(this) { if (this.surface === surface) this.surface = null }

  fun send(context: Context, command: CastCommand) {
    reduce(command)
    val target = synchronized(this) {
      surface ?: run {
        pending.addLast(command)
        null
      }
    }
    if (target != null) target(command) else context.openPlayerPage()
  }

  private fun reduce(command: CastCommand) {
    when (command) {
      is CastCommand.Load -> StateStore.state.update { it.copy(currentUri = command.uri, transportState = TransportState.Transitioning, positionMs = 0L, durationMs = 0L, lastError = "") }
      CastCommand.Play -> StateStore.setTransport(TransportState.Playing)
      CastCommand.Pause -> StateStore.setTransport(TransportState.Paused)
      CastCommand.Stop -> StateStore.clearMedia()

      is CastCommand.Seek -> StateStore.setPlayerPosition(command.positionMs.coerceAtLeast(0L), StateStore.state.value.durationMs)
    }
  }
}

class CastService : Service() {
  private var server: Dmr? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    startDmr()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY.also { startDmr() }

  override fun onDestroy() {
    server?.stop()
    server = null
    runCatching { multicastLock?.release() }
    multicastLock = null
    StateStore.setServerState(running = false, multicastLocked = false)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun startDmr() {
    if (server != null) return
    multicastLock = applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("NotchCatCastDlnaMulticast")?.apply {
      setReferenceCounted(false)
      acquire()
    }
    val dmr = Dmr(applicationContext) { CastSession.send(this, it) }
    val info = dmr.start()
    server = dmr
    StateStore.setServerState(
      running = true, uuid = info.uuid, ipAddress = info.ipAddress, httpPort = info.httpPort, wifiName = ssid(applicationContext), multicastLocked = multicastLock?.isHeld == true
    )
  }

  private fun notification(): Notification {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.application_name), NotificationManager.IMPORTANCE_LOW))
    val intent = PendingIntent.getActivity(this, 0, playerIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.application_name)).setContentText(getString(R.string.notification_service_text))
      .setContentIntent(intent).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
  }

  private companion object {
    const val CHANNEL_ID = "notch_cat_cast_service"
    const val NOTIFICATION_ID = 1001
  }
}

class IndexActivity : ComponentActivity() {
  private lateinit var binding: ActivityMainBinding
  private lateinit var player: ExoPlayer
  private lateinit var trackSelector: DefaultTrackSelector
  private var controlBarJob: Job? = null
  private var transientControlText = ""
  private var audioFallbackUsed = false
  private var lastVolume = StateStore.state.value.volume
  private var lastMuted = StateStore.state.value.muted
  private val surface: (CastCommand) -> Unit = { command ->
    when (command) {
      is CastCommand.Load -> load(command.uri, command.play)
      CastCommand.Play -> play()
      CastCommand.Pause -> pause()
      CastCommand.Stop -> stop()
      is CastCommand.Seek -> seek(command.positionMs)
    }
  }

  @Volatile
  private var playerScope: CoroutineScope? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationContext.startCastService()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    trackSelector = DefaultTrackSelector(this)
    val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true).forceDisableMediaCodecAsynchronousQueueing()

    player = ExoPlayer.Builder(this, renderersFactory).setTrackSelector(trackSelector).build().apply {
      videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
      volume = StateStore.state.value.exoVolume
    }
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.playerView.player = player

    player.addListener(object : Player.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
          Player.STATE_BUFFERING -> if (StateStore.state.value.currentUri.isNotBlank() && player.playWhenReady) setState(TransportState.Transitioning)
          Player.STATE_READY -> when {
            StateStore.state.value.currentUri.isBlank() -> setState(TransportState.NoMedia)
            player.playWhenReady -> setState(TransportState.Playing)
            state() == TransportState.Transitioning -> setState(TransportState.Stopped)
          }

          Player.STATE_ENDED -> clearPlayback()
          Player.STATE_IDLE -> if (StateStore.state.value.currentUri.isBlank()) setState(TransportState.NoMedia)
        }
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) setState(TransportState.Playing)
        else if (player.playbackState == Player.STATE_READY && state() == TransportState.Playing && !player.playWhenReady) setState(TransportState.Paused)
      }

      override fun onPlayerError(error: PlaybackException) {
        val currentUri = StateStore.state.value.currentUri
        val isAudioRendererFailure = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
        val isAudioDecoderFailure = isAudioRendererFailure || error.message?.contains("MediaCodecAudioRenderer") == true
        if (isAudioDecoderFailure && !audioFallbackUsed && currentUri.isNotBlank()) {
          audioFallbackUsed = true
          StateStore.setError("Audio decoder failed; retrying video-only: ${error.errorCodeName}", error)
          trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true))
          setState(TransportState.Transitioning)
          setMedia(currentUri, play = true)
          return
        }
        StateStore.setError("Playback error: ${error.errorCodeName}: ${error.message}", error)
        clearPlayback(clearError = false)
      }
    })

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          StateStore.state.collect { snapshot ->
            val idle = snapshot.currentUri.isBlank()
            binding.playerView.isVisible = !idle
            binding.idleBackdrop.isVisible = idle
            binding.idlePanel.isVisible = idle
            if (player.volume != snapshot.exoVolume) player.volume = snapshot.exoVolume
            if (snapshot.volume != lastVolume || snapshot.muted != lastMuted) {
              lastVolume = snapshot.volume
              lastMuted = snapshot.muted
              if (!idle) prompt(volumeText(snapshot))
            }
            if (idle) renderStatus(snapshot)
            snack(snapshot)
          }
        }
        launch {
          while (true) {
            StateStore.setPlayerPosition(safe(player.currentPosition), safe(player.duration))
            delay(1000)
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    playerScope = CoroutineScope(lifecycleScope.coroutineContext + SupervisorJob(lifecycleScope.coroutineContext[Job]))
    CastSession.attach(surface)
  }

  override fun onStop() {
    closeSurface()
    super.onStop()
  }

  override fun onDestroy() {
    closeSurface()
    player.release()
    super.onDestroy()
  }

  private fun closeSurface() {
    CastSession.detach(surface)
    controlBarJob?.cancel()
    controlBarJob = null
    binding.controlBar.animate().cancel()
    playerScope?.cancel()
    playerScope = null
  }

  override fun dispatchKeyEvent(event: KeyEvent) = event.action == KeyEvent.ACTION_DOWN && key(event.keyCode) || super.dispatchKeyEvent(event)

  private fun load(uri: String, play: Boolean) = onSurface {
    audioFallbackUsed = false
    trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false))
    setMedia(uri, play)
  }

  private fun play() = onSurface {
    val uri = StateStore.state.value.currentUri
    if (player.mediaItemCount == 0 && uri.isNotBlank()) setMedia(uri, play = true) else player.play()
    prompt(getString(R.string.player_play))
  }

  private fun pause() = onSurface {
    player.pause()
    prompt(getString(R.string.player_paused))
  }

  private fun stop() = onSurface {
    clearPlayback()
  }

  private fun seek(positionMs: Long) = onSurface {
    val target = positionMs.coerceAtLeast(0L)
    player.seekTo(target)
    prompt(getString(R.string.seek_to, formatTime(target)))
  }

  private fun onSurface(block: suspend () -> Unit) {
    playerScope?.launch {
      block()
    }
  }

  private fun state() = StateStore.state.value.transportState
  private fun setState(s: TransportState) = StateStore.state.update { it.copy(transportState = s) }
  private fun volumeText(snapshot: CastState) = if (snapshot.muted) getString(R.string.volume_muted) else getString(R.string.volume_value, snapshot.volume)

  private fun renderStatus(snapshot: CastState) {
    binding.statusState.text = getString(
      when {
        snapshot.lastError.isNotBlank() -> R.string.status_error_state
        snapshot.serviceRunning -> R.string.status_ready
        else -> R.string.status_starting
      }
    )
    binding.statusWifiValue.text = snapshot.wifiName
    binding.statusAddressValue.text = "${snapshot.ipAddress}:${snapshot.httpPort}".takeUnless { snapshot.httpPort == 0 } ?: "-"
    binding.statusServiceValue.text = getString(if (snapshot.serviceRunning) R.string.status_running else R.string.status_stopped)
    binding.statusMulticastValue.text = getString(if (snapshot.multicastLocked) R.string.status_locked else R.string.status_unlocked)
    binding.statusError.isVisible = snapshot.lastError.isNotBlank()
    binding.statusError.text = snapshot.lastError
  }

  private fun clearPlayback(clearError: Boolean = true) {
    player.stop()
    player.clearMediaItems()
    audioFallbackUsed = false
    StateStore.clearMedia(clearError)
  }

  private fun setMedia(uri: String, play: Boolean) {
    runCatching {
      val mediaItem = MediaItem.Builder().setUri(uri)
      val path = uri.substringBefore('?').lowercase()
      if (path.endsWith(".m3u8") || path.endsWith("/m3u8") || path.contains("/m3u8/")) {
        mediaItem.setMimeType(MimeTypes.APPLICATION_M3U8)
        Log.i(TAG, "loading HLS uri")
      }
      player.stop()
      player.setMediaItem(mediaItem.build())
      player.prepare()
      player.playWhenReady = play
      if (play) player.play()
    }.onFailure {
      StateStore.setError("Failed to load media: ${it.message}", it)
      setState(TransportState.Stopped)
    }
  }

  private fun key(keyCode: Int): Boolean {
    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000L, getString(R.string.seek_back_10s))
      KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000L, getString(R.string.seek_forward_30s))
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> CastSession.send(
        this, if (player.isPlaying) CastCommand.Pause else CastCommand.Play
      )

      KeyEvent.KEYCODE_MEDIA_PLAY -> CastSession.send(this, CastCommand.Play)
      KeyEvent.KEYCODE_MEDIA_PAUSE -> CastSession.send(this, CastCommand.Pause)
      KeyEvent.KEYCODE_MEDIA_STOP -> CastSession.send(this, CastCommand.Stop)
      else -> return false
    }
    return true
  }

  private fun seekBy(deltaMs: Long, label: String) {
    val duration = safe(player.duration)
    val target = if (duration > 0L) (player.currentPosition + deltaMs).coerceIn(0L, duration) else (player.currentPosition + deltaMs).coerceAtLeast(0L)
    player.seekTo(target)
    StateStore.setPlayerPosition(target, duration)
    prompt(getString(R.string.seek_with_time, label, formatTime(target)))
  }

  private fun prompt(message: String) {
    controlBarJob?.cancel()
    transientControlText = message
    snack(StateStore.state.value)
    controlBarJob = lifecycleScope.launch {
      delay(1400L)
      transientControlText = ""
      snack(StateStore.state.value)
    }
  }

  private fun snack(snapshot: CastState) {
    val hasMedia = snapshot.currentUri.isNotBlank()
    val pinned = snapshot.transportState == TransportState.Paused || snapshot.transportState == TransportState.Transitioning || snapshot.muted
    val shouldShow = hasMedia && (pinned || transientControlText.isNotBlank())
    if (!shouldShow) {
      if (binding.controlBar.isVisible) {
        binding.controlBar.animate().cancel()
        binding.controlBar.animate().alpha(0f).setDuration(180L).withEndAction { if (transientControlText.isBlank()) binding.controlBar.visibility = View.GONE }.start()
      }
      return
    }

    binding.controlBar.animate().cancel()
    binding.controlBar.visibility = View.VISIBLE
    binding.controlBar.alpha = if (pinned) 0.92f else 0.82f
    binding.controlStateText.text = transientControlText.ifBlank { controlText(snapshot) }
    binding.controlTimeText.text = "${formatTime(snapshot.positionMs)} / ${if (snapshot.durationMs > 0L) formatTime(snapshot.durationMs) else "--:--"}"

    if (snapshot.transportState == TransportState.Transitioning && snapshot.durationMs <= 0L) {
      binding.controlProgress.isIndeterminate = true
    } else {
      binding.controlProgress.isIndeterminate = false
      binding.controlProgress.progress = if (snapshot.durationMs <= 0L) 0 else ((snapshot.positionMs.coerceAtLeast(0L) * PROGRESS_MAX) / snapshot.durationMs).toInt().coerceIn(0, PROGRESS_MAX)
      binding.controlProgress.secondaryProgress = binding.controlProgress.progress
    }
  }

  private fun controlText(snapshot: CastState): String {
    val state = getString(snapshot.transportState.label)
    return when {
      !snapshot.muted -> state
      snapshot.transportState == TransportState.Paused || snapshot.transportState == TransportState.Transitioning -> "$state  ${getString(R.string.volume_muted)}"
      else -> getString(R.string.volume_muted)
    }
  }

  private companion object {
    const val PROGRESS_MAX = 10_000
  }
}

private fun safe(value: Long): Long = if (value == C.TIME_UNSET || value < 0L) 0L else value

@Suppress("DEPRECATION")
private fun ssid(context: Context): String {
  val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
  val capabilities = connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
  if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) return "Ethernet"
  val raw = (capabilities?.transportInfo as? WifiInfo)?.ssid ?: context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid ?: ""
  return raw.trim().removeSurrounding("\"").replace('\n', ' ').replace('\r', ' ').takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) } ?: "未获取"
}

private data class CastState(
  val uuid: String = "", val ipAddress: String = "0.0.0.0", val httpPort: Int = 0, val wifiName: String = "未获取",
  val serviceRunning: Boolean = false, val multicastLocked: Boolean = false, val currentUri: String = "",
  val transportState: TransportState = TransportState.NoMedia, val lastError: String = "", val positionMs: Long = 0L,
  val durationMs: Long = 0L, val volume: Int = 100, val muted: Boolean = false,
) {
  val exoVolume: Float get() = if (muted) 0f else volume.coerceIn(0, 100) / 100f
}

enum class TransportState(val wire: String, val label: Int) {
  NoMedia("NO_MEDIA_PRESENT", R.string.player_waiting), Stopped("STOPPED", R.string.player_stopped), Paused("PAUSED_PLAYBACK", R.string.player_paused), Playing(
    "PLAYING", R.string.player_playing
  ),
  Transitioning("TRANSITIONING", R.string.player_buffering),
}

private fun transportInfo(state: CastState): Map<String, String> = mapOf(
  "CurrentTransportState" to state.transportState.wire,
  "CurrentTransportStatus" to "OK",
  "CurrentSpeed" to "1",
)

private fun positionInfo(state: CastState): Map<String, String> = mapOf(
  "Track" to if (state.currentUri.isBlank()) "0" else "1",
  "TrackDuration" to formatTime(state.durationMs),
  "TrackMetaData" to "",
  "TrackURI" to state.currentUri,
  "RelTime" to formatTime(state.positionMs),
  "AbsTime" to formatTime(state.positionMs),
  "RelCount" to "2147483647",
  "AbsCount" to "2147483647",
)

private fun mediaInfo(state: CastState): Map<String, String> = mapOf(
  "NrTracks" to if (state.currentUri.isBlank()) "0" else "1",
  "MediaDuration" to formatTime(state.durationMs),
  "CurrentURI" to state.currentUri,
  "CurrentURIMetaData" to "",
  "NextURI" to "",
  "NextURIMetaData" to "",
  "PlayMedium" to "NETWORK",
  "RecordMedium" to "NOT_IMPLEMENTED",
  "WriteStatus" to "NOT_IMPLEMENTED",
)

private object StateStore {
  val state = MutableStateFlow(CastState())

  fun setServerState(
    running: Boolean,
    uuid: String = state.value.uuid,
    ipAddress: String = state.value.ipAddress,
    httpPort: Int = state.value.httpPort,
    wifiName: String = state.value.wifiName,
    multicastLocked: Boolean = state.value.multicastLocked
  ) = state.update { it.copy(serviceRunning = running, uuid = uuid, ipAddress = ipAddress, httpPort = httpPort, wifiName = wifiName, multicastLocked = multicastLocked) }

  fun setError(message: String, throwable: Throwable? = null) {
    if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    state.update { it.copy(lastError = message) }
  }

  fun setPlayerPosition(positionMs: Long, durationMs: Long) = state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L), durationMs = durationMs.coerceAtLeast(0L)) }
  fun setTransport(transportState: TransportState) = state.update { it.copy(transportState = if (it.currentUri.isBlank()) TransportState.NoMedia else transportState, lastError = "") }
  fun clearMedia(clearError: Boolean = true) = state.update {
    it.copy(currentUri = "", transportState = TransportState.NoMedia, positionMs = 0L, durationMs = 0L, lastError = if (clearError) "" else it.lastError)
  }

  fun setVolume(volume: Int) = state.update { it.copy(volume = volume.coerceIn(0, 100)) }
  fun setMuted(muted: Boolean) = state.update { it.copy(muted = muted) }
}

private fun formatTime(milliseconds: Long): String {
  val s = milliseconds.coerceAtLeast(0L) / 1000L
  return String.format(Locale.US, "%d:%02d:%02d", s / 3600L, s % 3600L / 60L, s % 60L)
}

fun String.escapeXml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
fun String.unescapeXml(): String = this.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

private data class DmrInfo(val uuid: String, val ipAddress: String, val httpPort: Int)

private class Dmr(private val context: Context, private val send: (CastCommand) -> Unit) {
  private var scope: CoroutineScope? = null
  private var httpServer: ServerSocket? = null
  private var ssdpSocket: MulticastSocket? = null
  private var airplayListener: NsdManager.RegistrationListener? = null

  private lateinit var uuid: String
  private var ipAddress = "0.0.0.0"
  private var httpPort = 0

  @SuppressLint("HardwareIds")
  fun start(): DmrInfo {
    if (scope?.isActive == true) return info()

    val prefs = context.getSharedPreferences("notch_cat_cast", Context.MODE_PRIVATE)
    uuid =
      prefs.getString("uuid", null) ?: UUID.nameUUIDFromBytes("NotchCatCast:${Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()}".toByteArray(Charsets.UTF_8))
        .toString().also { prefs.edit { putString("uuid", it) } }
    val addresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
      .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    ipAddress = addresses.firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: addresses.firstOrNull()?.hostAddress ?: "127.0.0.1"
    val serverSocket = ServerSocket(0)
    httpServer = serverSocket
    httpPort = serverSocket.localPort

    val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope = serverScope
    serverScope.launch { httpLoop(serverSocket, serverScope) }
    serverScope.launch { ssdpLoop(serverScope) }
    registerAirplay()
    serverScope.launch {
      delay(300)
      ssdpTargets().forEach { sendNotify(it, "ssdp:alive") }
    }
    return info()
  }

  fun stop() {
    val activeScope = scope ?: return
    if (::uuid.isInitialized) ssdpTargets().forEach { sendNotify(it, "ssdp:byebye") }
    scope = null
    activeScope.cancel()
    unregisterAirplay()
    runCatching { ssdpSocket?.close() }
    ssdpSocket = null
    runCatching { httpServer?.close() }
    httpServer = null
  }

  private fun info() = DmrInfo(uuid, ipAddress, httpPort)

  private fun httpLoop(serverSocket: ServerSocket, serverScope: CoroutineScope) {
    while (serverScope.isActive) {
      val socket = try {
        serverSocket.accept()
      } catch (error: Exception) {
        if (serverScope.isActive) StateStore.setError("HTTP server accept failed", error)
        break
      }
      serverScope.launch { handleHttp(socket) }
    }
  }

  private fun handleHttp(socket: Socket) {
    socket.use {
      val request = runCatching { readRequest(it) }.getOrElse { error ->
        StateStore.setError("HTTP request parse failed", error)
        return
      } ?: return

      val route = request.path.substringBefore("?")
      val action = if (request.method == "POST") soapAction(request) else ""
      val ua = request.headers["user-agent"]?.takeIf { it.isNotBlank() }?.take(MAX_LOG_BODY)
      Log.i(TAG, "HTTP ${socket.inetAddress.hostAddress} ${request.method} $route${if (action.isBlank()) "" else " action=$action"}${if (ua == null) "" else " ua=$ua"}")

      val isGetLike = request.method == "GET" || request.method == "HEAD"
      val response = when {
        isGetLike && route == "/server-info" -> airplayServerInfo()
        isGetLike && route == "/playback-info" -> airplayPlaybackInfo()
        isGetLike && route == "/scrub" -> airplayScrubInfo()
        request.method == "POST" && route == "/play" -> airplayPlay(request)
        request.method == "POST" && route == "/rate" -> airplayRate(request)
        request.method == "POST" && route == "/scrub" -> airplayScrub(request)
        request.method == "POST" && route == "/stop" -> send(CastCommand.Stop).let { HttpResponse.empty() }
        request.method == "POST" && route == "/reverse" -> HttpResponse(
          101,
          "Switching Protocols",
          "",
          extraHeaders = mapOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade")
        )

        isGetLike && (route == "/" || route == "/description.xml") -> HttpResponse.ok(deviceDescription())
        isGetLike && (route == "/upnp/AVTransport/scpd.xml" || route == "/_urn:schemas-upnp-org:service:AVTransport_scpd.xml") -> HttpResponse.ok(asset("AVTransport.scpd.xml"))
        isGetLike && (route == "/upnp/RenderingControl/scpd.xml" || route == "/_urn:schemas-upnp-org:service:RenderingControl_scpd.xml") -> HttpResponse.ok(asset("RenderingControl.scpd.xml"))
        isGetLike && (route == "/upnp/ConnectionManager/scpd.xml" || route == "/_urn:schemas-upnp-org:service:ConnectionManager_scpd.xml") -> HttpResponse.ok(asset("ConnectionManager.scpd.xml"))
        request.method == "POST" && (route == "/upnp/control/AVTransport" || route == "/_urn:schemas-upnp-org:service:AVTransport_control") -> handleAvTransport(request)
        request.method == "POST" && (route == "/upnp/control/RenderingControl" || route == "/_urn:schemas-upnp-org:service:RenderingControl_control") -> handleRenderingControl(request)
        request.method == "POST" && (route == "/upnp/control/ConnectionManager" || route == "/_urn:schemas-upnp-org:service:ConnectionManager_control") -> handleConnectionManager(request)
        request.method == "SUBSCRIBE" && (route.startsWith("/upnp/event/") || route.endsWith("_event")) -> HttpResponse(
          200,
          "OK",
          "",
          extraHeaders = mapOf("SID" to "uuid:${UUID.randomUUID()}", "TIMEOUT" to "Second-1800")
        )

        request.method == "UNSUBSCRIBE" && (route.startsWith("/upnp/event/") || route.endsWith("_event")) -> HttpResponse(statusCode = 200, reason = "OK", body = "")
        else -> HttpResponse(statusCode = 404, reason = "Not Found", body = "Not Found", contentType = "text/plain; charset=\"utf-8\"")
      }
      Log.i(TAG, "HTTP response ${response.statusCode} ${request.method} $route")
      writeResponse(it, if (request.method == "HEAD") response.copy(omitBody = true) else response)
    }
  }

  private fun ssdpLoop(serverScope: CoroutineScope) {
    val socket = MulticastSocket(null).apply {
      reuseAddress = true
      bind(InetSocketAddress(SSDP_PORT))
      soTimeout = 0
    }
    ssdpSocket = socket

    val group = InetAddress.getByName(SSDP_ADDRESS)
    val targetAddress = runCatching { InetAddress.getByName(ipAddress) }.getOrNull()
    val networkInterface = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { candidate -> candidate.inetAddresses.toList().any { it == targetAddress } }
    runCatching {
      if (networkInterface != null) {
        socket.networkInterface = networkInterface
        socket.joinGroup(InetSocketAddress(group, SSDP_PORT), networkInterface)
      } else {
        @Suppress("DEPRECATION") socket.joinGroup(group)
      }
    }.onFailure { StateStore.setError("Failed to join SSDP multicast group", it) }

    val buffer = ByteArray(8192)
    while (serverScope.isActive) {
      val packet = DatagramPacket(buffer, buffer.size)
      runCatching { socket.receive(packet) }.onSuccess {
        val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        if (message.startsWith("M-SEARCH", ignoreCase = true)) {
          val headers = parseHeaders(message.lines())
          if (!headers["man"].orEmpty().contains("ssdp:discover", ignoreCase = true)) return@onSuccess
          val searchTarget = headers["st"] ?: return@onSuccess
          Log.i(TAG, "SSDP search from=${packet.address.hostAddress}:${packet.port} st=$searchTarget")
          val normalizedTarget = searchTarget.trim()
          ssdpTargets().filter { normalizedTarget.equals("ssdp:all", ignoreCase = true) || it.equals(normalizedTarget, ignoreCase = true) }.forEach { target ->
            sendUdp(
              udpMessage(
                "HTTP/1.1 200 OK", "CACHE-CONTROL: max-age=1800", "DATE: ${httpDate()}", "EXT:", "LOCATION: ${locationUrl()}", "SERVER: $SERVER_HEADER", "ST: $target", "USN: ${usnFor(target)}"
              ), packet.address, packet.port, socket
            )
          }
        }
      }.onFailure {
        if (serverScope.isActive) StateStore.setError("SSDP receive failed", it)
      }
    }
  }

  private fun registerAirplay() {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return
    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
        Log.i(TAG, "Airplay registered ${serviceInfo.serviceName}:$httpPort")
      }

      override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = StateStore.setError("Airplay registration failed: $errorCode")
      override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
        Log.i(TAG, "Airplay unregistered")
      }

      override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "Airplay unregister failed: $errorCode")
      }
    }
    val service = NsdServiceInfo().apply {
      serviceName = context.getString(R.string.application_name)
      serviceType = "_airplay._tcp."
      port = httpPort
      setAttribute("deviceid", airplayDeviceId())
      setAttribute("features", AIRPLAY_FEATURES_HEX)
      setAttribute("model", AIRPLAY_MODEL)
      setAttribute("srcvers", AIRPLAY_SOURCE_VERSION)
      setAttribute("protovers", "1.0")
      setAttribute("flags", "0x4")
      setAttribute("pw", "false")
    }
    airplayListener = listener
    runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { StateStore.setError("Airplay registration failed", it) }
  }

  private fun unregisterAirplay() {
    val listener = airplayListener ?: return
    airplayListener = null
    runCatching { context.getSystemService(NsdManager::class.java)?.unregisterService(listener) }.onFailure { Log.w(TAG, "Airplay unregister failed: ${it.message}") }
  }

  private fun sendNotify(target: String, nts: String) {
    val alive = nts == "ssdp:alive"
    val headers = mutableListOf("NOTIFY * HTTP/1.1", "HOST: $SSDP_ADDRESS:$SSDP_PORT")
    if (alive) headers += listOf("CACHE-CONTROL: max-age=1800", "LOCATION: ${locationUrl()}", "SERVER: $SERVER_HEADER")
    headers += listOf("NT: $target", "NTS: $nts", "USN: ${usnFor(target)}")
    sendUdp(udpMessage(*headers.toTypedArray()), InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT, ssdpSocket)
  }

  private fun sendUdp(message: String, address: InetAddress, port: Int, socket: MulticastSocket?) {
    val bytes = message.toByteArray(Charsets.UTF_8)
    val packet = DatagramPacket(bytes, bytes.size, address, port)
    runCatching { socket?.send(packet) ?: MulticastSocket().use { it.send(packet) } }.onFailure { Log.w(TAG, "SSDP send failed: ${it.message}") }
  }

  private fun udpMessage(vararg lines: String) = lines.joinToString("\r\n", postfix = "\r\n\r\n")

  private fun handleAvTransport(request: HttpRequest): HttpResponse {
    val action = runCatching { enumValueOf<AvTransportAction>(soapAction(request)) }.getOrNull() ?: return soapFault("AVTransport", 401, "Invalid Action")
    return when (action) {
      AvTransportAction.SetAVTransportURI -> setTransportUri(request, action)
      AvTransportAction.SetNextAVTransportURI, AvTransportAction.SetPlayMode, AvTransportAction.Next, AvTransportAction.Previous -> avOk(action)
      AvTransportAction.Play -> send(CastCommand.Play).let { avOk(action) }
      AvTransportAction.Pause -> send(CastCommand.Pause).let { avOk(action) }
      AvTransportAction.Stop -> send(CastCommand.Stop).let { avOk(action) }
      AvTransportAction.Seek -> send(CastCommand.Seek(parseTime(soapArg(request.body, "Target")))).let { avOk(action) }
      AvTransportAction.GetTransportInfo -> avOk(action, transportInfo(StateStore.state.value))
      AvTransportAction.GetPositionInfo -> avOk(action, positionInfo(StateStore.state.value))
      AvTransportAction.GetMediaInfo -> avOk(action, mediaInfo(StateStore.state.value))
      AvTransportAction.GetTransportSettings -> avOk(action, mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
      AvTransportAction.GetDeviceCapabilities -> avOk(action, mapOf("PlayMedia" to "NETWORK", "RecMedia" to "", "RecQualityModes" to ""))
      AvTransportAction.GetCurrentTransportActions -> avOk(action, mapOf("Actions" to "Play,Stop,Pause,Seek"))
    }
  }

  private enum class AvTransportAction {
    SetAVTransportURI, SetNextAVTransportURI, SetPlayMode, Next, Previous, Play, Pause, Stop, Seek, GetTransportInfo, GetPositionInfo, GetMediaInfo, GetTransportSettings, GetDeviceCapabilities, GetCurrentTransportActions,
  }

  private fun setTransportUri(request: HttpRequest, action: AvTransportAction): HttpResponse {
    val uri = soapArg(request.body, "CurrentURI")
    if (uri.isBlank()) return soapFault("AVTransport", 714, "Illegal MIME-type")
    Log.i(TAG, "SetAVTransportURI host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")} uri=$uri")
    return send(CastCommand.Load(uri, play = false)).let { avOk(action) }
  }

  private fun avOk(action: AvTransportAction, values: Map<String, String> = emptyMap()) = soapOk("AVTransport", action.name, values)

  private fun handleRenderingControl(request: HttpRequest) = when (val action = soapAction(request)) {
    "GetVolume" -> soapOk("RenderingControl", action, mapOf("CurrentVolume" to StateStore.state.value.volume.toString()))
    "SetVolume" -> StateStore.setVolume(soapArg(request.body, "DesiredVolume").toIntOrNull() ?: StateStore.state.value.volume).let { soapOk("RenderingControl", action) }
    "GetMute" -> soapOk("RenderingControl", action, mapOf("CurrentMute" to if (StateStore.state.value.muted) "1" else "0"))
    "SetMute" -> StateStore.setMuted(soapArg(request.body, "DesiredMute") == "1" || soapArg(request.body, "DesiredMute").equals("true", ignoreCase = true)).let { soapOk("RenderingControl", action) }
    else -> soapFault("RenderingControl", 401, "Invalid Action")
  }

  private fun handleConnectionManager(request: HttpRequest) = when (val action = soapAction(request)) {
    "GetProtocolInfo" -> soapOk("ConnectionManager", action, mapOf("Source" to "", "Sink" to SINK_PROTOCOL_INFO))
    "GetCurrentConnectionIDs" -> soapOk("ConnectionManager", action, mapOf("ConnectionIDs" to "0"))
    "GetCurrentConnectionInfo" -> soapOk(
      "ConnectionManager",
      action,
      mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to SINK_PROTOCOL_INFO, "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
    )

    else -> soapFault("ConnectionManager", 401, "Invalid Action")
  }

  private fun airplayPlay(request: HttpRequest): HttpResponse {
    val params = airplayParams(request)
    val uri = params["Content-Location"] ?: params["content-location"] ?: ""
    if (uri.isBlank()) return HttpResponse(statusCode = 400, reason = "Bad Request", body = "Missing Content-Location", contentType = "text/plain; charset=\"utf-8\"")
    val startSeconds = params["Start-Position"]?.toDoubleOrNull()?.takeIf { it >= 1.0 } ?: 0.0
    Log.i(TAG, "Airplay play host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")} uri=$uri")
    send(CastCommand.Load(uri, play = true))
    if (startSeconds > 0.0) send(CastCommand.Seek((startSeconds * 1000).toLong()))
    return HttpResponse.empty()
  }

  private fun airplayRate(request: HttpRequest): HttpResponse {
    if ((queryArg(request.path, "value")?.toDoubleOrNull() ?: 1.0) == 0.0) send(CastCommand.Pause) else send(CastCommand.Play)
    return HttpResponse.empty()
  }

  private fun airplayScrub(request: HttpRequest): HttpResponse {
    val position = queryArg(request.path, "position")?.toDoubleOrNull() ?: return HttpResponse.empty()
    send(CastCommand.Seek((position * 1000).toLong()))
    return HttpResponse.empty()
  }

  private fun airplayScrubInfo(): HttpResponse {
    val state = StateStore.state.value
    return HttpResponse.ok("duration: ${state.durationMs / 1000.0}\nposition: ${state.positionMs / 1000.0}\n", contentType = "text/parameters")
  }

  private fun airplayServerInfo() = airplayPlist(
    """
    <key>deviceid</key><string>${airplayDeviceId()}</string>
    <key>features</key><integer>$AIRPLAY_FEATURES</integer>
    <key>model</key><string>$AIRPLAY_MODEL</string>
    <key>protovers</key><string>1.0</string>
    <key>srcvers</key><string>$AIRPLAY_SOURCE_VERSION</string>
    """.trimIndent()
  )

  private fun airplayPlaybackInfo(): HttpResponse {
    val state = StateStore.state.value
    val duration = state.durationMs / 1000.0
    val position = state.positionMs / 1000.0
    val rate = if (state.transportState == TransportState.Playing) 1 else 0
    return airplayPlist(
      """
      <key>duration</key><real>$duration</real>
      <key>loadedTimeRanges</key><array><dict><key>duration</key><real>$duration</real><key>start</key><real>0</real></dict></array>
      <key>playbackBufferEmpty</key><false/>
      <key>playbackBufferFull</key><false/>
      <key>playbackLikelyToKeepUp</key><true/>
      <key>position</key><real>$position</real>
      <key>rate</key><real>$rate</real>
      <key>readyToPlay</key><${if (state.transportState == TransportState.Transitioning) "false" else "true"}/>
      <key>seekableTimeRanges</key><array><dict><key>duration</key><real>$duration</real><key>start</key><real>0</real></dict></array>
      """.trimIndent()
    )
  }

  private fun airplayPlist(body: String) = HttpResponse.ok(
    """<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict>$body</dict></plist>""",
    contentType = "text/x-apple-plist+xml"
  )

  private fun airplayParams(request: HttpRequest): Map<String, String> {
    runCatching {
      (PropertyListParser.parse(request.bodyBytes) as? NSDictionary)?.let { dict ->
        return dict.allKeys().associateWith { key -> dict.objectForKey(key).toJavaObject().toString() }
      }
    }.onFailure { Log.w(TAG, "Airplay plist parse failed: ${it.message}") }
    return request.body.lineSequence().mapNotNull { line ->
      line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).trim() to line.substring(it + 1).trim() }
    }.toMap()
  }

  private fun airplayDeviceId() = uuid.replace("-", "").take(12).chunked(2).joinToString(":") { it.uppercase(Locale.US) }

  private fun soapOk(service: String, action: String, values: Map<String, String> = emptyMap()) =
    HttpResponse.ok(
      """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">${
        values.entries.joinToString(
          separator = ""
        ) { (key, value) -> "<$key>${value.escapeXml()}</$key>" }
      }</u:${action}Response></s:Body></s:Envelope>""")

  private fun soapFault(service: String, code: Int, description: String) = HttpResponse(
    statusCode = 500,
    reason = "Internal Server Error",
    body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>${description.escapeXml()}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""
  ).also { Log.w(TAG, "SOAP fault service=$service code=$code description=$description") }

  private fun deviceDescription() =
    """<?xml version="1.0" encoding="utf-8"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://$ipAddress:$httpPort</URLBase><device><deviceType>$MEDIA_RENDERER</deviceType><presentationURL>/</presentationURL><friendlyName>${
      context.getString(R.string.application_name).escapeXml()
    }</friendlyName><manufacturer>Microsoft Corporation</manufacturer><manufacturerURL>http://www.microsoft.com</manufacturerURL><modelDescription>Media Renderer</modelDescription><modelName>Windows Media Player</modelName><modelURL>http://go.microsoft.com/fwlink/Linkld=105927</modelURL><dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC><UDN>uuid:$uuid</UDN><serviceList>${
      serviceXml(
        AV_TRANSPORT, "AVTransport"
      )
    }${serviceXml(CONNECTION_MANAGER, "ConnectionManager")}${serviceXml(RENDERING_CONTROL, "RenderingControl")}</serviceList></device></root>"""

  private fun serviceXml(type: String, name: String) =
    """<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$name</serviceId><SCPDURL>_urn:schemas-upnp-org:service:${name}_scpd.xml</SCPDURL><controlURL>_urn:schemas-upnp-org:service:${name}_control</controlURL><eventSubURL>_urn:schemas-upnp-org:service:${name}_event</eventSubURL></service>"""

  private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

  private fun readRequest(socket: Socket): HttpRequest? {
    val input = socket.getInputStream()
    val headerBytes = ByteArrayOutputStream()
    var b: Int
    val last = ArrayDeque<Int>(4)
    while (true) {
      b = input.read()
      if (b == -1) return null
      headerBytes.write(b)
      if (last.size == 4) last.removeFirst()
      last.addLast(b)
      if (last.size == 4 && last.toList() == listOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)) break
      if (headerBytes.size() > 65536) throw IllegalArgumentException("HTTP header too large")
    }

    val headerText = headerBytes.toString(Charsets.ISO_8859_1.name())
    val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
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
    return HttpRequest(method = parts[0].uppercase(Locale.US), path = parts[1], headers = headers, body = String(bodyBytes, 0, offset, Charsets.UTF_8), bodyBytes = bodyBytes.copyOf(offset))
  }

  private fun writeResponse(socket: Socket, response: HttpResponse) {
    val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
    val hasConnection = response.extraHeaders.keys.any { it.equals("connection", ignoreCase = true) }
    val headers = udpMessage(
      *(listOf(
        "HTTP/1.1 ${response.statusCode} ${response.reason}",
        "DATE: ${httpDate()}",
        "SERVER: $SERVER_HEADER",
        "CONTENT-LENGTH: ${bodyBytes.size}",
        "CONTENT-TYPE: ${response.contentType}",
        if (hasConnection) null else "CONNECTION: close"
      ).filterNotNull() + response.extraHeaders.map { (key, value) -> "$key: $value" }).toTypedArray()
    )
    socket.getOutputStream().use {
      it.write(headers.toByteArray(Charsets.ISO_8859_1))
      if (!response.omitBody) it.write(bodyBytes)
      it.flush()
    }
  }

  private fun queryArg(path: String, name: String) = path.substringAfter('?', "").split('&').firstNotNullOfOrNull { part ->
    val index = part.indexOf('=')
    if (index <= 0 || !part.take(index).equals(name, ignoreCase = true)) null else Uri.decode(part.substring(index + 1))
  }

  private fun parseHeaders(lines: List<String>): Map<String, String> =
    lines.mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).lowercase(Locale.US).trim() to line.substring(it + 1).trim() } }.toMap()

  private fun soapAction(request: HttpRequest): String {
    request.headers["soapaction"]?.trim()?.trim('"')?.substringAfter('#')?.takeIf { it.isNotBlank() }?.let { return it }
    val actionRegex = Regex("<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)(?:\\s|>)", RegexOption.DOT_MATCHES_ALL)
    return actionRegex.find(request.body.substringAfter("<s:Body", request.body))?.groupValues?.getOrNull(1).orEmpty()
  }

  private fun soapArg(body: String, name: String) =
    Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.getOrNull(1)?.trim()?.unescapeXml().orEmpty()

  private fun parseTime(value: String): Long {
    val clean = value.trim().substringBefore(".")
    val parts = clean.split(":").mapNotNull { it.toLongOrNull() }
    return when (parts.size) {
      3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
      2 -> parts[0] * 60L + parts[1]
      1 -> parts[0]
      else -> 0L
    } * 1000L
  }

  private fun ssdpTargets(): List<String> = listOf("upnp:rootdevice", "uuid:$uuid", MEDIA_RENDERER, AV_TRANSPORT, CONNECTION_MANAGER, RENDERING_CONTROL)
  private fun usnFor(target: String) = when (target) {
    "uuid:$uuid" -> "uuid:$uuid"
    "upnp:rootdevice" -> "uuid:$uuid::upnp:rootdevice"
    else -> "uuid:$uuid::$target"
  }

  private fun locationUrl(): String = "http://$ipAddress:$httpPort/description.xml"
  private fun httpDate(): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC))
  private data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String, val bodyBytes: ByteArray)
  private data class HttpResponse(
    val statusCode: Int,
    val reason: String,
    val body: String,
    val contentType: String = """text/xml; charset="utf-8"""",
    val extraHeaders: Map<String, String> = emptyMap(),
    val omitBody: Boolean = false
  ) {
    companion object {
      fun ok(body: String, contentType: String = """text/xml; charset="utf-8"""") = HttpResponse(statusCode = 200, reason = "OK", body = body.trimStart(), contentType = contentType)
      fun empty() = HttpResponse(statusCode = 200, reason = "OK", body = "")
    }
  }

  private companion object {
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
    const val SINK_PROTOCOL_INFO = "http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:*:*"
    const val AIRPLAY_FEATURES = 17
    const val AIRPLAY_FEATURES_HEX = "0x11"
    const val AIRPLAY_MODEL = "AppleTV2,1"
    const val AIRPLAY_SOURCE_VERSION = "130.14"
    const val SERVER_HEADER = "Linux/5.15.170-android14-11-gf4a1f03072af HTTP/1.0 BDLE+DLNA/1.1 NotchCatCast/1.0"
    const val MAX_LOG_BODY = 2048
  }
}
