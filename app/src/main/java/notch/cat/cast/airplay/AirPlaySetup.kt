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

  data class Streams(val streams: List<AirPlayStream>) : AirPlaySetup
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
      return dict.streams()?.let(::Streams) ?: Empty
    }

    fun responseSession(eventPort: Int, timingPort: Int) = plist {
      put("eventPort", eventPort)
      put("timingPort", timingPort)
    }

    fun responseStreams(streams: List<AirPlayStreamPort>) = plist {
      put("streams", NSArray(*streams.map { stream ->
        NSDictionary().apply {
          put("type", stream.type)
          put("dataPort", stream.dataPort)
          stream.controlPort?.let { put("controlPort", it) }
        }
      }.toTypedArray()))
    }

    private fun plist(block: NSDictionary.() -> Unit): ByteArray =
      BinaryPropertyListWriter.writeToArray(NSDictionary().apply(block))
  }
}

internal data class AirPlayStream(val type: Int, val connectionId: String?)

internal data class AirPlayStreamPort(val type: Int, val dataPort: Int, val controlPort: Int? = null)

private fun NSDictionary.bytes(key: String): ByteArray? = objectForKey(key)?.toJavaObject() as? ByteArray

private fun NSDictionary.string(key: String): String? = objectForKey(key)?.toJavaObject()?.toString()

private fun NSDictionary.int(key: String): Int? = (objectForKey(key)?.toJavaObject() as? Number)?.toInt()

private fun NSDictionary.streams(): List<AirPlayStream>? {
  val streams = objectForKey("streams")?.toJavaObject() as? Array<*> ?: return null
  return streams.mapNotNull { item ->
    val stream = item as? Map<*, *> ?: return@mapNotNull null
    val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
    val connectionId = when (val value = stream["streamConnectionID"]) {
      is Number -> java.lang.Long.toUnsignedString(value.toLong())
      is String -> value
      else -> null
    }
    AirPlayStream(type, connectionId)
  }.takeIf { it.isNotEmpty() }
}
