package com.lemoneko.endfieldcharge.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `references/zmd-charge/tests/EndfieldCharge.Tests/HudAnimationsTests.cs`.
 *
 * These are the reference implementation's own invariants, so they are the closest thing to a
 * cross-implementation check available without running the C# code: if the cue table or the
 * remapping drifts, the port is no longer faithful.
 */
class HudCuesTest {

    private val eps = 1e-10

    @Test
    fun `baseline duration of six seconds maps identically`() {
        for (cue in HudCues.BASELINE_CUES) {
            assertEquals(cue, HudCues.mapCue(6.0, cue), eps)
        }
    }

    @Test
    fun `mapping is strictly increasing over every key frame`() {
        val cues = HudCues.BASELINE_CUES

        // Precondition: the baseline cues themselves must ascend, otherwise this proves nothing.
        for (i in 1 until cues.size) {
            assertTrue("baseline cue out of order: ${cues[i - 1]} -> ${cues[i]}", cues[i] > cues[i - 1])
        }

        for (duration in doubleArrayOf(3.0, 4.5, 6.0, 8.0, 10.0)) {
            var previous = Double.NEGATIVE_INFINITY
            for (cue in cues) {
                val mapped = HudCues.mapCue(duration, cue)
                assertTrue("duration ${duration}s mapped $cue to $mapped, not increasing", mapped > previous)
                previous = mapped
            }
        }
    }

    @Test
    fun `mapping pins both ends and never leaves the unit interval`() {
        for (duration in doubleArrayOf(3.0, 6.0, 10.0)) {
            assertEquals(0.0, HudCues.mapCue(duration, 0.0), eps)
            assertEquals(1.0, HudCues.mapCue(duration, 1.0), eps)

            var cue = 0.0
            while (cue <= 1.0) {
                val mapped = HudCues.mapCue(duration, cue)
                assertTrue("cue $cue mapped out of range: $mapped", mapped in 0.0..1.0)
                cue += 0.01
            }
        }
    }

    /**
     * The intro segment (0 to 0.42) must keep the baseline absolute length of `0.42 * 6 = 2.52 s`;
     * changing the duration only stretches the hold segment. Otherwise asking for a longer display
     * would also slow the entrance down.
     */
    @Test
    fun `intro segment keeps its absolute duration`() {
        for (duration in doubleArrayOf(3.0, 6.0, 10.0)) {
            val introSeconds = HudCues.mapCue(duration, 0.42) * duration
            assertEquals(0.42 * 6.0, introSeconds, 1e-6)
        }
    }

    @Test
    fun `simple mapping also ascends and pins the intro`() {
        for (duration in doubleArrayOf(3.0, 6.0, 10.0)) {
            var previous = Double.NEGATIVE_INFINITY
            for (cue in HudCues.SIMPLE_BASELINE_CUES) {
                val mapped = HudCues.mapCueSimple(duration, cue)
                assertTrue("duration ${duration}s mapped $cue to $mapped, not increasing", mapped > previous)
                previous = mapped
            }

            assertEquals(0.0, HudCues.mapCueSimple(duration, 0.0), eps)
            assertEquals(1.0, HudCues.mapCueSimple(duration, 1.0), eps)
            assertEquals(0.08 * 5.0, HudCues.mapCueSimple(duration, 0.08) * duration, 1e-6)
        }
    }

    @Test
    fun `every baseline cue stays inside the unit interval`() {
        assertTrue(HudCues.BASELINE_CUES.all { it in 0.0..1.0 })
        assertTrue(HudCues.SIMPLE_BASELINE_CUES.all { it in 0.0..1.0 })
    }
}

/** Mirrors `AnimationOptionsTests` in the reference test project. */
class AnimationOptionsTest {

    @Test
    fun `duration is clamped to three through ten seconds`() {
        for ((input, expected) in listOf(1.0 to 3.0, 3.0 to 3.0, 6.0 to 6.0, 20.0 to 10.0)) {
            assertEquals(expected, AnimationOptions(durationSeconds = input).clampedDuration, eps)
        }
    }

    @Test
    fun `the remaining parameters clamp to their own ranges`() {
        val options = AnimationOptions(
            bounceStrength = 9.0,
            rippleIntensity = -3.0,
            rippleSpread = 100.0,
        )
        assertEquals(0.5, options.clampedBounce, eps)
        assertEquals(0.0, options.clampedRippleIntensity, eps)
        assertEquals(1.5, options.clampedRippleSpread, eps)
    }

    @Test
    fun `defaults are inside every allowed range`() {
        val options = AnimationOptions.Default
        assertTrue(options.clampedDuration in 3.0..10.0)
        assertTrue(options.clampedBounce in 0.0..0.5)
        assertTrue(options.clampedRippleIntensity in 0.0..2.0)
        assertTrue(options.clampedRippleSpread in 0.5..1.5)
    }

    private val eps = 1e-10
}
