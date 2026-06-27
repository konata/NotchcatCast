package notch.cat.cast

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal fun deviceId(uuid: String) = uuid.replace("-", "").take(12).chunked(2).joinToString(":") { it.uppercase(Locale.US) }
internal fun httpDate(): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))
internal fun InputStream.bytes(size: Int) = readNBytes(size).takeIf { it.size == size }
internal fun ByteArray.intLe(offset: Int) = ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
internal fun ByteArray.shortLe(offset: Int) = java.lang.Short.toUnsignedInt(ByteBuffer.wrap(this, offset, Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).short)
internal fun ByteArray.shortBe(offset: Int) = java.lang.Short.toUnsignedInt(ByteBuffer.wrap(this, offset, Short.SIZE_BYTES).short)
internal fun ByteArray.intBe(offset: Int) = ByteBuffer.wrap(this, offset, Int.SIZE_BYTES).int
internal fun ByteArray.floatLe(offset: Int) = ByteBuffer.wrap(this, offset, Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).float
internal fun String.escapeXml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
internal fun String.unescapeXml() = replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
internal fun dnsTxt(records: Map<String, String>) = records.flatMap { (name, content) ->
  val entry = "$name=$content".toByteArray(Charsets.UTF_8)
  require(entry.size <= 255) { "TXT record entry too large: $name" }
  listOf(entry.size.toByte()) + entry.toList()
}.toByteArray()

internal data class MirrorFit(val width: Int, val height: Int)

internal fun mirrorFit(containerWidth: Int, containerHeight: Int, contentWidth: Int, contentHeight: Int): MirrorFit {
  if (containerWidth <= 0 || containerHeight <= 0) return MirrorFit(0, 0)
  if (contentWidth <= 0 || contentHeight <= 0) return MirrorFit(containerWidth, containerHeight)
  val contentRatio = contentWidth.toDouble() / contentHeight.toDouble()
  val containerRatio = containerWidth.toDouble() / containerHeight.toDouble()
  return if (contentRatio > containerRatio) MirrorFit(containerWidth, (containerWidth / contentRatio).roundToInt()) else MirrorFit((containerHeight * contentRatio).roundToInt(), containerHeight)
}
