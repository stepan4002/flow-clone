package com.flowkey.ime

import kotlin.math.max

/**
 * Timing rules for live Whisper dictation, all measured in AUDIO time (samples consumed),
 * never wall-clock time: the decoding loop can run seconds behind the microphone, and a
 * wall-clock auto-stop would then cut the user off while their speech is still queued.
 */
object LivePolicy {

    const val SAMPLE_RATE = RecognizerManager.SAMPLE_RATE
    private const val MIN_PARTIAL_INTERVAL_MS = 700L
    const val NO_SPEECH_TIMEOUT_MS = 8_000L
    const val MAX_TAKE_MS = 5 * 60_000L
    /** Partials need at least this much speech to be worth decoding. */
    const val MIN_PARTIAL_SPEECH_MS = 500L
    /** Early commit threshold so the composing region never grows unbounded. */
    const val MAX_COMPOSING_CHARS = 1000

    fun ms(samples: Long): Long = samples * 1000 / SAMPLE_RATE

    fun samples(ms: Long): Long = ms * SAMPLE_RATE / 1000

    /**
     * Next partial decode is due once at least 0.7 s of audio passed, or 1.5x the last decode
     * time, whichever is longer, so slow phones decode less often instead of falling behind.
     */
    fun partialDue(samplesSinceLastPartial: Long, lastDecodeMs: Long): Boolean =
        ms(samplesSinceLastPartial) >= max(MIN_PARTIAL_INTERVAL_MS, lastDecodeMs * 3 / 2)

    fun autoStop(heardVoice: Boolean, samplesSinceVoice: Long, autoStopMs: Long): Boolean =
        heardVoice && ms(samplesSinceVoice) >= autoStopMs

    fun noSpeech(heardVoice: Boolean, samplesTotal: Long): Boolean =
        !heardVoice && ms(samplesTotal) >= NO_SPEECH_TIMEOUT_MS

    fun maxTake(samplesTotal: Long): Boolean = ms(samplesTotal) >= MAX_TAKE_MS
}
