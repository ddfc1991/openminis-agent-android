package com.openminis.app.plus

/**
 * [Mines-38.9.11 i32] user 2026-10-20 08:27 「把云雾的速度给自动调节」 + 08:28 「感觉现在的太快了」: the v7 sky's clouds / fog /
 * light drift at a rate that follows the real wind from the weather Mines already fetched (Open-Meteo wind_speed_10m, m/s).
 * Rate 1.0 = the 38.9.10 e drift speed (judged too fast), so:
 *  - wind unknown / stale → 0.5 (half of 38.9.10 e);
 *  - calm (< 1 m/s) → barely moving (0.08 – 0.15);
 *  - 3 – 5 m/s → about half (0.45 – 0.55);
 *  - strong wind (≥ 12 m/s) → at most today's speed (1.0), never faster.
 * Changes ease in exponentially (time constant [TAU_S]) so a new weather reading never makes the clouds jump; the cloud
 * clock is integrated (never re-derived from the rate × wall time), so a rate change only bends the speed, not the position.
 * Pure, unit-tested in WindDriftCoreTest. The phone side: WeatherSky.cloudClock → AGSL uniform `cloudT`.
 */
object WindDriftCore {
    const val UNKNOWN = 0.5f
    const val MIN_RATE = 0.08f
    const val MAX_RATE = 1.0f
    const val TAU_S = 20f
    /**
     * [38.9.11.3] user 2026-10-21: 「云雾基本不动」 — after the 流畅 scale (×0.6) a calm / unknown wind gave ×0.05–0.3, i.e.
     * the mist crept a few pixels a second and read as standing still. The effective drift never goes below this (slow,
     * but clearly floating: the mid fog layer crosses the screen in about 30 s).
     */
    // [38.9.11.3 h] user 2026-10-21 20:39 「最起码和你这样动才行」 (Hark's chat sky: big soft clouds visibly drifting within a
    // few seconds): floor 0.4 → 0.7 (the mid fog layer now crosses the screen in ≈ 16 s; strong wind still caps at 1.0)
    const val VISIBLE_MIN = 0.7f
    /** the rate the shader clock follows: wind target × the mode's scale, never below [VISIBLE_MIN] */
    fun effective(target: Float, modeScale: Float): Float = maxOf(target * modeScale, VISIBLE_MIN).coerceAtMost(MAX_RATE)
    /** a jump of the page clock larger than this (resume, smoke clock shift) re-bases the clock instead of drifting */
    const val MAX_STEP_S = 5f

    /** (wind m/s, rate) knots, piecewise linear between them */
    private val knots = listOf(0.0 to 0.08, 1.0 to 0.15, 3.0 to 0.45, 5.0 to 0.55, 8.0 to 0.75, 12.0 to 1.0)

    fun targetRate(windMs: Double?): Float {
        if (windMs == null || windMs.isNaN() || windMs < 0) return UNKNOWN
        if (windMs >= knots.last().first) return MAX_RATE
        for (i in 1 until knots.size) {
            val (x1, y1) = knots[i]
            if (windMs <= x1) {
                val (x0, y0) = knots[i - 1]
                return (y0 + (y1 - y0) * (windMs - x0) / (x1 - x0)).toFloat().coerceIn(MIN_RATE, MAX_RATE)
            }
        }
        return MAX_RATE
    }

    /** one easing step from [current] toward [target] over [dtS] seconds */
    fun ease(current: Float, target: Float, dtS: Float, tau: Float = TAU_S): Float {
        if (dtS <= 0f) return current
        val k = 1f - kotlin.math.exp(-dtS / tau)
        return current + (target - current) * k
    }

    /**
     * The cloud clock. [advance] takes the page's sky time (s) and the target rate, returns the cloud time (s) for the
     * shader. First call starts at [startFactor] × sky time so a cold start looks like an ordinary moment of the sky.
     */
    class Clock(var rate: Float = UNKNOWN) {
        /** smoke before / after only: 38.9.11.1 behaviour (earlier instants get the latest cloud time) */
        @Volatile var legacy = false
        /**
         * [38.9.11.2] cloud time for an instant near the last one WITHOUT moving the clock: earlier key frames and still frames
         * (a still page now shows the same clouds the animated page had, instead of a different sky from the raw sky time).
         */
        fun at(skyT: Float, startFactor: Float = UNKNOWN): Float {
            if (lastSky.isNaN()) return skyT * startFactor
            val dt = skyT - lastSky
            if (kotlin.math.abs(dt) > MAX_STEP_S) return cloud
            var c = cloud + dt * rate
            if (c < 0f) c += 86_400f
            return c
        }
        var lastSky = Float.NaN
            private set
        var cloud = 0f
            private set
        fun advance(skyT: Float, target: Float, startFactor: Float = UNKNOWN): Float {
            if (lastSky.isNaN()) { cloud = skyT * startFactor; lastSky = skyT; return cloud }
            val dt = skyT - lastSky
            // [38.9.11.2] an EARLIER instant asked after a later one (key frame a rendered after key b, e.g. after a layer
            // reset): its own cloud time, the clock does not move. 38.9.11.1 returned b's cloud time → both keys equal →
            // the sky jumped ahead one key step and stood still ([legacy] keeps that for the before / after smoke).
            if (!legacy && dt < 0f && dt >= -MAX_STEP_S) return at(skyT)
            lastSky = skyT
            if (dt <= 0f || dt > MAX_STEP_S) return cloud      // held, wrapped at the day boundary, or a jump: no drift
            rate = ease(rate, target.coerceIn(MIN_RATE, MAX_RATE), dt)
            cloud += dt * rate
            if (cloud > 86_400f) cloud -= 86_400f              // keep float precision (≈ 8 ms steps)
            return cloud
        }
    }
}
