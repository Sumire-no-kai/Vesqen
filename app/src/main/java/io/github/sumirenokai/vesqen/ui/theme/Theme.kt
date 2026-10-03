package io.github.sumirenokai.vesqen.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Role mapping follows docs/redesign/B_PAPER_AND_SOUND.md §10. Surface tint is transparent so
// tonal elevation never washes paper with Moss; Moss stays reserved for actions and ACTIVE.
private val LightColorScheme = lightColorScheme(
    primary = MossDeep,
    onPrimary = OnMoss,
    primaryContainer = Color(0xFFE2E8CC),
    onPrimaryContainer = Ink,
    inversePrimary = MossBright,
    secondary = InkMuted,
    onSecondary = PaperRaised,
    secondaryContainer = Color(0xFFE8E2D5),
    onSecondaryContainer = Ink,
    tertiary = InkMuted,
    onTertiary = PaperRaised,
    tertiaryContainer = Color(0xFFE8E2D5),
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = Color(0xFFECE7DB),
    onSurfaceVariant = InkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2E2C27),
    inverseOnSurface = PaperRaised,
    error = VesqenError,
    onError = OnMoss,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = RadioIdleLight,
    outlineVariant = Hairline,
    scrim = Color.Black,
    surfaceBright = PaperRaised,
    surfaceDim = Color(0xFFE6E0D3),
    surfaceContainerLowest = PaperRaised,
    surfaceContainerLow = PaperNav,
    surfaceContainer = PaperNav,
    surfaceContainerHigh = Color(0xFFEFEAE0),
    surfaceContainerHighest = Color(0xFFE9E3D7),
)

private val DarkColorScheme = darkColorScheme(
    primary = MossBright,
    onPrimary = Night,
    primaryContainer = Color(0xFF3D4A18),
    onPrimaryContainer = NightText,
    inversePrimary = MossDeep,
    secondary = NightMuted,
    onSecondary = Night,
    secondaryContainer = Color(0xFF2E2C26),
    onSecondaryContainer = NightText,
    tertiary = NightMuted,
    onTertiary = Night,
    tertiaryContainer = Color(0xFF2E2C26),
    onTertiaryContainer = NightText,
    background = Night,
    onBackground = NightText,
    surface = NightRaised,
    onSurface = NightText,
    surfaceVariant = Color(0xFF2A2823),
    onSurfaceVariant = NightMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = NightText,
    inverseOnSurface = Night,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = RadioIdleDark,
    outlineVariant = NightText.copy(alpha = .14f),
    scrim = Color.Black,
    surfaceBright = Color(0xFF3A3832),
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF100F0D),
    surfaceContainerLow = NightNav,
    surfaceContainer = NightNav,
    surfaceContainerHigh = Color(0xFF24221D),
    surfaceContainerHighest = Color(0xFF2B2924),
)

@Composable
fun VesqenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalVesqenColors provides if (darkTheme) DarkExtendedColors else LightExtendedColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = rememberVesqenTypography(),
            content = content,
        )
    }
}
