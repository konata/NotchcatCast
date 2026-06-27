package notch.cat.cast.airplay

import com.github.serezhka.airplay.lib.internal.FairPlay
import com.github.serezhka.airplay.lib.internal.FairPlayVideoDecryptor
import com.github.serezhka.airplay.lib.internal.Pairing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal class AirPlaySession {
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
    val connectionId = streamConnectionId ?: error("AirPlay streamConnectionID missing")
    return FairPlayVideoDecryptor(fairPlay.decryptAesKey(key), pairing.sharedSecret, connectionId).also { videoDecryptor = it }
  }
}
