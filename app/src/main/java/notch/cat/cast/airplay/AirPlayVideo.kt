package notch.cat.cast.airplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AirPlayVideoPacket(val type: Int, val option: Int, val timestamp: Long, val payload: ByteArray) {
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
      return AirPlayVideoPacket(type, option, timestamp, bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + payloadSize))
    }
  }
}

object AirPlayH264 {
  private val startCode = byteArrayOf(0, 0, 0, 1)

  fun configToAnnexB(payload: ByteArray): ByteArray {
    return parseConfig(payload).annexB
  }

  fun parseConfig(payload: ByteArray): AirPlayAvcConfig {
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
    return AirPlayAvcConfig(sps, pps)
  }

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

  private fun u16(bytes: ByteArray, offset: Int) = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
  private fun u32(bytes: ByteArray, offset: Int) =
    ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
}
