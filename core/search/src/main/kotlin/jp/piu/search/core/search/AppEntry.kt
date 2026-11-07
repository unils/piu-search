// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.search

/**
 * 起動できるアプリ 1 つの識別子。
 *
 * 1 つのパッケージが複数の起動口（Activity）を持つことがあり、仕事用プロファイルには同じパッケージが別にあるので、3 つの組で区別する
 * （architecture.md の「プロファイル」「データの保存」）。
 */
public data class AppId(
    /** パッケージ名（`jp.co.recruit.mtl.android.hotpepper` など）。 */
    val packageName: String,
    /** 起動する Activity のクラス名。 */
    val activityName: String,
    /**
     * プロファイルのシリアル番号（Android の `UserManager.getSerialNumberForUser`）。
     *
     * 消したプロファイルの番号は使い回されないので、学習を別のプロファイルのアプリに取り違えない。
     */
    val profileSerial: Long,
)

/**
 * ジャンルの識別子（`maps`・`weather` など）。
 *
 * ジャンルの語彙（「ちず」「地図」など。M1-05）と、アプリが属するジャンルの判定（M2）をつなぐ。
 */
@JvmInline
public value class GenreId(
    /** 識別子の文字列。英小文字で書く。 */
    public val value: String,
)

/**
 * 検索キーを作るためのアプリの情報（[SearchKeyGenerator] の入力）。Android の型を使わない。
 */
public data class AppEntry(
    /** アプリの識別子。パッケージ名もここから使う。 */
    val id: AppId,
    /** 端末の言語での表示名（ホーム画面に出る名前）。 */
    val label: String,
    /**
     * 別の言語での表示名（日本語・英語など）。表示名と同じものが入っていてもよい（キーを作るときに除く）。
     *
     * 「Google マップ」の英語名「Maps」のように、別の言語の名前で探す人のため。
     */
    val localizedLabels: List<String> = emptyList(),
    /** アプリが属するジャンル。OS の情報から判定する（matching.md の「intent filter とカテゴリによるジャンル」）。 */
    val genres: List<GenreId> = emptyList(),
    /**
     * 署名の証明書の主体の DN（`O=LY Corporation, ST=Tokyo, C=JP` など）。組織名（`O=`）を提供元のキーにする（[SearchKeyKind.PUBLISHER]）。
     *
     * RFC 2253 の形（Android の `X500Principal.getName()`）でも、apksigner の出す形でもよい。署名者が複数なら複数。
     */
    val signerNames: List<String> = emptyList(),
)
