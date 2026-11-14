// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.data

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Process
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * アプリの署名の証明書の主体の DN を読む（M6-01。提供元のキーの材料）。権限は要らない。
 *
 * - `GET_SIGNING_CERTIFICATES` の今の署名者（`apkContentsSigners`）と、鍵を替えたアプリの前の鍵（`signingCertificateHistory`）
 * - 仕事用プロファイルにしかないパッケージは `PackageManager` から引けないので、APK のファイル（`sourceDir`）から読む
 *
 * 署名の真偽を確かめる用途ではない（検索の手がかりにするだけ）ので、偽の署名の問題（Lint の PackageManagerGetSignatures）は当たらない。
 */
public class SignerReader(
    private val packageManager: PackageManager,
) {
    /** [activity] のパッケージの署名者の DN（RFC 2253 の形）。読み取り失敗は例外で知らせ、再試行の対象にする。 */
    public fun read(activity: LaunchableActivity): List<String> {
        val info =
            if (activity.user == Process.myUserHandle()) {
                installedInfo(activity.id.packageName)
            } else {
                null
            } ?: activity.applicationInfo.sourceDir?.let(::archiveInfo)
        val signatures = info?.let(::signatures)?.takeIf { it.isNotEmpty() } ?: throw MaterialReadException()
        return signatures.map(::subjectOf).distinct()
    }

    private fun installedInfo(packageName: String): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags().toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, flags())
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // 一覧を取った後に消えた。次の同期で消える
            null
        }

    private fun archiveInfo(path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags().toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, flags())
        }

    // 署名を検索の手がかりに使うだけで、真偽の判定には使わない（クラスの説明）
    @SuppressLint("PackageManagerGetSignatures")
    private fun flags(): Int = PackageManager.GET_SIGNING_CERTIFICATES

    private fun signatures(info: PackageInfo): List<Signature> {
        val signing = info.signingInfo
        // 鍵を替えたアプリ（APK Signature Scheme v3 の lineage）の前の鍵も含める。Yahoo 系は Yahoo Japan Corporation の鍵から
        // LY Corporation の鍵に替えていて（2026-11-06 に apksigner lineage で確かめた）、前の鍵の組織名も提供元の手がかりになる。
        // 署名者が複数のときは履歴がない（null）
        return listOfNotNull(signing?.apkContentsSigners, signing?.signingCertificateHistory)
            .flatMap { it.toList() }
            .distinct()
    }

    private fun subjectOf(signature: Signature): String =
        try {
            val certificate =
                CertificateFactory
                    .getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(signature.toByteArray())) as X509Certificate
            certificate.subjectX500Principal.name
        } catch (_: CertificateException) {
            // 正常な署名の不在にすると欠けた提供元が固定されるため、次回に再試行する
            throw MaterialReadException()
        }
}
