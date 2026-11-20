package dev.dorfandiy.vanadiumupdater

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GreyDark = darkColorScheme(
    primary = Color(0xFFC4CDD3), onPrimary = Color(0xFF1F272C),
    primaryContainer = Color(0xFF3B454B), onPrimaryContainer = Color(0xFFE2E8EC),
    secondary = Color(0xFFB4BCC2), secondaryContainer = Color(0xFF384046), onSecondaryContainer = Color(0xFFDDE3E7),
    tertiaryContainer = Color(0xFF444C52), onTertiaryContainer = Color(0xFFE2E8EC),
    background = Color(0xFF111416), surface = Color(0xFF111416), onSurface = Color(0xFFE1E3E5), onSurfaceVariant = Color(0xFFC1C7CC),
    surfaceContainer = Color(0xFF1D2124), surfaceContainerHigh = Color(0xFF282C2F), surfaceContainerHighest = Color(0xFF33373A),
    outline = Color(0xFF8B9298), error = Color(0xFFFFB4AB),
)

private val GreyLight = lightColorScheme(
    primary = Color(0xFF4A555C), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3E7), onPrimaryContainer = Color(0xFF161D21),
    secondaryContainer = Color(0xFFDDE3E7), onSecondaryContainer = Color(0xFF161D21),
    background = Color(0xFFF7F9FA), surface = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFEBEFF1), surfaceContainerHigh = Color(0xFFE5E9EB), surfaceContainerHighest = Color(0xFFDFE3E5),
    outline = Color(0xFF72797E),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = if (isSystemInDarkTheme()) GreyDark else GreyLight,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
