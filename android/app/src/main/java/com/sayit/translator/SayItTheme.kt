package com.sayit.translator

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SayItPaper = Color(0xFFF2EEE5)
val SayItInk = Color(0xFF17322E)
val SayItBlue = Color(0xFF2E6BDC)
val SayItBlueSoft = Color(0xFFEDF3FF)
val SayItRed = Color(0xFFEF6253)
val SayItRedSoft = Color(0xFFFFF0ED)
val SayItControlLight = Color(0xFFF8F5EE)
val SayItControlLightPressed = Color(0xFFEEE9DF)
val SayItControlDark = Color(0xFF173F3A)
val SayItControlDarkPressed = Color(0xFF0F312E)
val SayItControlBorder = Color(0xFFDDD7CD)
val SayItControlDisabled = Color(0xFFEFECE6)
val SayItControlDisabledIcon = Color(0xFFA6AAA6)
val SayItMuted = Color(0xFF74817C)
val SayItReady = Color(0xFF2E8B57)

private val colors = lightColorScheme(
    primary = SayItBlue,
    secondary = SayItRed,
    background = SayItPaper,
    surface = Color(0xFFFAF8F2),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = SayItInk,
    onSurface = SayItInk,
    error = Color(0xFFA34032),
)

@Composable
fun SayItTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
