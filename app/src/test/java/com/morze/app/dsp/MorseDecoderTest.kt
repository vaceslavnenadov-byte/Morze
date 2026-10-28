package com.morze.app.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseDecoderTest {

    private fun decodeTimings(
        text: String,
        alphabet: Alphabet,
        wpm: Double,
        jitter: Double = 0.0,
        initialWpm: Double = 20.0,
    ): String {
        val collector = TextCollector()
        val decoder = MorseTimingDecoder(initialWpm, alphabet, collector)
        for ((down, ms) in MorseSignal.timings(text, alphabet, wpm, jitter)) {
            var left = ms
            while (left > 0) {
                val step = minOf(left, 10.0)
                decoder.process(down, step)
                left -= step
            }
        }
        decoder.flush()
        return collector.toString()
    }

    private fun decodeAudio(
        text: String,
        alphabet: Alphabet,
        wpm: Double,
        freq: Double,
        noise: Double = 0.0,
        jitter: Double = 0.0,
        sampleRate: Int = 44100,
    ): String {
        val collector = TextCollector()
        val processor = MorseAudioProcessor(sampleRate, collector)
        processor.alphabet = alphabet
        val pcm = MorseSignal.pcm(MorseSignal.timings(text, alphabet, wpm, jitter), sampleRate, freq, noise = noise)
        // Подаём кусками, как AudioRecord.
        var offset = 0
        val chunk = ShortArray(1024)
        while (offset < pcm.size) {
            val n = minOf(chunk.size, pcm.size - offset)
            System.arraycopy(pcm, offset, chunk, 0, n)
            processor.process(chunk, n)
            offset += n
        }
        processor.flush()
        return collector.toString()
    }

    @Test
    fun tablesRoundTrip() {
        for (alphabet in Alphabet.values()) {
            val text = if (alphabet == Alphabet.LATIN) "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789?/=" else "АБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ0123456789"
            val codes = MorseCode.encode(text, alphabet)
            assertEquals(text, codes.joinToString("") { MorseCode.decode(it, alphabet)!! })
        }
    }

    @Test
    fun timingsAtStartSpeed() {
        assertEquals("PARIS PARIS CQ DE R3ABC", decodeTimings("PARIS PARIS CQ DE R3ABC", Alphabet.LATIN, 20.0))
        assertEquals("ПРИВЕТ МИР", decodeTimings("ПРИВЕТ МИР", Alphabet.CYRILLIC, 20.0))
    }

    @Test
    fun adaptsToSlowAndFastSpeeds() {
        for (wpm in listOf(8.0, 12.0, 30.0, 40.0)) {
            val decoded = decodeTimings("PARIS PARIS PARIS PARIS THE QUICK BROWN FOX", Alphabet.LATIN, wpm, jitter = 0.1)
            assertTrue("$wpm WPM: '$decoded'", decoded.endsWith("PARIS PARIS THE QUICK BROWN FOX"))
        }
    }

    @Test
    fun audioClean() {
        assertEquals("CQ CQ DE R3ABC K", decodeAudio("CQ CQ DE R3ABC K", Alphabet.LATIN, 20.0, 700.0))
    }

    @Test
    fun audioCyrillicWithNoise() {
        assertEquals("ПРИВЕТ МИР 73", decodeAudio("ПРИВЕТ МИР 73", Alphabet.CYRILLIC, 15.0, 600.0, noise = 0.3, jitter = 0.1))
    }

    @Test
    fun audioFastHighTone() {
        val decoded = decodeAudio("TEST TEST HELLO WORLD 599", Alphabet.LATIN, 30.0, 1200.0, noise = 0.2)
        assertTrue(decoded, decoded.endsWith("HELLO WORLD 599"))
    }

    @Test
    fun audioSlowLowRate() {
        val decoded = decodeAudio("SOS SOS SOS", Alphabet.LATIN, 10.0, 500.0, noise = 0.1, sampleRate = 16000)
        assertTrue(decoded, decoded.endsWith("SOS SOS"))
    }

    @Test
    fun audioHeavyNoise() {
        val decoded = decodeAudio("CQ CQ CQ DE UA3AAA UA3AAA K", Alphabet.LATIN, 18.0, 750.0, noise = 0.6, jitter = 0.15)
        assertTrue(decoded, decoded.endsWith("DE UA3AAA UA3AAA K"))
    }

    @Test
    fun followsVolumeDrop() {
        val collector = TextCollector()
        val processor = MorseAudioProcessor(44100, collector)
        processor.alphabet = Alphabet.LATIN
        val loud = MorseSignal.pcm(MorseSignal.timings("LOUD", Alphabet.LATIN, 20.0), 44100, 700.0, amplitude = 0.8, noise = 0.02)
        val quiet = MorseSignal.pcm(MorseSignal.timings("QUIET SIGNAL", Alphabet.LATIN, 20.0), 44100, 700.0, amplitude = 0.03, noise = 0.02, seed = 3)
        processor.process(loud, loud.size)
        processor.process(quiet, quiet.size)
        processor.flush()
        assertTrue(collector.toString(), collector.toString().endsWith("SIGNAL"))
    }

    @Test
    fun silenceProducesNothing() {
        val collector = TextCollector()
        val processor = MorseAudioProcessor(44100, collector)
        val noise = MorseSignal.pcm(listOf(false to 3000.0), 44100, 700.0, noise = 0.3)
        processor.process(noise, noise.size)
        processor.flush()
        assertEquals("", collector.toString())
    }

    @Test
    fun frequencyTrackerFindsTone() {
        val collector = TextCollector()
        val processor = MorseAudioProcessor(44100, collector)
        val pcm = MorseSignal.pcm(MorseSignal.timings("TEST", Alphabet.LATIN, 20.0), 44100, 873.0, noise = 0.1)
        processor.process(pcm, pcm.size)
        assertEquals(873.0, processor.frequency, 10.0)
    }
}
