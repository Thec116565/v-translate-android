package com.vtranslate.live

import com.vtranslate.live.domain.LiveSubtitleCue
import com.vtranslate.live.domain.TextDeduplicator
import com.vtranslate.live.domain.WavAudioEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDomainUnitTest {

    @Test
    fun testTextDeduplicatorBlocksFlickerAndAllowsNewLines() {
        val dedup = TextDeduplicator(similarityThreshold = 0.82, cooldownMs = 10_000L)
        val first = dedup.filterNewSubtitle("你好世界！", nowMs = 1000L)
        assertEquals("你好世界！", first)

        // Identical or punctuation-only variant within cooldown is suppressed
        val dup = dedup.filterNewSubtitle("你好世界", nowMs = 2000L)
        assertNull(dup)

        // Completely new subtitle is emitted immediately
        val second = dedup.filterNewSubtitle("欢迎来到抖音直播间", nowMs = 2500L)
        assertEquals("欢迎来到抖音直播间", second)
    }

    @Test
    fun testWavAudioEncoderHeaderAndRms() {
        val samples = ShortArray(1600) { 1000 }
        val rms = WavAudioEncoder.calculateRms(samples)
        assertEquals(1000.0, rms, 1.0)

        val pcmBytes = ByteArray(3200)
        val wav = WavAudioEncoder.encodePcm16ToWavBytes(pcmBytes, sampleRate = 16000, channels = 1)
        assertNotNull(wav)
        assertEquals(3244, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
    }

    @Test
    fun testSrtTimestampFormatting() {
        val cue = LiveSubtitleCue(
            index = 1,
            timestampMs = 65_432L,
            sourceText = "原文",
            translatedText = "Bản dịch tiếng Việt"
        )
        val srt = cue.toSrtBlock()
        assertTrue(srt.contains("00:01:05,432 --> 00:01:08,232"))
        assertTrue(srt.contains("Bản dịch tiếng Việt"))
    }
}
