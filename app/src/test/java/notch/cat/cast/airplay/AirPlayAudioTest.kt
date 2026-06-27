package notch.cat.cast.airplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AirPlayAudioTest {
  @Test
  fun drainsAudioDataAndControlPackets() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val audio = AirPlayAudio(scope)
    try {
      val ports = audio.setup(96)

      sendUdp(ports.dataPort)
      sendUdp(ports.controlPort!!)

      assertTrue(waitUntil { audio.stats().dataPackets == 1L && audio.stats().controlPackets == 1L })
      assertEquals(AirPlayAudioStats(dataPackets = 1, controlPackets = 1), audio.stats())
    } finally {
      audio.stop()
      scope.cancel()
    }
  }

  private fun sendUdp(port: Int) {
    DatagramSocket().use { socket ->
      val bytes = byteArrayOf(1, 2, 3, 4)
      socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), port))
    }
  }

  private fun waitUntil(predicate: () -> Boolean): Boolean {
    repeat(50) {
      if (predicate()) return true
      Thread.sleep(20)
    }
    return false
  }
}
