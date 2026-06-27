package notch.cat.cast.airplay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

internal class AirPlayVideoServer(
  private val scope: CoroutineScope,
  private val decrypt: (ByteArray) -> Unit,
  private val event: (AirPlayVideoStreamEvent) -> Unit,
  private val stopped: () -> Unit,
) {
  private var server: ServerSocket? = null

  fun start(): Int {
    server?.let { return it.localPort }
    val socket = ServerSocket(0)
    server = socket
    Log.i(TAG, "AirPlay video listening on ${socket.localPort}")
    scope.launch { loop(socket) }
    return socket.localPort
  }

  fun stop() {
    runCatching { server?.close() }
    server = null
  }

  private fun loop(socket: ServerSocket) {
    while (scope.isActive) {
      val client = runCatching { socket.accept() }.getOrElse { return }
      scope.launch { handle(client) }
    }
  }

  private fun handle(socket: Socket) {
    runCatching {
      socket.use {
        val stream = AirPlayVideoStream(decrypt)
        val input = it.getInputStream()
        while (scope.isActive) {
          stream.handle(readPacket(input) ?: break).forEach(event)
        }
      }
    }.onFailure {
      if (scope.isActive) Log.e(TAG, "AirPlay video stream failed", it)
    }
    stopped()
  }

  private fun readPacket(input: InputStream): AirPlayVideoPacket? {
    val header = input.readFully(VIDEO_HEADER_BYTES) ?: return null
    val payloadSize = header.leInt(0)
    if (payloadSize < 0 || payloadSize > MAX_VIDEO_PAYLOAD_BYTES) {
      Log.w(TAG, "AirPlay video payload has invalid size: $payloadSize")
      return null
    }
    val packet = ByteArray(VIDEO_HEADER_BYTES + payloadSize)
    System.arraycopy(header, 0, packet, 0, header.size)
    val payload = input.readFully(payloadSize) ?: return null
    System.arraycopy(payload, 0, packet, VIDEO_HEADER_BYTES, payloadSize)
    return AirPlayVideoPacket.parse(packet)
  }

  private companion object {
    const val TAG = "mang"
    const val VIDEO_HEADER_BYTES = 128
    const val MAX_VIDEO_PAYLOAD_BYTES = 8 * 1024 * 1024
  }
}

private fun ByteArray.leInt(offset: Int) = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8) or ((this[offset + 2].toInt() and 0xff) shl 16) or ((this[offset + 3].toInt() and 0xff) shl 24)
