package notch.cat.cast.airplay

internal object AirPlayControl {
  fun isNoop(method: String, path: String) = when {
    method == "SET_PARAMETER" -> true
    method == "FLUSH" -> true
    method == "POST" && path == "/feedback" -> true
    method == "POST" && path == "/audioMode" -> true
    else -> false
  }

  fun isMisdirected(method: String, path: String) = method == "POST" && path == "/fp-setup2"
}
