// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import jp.piu.search.buildlogic.CoreDependencies.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CoreDependenciesTest {
    @ParameterizedTest
    @CsvSource(
        "androidx.annotation, annotation, true",
        "androidx, foo, true",
        "com.android.tools, common, true",
        "com.google.android.material, material, true",
        "com.google.android, android, true",
        // 似た名前でも Android のライブラリではない
        "androidxyz, foo, false",
        "com.androidx, foo, false",
        "com.google.guava, guava, false",
        "org.jetbrains.kotlin, kotlin-stdlib, false",
        "com.atilika.kuromoji, kuromoji-ipadic, false",
    )
    fun `ライブラリのグループで判定する`(
        group: String,
        name: String,
        forbidden: Boolean,
    ) {
        val component = Component(group, name, "1.0")
        assertEquals(if (forbidden) listOf(component) else emptyList<Component>(), CoreDependencies.findForbidden(listOf(component)))
    }

    @ParameterizedTest
    @CsvSource(
        ":core:text, false",
        ":core:reading, false",
        ":data, true",
        ":app, true",
    )
    fun `プロジェクトは core の下だけを許す`(
        path: String,
        forbidden: Boolean,
    ) {
        val component = Component(name = path.substringAfterLast(':'), projectPath = path)
        assertEquals(if (forbidden) listOf(component) else emptyList<Component>(), CoreDependencies.findForbidden(listOf(component)))
    }

    @Test
    fun `表示はライブラリなら座標、プロジェクトなら道`() {
        assertEquals("androidx.core:core:1.0", Component("androidx.core", "core", "1.0").toString())
        assertEquals("project :data", Component(name = "data", projectPath = ":data").toString())
    }
}
