package notch.cat.cast

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.provider.Settings
import android.util.Size
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

@Suppress("DEPRECATION")
private fun Context.hasRuntimePermission(permission: String): Boolean {
  if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false
  val op = AppOpsManager.permissionToOp(permission) ?: return true
  val mode = getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(op, applicationInfo.uid, packageName) ?: AppOpsManager.MODE_ALLOWED
  return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_FOREGROUND
}

internal fun Context.hasWifiNamePermission() = hasRuntimePermission(Manifest.permission.NEARBY_WIFI_DEVICES)
internal fun Context.hasNotificationPermission() = hasRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
internal fun Context.hasOverlayPermission() = Settings.canDrawOverlays(this)
internal fun Context.hasSetupPermissions() = hasWifiNamePermission() && hasNotificationPermission() && hasOverlayPermission()
internal fun Context.cancelOpenPlayerNotification() = getSystemService(NotificationManager::class.java)?.cancel(Consts.App.OPEN_PLAYER_NOTIFICATION_ID)

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
internal fun Context.wifiName(): String {
  val connectivityManager = getSystemService(ConnectivityManager::class.java)
  val capabilities = connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
  if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) return "Ethernet"
  val raw = (capabilities?.transportInfo as? WifiInfo)?.ssid ?: getSystemService(WifiManager::class.java)?.connectionInfo?.ssid ?: ""
  return raw.trim().removeSurrounding("\"").replace('\n', ' ').replace('\r', ' ').takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) } ?: getString(R.string.status_wifi_unacquired)
}

internal fun mirrorSourceSize(type: Int, header: ByteArray): Size? {
  if (type != 1) return null
  fun dimension(offset: Int) = header.floatLe(offset).toInt().takeIf { it > 0 }
  val sourceWidth = dimension(40) ?: dimension(16) ?: return null
  val sourceHeight = dimension(44) ?: dimension(20) ?: return null
  return Size(dimension(56) ?: sourceWidth, dimension(60) ?: sourceHeight)
}

private data class ReceiverBinding(val binder: ReceiverService.ReceiverBinder, val connection: ServiceConnection)

internal suspend fun Context.withReceiver(block: suspend ReceiverService.ReceiverBinder.() -> Unit) {
  val binding = bindReceiver()
  try {
    binding.binder.block()
  } finally {
    runCatching { unbindService(binding.connection) }
  }
}

private suspend fun Context.bindReceiver(): ReceiverBinding = suspendCancellableCoroutine { continuation ->
  val context = applicationContext
  val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
      val binder = service as? ReceiverService.ReceiverBinder
      if (binder == null) {
        continuation.cancel(CancellationException("Unexpected ReceiverService binder: ${service::class.java.name}"))
        return
      }
      if (continuation.isActive) continuation.resume(ReceiverBinding(binder, this)) else runCatching { context.unbindService(this) }
    }

    override fun onServiceDisconnected(name: ComponentName) {
      if (continuation.isActive) continuation.cancel(CancellationException("ReceiverService disconnected before binding completed"))
    }
  }
  val bound = context.bindService(Intent(context, ReceiverService::class.java), connection, Context.BIND_AUTO_CREATE)
  if (!bound) continuation.cancel(CancellationException("bindService returned false"))
  continuation.invokeOnCancellation { if (bound) runCatching { context.unbindService(connection) } }
}
