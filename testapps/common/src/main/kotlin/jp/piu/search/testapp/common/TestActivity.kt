// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.testapp.common

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * テスト用のアプリの画面。Activity の表示名を出すだけ。
 *
 * 起動を確かめるときに、どの Activity が開いたかを画面で見分けられるようにする。
 * AndroidX に依存させず、テスト用のアプリを小さく保つ。
 */
public open class TestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = title })
    }
}

/**
 * 同じアプリの 2 つ目の Activity。
 *
 * 1 つのアプリに LAUNCHER の Activity が 2 つある場合や、起動の入口とは別の Activity だけが
 * intent filter を持つ場合を作る。
 */
public class SecondTestActivity : TestActivity()
