@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package notch.cat.cast

import android.Manifest
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
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import notch.cat.cast.databinding.ActivityMainBinding
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private fun Context.playerIntent() = Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
private fun Context.startReceiver() = runCatching { startForegroundService(Intent(this, ReceiverService::class.java)) }.onFailure { Log.e(Consts.App.TAG, "start receiver failed", it) }
private fun Context.openPlayerPage() {
  val opened = runCatching {
    startActivity(playerIntent())
    cancelOpenPlayerNotification()
  }.onFailure { Log.e(Consts.App.TAG, "open player page failed", it) }.isSuccess
  if (opened) return
  if (hasNotificationPermission()) notifyOpenPlayer() else Log.w(Consts.App.TAG, "cannot notify player open: missing POST_NOTIFICATIONS")
}
private fun Context.notifyOpenPlayer() {
  if (!hasNotificationPermission()) {
    Log.w(Consts.App.TAG, "cannot notify player open: missing POST_NOTIFICATIONS")
    return
  }
  val notifications = getSystemService(NotificationManager::class.java) ?: return
  notifications.createNotificationChannel(NotificationChannel(Consts.App.OPEN_PLAYER_CHANNEL_ID, getString(R.string.notification_open_channel), NotificationManager.IMPORTANCE_DEFAULT))
  val intent = PendingIntent.getActivity(this, 0, playerIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification = Notification.Builder(this, Consts.App.OPEN_PLAYER_CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.notification_open_title))
    .setContentText(getString(R.string.notification_open_text)).setContentIntent(intent).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).build()
  notifications.notify(Consts.App.OPEN_PLAYER_NOTIFICATION_ID, notification)
}

class AutoStartReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in Consts.App.START_ACTIONS) return
    Log.i(Consts.App.TAG, "auto start action=${intent.action}")
    context.startReceiver()
  }
}

sealed interface PlaybackCommand {
  data class Load(val uri: String, val play: Boolean) : PlaybackCommand
  data class Seek(val positionMs: Long) : PlaybackCommand
  data object Play : PlaybackCommand
  data object Pause : PlaybackCommand
  data object Stop : PlaybackCommand
  data object StartMirror : PlaybackCommand
  data object StopMirror : PlaybackCommand
}

private object PlayerLink {
  private val backlog = ArrayDeque<PlaybackCommand>()

  @Volatile
  private var player: ((PlaybackCommand) -> Unit)? = null

  fun attach(player: (PlaybackCommand) -> Unit) = synchronized(this) {
    this.player = player
    backlog.toList().also { backlog.clear() }
  }.forEach { player(it) }

  fun detach(player: (PlaybackCommand) -> Unit) = synchronized(this) { if (this.player === player) this.player = null }

  fun send(context: Context, command: PlaybackCommand) {
    reduce(command)
    val player = synchronized(this) {
      player ?: run {
        backlog.addLast(command)
        null
      }
    }
    if (player != null) player(command) else context.openPlayerPage()
  }

  private fun reduce(command: PlaybackCommand) {
    when (command) {
      is PlaybackCommand.Load -> Runtime.state.update { it.copy(currentUri = command.uri, transportState = TransportState.Transitioning, positionMs = 0L, durationMs = 0L, lastError = "") }
      PlaybackCommand.Play -> Runtime.transport(TransportState.Playing)
      PlaybackCommand.Pause -> Runtime.transport(TransportState.Paused)
      PlaybackCommand.Stop -> Runtime.clear()
      PlaybackCommand.StartMirror -> Runtime.mirror()
      PlaybackCommand.StopMirror -> Runtime.clear()

      is PlaybackCommand.Seek -> Runtime.position(command.positionMs.coerceAtLeast(0L), Runtime.state.value.durationMs)
    }
  }
}

class ReceiverService : Service() {
  private val binder = ReceiverBinder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var dlna: DlnaRenderer? = null
  private var airplay: AirplayReceiver? = null
  private var mirror: MirrorStream? = null
  private var multicastLock: WifiManager.MulticastLock? = null

