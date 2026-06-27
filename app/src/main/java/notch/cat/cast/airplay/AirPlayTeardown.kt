package notch.cat.cast.airplay

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.NSDictionary

internal data class AirPlayTeardown(private val streamTypes: Set<Int>) {
  val stopMirror: Boolean get() = streamTypes.isEmpty() || 110 in streamTypes
  val stopAudio: Boolean get() = streamTypes.isEmpty() || streamTypes.any { it in AUDIO_TYPES }

  companion object {
    fun parse(body: ByteArray): AirPlayTeardown {
      if (body.isEmpty()) return AirPlayTeardown(emptySet())
      return runCatching {
        val dict = BinaryPropertyListParser.parse(body) as NSDictionary
        AirPlayTeardown(dict.streamTypes())
      }.getOrElse { AirPlayTeardown(emptySet()) }
    }

    private val AUDIO_TYPES = setOf(96, 100, 101)
  }
}

private fun NSDictionary.streamTypes(): Set<Int> {
  val streams = objectForKey("streams")?.toJavaObject() as? Array<*> ?: return emptySet()
  return streams.mapNotNull { stream ->
    ((stream as? Map<*, *>)?.get("type") as? Number)?.toInt()
  }.toSet()
}
