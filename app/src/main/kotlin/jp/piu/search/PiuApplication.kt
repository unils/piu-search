// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import android.app.Application
import android.content.Context

/**
 * アプリのプロセス。部品の組み立て（[AppContainer]）を持つ。
 */
open class PiuApplication : Application() {
    /** 部品。プロセスの起動時に作り、記録と索引の読み込みと、画面に依存しない監視を始める。 */
    val container: AppContainer by lazy { createContainer() }

    /** 部品を作る。テストでは、書き手を止められる部品に差し替える。 */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        container.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.onTrimMemory(level)
    }

    // 実機では呼ばれない。Robolectric がテストの終わりに呼ぶので、書き手と受け取りを片付ける（終わるのは待たない）
    override fun onTerminate() {
        container.cancel()
        super.onTerminate()
    }
}

/** この [Context] のアプリの部品。 */
val Context.appContainer: AppContainer get() = (applicationContext as PiuApplication).container
