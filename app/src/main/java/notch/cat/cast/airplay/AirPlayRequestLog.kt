package notch.cat.cast.airplay

internal object AirPlayRequestLog {
  fun summary(request: AirPlayRtsp.Request): String {
    val path = if (request.path.startsWith("/info?")) request.path else request.path.substringBefore("?")
    val cseq = request.headers["cseq"] ?: "-"
    val userAgent = request.headers["user-agent"] ?: "-"
    val contentType = request.headers["content-type"] ?: "-"
    return "${request.method} $path cseq=$cseq ua=$userAgent ct=$contentType len=${request.body.size}"
  }
}
