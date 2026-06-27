package notch.cat.cast.airplay

import java.security.MessageDigest

internal object AirPlayKey {
  fun seed(uuid: String): ByteArray = MessageDigest.getInstance("SHA-512")
    .digest("NotchCatCastAirPlay:$uuid".toByteArray(Charsets.UTF_8))
    .copyOf(32)
}
