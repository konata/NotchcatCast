package notch.cat.cast.airplay

internal object AirPlayRequestLog {
  fun summary(request: AirPlayRtsp.Request): String {
    val path = request.path.substringBefore("?")
    val cseq = request.headers["cseq"] ?: "-"
    val userAgent = request.headers["user-agent"] ?: "-"
    return "${request.method} $path cseq=$cseq ua=$userAgent len=${request.body.size}"
  }
}
