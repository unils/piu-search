// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * ライセンスの画面（[LicensesScreen]）。設定画面の「このアプリについて」から開く。
 *
 * 中身は APK の assets/licenses/ にあり、ビルドが入れる（app/build.gradle.kts）。合わせて 100 KB ほどあるので裏で読む。
 */
class LicensesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PiuTheme {
                val licenses by produceState<OssLicenses?>(null) {
                    value =
                        withContext(Dispatchers.IO) {
                            try {
                                OssLicenses.load(assets)
                            } catch (e: IOException) {
                                // ビルドが入れるので起きないはず。起きても画面は見出しだけで落とさない
                                Log.e(TAG, "ライセンスの文を読めない: ${e.javaClass.simpleName}")
                                null
                            }
                        }
                }
                LicensesScreen(licenses)
            }
        }
    }

    private companion object {
        const val TAG = "PiuLicenses"
    }
}
