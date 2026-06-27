package notch.cat.cast.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class AirPlayVideoControl { NONE, SUSPEND, RESUME }

enum class AirPlayVideoPayload { FRAME, CODEC_CONFIG, EMPTY_CONFIG, KEEP_ALIVE, REPORT, UNKNOWN }

data class AirPlayVideoFormat(val sourceWidth: Int, val sourceHeight: Int, val width: Int, val height: Int)

sealed interface AirPlayCodecConfig {
  val mimeType: String
  val width: Int
  val height: Int
  val csd: List<ByteArray>

  companion object {
    fun parse(payload: ByteArray, format: AirPlayVideoFormat? = null): AirPlayCodecConfig =
      if (AirPlayH265.isConfig(payload)) AirPlayH265.parseConfig(payload, format) else AirPlayH264.parseConfig(payload, format)
  }
}

data class AirPlayAvcConfig(
  val sps: ByteArray,
  val pps: ByteArray,
  override val width: Int = 1920,
  override val height: Int = 1080
) : AirPlayCodecConfig {
  override val mimeType = "video/avc"
  override val csd get() = listOf(START_CODE + sps, START_CODE + pps)
  val annexB: ByteArray get() = START_CODE + sps + START_CODE + pps
}

data class AirPlayHevcConfig(
  val vps: ByteArray,
  val sps: ByteArray,
  val pps: ByteArray,
  override val width: Int = 1920,
  override val height: Int = 1080
) : AirPlayCodecConfig {
  override val mimeType = "video/hevc"
  override val csd get() = listOf(START_CODE + vps + START_CODE + sps + START_CODE + pps)
}

data class AirPlayVideoPacket(
  val type: Int,
  val option: Int,
  val timestamp: Long,
  val payload: ByteArray,
  val format: AirPlayVideoFormat? = null
) {
  val control: AirPlayVideoControl
    get() = when (option) {
      0x0156, 0x015e -> AirPlayVideoControl.SUSPEND
      0x0116, 0x011e -> AirPlayVideoControl.RESUME
      else -> AirPlayVideoControl.NONE
    }

  val payloadType: AirPlayVideoPayload
    get() = when (type) {
      0 -> AirPlayVideoPayload.FRAME
      1 -> if (payload.isEmpty()) AirPlayVideoPayload.EMPTY_CONFIG else AirPlayVideoPayload.CODEC_CONFIG
      2 -> AirPlayVideoPayload.KEEP_ALIVE
      5 -> AirPlayVideoPayload.REPORT
      else -> AirPlayVideoPayload.UNKNOWN
    }

  companion object {
    private const val HEADER_BYTES = 128

    fun parse(bytes: ByteArray): AirPlayVideoPacket {
      require(bytes.size >= HEADER_BYTES) { "AirPlay video packet is shorter than $HEADER_BYTES bytes" }
      val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
      val payloadSize = header.int
      require(payloadSize >= 0 && bytes.size >= HEADER_BYTES + payloadSize) { "Invalid AirPlay video payload length: $payloadSize" }
      val type = bytes[4].toInt() and 0xff
      val option = (bytes[6].toInt() and 0xff) or ((bytes[7].toInt() and 0xff) shl 8)
      header.position(8)
      val timestamp = header.long
      return AirPlayVideoPacket(type, option, timestamp, bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + payloadSize), parseFormat(type, bytes))
    }

    private fun parseFormat(type: Int, bytes: ByteArray): AirPlayVideoFormat? {
      if (type != 1) return null
      fun dimension(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float.toInt().takeIf { it > 0 }
      val sourceWidth = dimension(40) ?: dimension(16) ?: return null
      val sourceHeight = dimension(44) ?: dimension(20) ?: return null
      val width = dimension(56) ?: sourceWidth
      val height = dimension(60) ?: sourceHeight
      return AirPlayVideoFormat(sourceWidth, sourceHeight, width, height)
    }
  }
}

private val START_CODE = byteArrayOf(0, 0, 0, 1)

object AirPlayH264 {
  fun configToAnnexB(payload: ByteArray): ByteArray {
    return parseConfig(payload).annexB
  }

  fun parseConfig(payload: ByteArray, format: AirPlayVideoFormat? = null): AirPlayAvcConfig {
    require(payload.size >= 8) { "AVC config is too short" }
    var offset = 6
    val spsLength = u16(payload, offset)
    offset += 2
    require(payload.size >= offset + spsLength + 3) { "AVC config has truncated SPS" }
    val sps = payload.copyOfRange(offset, offset + spsLength)
    offset += spsLength + 1
    val ppsLength = u16(payload, offset)
    offset += 2
    require(payload.size >= offset + ppsLength) { "AVC config has truncated PPS" }
    val pps = payload.copyOfRange(offset, offset + ppsLength)
    return AirPlayAvcConfig(sps, pps, width = format?.width ?: 1920, height = format?.height ?: 1080)
  }

  fun samplesToAnnexB(payload: ByteArray) {
    AirPlayNalUnits.samplesToAnnexB(payload)
  }

  private fun u16(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
}

object AirPlayH265 {
  fun isConfig(payload: ByteArray) = payload.size >= 0x79 && payload[4] == 'h'.code.toByte() && payload[5] == 'v'.code.toByte() && payload[6] == 'c'.code.toByte() && payload[7] == '1'.code.toByte()

  fun parseConfig(payload: ByteArray, format: AirPlayVideoFormat? = null): AirPlayHevcConfig {
    require(isConfig(payload)) { "HEVC config is missing hvc1 marker" }
    var offset = 0x75
    val vps = payload.hevcArray(offset, 0xa0.toByte()).also { offset = it.nextOffset }.bytes
    val sps = payload.hevcArray(offset, 0xa1.toByte()).also { offset = it.nextOffset }.bytes
    val pps = payload.hevcArray(offset, 0xa2.toByte()).bytes
    return AirPlayHevcConfig(vps, sps, pps, width = format?.width ?: 1920, height = format?.height ?: 1080)
  }

  private data class HevcArray(val bytes: ByteArray, val nextOffset: Int)

  private fun ByteArray.hevcArray(offset: Int, type: Byte): HevcArray {
    require(size >= offset + 5) { "HEVC config array is truncated" }
    require(this[offset] == type && this[offset + 1] == 0.toByte() && this[offset + 2] == 1.toByte()) { "HEVC config has unexpected array type" }
    val length = ((this[offset + 3].toInt() and 0xff) shl 8) or (this[offset + 4].toInt() and 0xff)
    val start = offset + 5
    require(length > 0 && size >= start + length) { "HEVC config NAL is truncated" }
    return HevcArray(copyOfRange(start, start + length), start + length)
  }
}

object AirPlayNalUnits {
  fun samplesToAnnexB(payload: ByteArray) {
    var offset = 0
    while (offset + 4 <= payload.size) {
      val naluSize = u32(payload, offset)
      if (naluSize == 1) return
      require(naluSize > 0 && offset + 4 + naluSize <= payload.size) { "Corrupt H.264 sample length: $naluSize" }
      payload[offset] = 0
      payload[offset + 1] = 0
      payload[offset + 2] = 0
      payload[offset + 3] = 1
      offset += 4 + naluSize
    }
  }

  private fun u32(bytes: ByteArray, offset: Int) =
    ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
}
