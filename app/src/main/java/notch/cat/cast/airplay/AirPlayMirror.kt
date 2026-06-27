package notch.cat.cast.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

internal data class AirPlayInputWrite(val queued: Boolean, val size: Int)

internal object AirPlayCodecInput {
  fun writeFrame(buffer: ByteBuffer, frame: ByteArray): AirPlayInputWrite {
    buffer.clear()
    if (frame.size > buffer.capacity()) return AirPlayInputWrite(queued = false, size = 0)
    buffer.put(frame)
    return AirPlayInputWrite(queued = true, size = frame.size)
  }
}

interface AirPlayMirrorSink {
  fun onMirrorConfig(config: AirPlayCodecConfig)
  fun onMirrorFrame(frame: ByteArray)
  fun onMirrorStopped()
}

object AirPlayMirrorBus {
  private val lock = Any()
  private var sink: AirPlayMirrorSink? = null
  private var active = false
  private var config: AirPlayCodecConfig? = null

  fun attach(target: AirPlayMirrorSink) = synchronized(lock) {
    sink = target
    if (active) config?.let(target::onMirrorConfig)
  }

  fun detach(target: AirPlayMirrorSink) = synchronized(lock) {
    if (sink === target) sink = null
  }

  fun start() = synchronized(lock) {
    active = true
    config = null
  }

  fun config(value: AirPlayCodecConfig) {
    val target = synchronized(lock) {
      active = true
      config = value
      sink
    }
    target?.onMirrorConfig(value)
  }

  fun frame(value: ByteArray) {
    val target = synchronized(lock) { if (active) sink else null }
    target?.onMirrorFrame(value)
  }

  fun stop() {
    val target = synchronized(lock) {
      active = false
      config = null
      sink
    }
    target?.onMirrorStopped()
  }
}

class AirPlayMirrorDecoder(private val surface: Surface) : AirPlayMirrorSink {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val events = Channel<Event>(capacity = 32)
  private var codec: MediaCodec? = null
  private var ptsUs = 0L

  init {
    scope.launch {
      for (event in events) {
        when (event) {
          is Event.Config -> configure(event.config)
          is Event.Frame -> queue(event.frame)
          Event.Stop -> releaseCodec()
        }
      }
    }
  }

  override fun onMirrorConfig(config: AirPlayCodecConfig) {
    events.trySend(Event.Config(config))
  }

  override fun onMirrorFrame(frame: ByteArray) {
    events.trySend(Event.Frame(frame.copyOf()))
  }

  override fun onMirrorStopped() {
    events.trySend(Event.Stop)
  }

  fun release() {
    events.close()
    releaseCodec()
    scope.cancel()
  }

  private fun configure(config: AirPlayCodecConfig) {
    releaseCodec()
    ptsUs = 0L
    codec = MediaCodec.createDecoderByType(config.mimeType).apply {
      val format = MediaFormat.createVideoFormat(config.mimeType, config.width, config.height)
      config.csd.forEachIndexed { index, bytes -> format.setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
      configure(format, surface, null, 0)
      start()
    }
  }

  private fun queue(frame: ByteArray) {
    val active = codec ?: return
    val inputIndex = active.dequeueInputBuffer(10_000)
    if (inputIndex >= 0) {
      val write = active.getInputBuffer(inputIndex)?.let { AirPlayCodecInput.writeFrame(it, frame) } ?: AirPlayInputWrite(queued = false, size = 0)
      if (!write.queued) {
        Log.w(TAG, "AirPlay frame too large for decoder input: frame=${frame.size}")
        active.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
        return
      }
      active.queueInputBuffer(inputIndex, 0, write.size, ptsUs, 0)
      ptsUs += 33_333L
    }

    val info = MediaCodec.BufferInfo()
    var outputIndex = active.dequeueOutputBuffer(info, 0)
    while (outputIndex >= 0) {
      active.releaseOutputBuffer(outputIndex, true)
      outputIndex = active.dequeueOutputBuffer(info, 0)
    }
  }

  private fun releaseCodec() {
    val active = codec ?: return
    codec = null
    runCatching { active.stop() }
    runCatching { active.release() }
  }

  private sealed interface Event {
    data class Config(val config: AirPlayCodecConfig) : Event
    data class Frame(val frame: ByteArray) : Event
    data object Stop : Event
  }

  private companion object {
    const val TAG = "mang"
  }
}
