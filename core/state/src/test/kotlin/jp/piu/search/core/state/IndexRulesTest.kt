// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.GenreId
import jp.piu.search.core.search.SearchKeyKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 同期の規則の具体例（材料の回復・KEYS だけの回復・再試行の重なり・入口の増減・アーカイブ・読めないプロファイル・ジャンル・知らせ）。 */
class IndexRulesTest {
    @Test
    fun `初めての同期で一覧のすべての組を読み、キーを作る`() {
        val round = sync(IndexSnapshot.EMPTY, device(MAPS to maps()))
        assertEquals(
            setOf(ReadReason.MISSING),
            round.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        val state = round.next.packages.getValue(MAPS)
        assertEquals(1, state.materials.generation)
        assertEquals(MaterialKey.of(maps().observation, ENV), state.materials.key)
        assertEquals(DerivedKey.of(1, ENV), state.derived.key)
        assertEquals(
            setOf("pub:O=Google"),
            state.derived.keysByEntry
                .getValue("Main")
                .filter {
                    it.kind == SearchKeyKind.PUBLISHER
                }.map { it.text.value }
                .toSet(),
        )
        assertEquals(setOf(0L), round.outcome.appliedProfiles)
        assertEquals(setOf(MAPS), round.outcome.changedPackages)
    }

    @Test
    fun `変わらない端末の 2 回目の同期は材料もキーも読まない`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val second = sync(first, device)
        assertTrue(second.plan.isIdle)
        assertEquals(first, second.next)
    }

    @Test
    fun `材料の回復 - 署名が読めるようになると、同じ APK のままでも提供元のキーが直る`() {
        val device = device(MAPS to maps())
        val failed = sync(IndexSnapshot.EMPTY, device, faults = Faults(signer = mapOf(MAPS to TRANSIENT))).next
        val broken = failed.packages.getValue(MAPS)
        assertTrue(
            broken.materials.issues
                .getValue(Stage.SIGNER)
                .retryOnce,
        )
        assertTrue(
            broken.derived.keysByEntry
                .getValue("Main")
                .none { it.kind == SearchKeyKind.PUBLISHER },
        )

        val recovered = sync(failed, device)
        assertEquals(
            setOf(ReadReason.RETRY),
            recovered.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        val state = recovered.next.packages.getValue(MAPS)
        // 材料の鍵は同じだが、世代が進むのでキーも作り直す
        assertEquals(broken.materials.key, state.materials.key)
        assertEquals(2, state.materials.generation)
        assertEquals(DerivedKey.of(2, ENV), state.derived.key)
        assertTrue(
            state.derived.keysByEntry
                .getValue("Main")
                .any { it.text.value == "pub:O=Google" },
        )
        assertTrue(state.materials.issues.isEmpty())
        assertTrue(sync(recovered.next, device).plan.isIdle)
    }

    @Test
    fun `KEYS だけの回復 - 材料を読まずにキーだけを作り直す`() {
        val device = device(MAPS to maps())
        val failed = sync(IndexSnapshot.EMPTY, device, faults = Faults(keys = mapOf((MAPS to "Main") to TRANSIENT))).next
        val broken = failed.packages.getValue(MAPS)
        assertTrue(broken.derived.issue!!.retryOnce)
        // 前のキーがない入口は、表示名を正規化しただけのキー
        assertEquals(SubstituteKeys.of("マップ"), broken.derived.keysByEntry.getValue("Main"))

        val recovered = sync(failed, device)
        assertTrue(recovered.plan.reads.isEmpty())
        assertEquals(setOf(KeyReason.RETRY), recovered.plan.keys.getValue(MAPS))
        val state = recovered.next.packages.getValue(MAPS)
        assertEquals(broken.materials, state.materials)
        assertNull(state.derived.issue)
        assertTrue(
            state.derived.keysByEntry
                .getValue("Main")
                .any { it.text.value == "name:マップ" },
        )
        assertTrue(sync(recovered.next, device).plan.isIdle)
    }

    @Test
    fun `KEYS の不具合の疑いは再試行せず、前のキーを保つ`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val updated = device(MAPS to maps().copy(apkKey = "apk-2"))
        val failed = sync(first, updated, faults = Faults(keys = mapOf((MAPS to "Main") to BUG))).next
        val state = failed.packages.getValue(MAPS)
        assertFalse(state.derived.issue!!.retryOnce)
        assertEquals(
            first.packages
                .getValue(MAPS)
                .derived.keysByEntry,
            state.derived.keysByEntry,
        )
        assertTrue(sync(failed, updated).plan.isIdle)
    }

