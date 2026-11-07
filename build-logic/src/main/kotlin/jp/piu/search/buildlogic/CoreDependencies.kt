// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.buildlogic

import java.io.Serializable

/**
 * `core:*` の依存に、持たせない部品が入っていないかを判定する。
 *
 * `core:*` は照合と学習を JVM だけでテストするために Android から切り離してある（architecture.md の「モジュール」）。
 * 推移的な依存で Android のライブラリが紛れ込むと、その前提が崩れる。
 */
object CoreDependencies {
    /** Android のライブラリのグループ。グループがこれと同じか、`.` を挟んで続くものを拒む。 */
    val FORBIDDEN_GROUPS: List<String> =
        listOf(
            "androidx",
            "com.android",
            "com.google.android",
        )

    /** `core:*` が依存してよいプロジェクトの道の接頭辞。`data`・`app` は Android のモジュール。 */
    const val ALLOWED_PROJECT_PREFIX: String = ":core:"

    /**
     * 依存 1 つ。
     *
     * 外部のライブラリは [group] と [name] を、同じビルドのプロジェクトは [projectPath] を持つ。
     */
    data class Component(
        /** Maven のグループ。プロジェクトなら null。 */
        val group: String? = null,
        /** Maven の名前、またはプロジェクトの名前。 */
        val name: String,
        /** 版。プロジェクトなら null。 */
        val version: String? = null,
        /** プロジェクトの道（`:core:text` など）。外部のライブラリなら null。 */
        val projectPath: String? = null,
    ) : Serializable {
        override fun toString(): String = projectPath?.let { "project $it" } ?: listOfNotNull(group, name, version).joinToString(":")
    }

    /**
     * [components] のうち、`core:*` の依存に持たせないものを返す。
     */
    fun findForbidden(components: Iterable<Component>): List<Component> =
        components.filter { component ->
            val path = component.projectPath
            if (path != null) {
                !path.startsWith(ALLOWED_PROJECT_PREFIX)
            } else {
                val group = component.group.orEmpty()
                FORBIDDEN_GROUPS.any { group == it || group.startsWith("$it.") }
            }
        }
}
