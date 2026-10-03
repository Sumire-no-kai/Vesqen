package io.github.sumirenokai.vesqen.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// B · Paper & Sound tokens (docs/redesign/B_PAPER_AND_SOUND.md §2). Contrast pairs are checked
// by PaperAndSoundColorTest; change a value there and here together.
internal val Paper = Color(0xFFF3EFE6)
internal val PaperRaised = Color(0xFFFBF9F4)
internal val PaperNav = Color(0xFFF6F3EC)
internal val Ink = Color(0xFF1A1A16)
internal val InkMuted = Color(0xFF5E5A50)
internal val Hairline = Color(0xFFD8D1C1)
internal val RadioIdleLight = Color(0xFF7C776B)
internal val MossDeep = Color(0xFF536B1E)
internal val OnMoss = Color(0xFFFFFFFF)
internal val AmberDeep = Color(0xFF7A4F00)

internal val Night = Color(0xFF151411)
internal val NightRaised = Color(0xFF1E1C18)
internal val NightNav = Color(0xFF191814)
internal val NightText = Color(0xFFEDE8DC)
internal val NightMuted = Color(0xFFA8A294)
internal val RadioIdleDark = Color(0xFF8A8578)
internal val MossBright = Color(0xFFBFD66B)
internal val AmberBright = Color(0xFFF2C36B)

internal val VesqenError = Color(0xFFBA1A1A)

/** Paper & Sound roles that Material's color scheme has no slot for. */
@Immutable
data class VesqenExtendedColors(
    val navigation: Color,
    /** Dividers drawn as ink alpha, so they also hold on album-tinted backgrounds. */
    val hairline: Color,
    val chipNeutral: Color,
    val radioIdle: Color,
    /** Recoverable warnings such as stopped strict output. Never used to raise a claim. */
    val warning: Color,
    /** Warm shadow tint for light surfaces; dark surfaces separate by fill and border instead. */
    val shadow: Color,
)

internal val LightExtendedColors = VesqenExtendedColors(
    navigation = PaperNav,
    hairline = Ink.copy(alpha = .13f),
    chipNeutral = Ink.copy(alpha = .07f),
    radioIdle = RadioIdleLight,
    warning = AmberDeep,
    shadow = Color(0xFF28201A),
)

internal val DarkExtendedColors = VesqenExtendedColors(
    navigation = NightNav,
    hairline = NightText.copy(alpha = .13f),
    chipNeutral = NightText.copy(alpha = .08f),
    radioIdle = RadioIdleDark,
    warning = AmberBright,
    shadow = Color(0xFF000000),
)

val LocalVesqenColors = staticCompositionLocalOf { LightExtendedColors }

// Previous brand values still used by the protected full player until #35 replaces that screen.
internal val SignalMossBright = MossBright
internal val InkLight = Color(0xFFE7E8E1)
internal val MutedDark = Color(0xFFC8C9BE)
internal val WarningAmberBright = AmberBright
internal val WarningAmberDeep = AmberDeep

/**
 * A separate material ladder for the protected full player. These are deliberately near-neutral:
 * the listener sees one midnight instrument, while Signal Moss remains the scarce action signal.
 */
internal object FocusedPlayerMaterial {
    val Canvas = Color(0xFF101415)
    val Dock = Color(0xFF191F20)
    val Raised = Color(0xFF202728)
    val ArtworkFrame = Color(0xFF252C2D)
    val AmbientLiftShadow = Color(0x38000000)
    val SpotLiftShadow = Color(0x4D000000)
    const val ArtworkReflectionAlpha = .22f
    const val CanvasScrimAlpha = .82f
    val VisibleArtworkReflection: Float
        get() = ArtworkReflectionAlpha * (1f - CanvasScrimAlpha)
}
