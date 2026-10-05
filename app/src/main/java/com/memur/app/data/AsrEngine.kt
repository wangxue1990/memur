package com.memur.app.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地语音识别引擎（sherpa-onnx + SenseVoice int8，模型打包在 assets 中，完全离线）。
 */
class AsrEngine(private val appContext: Context) {

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private val mutex = Mutex()

    private suspend fun ensureLoaded(): OfflineRecognizer = mutex.withLock {
        recognizer ?: withContext(Dispatchers.IO) {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "asr/model.int8.onnx",
                        language = "auto",
                        useInverseTextNormalization = true
                    ),
                    tokens = "asr/tokens.txt",
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
            val r = OfflineRecognizer(assetManager = appContext.assets, config = config)
            recognizer = r
            r
        }.also { recognizer = it }
    }

    suspend fun warmup() { ensureLoaded() }

    /** 识别音频文件：支持 WAV（16bit PCM）与常见压缩格式（经 MediaCodec 解码重采样到 16k） */
    suspend fun transcribeFile(path: String): String = withContext(Dispatchers.Default) {
        val engine = ensureLoaded()
        val samples = loadSamples(File(path))
            ?: throw IllegalArgumentException("无法读取音频文件：$path")
        if (samples.isEmpty()) return@withContext ""
        val stream = engine.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    // ---------- 音频读取 ----------

    /** 返回 [-1,1] 的浮点采样（16kHz）。WAV 直读，其余走 MediaCodec 解码 */
    private fun loadSamples(file: File): FloatArray? {
        if (!file.exists()) return null
        return try {
            if (isWav(file)) readWav(file) else decodeCompressed(file)
        } catch (e: Exception) {
            null
        }
    }

    private fun isWav(f: File): Boolean {
        val head = ByteArray(12)
        f.inputStream().use { it.read(head) }
        return head.size >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    private fun readWav(f: File): FloatArray {
        val bytes = f.readBytes()
        // 遍历 chunk 找 data
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        var sampleRate = 16000
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = readLeInt(bytes, pos + 4)
            if (id == "fmt ") {
                sampleRate = readLeInt(bytes, pos + 12)
                bits = readLeShort(bytes, pos + 22).toInt()
            } else if (id == "data") {
                dataOffset = pos + 8
                dataLen = if (size > 0) size else bytes.size - dataOffset
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOffset > 0) { "WAV 无 data 块" }
        require(bits == 16) { "仅支持 16bit PCM WAV" }
        val n = dataLen / 2
        val samples = FloatArray(n)
        val buf = ByteBuffer.wrap(bytes, dataOffset, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) samples[i] = buf.short.toFloat() / 32768f
        return if (sampleRate == 16000) samples else resample(samples, sampleRate, 16000)
    }

    /** MediaExtractor + MediaCodec 解码任意音频到 PCM，再重采样到 16k */
    private fun decodeCompressed(f: File): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(f.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = fmt
                    break
                }
            }
            require(trackIndex >= 0) { "无音频轨道" }
            extractor.selectTrack(trackIndex)
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val pcm = java.io.ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false
                var spin = 0
                while (!sawOutputEOS && spin < 10000) {
                    spin++
                    if (!sawInputEOS) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        val out = codec.getOutputBuffer(outIdx)!!
                        val chunk = ByteArray(info.size)
                        out.get(chunk)
                        out.clear()
                        pcm.write(chunk)
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    }
                }
                val raw = pcm.toByteArray()
                val n = raw.size / 2
                val samples = FloatArray(n)
                val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) samples[i] = buf.short.toFloat() / 32768f
                return if (srcRate == 16000) samples else resample(samples, srcRate, 16000)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** 线性插值重采样 */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] * (1 - frac) + input[i1] * frac
        }
        return out
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()
}
