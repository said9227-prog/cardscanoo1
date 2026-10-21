package com.docscanner.ocr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val Primary = Color(0xFF0F6E5C)
val PrimaryContainerLight = Color(0xFFB8EFE0)
val OnPrimaryContainerLight = Color(0xFF00201A)
val Secondary = Color(0xFF2F6B8F)
val BackgroundLight = Color(0xFFF6FBF9)
val SurfaceLight = Color(0xFFFFFFFF)
val OnSurfaceLight = Color(0xFF11201C)
val NumberAccentLight = Color(0xFFB4531F)

val PrimaryDark = Color(0xFF7FD9C4)
val PrimaryContainerDark = Color(0xFF005143)
val OnPrimaryContainerDark = Color(0xFFB8EFE0)
val SecondaryDark = Color(0xFF9CCBF0)
val BackgroundDark = Color(0xFF0F1614)
val SurfaceDark = Color(0xFF17211E)
val OnSurfaceDark = Color(0xFFE2EAE7)
val NumberAccentDark = Color(0xFFFFB68F)

val BoxFill = Color(0x2EFFC107)
val BoxStroke = Color(0xFFFFB300)
val SelectedBoxFill = Color(0x332F6B8F)
val SelectedBoxStroke = Color(0xFF2F6B8F)

/** Colours for the line-confidence badge. */
val ConfidenceHigh = Color(0xFF2E7D32)
val ConfidenceMedium = Color(0xFFEF6C00)
val ConfidenceLow = Color(0xFFC62828)

/** Distinct accent colours used to tell the recognised text cards apart. */
val cardAccentPalette = listOf(
    Color(0xFF0F6E5C),
    Color(0xFF2F6B8F),
    Color(0xFF7B61FF),
    Color(0xFFB4531F),
    Color(0xFF1E88E5),
    Color(0xFF00897B)
)

fun cardAccent(index: Int): Color {
    val size = cardAccentPalette.size
    return cardAccentPalette[((index % size) + size) % size]
}

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = Secondary,
    onSecondary = Color.White,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFE4EFEB),
    onSurfaceVariant = Color(0xFF46564F),
    outline = Color(0xFF7A8B85),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF00382E),
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = Color(0xFF00344D),
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF283430),
    onSurfaceVariant = Color(0xFFBCCBC5),
    outline = Color(0xFF86968F),
    error = Color(0xFFFFB4AB)
)

@Composable
fun DocScannerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}

/** Accent colour used to highlight numbers inside recognised text. */
val numberAccent: Color
    @Composable get() =
        if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
            NumberAccentDark
        } else {
            NumberAccentLight
        }