  override fun onCreate() {
    super.onCreate()
    startForeground(Consts.App.SERVICE_NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    startReceivers()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY.also { startReceivers() }

  override fun onDestroy() {
    airplay?.stop()
    airplay = null
    mirror = null
    dlna?.stop()
    dlna = null
    runCatching { multicastLock?.release() }
    multicastLock = null
    Runtime.server(running = false, multicastLocked = false)
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder = binder

  inner class ReceiverBinder : Binder() {
    fun attachMirror(sink: MirrorSink) {
      startReceivers()
      mirror?.attach(sink)
    }

    fun detachMirror(sink: MirrorSink) {
      mirror?.detach(sink)
    }
  }

  private fun startReceivers() {
    if (dlna != null) return
    multicastLock = applicationContext.getSystemService(WifiManager::class.java)?.createMulticastLock("NotchCatCastDlnaMulticast")?.apply {
      setReferenceCounted(false)
      acquire()
    }
    val dlna = DlnaRenderer(applicationContext) { PlayerLink.send(this, it) }
    val endpoint = dlna.start(scope)
    val mirror = MirrorStream()
    this.dlna = dlna
    this.mirror = mirror
    airplay = AirplayReceiver(applicationContext, endpoint.uuid, mirror) { PlayerLink.send(this, it) }.also { it.start(scope) }
    Runtime.server(
      running = true, uuid = endpoint.uuid, ipAddress = endpoint.ipAddress, httpPort = endpoint.httpPort, wifiName = applicationContext.wifiName(), multicastLocked = multicastLock?.isHeld == true
    )
  }

  private fun notification(): Notification {
    val notifications = getSystemService(NotificationManager::class.java)!!
    notifications.createNotificationChannel(NotificationChannel(Consts.App.SERVICE_CHANNEL_ID, getString(R.string.application_name), NotificationManager.IMPORTANCE_LOW))
    val intent = PendingIntent.getActivity(this, 0, playerIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, Consts.App.SERVICE_CHANNEL_ID).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.application_name)).setContentText(getString(R.string.notification_service_text))
      .setContentIntent(intent).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
  }
}

class PlayerActivity : ComponentActivity() {
  private lateinit var binding: ActivityMainBinding
  private lateinit var player: ExoPlayer
  private lateinit var trackSelector: DefaultTrackSelector
  private var promptJob: Job? = null
  private var promptText = ""
  private var videoOnlyFallbackUsed = false
  private var lastVolume = Runtime.state.value.volume
  private var lastMuted = Runtime.state.value.muted
  private var mirrorDecoder: MirrorDecoder? = null
  private var mirrorSink: MirrorSink? = null
  private var mirrorJob: Job? = null
  private var requestingPermissions = false
  private var askedWifiPermission = false
  private var askedNotificationPermission = false
  private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
    Runtime.wifi(applicationContext.wifiName())
    if (requestingPermissions) advancePermissionFlow() else renderPermissionButton()
  }
  private val playerCommands: (PlaybackCommand) -> Unit = { command ->
    when (command) {
      is PlaybackCommand.Load -> load(command.uri, command.play)
      PlaybackCommand.Play -> play()
      PlaybackCommand.Pause -> pause()
      PlaybackCommand.Stop -> stop()
      is PlaybackCommand.Seek -> seek(command.positionMs)
      PlaybackCommand.StartMirror -> startMirror()
      PlaybackCommand.StopMirror -> stopMirror()
    }
  }
  private val mirrorSurface = object : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) {
      attachMirrorDecoder(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
      releaseMirrorDecoder()
    }
  }

  @Volatile
  private var playerScope: CoroutineScope? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applicationContext.startReceiver()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    trackSelector = DefaultTrackSelector(this)
    val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true).forceDisableMediaCodecAsynchronousQueueing()

    player = ExoPlayer.Builder(this, renderersFactory).setTrackSelector(trackSelector).build().apply {
      videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
      volume = Runtime.state.value.playerVolume
    }
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    binding.playerView.player = player
    binding.mirrorView.holder.addCallback(mirrorSurface)
    binding.permissionButton.setOnClickListener { beginPermissionFlow() }

    player.addListener(object : Player.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
          Player.STATE_BUFFERING -> if (Runtime.state.value.currentUri.isNotBlank() && player.playWhenReady) transportState(TransportState.Transitioning)
          Player.STATE_READY -> when {
            Runtime.state.value.currentUri.isBlank() -> transportState(TransportState.NoMedia)
            player.playWhenReady -> transportState(TransportState.Playing)
            transportState() == TransportState.Transitioning -> transportState(TransportState.Stopped)
          }

          Player.STATE_ENDED -> clearPlayback()
          Player.STATE_IDLE -> if (Runtime.state.value.currentUri.isBlank()) transportState(TransportState.NoMedia)
        }
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) transportState(TransportState.Playing)
        else if (player.playbackState == Player.STATE_READY && transportState() == TransportState.Playing && !player.playWhenReady) transportState(TransportState.Paused)
      }

      override fun onPlayerError(error: PlaybackException) {
        val currentUri = Runtime.state.value.currentUri
        val isAudioRendererFailure = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat?.sampleMimeType?.startsWith("audio/") == true
        val isAudioDecoderFailure = isAudioRendererFailure || error.message?.contains("MediaCodecAudioRenderer") == true
        if (isAudioDecoderFailure && !videoOnlyFallbackUsed && currentUri.isNotBlank()) {
          videoOnlyFallbackUsed = true
          Runtime.error("Audio decoder failed; retrying video-only: ${error.errorCodeName}", error)
          trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true))
          transportState(TransportState.Transitioning)
          setMedia(currentUri, play = true)
          return
        }
        Runtime.error("Playback error: ${error.errorCodeName}: ${error.message}", error)
        clearPlayback(clearError = false)
      }
    })

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch {
          Runtime.state.collect { snapshot ->
            val idle = snapshot.currentUri.isBlank()
            binding.playerView.isVisible = !idle && !snapshot.isMirror
            binding.mirrorView.isVisible = snapshot.isMirror
            binding.idleBackdrop.isVisible = idle
            binding.idlePanel.isVisible = idle
            if (player.volume != snapshot.playerVolume) player.volume = snapshot.playerVolume
            if (snapshot.volume != lastVolume || snapshot.muted != lastMuted) {
              lastVolume = snapshot.volume
              lastMuted = snapshot.muted
              if (!idle) prompt(volumeText(snapshot))
            }
            if (idle) renderStatus(snapshot)
            renderControls(snapshot)
          }
        }
        launch {
          while (true) {
            Runtime.position(playbackTime(player.currentPosition), playbackTime(player.duration))
            delay(1000)
          }
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    applicationContext.cancelOpenPlayerNotification()
    playerScope = CoroutineScope(lifecycleScope.coroutineContext + SupervisorJob(lifecycleScope.coroutineContext[Job]))
    PlayerLink.attach(playerCommands)
  }

  override fun onResume() {
    super.onResume()
    Runtime.wifi(applicationContext.wifiName())
    renderPermissionButton()
  }

  override fun onStop() {
    player.pause()
    detachPlayer()
    super.onStop()
  }

  override fun onDestroy() {
    detachPlayer()
    player.release()
    super.onDestroy()
  }

  private fun detachPlayer() {
    PlayerLink.detach(playerCommands)
    releaseMirrorDecoder()
    promptJob?.cancel()
    promptJob = null
    binding.controlBar.animate().cancel()
    playerScope?.cancel()
    playerScope = null
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val down = event.action == KeyEvent.ACTION_DOWN
    when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) seekBy(-10_000L, getString(R.string.seek_back_10s))
      KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seekBy(30_000L, getString(R.string.seek_forward_30s))
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> if (down) PlayerLink.send(this, if (player.isPlaying) PlaybackCommand.Pause else PlaybackCommand.Play)
      KeyEvent.KEYCODE_MEDIA_PLAY -> if (down) PlayerLink.send(this, PlaybackCommand.Play)
      KeyEvent.KEYCODE_MEDIA_PAUSE -> if (down) PlayerLink.send(this, PlaybackCommand.Pause)
      KeyEvent.KEYCODE_MEDIA_STOP -> if (down) PlayerLink.send(this, PlaybackCommand.Stop)
      else -> return super.dispatchKeyEvent(event)
    }
    return true
  }

  private fun load(uri: String, play: Boolean) = onPlayer {
    videoOnlyFallbackUsed = false
    trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false))
    setMedia(uri, play)
  }

  private fun play() = onPlayer {
    val uri = Runtime.state.value.currentUri
    if (player.mediaItemCount == 0 && uri.isNotBlank()) setMedia(uri, play = true) else player.play()
    prompt(getString(R.string.player_play))
  }

  private fun pause() = onPlayer {
    player.pause()
    prompt(getString(R.string.player_paused))
  }

  private fun stop() = onPlayer {
    clearPlayback()
  }

  private fun startMirror() = onPlayer {
    player.stop()
    player.clearMediaItems()
    videoOnlyFallbackUsed = false
    prompt(getString(R.string.player_playing))
  }

  private fun stopMirror() = onPlayer {
    mirrorDecoder?.stop()
    clearPlayback()
  }

  private fun attachMirrorDecoder(holder: SurfaceHolder) {
    if (!holder.surface.isValid) return
    releaseMirrorDecoder()
    val decoder = MirrorDecoder(holder.surface)
    val sink = object : MirrorSink {
      override fun format(codec: MirrorCodec) {
        runOnUiThread { fitMirrorView(codec.width, codec.height) }
        decoder.format(codec)
      }

      override fun frame(bytes: ByteArray) = decoder.frame(bytes)
      override fun stop() = decoder.stop()
    }
    mirrorDecoder = decoder
    mirrorSink = sink
    mirrorJob = lifecycleScope.launch {
      try {
        applicationContext.withReceiver {
          attachMirror(sink)
          try {
            awaitCancellation()
          } finally {
            detachMirror(sink)
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        Log.e(Consts.App.TAG, "bind cast service failed", error)
      }
    }
  }

  private fun releaseMirrorDecoder() {
    mirrorJob?.cancel()
    mirrorJob = null
    mirrorSink = null
    mirrorDecoder?.let {
      it.release()
    }
    mirrorDecoder = null
  }

  private fun fitMirrorView(contentWidth: Int, contentHeight: Int) {
    val rootWidth = binding.root.width
    val rootHeight = binding.root.height
    if (rootWidth <= 0 || rootHeight <= 0) {
      binding.root.post { fitMirrorView(contentWidth, contentHeight) }
      return
    }
    val fit = mirrorFit(rootWidth, rootHeight, contentWidth, contentHeight)
    val layout = binding.mirrorView.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(fit.width, fit.height)
    if (layout.width == fit.width && layout.height == fit.height && layout.gravity == Gravity.CENTER) return
    binding.mirrorView.layoutParams = FrameLayout.LayoutParams(fit.width, fit.height, Gravity.CENTER)
    Log.i(Consts.App.TAG, "Airplay mirror view fit video=${contentWidth}x$contentHeight view=${fit.width}x${fit.height} root=${rootWidth}x$rootHeight")
  }

  private fun seek(positionMs: Long) = onPlayer {
    val position = positionMs.coerceAtLeast(0L)
    player.seekTo(position)
    prompt(getString(R.string.seek_to, Soap.formatTime(position)))
  }

  private fun onPlayer(block: suspend () -> Unit) {
    playerScope?.launch {
      block()
    }
  }

  private fun transportState() = Runtime.state.value.transportState
  private fun transportState(transport: TransportState) = Runtime.state.update { it.copy(transportState = transport) }
  private fun volumeText(snapshot: CastSnapshot) = if (snapshot.muted) getString(R.string.volume_muted) else getString(R.string.volume_value, snapshot.volume)

  private fun renderStatus(snapshot: CastSnapshot) {
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
    renderPermissionButton()
  }

  private fun beginPermissionFlow() {
    requestingPermissions = true
    askedWifiPermission = false
    askedNotificationPermission = false
    advancePermissionFlow()
  }

  private fun advancePermissionFlow() {
    renderPermissionButton()
    when {
      !hasWifiNamePermission() && !askedWifiPermission -> {
        askedWifiPermission = true
        permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
      }

      !hasNotificationPermission() && !askedNotificationPermission -> {
        askedNotificationPermission = true
        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }

      !hasOverlayPermission() -> {
        requestingPermissions = false
        runCatching {
          startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure { Log.e(Consts.App.TAG, "open overlay permission settings failed", it) }
      }

      else -> {
        requestingPermissions = false
        Runtime.wifi(applicationContext.wifiName())
        renderPermissionButton()
      }
    }
  }

  private fun renderPermissionButton() {
    binding.permissionButton.isVisible = !hasSetupPermissions()
  }

  private fun clearPlayback(clearError: Boolean = true) {
    player.stop()
    player.clearMediaItems()
    videoOnlyFallbackUsed = false
    Runtime.clear(clearError)
  }

  private fun setMedia(uri: String, play: Boolean) {
    runCatching {
      val mediaItem = MediaItem.Builder().setUri(uri)
      val path = uri.substringBefore('?').lowercase()
      if (path.endsWith(".m3u8") || path.endsWith("/m3u8") || path.contains("/m3u8/")) {
        mediaItem.setMimeType(MimeTypes.APPLICATION_M3U8)
        Log.i(Consts.App.TAG, "loading HLS uri")
      }
      player.stop()
      player.setMediaItem(mediaItem.build())
      player.prepare()
      player.playWhenReady = play
      if (play) player.play()
    }.onFailure {
      Runtime.error("Failed to load media: ${it.message}", it)
      transportState(TransportState.Stopped)
    }
  }

  private fun seekBy(offsetMs: Long, caption: String) {
    val duration = playbackTime(player.duration)
    val position = if (duration > 0L) (player.currentPosition + offsetMs).coerceIn(0L, duration) else (player.currentPosition + offsetMs).coerceAtLeast(0L)
    player.seekTo(position)
    Runtime.position(position, duration)
    prompt(getString(R.string.seek_with_time, caption, Soap.formatTime(position)))
  }

  private fun prompt(message: String) {
    promptJob?.cancel()
    promptText = message
    renderControls(Runtime.state.value)
    promptJob = lifecycleScope.launch {
      delay(1400L)
      promptText = ""
      renderControls(Runtime.state.value)
    }
  }

  private fun renderControls(snapshot: CastSnapshot) {
    val hasMedia = snapshot.currentUri.isNotBlank()
    val pinned = snapshot.transportState == TransportState.Paused || snapshot.transportState == TransportState.Transitioning || snapshot.muted
    val shouldShow = hasMedia && (pinned || promptText.isNotBlank())
    if (!shouldShow) {
      if (binding.controlBar.isVisible) {
        binding.controlBar.animate().cancel()
        binding.controlBar.animate().alpha(0f).setDuration(180L).withEndAction { if (promptText.isBlank()) binding.controlBar.visibility = View.GONE }.start()
      }
      return
    }

    binding.controlBar.animate().cancel()
    binding.controlBar.visibility = View.VISIBLE
    binding.controlBar.alpha = if (pinned) 0.92f else 0.82f
    binding.controlStateText.text = promptText.ifBlank { controlText(snapshot) }
    binding.controlTimeText.text = "${Soap.formatTime(snapshot.positionMs)} / ${if (snapshot.durationMs > 0L) Soap.formatTime(snapshot.durationMs) else "--:--"}"

    if (snapshot.transportState == TransportState.Transitioning && snapshot.durationMs <= 0L) {
      binding.controlProgress.isIndeterminate = true
    } else {
      binding.controlProgress.isIndeterminate = false
      binding.controlProgress.progress =
        if (snapshot.durationMs <= 0L) 0 else ((snapshot.positionMs.coerceAtLeast(0L) * Consts.App.PROGRESS_MAX) / snapshot.durationMs).toInt().coerceIn(0, Consts.App.PROGRESS_MAX)
      binding.controlProgress.secondaryProgress = binding.controlProgress.progress
    }
  }

  private fun controlText(snapshot: CastSnapshot): String {
    val status = getString(snapshot.transportState.text)
    return when {
      !snapshot.muted -> status
      snapshot.transportState == TransportState.Paused || snapshot.transportState == TransportState.Transitioning -> "$status  ${getString(R.string.volume_muted)}"
      else -> getString(R.string.volume_muted)
    }
  }
}

private fun playbackTime(time: Long): Long = if (time == C.TIME_UNSET || time < 0L) 0L else time

private data class CastSnapshot(
  val uuid: String = "", val ipAddress: String = "0.0.0.0", val httpPort: Int = 0, val wifiName: String = "未获取",
  val serviceRunning: Boolean = false, val multicastLocked: Boolean = false, val currentUri: String = "",
  val transportState: TransportState = TransportState.NoMedia, val lastError: String = "", val positionMs: Long = 0L,
  val durationMs: Long = 0L, val volume: Int = 100, val muted: Boolean = false,
) {
  val playerVolume: Float get() = if (muted) 0f else volume.coerceIn(0, 100) / 100f
  val isMirror: Boolean get() = currentUri == Consts.App.MIRROR_URI
}

enum class TransportState(val protocol: String, val text: Int) {
  NoMedia("NO_MEDIA_PRESENT", R.string.player_waiting), Stopped("STOPPED", R.string.player_stopped), Paused("PAUSED_PLAYBACK", R.string.player_paused), Playing(
    "PLAYING", R.string.player_playing
  ),
  Transitioning("TRANSITIONING", R.string.player_buffering),
}

private fun transportInfo(state: CastSnapshot): Map<String, String> = mapOf(
  "CurrentTransportState" to state.transportState.protocol,
  "CurrentTransportStatus" to "OK",
  "CurrentSpeed" to "1",
)

private fun positionInfo(state: CastSnapshot): Map<String, String> = mapOf(
  "Track" to if (state.currentUri.isBlank()) "0" else "1",
  "TrackDuration" to Soap.formatTime(state.durationMs),
  "TrackMetaData" to "",
  "TrackURI" to state.currentUri,
  "RelTime" to Soap.formatTime(state.positionMs),
  "AbsTime" to Soap.formatTime(state.positionMs),
  "RelCount" to "2147483647",
  "AbsCount" to "2147483647",
)

private fun mediaInfo(state: CastSnapshot): Map<String, String> = mapOf(
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

private object Runtime {
  val state = MutableStateFlow(CastSnapshot())

  fun server(
    running: Boolean,
    uuid: String = state.value.uuid,
    ipAddress: String = state.value.ipAddress,
    httpPort: Int = state.value.httpPort,
    wifiName: String = state.value.wifiName,
    multicastLocked: Boolean = state.value.multicastLocked
  ) = state.update { it.copy(serviceRunning = running, uuid = uuid, ipAddress = ipAddress, httpPort = httpPort, wifiName = wifiName, multicastLocked = multicastLocked) }

  fun error(message: String, throwable: Throwable? = null) {
    if (throwable != null) Log.e(Consts.App.TAG, message, throwable) else Log.e(Consts.App.TAG, message)
    state.update { it.copy(lastError = message) }
  }

  fun position(positionMs: Long, durationMs: Long) = state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L), durationMs = durationMs.coerceAtLeast(0L)) }
  fun transport(transportState: TransportState) = state.update { it.copy(transportState = if (it.currentUri.isBlank()) TransportState.NoMedia else transportState, lastError = "") }
  fun wifi(wifiName: String) = state.update { it.copy(wifiName = wifiName) }
  fun mirror() = state.update { it.copy(currentUri = Consts.App.MIRROR_URI, transportState = TransportState.Playing, positionMs = 0L, durationMs = 0L, lastError = "") }
  fun clear(clearError: Boolean = true) = state.update {
    it.copy(currentUri = "", transportState = TransportState.NoMedia, positionMs = 0L, durationMs = 0L, lastError = if (clearError) "" else it.lastError)
  }

  fun volume(volume: Int) = state.update { it.copy(volume = volume.coerceIn(0, 100)) }
  fun muted(muted: Boolean) = state.update { it.copy(muted = muted) }
}

