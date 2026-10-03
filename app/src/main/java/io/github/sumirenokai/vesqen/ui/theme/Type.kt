package io.github.sumirenokai.vesqen.ui.theme

import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.sumirenokai.vesqen.R

// Instrument Sans ships as one variable file; selecting its weight axis is still experimental in
// Compose. Without it every weight would render the default 400 instance or a synthetic bold.
@OptIn(ExperimentalTextApi::class)
private fun instrumentSans(weight: Int) = Font(
    R.font.instrument_sans,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Body, labels and data. Chinese falls back to the system sans (B spec §3). */
internal val InstrumentSans = FontFamily(
    instrumentSans(400),
    instrumentSans(500),
    instrumentSans(600),
    instrumentSans(700),
)

/** Latin-only serif, including the italic used for "Liner notes". */
internal val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular),
    Font(R.font.instrument_serif_italic, style = FontStyle.Italic),
)

/** Times, counts and measured values: proportional sans with tabular figures, not monospace. */
val VesqenDataStyle = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontFeatureSettings = "tnum",
)

/**
 * Titles use Instrument Serif with the system serif as fallback, so Chinese titles use the
 * phone's Songti when it has one and its default CJK face otherwise. Android 8–9 cannot name a
 * fallback family for a custom font, so Chinese titles there use the system sans.
 */
@Composable
private fun rememberTitleSerif(): FontFamily {
    val resources = LocalContext.current.resources
    return remember(resources) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val font = android.graphics.fonts.Font.Builder(resources, R.font.instrument_serif_regular).build()
            val family = android.graphics.fonts.FontFamily.Builder(font).build()
            FontFamily(
                Typeface(
                    android.graphics.Typeface.CustomFallbackBuilder(family)
                        .setSystemFallback("serif")
                        .build(),
                ),
            )
        } else {
            InstrumentSerif
        }
    }
}

// Serif only at 16 sp and above, one weight (400); body, labels and data stay sans (B spec §3).
@Composable
internal fun rememberVesqenTypography(): Typography {
    val serif = rememberTitleSerif()
    return remember(serif) {
        fun title(size: Int, lineHeight: Int) = TextStyle(
            fontFamily = serif,
            fontWeight = FontWeight.Normal,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
        )
        fun sans(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
            fontFamily = InstrumentSans,
            fontWeight = weight,
            fontSize = size.sp,
            lineHeight = lineHeight.sp,
        )
        Typography(
            displayLarge = title(34, 38),
            displayMedium = title(32, 38),
            displaySmall = title(28, 31),
            headlineLarge = title(26, 30),
            headlineMedium = title(22, 28),
            headlineSmall = title(21, 25),
            titleLarge = title(19, 24),
            titleMedium = title(17, 22),
            titleSmall = sans(14, 20, FontWeight.SemiBold),
            bodyLarge = sans(16, 24),
            bodyMedium = sans(15, 23),
            bodySmall = sans(13, 19),
            labelLarge = sans(15, 20, FontWeight.SemiBold),
            labelMedium = sans(12, 16, FontWeight.SemiBold).copy(letterSpacing = .06.em),
            // Eyebrows. Latin callers upper-case the text; Chinese keeps case and spacing only.
            labelSmall = sans(12, 16, FontWeight.Medium).copy(letterSpacing = .14.em),
        )
    }
}
