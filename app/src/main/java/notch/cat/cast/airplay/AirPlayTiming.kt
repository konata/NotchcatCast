package notch.cat.cast.airplay

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

internal class AirPlayTiming(private val scope: CoroutineScope) {
  private var socket: DatagramSocket? = null
  private var job: Job? = null

  fun start(remote: InetAddress, remotePort: Int): Int {
    if (remotePort <= 0) return 0
    stop()
    val active = DatagramSocket(0).apply { soTimeout = 300 }
    socket = active
    job = scope.launch(Dispatchers.IO) { loop(active, InetSocketAddress(remote, remotePort)) }
    Log.i(TAG, "AirPlay timing local=${active.localPort} remote=${remote.hostAddress}:$remotePort")
    return active.localPort
  }

  fun stop() {
    job?.cancel()
    job = null
    socket?.close()
    socket = null
  }

  private suspend fun loop(active: DatagramSocket, remote: InetSocketAddress) {
    var clientReferenceTime = 0L
    var receiveTimeNs = 0L
    while (scope.isActive && !active.isClosed) {
      runCatching {
        val request = AirPlayNtp.request(
          transmitTimeNs = AirPlayNtp.nowNs(),
          clientReferenceTime = clientReferenceTime,
          receiveTimeNs = receiveTimeNs
        )
        active.send(DatagramPacket(request, request.size, remote))
        val response = ByteArray(128)
        val packet = DatagramPacket(response, response.size)
        try {
          active.receive(packet)
          receiveTimeNs = AirPlayNtp.nowNs()
          clientReferenceTime = AirPlayNtp.longBe(response, 24)
        } catch (_: SocketTimeoutException) {
        }
      }.onFailure {
        if (scope.isActive && !active.isClosed) Log.w(TAG, "AirPlay timing tick failed", it)
      }
      delay(3_000)
    }
  }

  private companion object {
    const val TAG = "mang"
  }
}

internal object AirPlayNtp {
  fun request(transmitTimeNs: Long, clientReferenceTime: Long = 0, receiveTimeNs: Long = 0): ByteArray {
    val bytes = ByteArray(32)
    bytes[0] = 0x80.toByte()
    bytes[1] = 0xd2.toByte()
    bytes[3] = 0x07
    if (clientReferenceTime != 0L) bytes.putLongBe(8, clientReferenceTime)
    if (receiveTimeNs != 0L) bytes.putTimestamp(16, receiveTimeNs)
    bytes.putTimestamp(24, transmitTimeNs)
    return bytes
  }

  fun nowNs(): Long = System.currentTimeMillis() * 1_000_000L

  fun longBe(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    repeat(8) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
    return value
  }

  private fun ByteArray.putTimestamp(offset: Int, nsSince1970: Long) {
    val seconds = nsSince1970 / NANOS_PER_SECOND + SECONDS_FROM_1900_TO_1970
    val fraction = ((nsSince1970 % NANOS_PER_SECOND) shl 32) / NANOS_PER_SECOND
    putIntBe(offset, seconds)
    putIntBe(offset + 4, fraction)
  }

  private fun ByteArray.putIntBe(offset: Int, value: Long) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
  }

  private fun ByteArray.putLongBe(offset: Int, value: Long) {
    repeat(8) { this[offset + it] = (value ushr (56 - it * 8)).toByte() }
  }

  private const val NANOS_PER_SECOND = 1_000_000_000L
  private const val SECONDS_FROM_1900_TO_1970 = 2_208_988_800L
}