private data class DlnaEndpoint(val uuid: String, val ipAddress: String, val httpPort: Int)
private typealias HttpRequest = Http.Request
private typealias HttpResponse = Http.Response

private class DlnaRenderer(private val context: Context, private val send: (PlaybackCommand) -> Unit) {
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
        ssdpTargets().forEach { sendNotify(it, "ssdp:alive") }
        delay(Consts.Dlna.SSDP_ALIVE_INTERVAL_MS)
      }
    }
    return endpoint()
  }

  fun stop() {
    val rendererScope = scope ?: return
    if (::uuid.isInitialized) ssdpTargets().forEach { sendNotify(it, "ssdp:byebye") }
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
      serverScope.launch { handleHttp(socket) }
    }
  }

  private fun handleHttp(socket: Socket) {
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
          val normalizedTarget = searchTarget.trim()
          ssdpTargets().filter { normalizedTarget.equals("ssdp:all", ignoreCase = true) || it.equals(normalizedTarget, ignoreCase = true) }.forEach { target ->
            sendUdp(
              Http.message(
                "HTTP/1.1 200 OK", "CACHE-CONTROL: max-age=1800", "DATE: ${httpDate()}", "EXT:", "LOCATION: ${locationUrl()}", "SERVER: ${Consts.Dlna.SERVER_HEADER}", "ST: $target", "USN: ${usnFor(target)}"
              ), packet.address, packet.port, socket
            )
          }
        }
      }.onFailure {
        if (serverScope.isActive) Runtime.error("SSDP receive failed", it)
      }
    }
  }

  private fun sendNotify(target: String, nts: String) {
    val alive = nts == "ssdp:alive"
    val headers = mutableListOf("NOTIFY * HTTP/1.1", "HOST: ${Consts.Dlna.SSDP_ADDRESS}:${Consts.Dlna.SSDP_PORT}")
    if (alive) headers += listOf("CACHE-CONTROL: max-age=1800", "LOCATION: ${locationUrl()}", "SERVER: ${Consts.Dlna.SERVER_HEADER}")
    headers += listOf("NT: $target", "NTS: $nts", "USN: ${usnFor(target)}")
    sendUdp(Http.message(*headers.toTypedArray()), InetAddress.getByName(Consts.Dlna.SSDP_ADDRESS), Consts.Dlna.SSDP_PORT, ssdpSocket)
  }

  private fun sendUdp(message: String, address: InetAddress, port: Int, socket: MulticastSocket?) {
    val bytes = message.toByteArray(Charsets.UTF_8)
    val packet = DatagramPacket(bytes, bytes.size, address, port)
    runCatching { socket?.send(packet) ?: MulticastSocket().use { it.send(packet) } }.onFailure { Log.w(Consts.App.TAG, "SSDP send failed: ${it.message}") }
  }

  private fun handleAvTransport(request: HttpRequest): HttpResponse {
    val action = runCatching { enumValueOf<AvTransportAction>(Soap.action(request.headers, request.body)) }.getOrNull() ?: return soapFault("AVTransport", 401, "Invalid Action")
    return when (action) {
      AvTransportAction.SetAVTransportURI -> setTransportUri(request, action)
      AvTransportAction.SetNextAVTransportURI, AvTransportAction.SetPlayMode, AvTransportAction.Next, AvTransportAction.Previous -> avOk(action)
      AvTransportAction.Play -> send(PlaybackCommand.Play).let { avOk(action) }
      AvTransportAction.Pause -> send(PlaybackCommand.Pause).let { avOk(action) }
      AvTransportAction.Stop -> send(PlaybackCommand.Stop).let { avOk(action) }
      AvTransportAction.Seek -> send(PlaybackCommand.Seek(Soap.parseTime(Soap.argument(request.body, "Target")))).let { avOk(action) }
      AvTransportAction.GetTransportInfo -> avOk(action, transportInfo(Runtime.state.value))
      AvTransportAction.GetPositionInfo -> avOk(action, positionInfo(Runtime.state.value))
      AvTransportAction.GetMediaInfo -> avOk(action, mediaInfo(Runtime.state.value))
      AvTransportAction.GetTransportSettings -> avOk(action, mapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"))
      AvTransportAction.GetDeviceCapabilities -> avOk(action, mapOf("PlayMedia" to "NETWORK", "RecMedia" to "", "RecQualityModes" to ""))
      AvTransportAction.GetCurrentTransportActions -> avOk(action, mapOf("Actions" to "Play,Stop,Pause,Seek"))
    }
  }

  private enum class AvTransportAction {
    SetAVTransportURI, SetNextAVTransportURI, SetPlayMode, Next, Previous, Play, Pause, Stop, Seek, GetTransportInfo, GetPositionInfo, GetMediaInfo, GetTransportSettings, GetDeviceCapabilities, GetCurrentTransportActions,
  }

  private fun setTransportUri(request: HttpRequest, action: AvTransportAction): HttpResponse {
    val uri = Soap.argument(request.body, "CurrentURI")
    if (uri.isBlank()) return soapFault("AVTransport", 714, "Illegal MIME-type")
    Log.i(Consts.App.TAG, "SetAVTransportURI host=${runCatching { Uri.parse(uri).host.orEmpty() }.getOrDefault("")} uri=$uri")
    return send(PlaybackCommand.Load(uri, play = false)).let { avOk(action) }
  }

  private fun avOk(action: AvTransportAction, arguments: Map<String, String> = emptyMap()) = soapOk("AVTransport", action.name, arguments)

  private fun handleRenderingControl(request: HttpRequest) = when (val action = Soap.action(request.headers, request.body)) {
    "GetVolume" -> soapOk("RenderingControl", action, mapOf("CurrentVolume" to Runtime.state.value.volume.toString()))
    "SetVolume" -> Runtime.volume(Soap.argument(request.body, "DesiredVolume").toIntOrNull() ?: Runtime.state.value.volume).let { soapOk("RenderingControl", action) }
    "GetMute" -> soapOk("RenderingControl", action, mapOf("CurrentMute" to if (Runtime.state.value.muted) "1" else "0"))
    "SetMute" -> Runtime.muted(Soap.argument(request.body, "DesiredMute").let { it == "1" || it.equals("true", ignoreCase = true) }).let { soapOk("RenderingControl", action) }
    else -> soapFault("RenderingControl", 401, "Invalid Action")
  }

  private fun handleConnectionManager(request: HttpRequest) = when (val action = Soap.action(request.headers, request.body)) {
    "GetProtocolInfo" -> soapOk("ConnectionManager", action, mapOf("Source" to "", "Sink" to Consts.Dlna.SINK_PROTOCOL_INFO))
    "GetCurrentConnectionIDs" -> soapOk("ConnectionManager", action, mapOf("ConnectionIDs" to "0"))
    "GetCurrentConnectionInfo" -> soapOk(
      "ConnectionManager",
      action,
      mapOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to Consts.Dlna.SINK_PROTOCOL_INFO, "PeerConnectionManager" to "", "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK")
    )

    else -> soapFault("ConnectionManager", 401, "Invalid Action")
  }

  private fun soapOk(service: String, action: String, arguments: Map<String, String> = emptyMap()) =
    HttpResponse.ok(Soap.ok(service, action, arguments))

  private fun soapFault(service: String, code: Int, description: String) = HttpResponse(
    statusCode = 500,
    reason = "Internal Server Error",
    body = Soap.faultBody(code, description)
  ).also { Log.w(Consts.App.TAG, "SOAP fault service=$service code=$code description=$description") }

  private fun deviceDescription() =
    """<?xml version="1.0" encoding="utf-8"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>http://$ipAddress:$httpPort</URLBase><device><deviceType>${Consts.Dlna.MEDIA_RENDERER}</deviceType><presentationURL>/</presentationURL><friendlyName>${
      context.getString(R.string.application_name).escapeXml()
    }</friendlyName><manufacturer>Microsoft Corporation</manufacturer><manufacturerURL>http://www.microsoft.com</manufacturerURL><modelDescription>Media Renderer</modelDescription><modelName>Windows Media Player</modelName><modelURL>http://go.microsoft.com/fwlink/Linkld=105927</modelURL><dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC><UDN>uuid:$uuid</UDN><serviceList>${
      serviceXml(
        Consts.Dlna.AV_TRANSPORT, "AVTransport"
      )
    }${serviceXml(Consts.Dlna.CONNECTION_MANAGER, "ConnectionManager")}${serviceXml(Consts.Dlna.RENDERING_CONTROL, "RenderingControl")}</serviceList></device></root>"""

  private fun serviceXml(type: String, name: String) =
    """<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$name</serviceId><SCPDURL>_urn:schemas-upnp-org:service:${name}_scpd.xml</SCPDURL><controlURL>_urn:schemas-upnp-org:service:${name}_control</controlURL><eventSubURL>_urn:schemas-upnp-org:service:${name}_event</eventSubURL></service>"""

  private fun asset(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

  private fun ssdpTargets(): List<String> = listOf("upnp:rootdevice", "uuid:$uuid", Consts.Dlna.MEDIA_RENDERER, Consts.Dlna.AV_TRANSPORT, Consts.Dlna.CONNECTION_MANAGER, Consts.Dlna.RENDERING_CONTROL)
  private fun usnFor(target: String) = when (target) {
    "uuid:$uuid" -> "uuid:$uuid"
    "upnp:rootdevice" -> "uuid:$uuid::upnp:rootdevice"
    else -> "uuid:$uuid::$target"
  }

  private fun locationUrl(): String = "http://$ipAddress:$httpPort/description.xml"
}
