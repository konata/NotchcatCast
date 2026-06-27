package notch.cat.cast.airplay

internal object AirPlayTxt {
  fun airplay(deviceId: String, pi: String, pk: String) = linkedMapOf(
    "deviceid" to deviceId,
    "features" to AirPlayProfile.FEATURES_HEX,
    "srcvers" to AirPlayProfile.SOURCE_VERSION,
    "flags" to AirPlayProfile.FLAGS_HEX,
    "vv" to "2",
    "model" to AirPlayProfile.MODEL,
    "pi" to pi,
    "pw" to "false",
    "pk" to pk,
  )

  fun raop(pk: String) = linkedMapOf(
    "ch" to "2",
    "cn" to "0,1,2,3",
    "da" to "true",
    "et" to "0,3,5",
    "vv" to "2",
    "ft" to AirPlayProfile.FEATURES_HEX,
    "am" to AirPlayProfile.MODEL,
    "md" to "0,1,2",
    "rhd" to "5.6.0.0",
    "pw" to "false",
    "sf" to AirPlayProfile.FLAGS_HEX,
    "sr" to "44100",
    "ss" to "16",
    "sv" to "false",
    "tp" to "UDP",
    "txtvers" to "1",
    "vs" to AirPlayProfile.SOURCE_VERSION,
    "vn" to "65537",
    "pk" to pk,
  )

  fun bytes(values: Map<String, String>) = values.flatMap { (key, value) ->
    val entry = "$key=$value".toByteArray(Charsets.UTF_8)
    require(entry.size <= 255) { "TXT record entry too large: $key" }
    listOf(entry.size.toByte()) + entry.toList()
  }.toByteArray()
}
