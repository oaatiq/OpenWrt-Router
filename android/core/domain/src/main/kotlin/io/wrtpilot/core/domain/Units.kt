package io.wrtpilot.core.domain

import kotlin.math.abs

/** Decimal byte units; the UI picks localised labels ("MB" / "Mo" / "ميغابايت"). */
enum class ByteUnit { B, KB, MB, GB, TB }

/** Bit rate units. */
enum class RateUnit { BPS, KBPS, MBPS, GBPS }

data class Scaled<U>(val value: Double, val unit: U)

object Units {

    fun scaleBytes(bytes: Long): Scaled<ByteUnit> {
        var v = abs(bytes.toDouble())
        var i = 0
        while (v >= 1000 && i < ByteUnit.entries.size - 1) {
            v /= 1000
            i++
        }
        return Scaled(if (bytes < 0) -v else v, ByteUnit.entries[i])
    }

    fun scaleRate(bitsPerSecond: Long): Scaled<RateUnit> {
        var v = abs(bitsPerSecond.toDouble())
        var i = 0
        while (v >= 1000 && i < RateUnit.entries.size - 1) {
            v /= 1000
            i++
        }
        return Scaled(v, RateUnit.entries[i])
    }

    /** Number of fraction digits that keeps 3 significant digits (12.3, 1.23, 123). */
    fun fractionDigits(value: Double): Int = when {
        value >= 100 || value == 0.0 -> 0
        value >= 10 -> 1
        else -> 2
    }

    fun mbpsToKbps(mbps: Double): Int = (mbps * 1000).toInt()

    fun kbpsToMbps(kbps: Int): Double = kbps / 1000.0
}
