package cn.luke.wearsync

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color

data class ThemeColors(
    val background: Color,
    val surfaceCard: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val btnText: Color
)

object ThemeUtils {
    fun isDarkTheme(context: Context): Boolean {
        val currentNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    fun getColors(context: Context, isDark: Boolean): ThemeColors {
        val prefs = context.getSharedPreferences("dndsync_prefs", Context.MODE_PRIVATE)
        val defaultAccent = if (isDark) 0xFFFFB74D.toInt() else 0xFFF57C00.toInt()
        val userAccentColor = prefs.getInt("user_accent_color", defaultAccent)

        return if (isDark) {
            ThemeColors(
                background = Color(0xFF121212),
                surfaceCard = Color(0xFF1E1E1E),
                textPrimary = Color(0xFFFFFFFF),
                textSecondary = Color(0xFFAAAAAA),
                accent = Color(userAccentColor),
                btnText = Color(0xFF121212)
            )
        } else {
            ThemeColors(
                background = Color(0xFFF5F5F5),
                surfaceCard = Color(0xFFFFFFFF),
                textPrimary = Color(0xFF212121),
                textSecondary = Color(0xFF757575),
                accent = Color(userAccentColor),
                btnText = Color(0xFFFFFFFF)
            )
        }
    }
}
