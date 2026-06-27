package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary

internal object AirPlayInfo {
  fun response(
    path: String = "/info",
    headers: Map<String, String>,
    body: ByteArray,
    name: String,
    uuid: String,
    deviceId: String,
    publicKey: ByteArray,
    publicKeyHex: String
  ): ByteArray {
    val bplist = headers["content-type"]?.contains(BPLIST, ignoreCase = true) == true
    val queryQualifiers = path.substringAfter("?", "").split("?").filter { it.isNotBlank() }.toSet()
    if (bplist || queryQualifiers.isNotEmpty()) {
      val qualifiers = if (bplist) qualifiers(body) else queryQualifiers
      return BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
        if ("txtAirPlay" in qualifiers) put("txtAirPlay", NSData(AirPlayTxt.bytes(AirPlayTxt.airplay(deviceId, uuid, publicKeyHex))))
        if ("txtRAOP" in qualifiers) put("txtRAOP", NSData(AirPlayTxt.bytes(AirPlayTxt.raop(publicKeyHex))))
      })
    }
    return plist(name, uuid, deviceId, publicKey)
  }

  fun plist(name: String, uuid: String, deviceId: String, publicKey: ByteArray) =
    BinaryPropertyListWriter.writeToArray(NSDictionary().apply {
      put("audioFormats", NSArray(
        NSDictionary().apply { put("type", 100); put("audioInputFormats", 67108860); put("audioOutputFormats", 67108860) },
        NSDictionary().apply { put("type", 101); put("audioInputFormats", 67108860); put("audioOutputFormats", 67108860) },
      ))
      put("audioLatencies", NSArray(
        NSDictionary().apply { put("type", 100); put("audioType", "default"); put("inputLatencyMicros", false) },
        NSDictionary().apply { put("type", 101); put("audioType", "default"); put("inputLatencyMicros", false) },
      ))
      put("displays", NSArray(NSDictionary().apply {
        put("features", 14)
        put("height", 1080)
        put("heightPixels", 1080)
        put("heightPhysical", false)
        put("width", 1920)
        put("widthPixels", 1920)
        put("widthPhysical", false)
        put("maxFPS", 60)
        put("overscanned", false)
        put("refreshRate", 60)
        put("rotation", false)
        put("uuid", uuid)
      }))
      put("deviceID", deviceId)
      put("features", AirPlayProfile.FEATURES)
      put("keepAliveLowPower", 1)
      put("keepAliveSendStatsAsBody", 1)
      put("macAddress", deviceId)
      put("model", AirPlayProfile.MODEL)
      put("name", name)
      put("pi", uuid)
      put("pk", NSData(publicKey))
      put("sourceVersion", AirPlayProfile.SOURCE_VERSION)
      put("statusFlags", 68)
      put("vv", 2)
    })

  private fun qualifiers(body: ByteArray) = runCatching {
    val dict = BinaryPropertyListParser.parse(body) as? NSDictionary ?: return@runCatching emptySet()
    val array = dict.objectForKey("qualifier") as? NSArray ?: return@runCatching emptySet()
    array.array.map { it.toJavaObject().toString() }.toSet()
  }.getOrDefault(emptySet())

  private const val BPLIST = "application/x-apple-binary-plist"
}
