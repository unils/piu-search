// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

/** 空の一覧と、取得できなかった一覧を取り違えないための読み取り状態。 */
public enum class ProfileReadStatus {
    /** 一覧を最後まで読めた。空なら入口がない。 */
    COMPLETE,

    /** 一時停止・ロックなどで今は読めない。既存の索引を保つ。 */
    UNAVAILABLE,

    /** 問い合わせに失敗した。既存の索引を保ち、次に再試行する。 */
    FAILED,
}

/**
 * プロファイル単位の読み取り結果。個別の失敗をほかのパッケージへ広げない。
 *
 * @property status 一覧の読み取り状態
 * @property items 読めた材料
 * @property failedPackages 一覧にはあるが詳細を読めなかったパッケージ
 */
public data class ProfileRead<T>(
    val status: ProfileReadStatus,
    val items: List<T> = emptyList(),
    val failedPackages: Set<String> = emptySet(),
)

/** 正常な不在と区別する、再試行の必要な材料の読み取り失敗。 */
internal class MaterialReadException : RuntimeException()
