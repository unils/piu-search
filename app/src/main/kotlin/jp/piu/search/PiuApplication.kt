// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Application
import android.content.Context

/**
 * アプリのプロセス。部品の組み立て（[AppContainer]）を持つ。
 */
class PiuApplication : Application() {
    /** 部品。プロセスの起動時に、画面に依存しないプロファイルの監視も始める。 */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.profileLifecycle.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.onTrimMemory(level)
    }
}

/** この [Context] のアプリの部品。 */
val Context.appContainer: AppContainer get() = (applicationContext as PiuApplication).container
