package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Strict Black and White Monochrome Palette (#000000, #141414, #FFFFFF, #F4F4F4, #888888)
val PureBlack = Color(0xFF000000)
val DarkSurface = Color(0xFF141414)
val DarkSurfaceVariant = Color(0xFF1F1F1F)
val DarkBorderGray = Color(0xFF888888)
val DarkTextMuted = Color(0xFFAAAAAA)

val PureWhite = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF4F4F4)
val LightSurfaceVariant = Color(0xFFE5E5E5)
val LightBorderGray = Color(0xFF888888)
val LightTextMuted = Color(0xFF666666)

// Monochrome theme background and text mappings
val DarkBackground = PureBlack
val LightBackground = PureWhite
val TextPrimaryDark = PureWhite
val TextSecondaryDark = DarkTextMuted
val TextPrimaryLight = PureBlack
val TextSecondaryLight = LightTextMuted

// Strict monochrome button & recording indicators (No bright/neon colors)
val RecordRedPrimary = PureWhite
val RecordRedVariant = DarkBorderGray
val RecordRedGlow = Color(0x33FFFFFF)
val RecordRedContainer = DarkSurfaceVariant
val OnRecordRedContainer = PureWhite

// Neutral accents (Strict monochrome gray steps)
val AccentBlue = Color(0xFF888888)
val AccentGreen = Color(0xFFAAAAAA)
val AccentAmber = Color(0xFFCCCCCC)
