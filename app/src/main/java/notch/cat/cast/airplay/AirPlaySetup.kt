package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSDictionary

internal sealed interface AirPlaySetup {
  data class Session(
    val ekey: ByteArray,
    val eiv: ByteArray?,
    val timingProtocol: String?,
    val timingPort: Int?
  ) : AirPlaySetup

  data class Stream(val stream: AirPlayStream) : AirPlaySetup
  data object Empty : AirPlaySetup

  companion object {
    fun parse(body: ByteArray): AirPlaySetup {
      val dict = BinaryPropertyListParser.parse(body) as NSDictionary
      val ekey = dict.bytes("ekey")
      if (ekey != null) {
        return Session(
          ekey = ekey,
          eiv = dict.bytes("eiv"),
          timingProtocol = dict.string("timingProtocol"),
          timingPort = dict.int("timingPort")
        )
      }
      return dict.stream()?.let(::Stream) ?: Empty
    }

    fun responseSession(eventPort: Int, timingPort: Int) = plist {
      put("eventPort", eventPort)
      put("timingPort", timingPort)
    }

    fun responseVideo(dataPort: Int) = plist {
      put("streams", NSArray(NSDictionary().apply {
        put("type", 110)
        put("dataPort", dataPort)
      }))
    }

    fun responseAudio(type: Int, dataPort: Int, controlPort: Int) = plist {
      put("streams", NSArray(NSDictionary().apply {
        put("type", type)
        put("dataPort", dataPort)
        put("controlPort", controlPort)
      }))
    }

    private fun plist(block: NSDictionary.() -> Unit): ByteArray =
      BinaryPropertyListWriter.writeToArray(NSDictionary().apply(block))
  }
}

internal data class AirPlayStream(val type: Int, val connectionId: String?)

private fun NSDictionary.bytes(key: String): ByteArray? = objectForKey(key)?.toJavaObject() as? ByteArray

private fun NSDictionary.string(key: String): String? = objectForKey(key)?.toJavaObject()?.toString()

private fun NSDictionary.int(key: String): Int? = (objectForKey(key)?.toJavaObject() as? Number)?.toInt()

private fun NSDictionary.stream(): AirPlayStream? {
  val streams = objectForKey("streams")?.toJavaObject() as? Array<*> ?: return null
  val stream = streams.firstOrNull() as? Map<*, *> ?: return null
  val type = (stream["type"] as? Number)?.toInt() ?: return null
  val connectionId = when (val value = stream["streamConnectionID"]) {
    is Number -> java.lang.Long.toUnsignedString(value.toLong())
    is String -> value
    else -> null
  }
  return AirPlayStream(type, connectionId)
}
