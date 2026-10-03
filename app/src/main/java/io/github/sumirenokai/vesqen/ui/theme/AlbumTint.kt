package io.github.sumirenokai.vesqen.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The cover's dominant OKLCH hue and the median chroma of that hue band. */
data class AlbumHue(val hueDegrees: Float, val chroma: Float)

private const val HUE_BINS = 24
private const val HUE_BIN_DEGREES = 360f / HUE_BINS
private const val MIN_CHROMATIC_SHARE = .08f

/**
 * Finds the cover hue that tints the Now background (B spec §2.3). Callers pass a cover already
 * scaled to at most 64 px on its longest side. Near-black, near-white, grey and transparent
 * pixels are ignored; when fewer than 8% of the opaque pixels remain, the cover is treated as
 * achromatic and null is returned.
 */
internal fun dominantAlbumHue(argbPixels: IntArray): AlbumHue? {
    val weights = FloatArray(HUE_BINS)
    val members = Array(HUE_BINS) { ArrayList<Oklch>() }
    var opaque = 0
    var chromatic = 0
    for (argb in argbPixels) {
        if (argb ushr 24 < 128) continue
        opaque++
        val color = argbToOklch(argb)
        if (color.lightness < .2f || color.lightness > .95f || color.chroma < .02f) continue
        chromatic++
        val bin = (color.hue / HUE_BIN_DEGREES).toInt().coerceIn(0, HUE_BINS - 1)
        weights[bin] += color.chroma
        members[bin] += color
    }
    if (opaque == 0 || chromatic < opaque * MIN_CHROMATIC_SHARE) return null
    val band = members[weights.indices.maxBy { weights[it] }]
    // Bins never straddle 0°, so a chroma-weighted mean of hues inside one bin needs no wrapping.
    val hue = band.sumOf { (it.hue * it.chroma).toDouble() } / band.sumOf { it.chroma.toDouble() }
    val chromas = band.map { it.chroma }.sorted()
    val median = if (chromas.size % 2 == 1) chromas[chromas.size / 2]
        else (chromas[chromas.size / 2 - 1] + chromas[chromas.size / 2]) / 2
    return AlbumHue(hue.toFloat(), median)
}

/**
 * Fixed lightness and capped chroma keep ink and muted text above their contrast floors for
 * every hue (PaperAndSoundColorTest). Only the background is tinted; nothing else follows it.
 */
internal fun albumBackground(hue: AlbumHue?, dark: Boolean): Color {
    if (hue == null) return if (dark) Night else Paper
    return if (dark) {
        oklchToColor(.21f, (.20f * hue.chroma).coerceIn(.006f, .024f), hue.hueDegrees)
    } else {
        oklchToColor(.915f, (.25f * hue.chroma).coerceIn(.008f, .030f), hue.hueDegrees)
    }
}

internal data class Oklch(val lightness: Float, val chroma: Float, val hue: Float)

internal fun argbToOklch(argb: Int): Oklch {
    val r = decode((argb shr 16) and 0xFF)
    val g = decode((argb shr 8) and 0xFF)
    val b = decode(argb and 0xFF)
    val l = cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b)
    val m = cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b)
    val s = cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b)
    val lightness = .2104542553 * l + .7936177850 * m - .0040720468 * s
    val a = 1.9779984951 * l - 2.4285922050 * m + .4505937099 * s
    val bAxis = .0259040371 * l + .7827717662 * m - .8086757660 * s
    val hue = Math.toDegrees(atan2(bAxis, a)).let { if (it < 0) it + 360 else it }
    return Oklch(lightness.toFloat(), sqrt(a * a + bAxis * bAxis).toFloat(), hue.toFloat())
}

internal fun oklchToColor(lightness: Float, chroma: Float, hueDegrees: Float): Color {
    val radians = Math.toRadians(hueDegrees.toDouble())
    val a = chroma * cos(radians)
    val b = chroma * sin(radians)
    val l = (lightness + .3963377774 * a + .2158037573 * b).pow(3)
    val m = (lightness - .1055613458 * a - .0638541728 * b).pow(3)
    val s = (lightness - .0894841775 * a - 1.2914855480 * b).pow(3)
    return Color(
        red = encode(4.0767416621 * l - 3.3077115913 * m + .2309699292 * s),
        green = encode(-1.2684380046 * l + 2.6097574011 * m - .3413193965 * s),
        blue = encode(-.0041960863 * l - .7034186147 * m + 1.7076147010 * s),
    )
}

private fun decode(channel: Int): Double {
    val c = channel / 255.0
    return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
}

// Out-of-gamut channels are clipped; at these lightness and chroma caps that changes little.
private fun encode(linear: Double): Float {
    val c = linear.coerceIn(0.0, 1.0)
    return (if (c <= .0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - .055).toFloat()
}
