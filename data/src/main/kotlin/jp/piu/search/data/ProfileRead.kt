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
 * @property failure [ProfileReadStatus.FAILED] の理由（[MaterialCauses.causeOf]）。報告の件数に使う
 */
public data class ProfileRead<T>(
    val status: ProfileReadStatus,
    val items: List<T> = emptyList(),
    val failure: String? = null,
)
