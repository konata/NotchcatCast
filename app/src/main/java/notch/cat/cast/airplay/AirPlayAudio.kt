package notch.cat.cast.airplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

internal data class AirPlayAudioStats(val dataPackets: Long, val controlPackets: Long)

internal class AirPlayAudio(private val scope: CoroutineScope) {
  private var data: DatagramSocket? = null
  private var control: DatagramSocket? = null
  private val dataPackets = AtomicLong()
  private val controlPackets = AtomicLong()

  fun setup(type: Int): AirPlayStreamPort {
    val dataSocket = data ?: DatagramSocket(0).also {
      data = it
      drain(it, dataPackets)
    }
    val controlSocket = control ?: DatagramSocket(0).also {
      control = it
      drain(it, controlPackets)
    }
    return AirPlayStreamPort(type, dataSocket.localPort, controlSocket.localPort)
  }

  fun stop() {
    runCatching { data?.close() }
    runCatching { control?.close() }
    data = null
    control = null
  }

  fun stats() = AirPlayAudioStats(dataPackets.get(), controlPackets.get())

  private fun drain(socket: DatagramSocket, counter: AtomicLong) {
    scope.launch {
      val packet = DatagramPacket(ByteArray(MAX_PACKET_BYTES), MAX_PACKET_BYTES)
      while (isActive && !socket.isClosed) {
        try {
          packet.length = packet.data.size
          socket.receive(packet)
          counter.incrementAndGet()
        } catch (_: SocketException) {
          break
        }
      }
    }
  }

  private companion object {
    const val MAX_PACKET_BYTES = 2048
  }
}
