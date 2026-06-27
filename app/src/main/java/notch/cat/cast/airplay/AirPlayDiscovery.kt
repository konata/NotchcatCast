package notch.cat.cast.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import notch.cat.cast.R
import java.util.Locale

internal object AirPlayProfile {
  const val PORT = 7000
  const val FEATURES_HEX = "0x5A7FFEE6,0x0"
  const val FEATURES = 1518337766L
  const val FLAGS = 4
  const val FLAGS_HEX = "0x4"
  const val SOURCE_VERSION = "220.68"
  const val MODEL = "AppleTV3,2"
}

internal class AirPlayDiscovery(
  private val context: Context,
  private val uuid: String,
  private val publicKeyHex: String
) {
  private var airplayRegistration: NsdManager.RegistrationListener? = null
  private var raopRegistration: NsdManager.RegistrationListener? = null

  fun register(port: Int) {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return
    val airplay = listener("AirPlay")
    val raop = listener("RAOP")
    airplayRegistration = airplay
    raopRegistration = raop
    val id = deviceId(uuid)
    val name = context.getString(R.string.application_name)
    runCatching {
      nsd.registerService(NsdServiceInfo().apply {
        serviceName = name
        serviceType = "_airplay._tcp."
        this.port = port
        AirPlayTxt.airplay(id, uuid, publicKeyHex).forEach { (key, value) -> setAttribute(key, value) }
      }, NsdManager.PROTOCOL_DNS_SD, airplay)
      nsd.registerService(NsdServiceInfo().apply {
        serviceName = "${id.replace(":", "")}@$name"
        serviceType = "_raop._tcp."
        this.port = port
        AirPlayTxt.raop(publicKeyHex).forEach { (key, value) -> setAttribute(key, value) }
      }, NsdManager.PROTOCOL_DNS_SD, raop)
    }.onFailure { Log.e(TAG, "AirPlay registration failed", it) }
  }

  fun unregister() {
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

  companion object {
    fun deviceId(uuid: String) = uuid.replace("-", "").take(12).chunked(2).joinToString(":") { it.uppercase(Locale.US) }
  }
}

private const val TAG = "mang"
