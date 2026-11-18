package com.vtranslate.live.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Encodes raw PCM16 audio buffers into standard 16kHz Mono WAV byte arrays in memory
 * and provides RMS Voice Activity Detection (VAD) for real-time audio segmentation.
 */
object WavAudioEncoder {

    fun calculateRms(pcm16Samples: ShortArray, length: Int = pcm16Samples.size): Double {
        val count = length.coerceAtMost(pcm16Samples.size)
        if (count <= 0) return 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = pcm16Samples[i].toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / count.toDouble())
    }

    fun encodePcm16ToWavBytes(
        pcmData: ByteArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
    ): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = (channels * 2).toShort()
        val dataSize = pcmData.size
        val totalDataLen = dataSize + 36

        val out = ByteArrayOutputStream(dataSize + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM chunk size
        header.putShort(1) // AudioFormat = 1 (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign)
        header.putShort(16) // BitsPerSample = 16

        // data subchunk
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }
}