    @Test
    fun `再試行と鍵の変化が重なった回は、再試行の印を消費しない`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(signer = mapOf(MAPS to TRANSIENT))).next
        val updated = device(MAPS to maps().copy(apkKey = "apk-2"))
        val overlapped = sync(first, updated, faults = Faults(signer = mapOf(MAPS to TRANSIENT)))
        assertEquals(
            setOf(ReadReason.KEY_CHANGED, ReadReason.RETRY),
            overlapped.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertTrue(
            overlapped.next.packages
                .getValue(MAPS)
                .materials.issues
                .getValue(Stage.SIGNER)
                .retryOnce,
        )

        val retried = sync(overlapped.next, updated, faults = Faults(signer = mapOf(MAPS to TRANSIENT)))
        assertEquals(
            setOf(ReadReason.RETRY),
            retried.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        val issue =
            retried.next.packages
                .getValue(MAPS)
                .materials.issues
                .getValue(Stage.SIGNER)
        assertFalse(issue.retryOnce)
        assertEquals(TODAY, issue.firstFailedOn)
        assertTrue(sync(retried.next, updated, faults = Faults(signer = mapOf(MAPS to TRANSIENT))).plan.isIdle)
    }

    @Test
    fun `材料の再試行と KEYS の再試行が重なる回は、材料を読んで両方の印を消費する`() {
        val device = device(MAPS to maps())
        val faults = Faults(label = mapOf(MAPS to TRANSIENT), keys = mapOf((MAPS to "Main") to TRANSIENT))
        val first = sync(IndexSnapshot.EMPTY, device, faults = faults).next
        val broken = first.packages.getValue(MAPS)
        assertTrue(
            broken.materials.issues
                .getValue(Stage.LABEL)
                .retryOnce,
        )
        assertTrue(broken.derived.issue!!.retryOnce)

        val retried = sync(first, device, faults = faults)
        assertEquals(
            setOf(ReadReason.RETRY),
            retried.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertTrue(MAPS !in retried.plan.keys)
        val state = retried.next.packages.getValue(MAPS)
        assertFalse(
            state.materials.issues
                .getValue(Stage.LABEL)
                .retryOnce,
        )
        assertFalse(state.derived.issue!!.retryOnce)
        assertTrue(sync(retried.next, device, faults = faults).plan.isIdle)
    }

    @Test
    fun `入口の増減 - APK が同じでも入口が変われば読み直し、入口の集合を一覧に合わせる`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val more = maps().copy(labels = mapOf("Main" to "マップ", "Go" to "マップ Go"))
        val added = sync(first, device(MAPS to more))
        assertEquals(
            setOf(ReadReason.KEY_CHANGED),
            added.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertEquals(
            setOf("Main", "Go"),
            added.next.packages
                .getValue(MAPS)
                .derived.keysByEntry.keys,
        )

        val fewer = maps().copy(labels = mapOf("Go" to "マップ Go"))
        val removed = sync(added.next, device(MAPS to fewer)).next.packages.getValue(MAPS)
        assertEquals(setOf("Go"), removed.observed.entries)
        assertEquals(setOf("Go"), removed.derived.keysByEntry.keys)
        assertEquals(listOf("Go"), removed.apps().map { it.id.activityName })
    }

    @Test
    fun `アーカイブ - 補助の材料を読まずに空にし、補助の段の欠けを消す。復元すると読む`() {
        val failed = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(signer = mapOf(MAPS to DETERMINISTIC))).next
        val archivedDevice = device(MAPS to maps().copy(archived = true, apkKey = null))
        val archived = sync(failed, archivedDevice)
        assertFalse(
            archived.plan.reads
                .getValue(MAPS)
                .auxiliary,
        )
        val state = archived.next.packages.getValue(MAPS)
        assertTrue(state.materials.localized.isEmpty())
        assertTrue(state.materials.signerDns.isEmpty())
        assertTrue(state.materials.issues.isEmpty())
        assertTrue(
            state.derived.keysByEntry
                .getValue("Main")
                .none { it.kind == SearchKeyKind.PUBLISHER },
        )
        assertTrue(sync(archived.next, archivedDevice).plan.isIdle)

        val restored = sync(archived.next, device(MAPS to maps()))
        assertTrue(
            restored.plan.reads
                .getValue(MAPS)
                .auxiliary,
        )
        val back = restored.next.packages.getValue(MAPS)
        assertEquals(listOf("O=Google"), back.materials.signerDns)
        assertEquals(mapOf("Main" to listOf("Maps")), back.materials.localized)
    }

    @Test
    fun `LABEL と SIGNER の失敗は、読めた材料だけでキーを作り、古い提供元を引き継がない`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val updated = maps().copy(apkKey = "apk-2", signers = listOf("O=Other"))
        val round =
            sync(
                first,
                device(MAPS to updated),
                faults =
                    Faults(
                        label = mapOf(MAPS to DETERMINISTIC),
                        signer =
                            mapOf(MAPS to DETERMINISTIC),
                    ),
            )
        val state = round.next.packages.getValue(MAPS)
        assertTrue(state.materials.signerDns.isEmpty())
        assertTrue(state.materials.localized.isEmpty())
        assertEquals(
            fakeKeys(
                round.jobs
                    .getValue(MAPS)
                    .single()
                    .entry,
            ),
            state.derived.keysByEntry.getValue("Main"),
        )
        assertTrue(
            state.derived.keysByEntry
                .getValue("Main")
                .none { it.kind == SearchKeyKind.PUBLISHER },
        )
        assertEquals(setOf(Stage.LABEL, Stage.SIGNER), state.materials.issues.keys)
    }

    @Test
    fun `ENTRY の失敗 - 旧い入口は旧い材料とキー、新しい入口は代わりのキー。補助の段の前の行は保ち、鍵の変化と重なった回なので印も残す`() {
        val first =
            sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(label = mapOf(MAPS to TRANSIENT))).next
        val old = first.packages.getValue(MAPS)
        val changed = maps().copy(apkKey = "apk-2", labels = mapOf("Main" to "新マップ", "Go" to "マップ Go"))
        val round = sync(first, device(MAPS to changed), faults = Faults(entry = mapOf(MAPS to TRANSIENT)))
        assertEquals(listOf("Go"), round.jobs.getValue(MAPS).map { it.entry.id.activityName })
        assertTrue(
            round.jobs
                .getValue(MAPS)
                .single()
                .substitute,
        )
        val state = round.next.packages.getValue(MAPS)
        assertEquals(old.derived.keysByEntry.getValue("Main"), state.derived.keysByEntry.getValue("Main"))
        assertEquals(SubstituteKeys.of(MAPS.packageName), state.derived.keysByEntry.getValue("Go"))
        assertEquals("マップ", state.materials.labels.getValue("Main"))
        assertEquals(old.materials.signerDns, state.materials.signerDns)
        // 鍵は成否によらず今の値
        assertEquals(MaterialKey.of(changed.observation, ENV), state.materials.key)
        assertTrue(
            state.materials.issues
                .getValue(Stage.ENTRY)
                .retryOnce,
        )
        assertTrue(
            state.materials.issues
                .getValue(Stage.LABEL)
                .retryOnce,
        )
        assertEquals(setOf("Go"), state.materials.substituted)
    }

    @Test
    fun `ENTRY が欠けた新しい入口は、キーだけを作り直す回も代わりのキーに留め、旧い提供元を付けない`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val changed = device(MAPS to maps().copy(apkKey = "apk-2", labels = mapOf("Go" to "マップ Go")))
        val stuck = Faults(entry = mapOf(MAPS to DETERMINISTIC))
        val failed = sync(first, changed, faults = stuck).next
        val rebuilt = sync(failed, changed, env = ENV.copy(generatorVersion = 8), faults = stuck)
        assertEquals(setOf(KeyReason.KEY_CHANGED), rebuilt.plan.keys.getValue(MAPS))
        assertTrue(
            rebuilt.jobs
                .getValue(MAPS)
                .single()
                .substitute,
        )
        val state = rebuilt.next.packages.getValue(MAPS)
        assertTrue(
            state.derived.keysByEntry
                .getValue("Go")
                .none { it.kind == SearchKeyKind.PUBLISHER },
        )
        // 材料が読めた回に、通常のキーへ切り替える
        val recovered =
            sync(rebuilt.next, changed, env = ENV.copy(generatorVersion = 8), notices = listOf(Notice(1, MAPS.packageName, 0, 0)))
        val back = recovered.next.packages.getValue(MAPS)
        assertTrue(back.materials.substituted.isEmpty())
        assertTrue(
            back.derived.keysByEntry
                .getValue("Go")
                .any { it.text.value == "name:マップ Go" },
        )
    }

    @Test
    fun `鍵の変化と重なった回に確かめられなかった段は、印を残し、次の回に再試行で読む`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(signer = mapOf(MAPS to TRANSIENT))).next
        val changed = device(MAPS to maps().copy(apkKey = "apk-2", signers = listOf("O=Other")))
        val failed = sync(first, changed, faults = Faults(entry = mapOf(MAPS to DETERMINISTIC))).next
        assertTrue(
            failed.packages
                .getValue(MAPS)
                .materials.issues
                .getValue(Stage.SIGNER)
                .retryOnce,
        )
        val retried = sync(failed, changed)
        assertEquals(
            setOf(ReadReason.RETRY),
            retried.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertEquals(
            listOf("O=Other"),
            retried.next.packages
                .getValue(MAPS)
                .materials.signerDns,
        )
        assertTrue(sync(retried.next, changed).plan.isIdle)
    }

    @Test
    fun `保った欠けの行は確かめた版だけを進め、例外が起きた版を変えない`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(signer = mapOf(MAPS to BUG))).next
        val v2 = ENV.copy(appVersion = 2)
        val changed = device(MAPS to maps().copy(apkKey = "apk-2"))
        val failed = sync(first, changed, env = v2, faults = Faults(entry = mapOf(MAPS to DETERMINISTIC))).next
        val signer =
            failed.packages
                .getValue(MAPS)
                .materials.issues
                .getValue(Stage.SIGNER)
        assertEquals(1, signer.failedVersion)
        assertEquals(2, signer.checkedVersion)
        assertTrue(sync(failed, changed, env = v2, faults = Faults(entry = mapOf(MAPS to DETERMINISTIC))).plan.isIdle)
    }

    @Test
    fun `材料を頼んだプロファイルの結果がなければ止め、読み終えた後の状態を一覧の状態に残す`() {
        val observation = device(MAPS to maps()).observe()
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, observation, ENV)
        assertThrows<IllegalArgumentException> { IndexRules.keyJobs(IndexSnapshot.EMPTY, observation, plan, emptyMap(), ENV) }
        assertThrows<IllegalArgumentException> {
            IndexRules.apply(IndexSnapshot.EMPTY, observation, plan, emptyMap(), GenreResult.Found(emptyMap()), emptyMap(), ENV, TODAY)
        }
        for (status in listOf(ListingStatus.UNAVAILABLE, ListingStatus.FAILED)) {
            val reads = mapOf(0L to ProfileReads(status, failure = "x".takeIf { status == ListingStatus.FAILED }))
            val outcome =
                IndexRules.apply(
                    IndexSnapshot.EMPTY,
                    observation,
                    plan,
                    reads,
                    GenreResult.Found(emptyMap()),
                    emptyMap(),
                    ENV,
                    TODAY,
                )
            assertEquals(status, outcome.snapshot.listings[0])
            assertTrue(outcome.snapshot.packages.isEmpty())
        }
    }

    @Test
    fun `呼ぶ側が渡した集まりを後で変えても、公開した写しと次の計画は変わらない`() {
        val entries = mutableSetOf("Main")
        val labels = mutableMapOf<String, String?>("Main" to "マップ")
        val keys = mutableListOf(SubstituteKeys.of("マップ").first())
        val observation =
            DeviceObservation(
                mapOf(0L to ProfileListing(ListingStatus.COMPLETE, mapOf(MAPS.packageName to PackageObservation(entries, false, "k", 1)))),
            )
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, observation, ENV)
        val seen = KeyObservation("k", 1)
        val reads = mapOf(0L to ProfileReads(ListingStatus.COMPLETE, mapOf(MAPS.packageName to MaterialRead(labels, null, seen, seen))))
        val built = mapOf(MAPS to mapOf("Main" to KeyOutcome.Built(keys)))
        val snapshot =
            IndexRules
                .apply(
                    IndexSnapshot.EMPTY,
                    observation,
                    plan,
                    reads,
                    GenreResult.Found(emptyMap()),
                    built,
                    ENV,
                    TODAY,
                ).snapshot
        val published = snapshot.packages.getValue(MAPS)
        entries += "Go"
        labels["Main"] = "別名"
        keys.clear()
        assertEquals(setOf("Main"), published.observed.entries)
        assertEquals(setOf("Main"), published.materials.key.entries)
        assertEquals("マップ", published.materials.labels.getValue("Main"))
        assertEquals(
            1,
            published.derived.keysByEntry
                .getValue("Main")
                .size,
        )
        assertEquals(
            setOf(ReadReason.KEY_CHANGED),
            IndexRules
                .plan(snapshot, observation, ENV)
                .reads
                .getValue(MAPS)
                .reasons,
        )
    }

    @Test
    fun `読む前後で鍵が違えば CHANGED_DURING_READ とし、その回の補助の材料を採らない`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val changed = device(MAPS to maps().copy(apkKey = "apk-2", signers = listOf("O=Other")))
        val round = sync(first, changed, faults = Faults(moved = setOf(MAPS)))
        val state = round.next.packages.getValue(MAPS)
        val issue = state.materials.issues.getValue(Stage.ENTRY)
        assertEquals(Failure.CHANGED_DURING_READ, issue.failure.cause)
        assertTrue(issue.retryOnce)
        assertEquals(listOf("O=Google"), state.materials.signerDns)
        assertEquals(
            first.packages
                .getValue(MAPS)
                .derived.keysByEntry,
            state.derived.keysByEntry,
        )

        val retried = sync(round.next, changed).next.packages.getValue(MAPS)
        assertEquals(listOf("O=Other"), retried.materials.signerDns)
        assertTrue(retried.materials.issues.isEmpty())
    }

    @Test
    fun `読めないプロファイルの組は前の写しのまま保ち、読めるようになれば環境の変化を拾う`() {
        val both = device(MAPS to maps(), WORK_MAIL to mail())
        val first = sync(IndexSnapshot.EMPTY, both).next
        val paused = both.copy(profiles = both.profiles + (WORK to FakeProfile(ListingStatus.UNAVAILABLE)))
        val english = ENV.copy(locale = "en-US")
        val whilePaused = sync(first, paused, env = english)
        assertEquals(setOf(MAPS), whilePaused.plan.reads.keys)
        assertEquals(first.packages.getValue(WORK_MAIL), whilePaused.next.packages.getValue(WORK_MAIL))
        assertEquals(ListingStatus.UNAVAILABLE, whilePaused.next.listings[WORK])

        val resumed = sync(whilePaused.next, both, env = english)
        assertEquals(
            setOf(ReadReason.KEY_CHANGED),
            resumed.plan.reads
                .getValue(WORK_MAIL)
                .reasons,
        )
        assertEquals(
            "en-US",
            resumed.next.packages
                .getValue(WORK_MAIL)
                .materials.key.locale,
        )
    }

    @Test
    fun `材料を読み終えた後に読めなくなったプロファイルは、更新も削除も当てない`() {
        val first = sync(IndexSnapshot.EMPTY, device(WORK_MAIL to mail(), PackageKey(WORK, "gone") to mail())).next
        val lost = device(WORK_MAIL to mail().copy(apkKey = "apk-2"))
        val flaky = lost.copy(profiles = lost.profiles + (WORK to lost.profiles.getValue(WORK).copy(readsComplete = false)))
        val round = sync(first, flaky)
        assertEquals(first.packages, round.next.packages)
        assertTrue(WORK !in round.outcome.appliedProfiles)
        assertTrue(round.jobs.isEmpty())
    }

    @Test
    fun `ジャンルの失敗は既存のジャンルを保ち、成功した空はジャンルを消す`() {
        val maps = maps().copy(genres = mapOf("Main" to listOf(GenreId("maps"))))
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps)).next
        assertEquals(
            listOf(GenreId("maps")),
            first.packages
                .getValue(MAPS)
                .genres
                .getValue("Main"),
        )
        assertTrue(
            first
                .apps()
                .single()
                .keys
                .any { it.kind == SearchKeyKind.GENRE && it.text.value == "maps" },
        )

        val more = maps.copy(labels = maps.labels + ("Go" to "マップ Go"), genres = maps.genres + ("Go" to listOf(GenreId("maps"))))
        val failed = sync(first, device(MAPS to more), faults = Faults(genre = TRANSIENT)).next.packages.getValue(MAPS)
        assertEquals(mapOf("Main" to listOf(GenreId("maps"))), failed.genres)

        val empty = sync(first, device(MAPS to maps.copy(genres = emptyMap()))).next.packages.getValue(MAPS)
        assertTrue(empty.genres.isEmpty())
    }

    @Test
    fun `一覧から消えた組と、削除を確認したプロファイルの組を消す`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps(), CAMERA to maps(), WORK_MAIL to mail())).next
        val round = sync(first, device(MAPS to maps()).copy(removed = setOf(WORK)))
        assertEquals(setOf(MAPS), round.next.packages.keys)
        assertEquals(setOf(CAMERA, WORK_MAIL), round.outcome.changedPackages)
        assertTrue(WORK !in round.next.listings)
    }

    @Test
    fun `知らせのある組は鍵が同じでも読み直し、当てた知らせを消せる。同期の後に届いた知らせは対象にしない`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val round = sync(first, device, notices = listOf(Notice(1, MAPS.packageName, 0, 0)))
        assertEquals(
            setOf(ReadReason.NOTICE),
            round.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertEquals(setOf(1L), round.outcome.consumableNotices)
        // 失敗した組の知らせも消す（決定的な欠けの組を毎回読み直さないため）
        val failed =
            sync(first, device, faults = Faults(entry = mapOf(MAPS to DETERMINISTIC)), notices = listOf(Notice(2, MAPS.packageName, 0, 0)))
        assertEquals(setOf(2L), failed.outcome.consumableNotices)
    }

    @Test
    fun `番号を解決できない知らせは残り、解決できた同期で読まれる`() {
        val device = device(WORK_MAIL to mail())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val notice = Notice(5, WORK_MAIL.packageName, null, 10)
        val unresolved = sync(first, device, notices = listOf(notice))
        assertNull(unresolved.plan.noticeTargets.getValue(5))
        assertTrue(unresolved.plan.isIdle)
        assertTrue(unresolved.outcome.consumableNotices.isEmpty())

        val resolved = sync(first, device, notices = listOf(notice), userSerials = mapOf(10 to WORK))
        assertEquals(
            setOf(ReadReason.NOTICE),
            resolved.plan.reads
                .getValue(WORK_MAIL)
                .reasons,
        )
        assertEquals(setOf(5L), resolved.outcome.consumableNotices)
    }

    @Test
    fun `読めないプロファイルの組の知らせは残り、消えたプロファイルと不在の組の知らせは消せる`() {
        val both = device(MAPS to maps(), WORK_MAIL to mail())
        val first = sync(IndexSnapshot.EMPTY, both).next
        val notices = listOf(Notice(1, WORK_MAIL.packageName, WORK, 10), Notice(2, "absent", 0, 0))
        val paused = both.copy(profiles = both.profiles + (WORK to FakeProfile(ListingStatus.FAILED)))
        assertEquals(setOf(2L), sync(first, paused, notices = notices).outcome.consumableNotices)
        val removed = both.copy(profiles = both.profiles - WORK, removed = setOf(WORK))
        assertEquals(setOf(1L, 2L), sync(first, removed, notices = notices).outcome.consumableNotices)
    }

    @Test
    fun `鍵のない組は再起動 1 回につき 1 回だけ読み直し、回数が取れなければ読み直さない`() {
        val keyless = device(WORK_MAIL to mail().copy(apkKey = null, updatedAt = null))
        val first = sync(IndexSnapshot.EMPTY, keyless).next
        assertTrue(sync(first, keyless).plan.isIdle)
        val rebooted = sync(first, keyless, env = ENV.copy(bootCount = 6))
        assertEquals(
            setOf(ReadReason.BOOT),
            rebooted.plan.reads
                .getValue(WORK_MAIL)
                .reasons,
        )
        assertTrue(sync(rebooted.next, keyless, env = ENV.copy(bootCount = 6)).plan.isIdle)
        assertTrue(sync(first, keyless, env = ENV.copy(bootCount = null)).plan.isIdle)
        // 時刻のある組は時刻で拾うので、再起動では読み直さない
        val timed = device(WORK_MAIL to mail().copy(apkKey = null))
        assertTrue(sync(sync(IndexSnapshot.EMPTY, timed).next, timed, env = ENV.copy(bootCount = 6)).plan.isIdle)
    }

    @Test
    fun `欠けを書いた版が今の版と違えば読み直す。KEYS の欠けだけならキーだけを作り直す`() {
        val device = device(MAPS to maps(), CAMERA to maps())
        val first =
            sync(
                IndexSnapshot.EMPTY,
                device,
                faults = Faults(signer = mapOf(MAPS to DETERMINISTIC), keys = mapOf((CAMERA to "Main") to BUG)),
            ).next
        val updated = sync(first, device, env = ENV.copy(appVersion = 2))
        assertEquals(
            setOf(ReadReason.VERSION),
            updated.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertEquals(setOf(KeyReason.VERSION), updated.plan.keys.getValue(CAMERA))
        assertTrue(
            updated.next.packages.values
                .all { it.materials.issues.isEmpty() && it.derived.issue == null },
        )
    }

    @Test
    fun `生成器の版が変われば材料を読まずにキーだけを作り直し、言語が変われば材料を読み直す`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val generator = sync(first, device, env = ENV.copy(generatorVersion = 8))
        assertTrue(generator.plan.reads.isEmpty())
        assertEquals(setOf(KeyReason.KEY_CHANGED), generator.plan.keys.getValue(MAPS))
        assertEquals(
            8,
            generator.next.packages
                .getValue(MAPS)
                .derived.key.generatorVersion,
        )
        assertEquals(
            1,
            generator.next.packages
                .getValue(MAPS)
                .materials.generation,
        )

        val locale = sync(first, device, env = ENV.copy(locale = "en-US"))
        assertEquals(
            setOf(ReadReason.KEY_CHANGED),
            locale.plan.reads
                .getValue(MAPS)
                .reasons,
        )
    }

    @Test
    fun `キーの生成の結果が欠けていれば適用しない`() {
        val device = device(MAPS to maps())
        val observation = device.observe()
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, observation, ENV)
        val reads = mapOf(0L to ProfileReads(ListingStatus.COMPLETE, mapOf(MAPS.packageName to readOf(maps()))))
        assertThrows<IllegalArgumentException> {
            IndexRules.apply(IndexSnapshot.EMPTY, observation, plan, reads, GenreResult.Found(emptyMap()), emptyMap(), ENV, TODAY)
        }
    }

    @Test
    fun `代わりのキーは表示名に NFKC と正規化だけをかけ、空の表示名ならキー 0 個`() {
        assertEquals(listOf("abc"), SubstituteKeys.of("ＡＢＣ").map { it.text.value })
        assertTrue(SubstituteKeys.of("").isEmpty())
        assertTrue(IndexSnapshot.EMPTY.toSearchIndex().size == 0)
    }

    private fun readOf(pkg: FakePackage): MaterialRead {
        val keys = KeyObservation(pkg.apkKey, pkg.updatedAt)
        return MaterialRead(pkg.labels, null, keys, keys, pkg.localized, signerDns = pkg.signers)
    }

    private companion object {
        const val WORK = 10L
        val MAPS = PackageKey(0, "com.google.android.apps.maps")
        val CAMERA = PackageKey(0, "jp.example.camera")
        val WORK_MAIL = PackageKey(WORK, "jp.example.mail")

        fun maps() =
            FakePackage(
                labels = mapOf("Main" to "マップ"),
                localized = mapOf("Main" to listOf("Maps")),
                signers = listOf("O=Google"),
            )

        fun mail() = FakePackage(labels = mapOf("Inbox" to "メール"), apkKey = "mail-1")

        fun device(vararg packages: Pair<PackageKey, FakePackage>): FakeDevice =
            FakeDevice(
                packages
                    .groupBy({ it.first.profileSerial }, { it.first.packageName to it.second })
                    .mapValues { FakeProfile(packages = it.value.toMap()) },
            )
    }
}
