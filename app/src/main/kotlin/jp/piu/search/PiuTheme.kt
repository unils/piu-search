// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * アプリ全体の配色。
 *
 * Android 12 以降は壁紙から作る配色（dynamic color）に合わせ、ホーム画面との見た目の差を小さくする。
 */
@Composable
fun PiuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> {
                darkColorScheme()
            }

            else -> {
                lightColorScheme()
            }
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
