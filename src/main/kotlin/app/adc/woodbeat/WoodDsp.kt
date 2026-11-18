package app.adc.woodbeat

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Winamp-style audio chain inside ExoPlayer: preamp + 10-band peaking EQ, balance,
 * and the analyser data (spectrum bars + oscilloscope wave) for the LCD. 16-bit PCM only
 * (ExoPlayer converts other PCM formats to 16-bit before custom processors).
 */
class WoodDsp : BaseAudioProcessor() {

    companion object {
        val FREQS = floatArrayOf(60f, 170f, 310f, 600f, 1000f, 3000f, 6000f, 12000f, 14000f, 16000f)
        val LABELS = arrayOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
        const val BANDS = 10
        const val BARS = 19
        const val WAVE = 96
        private const val FFT = 512
        private const val Q = 1.1
    }

    // Settings (written on the UI thread, read on the audio thread).
    @Volatile var enabled = true
    @Volatile var preampDb = 0f
    @Volatile var balance = 0f
    val gainsDb = FloatArray(BANDS)
    @Volatile private var dirty = true
    fun changed() { dirty = true }

    // Analyser output (written on the audio thread, read by the UI).
    val bars = FloatArray(BARS)
    val wave = FloatArray(WAVE)
    @Volatile var analysedAt = 0L

    private var rate = 44100
    private var channels = 2
    private val b0 = DoubleArray(BANDS); private val b1 = DoubleArray(BANDS); private val b2 = DoubleArray(BANDS)
    private val a1 = DoubleArray(BANDS); private val a2 = DoubleArray(BANDS)
    private val active = BooleanArray(BANDS)
    private var pre = 1.0
    private var state = Array(8) { DoubleArray(BANDS * 4) }

    private val ring = FloatArray(FFT)
    private var ringPos = 0
    private var sinceFft = 0
    private val re = FloatArray(FFT)
    private val im = FloatArray(FFT)
    private val window = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
    private val barLo = IntArray(BARS)
    private val barHi = IntArray(BARS)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        state = Array(max(channels, 1)) { DoubleArray(BANDS * 4) }
        for (i in 0 until BARS) {
            val lo = 50.0 * (16000.0 / 50.0).pow(i / BARS.toDouble())
            val hi = 50.0 * (16000.0 / 50.0).pow((i + 1) / BARS.toDouble())
            barLo[i] = max(1, (lo * FFT / rate).toInt())
            barHi[i] = min(FFT / 2 - 1, max(barLo[i], (hi * FFT / rate).toInt()))
        }
        dirty = true
        return inputAudioFormat
    }

    private fun recompute() {
        dirty = false
        pre = 10.0.pow(preampDb / 20.0)
        for (b in 0 until BANDS) {
            val g = gainsDb[b].toDouble()
            active[b] = g != 0.0
            val f = min(FREQS[b].toDouble(), rate * 0.45)
            val a = 10.0.pow(g / 40.0)
            val w0 = 2 * PI * f / rate
            val alpha = sin(w0) / (2 * Q)
            val a0 = 1 + alpha / a
            b0[b] = (1 + alpha * a) / a0
            b1[b] = (-2 * cos(w0)) / a0
            b2[b] = (1 - alpha * a) / a0
            a1[b] = (-2 * cos(w0)) / a0
            a2[b] = (1 - alpha / a) / a0
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (dirty) recompute()
        inputBuffer.order(ByteOrder.nativeOrder())
        val out = replaceOutputBuffer(size)
        val ch = channels
        val frames = size / (2 * ch)
        val eq = enabled
        val bal = balance
        val lGain = if (bal > 0) 1f - bal else 1f
        val rGain = if (bal < 0) 1f + bal else 1f
        for (f in 0 until frames) {
            var mono = 0.0
            for (c in 0 until ch) {
                var x = inputBuffer.short.toDouble()
                if (eq) {
                    x *= pre
                    val z = state[c]
                    for (b in 0 until BANDS) {
                        if (!active[b]) continue
                        val o = b * 4
                        val y = b0[b] * x + b1[b] * z[o] + b2[b] * z[o + 1] - a1[b] * z[o + 2] - a2[b] * z[o + 3]
                        z[o + 1] = z[o]; z[o] = x
                        z[o + 3] = z[o + 2]; z[o + 2] = y
                        x = y
                    }
                }
                if (ch >= 2) {
                    if (c == 0) x *= lGain else if (c == 1) x *= rGain
                }
                val s = if (x > 32767.0) 32767.0 else if (x < -32768.0) -32768.0 else x
                out.putShort(s.toInt().toShort())
                mono += s
            }
            analyse((mono / ch / 32768.0).toFloat())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private fun analyse(sample: Float) {
        ring[ringPos] = sample
        ringPos = (ringPos + 1) % FFT
        if (++sinceFft < rate / 40) return
        sinceFft = 0
        for (i in 0 until FFT) {
            val v = ring[(ringPos + i) % FFT]
            re[i] = v * window[i]
            im[i] = 0f
        }
        val step = FFT / WAVE
        for (i in 0 until WAVE) wave[i] = ring[(ringPos + FFT - WAVE * step + i * step) % FFT]
        fft(re, im)
        for (b in 0 until BARS) {
            var m = 0f
            for (k in barLo[b]..barHi[b]) m = max(m, sqrt(re[k] * re[k] + im[k] * im[k]))
            val db = 20 * log10(m / (FFT / 4f) + 1e-6f)
            bars[b] = ((db + 54f) / 54f).coerceIn(0f, 1f)
        }
        analysedAt = System.nanoTime()
    }

    override fun onFlush() {
        for (z in state) z.fill(0.0)
        ring.fill(0f)
        bars.fill(0f)
        wave.fill(0f)
    }

    override fun onReset() {
        onFlush()
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = x[i]; x[i] = x[j]; x[j] = t
                t = y[i]; y[i] = y[j]; y[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = x[i + k]; val ui = y[i + k]
                    val vr = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val vi = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k] = ur + vr; y[i + k] = ui + vi
                    x[i + k + len / 2] = ur - vr; y[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
