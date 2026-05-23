package org.lolicode.moemusic.standalone

import org.lolicode.moemusic.clientcore.audio.PcmRingBuffer
import org.lolicode.moemusic.clientcore.audio.StreamingAudioOutput
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt

class JavaSoundAudioOutput(
    private val ringBuffer: PcmRingBuffer,
    private val onPlaybackPositionChanged: (Long) -> Unit = {},
) : StreamingAudioOutput {

    private val logger = LoggerFactory.getLogger(JavaSoundAudioOutput::class.java)
    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var streamThread: Thread? = null

    @Volatile
    private var line: SourceDataLine? = null

    @Volatile
    private var desiredGain: Float = 1.0f

    @Volatile
    private var requestedStartPositionMs: Long = 0L

    override fun start(startPositionMs: Long) {
        if (streamThread?.isAlive == true) return
        requestedStartPositionMs = startPositionMs.coerceAtLeast(0L)
        stopRequested.set(false)
        streamThread = Thread({ streamLoop() }, "MoeMusic-JavaSound").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        stopRequested.set(true)
        line?.runCatching {
            stop()
            flush()
            close()
        }
        streamThread?.interrupt()
        streamThread?.join(2_000)
        streamThread = null
        line = null
    }

    override fun seek() {
        ringBuffer.reset()
        line?.flush()
    }

    override fun setGain(gain: Float) {
        desiredGain = gain.coerceIn(0.0f, 1.0f)
    }

    private fun streamLoop() {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), SAMPLE_BITS, CHANNELS, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val sourceLine = runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).also {
                it.open(format, LINE_BUFFER_BYTES)
                it.start()
            }
        }.getOrElse { error ->
            logger.error("Failed to open Java Sound output: {}", error.message, error)
            return
        }
        line = sourceLine

        val scratch = ByteArray(CHUNK_BYTES)
        val mixed = ByteArray(CHUNK_BYTES)
        var playedFrames = 0L

        try {
            while (!stopRequested.get() && !Thread.currentThread().isInterrupted) {
                val read = ringBuffer.read(scratch, 100)
                if (read <= 0) continue

                val output = applyGain(scratch, mixed, read, desiredGain)
                sourceLine.write(output, 0, read)
                playedFrames += read / BYTES_PER_FRAME
                onPlaybackPositionChanged(requestedStartPositionMs + playedFrames * 1_000L / SAMPLE_RATE)
            }
        } finally {
            runCatching {
                sourceLine.stop()
                sourceLine.flush()
                sourceLine.close()
            }
            line = null
        }
    }

    private fun applyGain(input: ByteArray, output: ByteArray, size: Int, gain: Float): ByteArray {
        if (gain >= 0.999f) return input
        var index = 0
        while (index + 1 < size) {
            val sample = ((input[index].toInt() and 0xFF) or (input[index + 1].toInt() shl 8)).toShort().toInt()
            val scaled = (sample * gain)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[index] = (scaled and 0xFF).toByte()
            output[index + 1] = ((scaled ushr 8) and 0xFF).toByte()
            index += 2
        }
        if (index < size) output[index] = input[index]
        return output
    }

    private companion object {
        private const val SAMPLE_RATE = 48_000
        private const val SAMPLE_BITS = 16
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = CHANNELS * 2
        private const val CHUNK_FRAMES = 960
        private const val CHUNK_BYTES = CHUNK_FRAMES * BYTES_PER_FRAME
        private const val LINE_BUFFER_BYTES = CHUNK_BYTES * 8
    }
}
