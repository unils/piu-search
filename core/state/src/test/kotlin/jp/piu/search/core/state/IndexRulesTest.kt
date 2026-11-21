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

/**
 * 同期の規則の具体例（全部か無か・成功した空・再試行の枠・入口の増減・アーカイブ・読めないプロファイル・ジャンル・知らせ・
 * 呼ぶ側の誤り）。
 */
class IndexRulesTest {
    @Test
    fun `初めての同期で一覧のすべての組を作り、検索に使う値と最後の試みに今の鍵を持つ`() {
        val round = sync(IndexSnapshot.EMPTY, device(MAPS to maps()))
        assertEquals(
            setOf(ReadReason.MISSING),
            round.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        val state = round.next.packages.getValue(MAPS)
        assertEquals(currentFromDevice(MAPS, maps(), ENV), state.current)
        assertEquals(Attempt(materialKey(maps(), ENV), derivedKey(ENV), ENV.bootCount, null, retry = false), state.attempt)
        assertEquals(setOf(0L), round.outcome.appliedProfiles)
        assertEquals(setOf(MAPS), round.outcome.staleIcons)
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
    fun `ないことが決まっているもの（別の言語の名前・署名がない）は成功した空で、欠けにしない`() {
        val bare = maps().copy(localized = emptyMap(), signers = emptyList())
        val state = sync(IndexSnapshot.EMPTY, device(MAPS to bare)).next.packages.getValue(MAPS)
        assertNull(state.attempt.issue)
        assertEquals(emptyList<String>(), state.current!!.signerDns)
        assertTrue(sync(sync(IndexSnapshot.EMPTY, device(MAPS to bare)).next, device(MAPS to bare)).plan.isIdle)
    }

    @Test
    fun `本当の失敗が 1 つでもあれば、検索に使う値を材料・キー・由来ごと保ち、最後の試みにだけ今の鍵と欠けを書く`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val old = first.packages.getValue(MAPS)
        val updated = maps().copy(apkKey = "apk-2", labels = mapOf("Main" to "新マップ"), signers = listOf("O=New"))
        val round = sync(first, device(MAPS to updated), faults = Faults(signer = mapOf(MAPS to DETERMINISTIC)), today = "2026-11-20")
        assertTrue(MAPS !in round.jobs)
        val state = round.next.packages.getValue(MAPS)
        assertEquals(old.current, state.current)
        assertEquals(materialKey(updated, ENV), state.attempt.materialKey)
        assertEquals(Issue(Stage.SIGNER, DETERMINISTIC, ENV.appVersion, "2026-11-20", "2026-11-20"), state.attempt.issue)
        assertFalse(state.attempt.retry)
        // 同じ鍵では読み直さない
        assertTrue(sync(round.next, device(MAPS to updated), faults = Faults(signer = mapOf(MAPS to DETERMINISTIC))).plan.isIdle)
        // 続けて欠けている間は初めの日を保ち、成功すれば欠けを消す
        val again =
            sync(
                round.next,
                device(MAPS to updated.copy(apkKey = "apk-3")),
                faults = Faults(entry = mapOf(MAPS to BUG)),
                today = "2026-11-21",
            )
        assertEquals(
            "2026-11-20",
            again.next.packages
                .getValue(MAPS)
                .attempt.issue!!
                .firstFailedOn,
        )
        val fixed = sync(again.next, device(MAPS to updated.copy(apkKey = "apk-4"))).next.packages.getValue(MAPS)
        assertEquals(currentFromDevice(MAPS, updated.copy(apkKey = "apk-4"), ENV), fixed.current)
        assertNull(fixed.attempt.issue)
    }

    @Test
    fun `材料が読めてもキーの生成が失敗すれば、読めた材料も採らず前の値を保つ`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val updated = device(MAPS to maps().copy(apkKey = "apk-2", signers = listOf("O=New")))
        val failed = sync(first, updated, faults = Faults(keys = mapOf((MAPS to "Main") to BUG))).next.packages.getValue(MAPS)
        assertEquals(first.packages.getValue(MAPS).current, failed.current)
        assertEquals(Stage.KEYS, failed.attempt.issue!!.stage)
        assertFalse(failed.attempt.retry)
    }

    @Test
    fun `一度も成功していない組は、試みで取れた表示名（なければパッケージ名）の代わりのキーで入れる`() {
        val signer = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(signer = mapOf(MAPS to TRANSIENT))).next
        val state = signer.packages.getValue(MAPS)
        assertNull(state.current)
        assertEquals(listOf("マップ"), state.apps().map { it.label })
        assertEquals(SubstituteKeys.of("マップ"), state.apps().single().keys)
        val entry = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(entry = mapOf(MAPS to TRANSIENT))).next
        assertEquals(
            SubstituteKeys.of(MAPS.packageName),
            entry.packages
                .getValue(MAPS)
                .apps()
                .single()
                .keys,
        )
    }

    @Test
    fun `一時的な失敗は枠を立てて次の同期で 1 回だけ作り直し、そこでも失敗すれば枠が下りる`() {
        val device = device(MAPS to maps())
        val stuck = Faults(signer = mapOf(MAPS to TRANSIENT))
        val failed = sync(IndexSnapshot.EMPTY, device, faults = stuck).next
        assertTrue(
            failed.packages
                .getValue(MAPS)
                .attempt.retry,
        )
        val retried = sync(failed, device, faults = stuck)
        assertTrue(
            retried.plan.reads
                .getValue(MAPS)
                .retryOnly,
        )
        assertFalse(
            retried.next.packages
                .getValue(MAPS)
                .attempt.retry,
        )
        assertTrue(sync(retried.next, device, faults = stuck).plan.isIdle)
        // 失敗がなくなれば、再試行で回復する
        val recovered = sync(failed, device).next.packages.getValue(MAPS)
        assertEquals(currentFromDevice(MAPS, maps(), ENV), recovered.current)
    }

    @Test
    fun `再試行と鍵の変化や知らせが重なった回は、その変化への 1 回目の試みなので、失敗すれば枠を立て直す`() {
        val stuck = Faults(signer = mapOf(MAPS to TRANSIENT))
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = stuck).next
        val updated = device(MAPS to maps().copy(apkKey = "apk-2"))
        val overlapped = sync(first, updated, faults = stuck)
        assertEquals(
            setOf(ReadReason.KEY_CHANGED, ReadReason.RETRY),
            overlapped.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertTrue(
            overlapped.next.packages
                .getValue(MAPS)
                .attempt.retry,
        )
        val noticed = sync(first, device(MAPS to maps()), faults = stuck, notices = listOf(Notice(1, MAPS.packageName, 0, 0)))
        assertTrue(
            noticed.next.packages
                .getValue(MAPS)
                .attempt.retry,
        )
    }

    @Test
    fun `キーだけを作り直す回の一時的な失敗は、次の同期で組を作り直す`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val v8 = ENV.copy(generatorVersion = 8)
        val failed = sync(first, device, env = v8, faults = Faults(keys = mapOf((MAPS to "Main") to TRANSIENT)))
        assertEquals(setOf(MAPS), failed.plan.keys)
        val state = failed.next.packages.getValue(MAPS)
        assertEquals(first.packages.getValue(MAPS).current, state.current)
        assertTrue(state.attempt.retry)
        val retried = sync(failed.next, device, env = v8)
        assertTrue(
            retried.plan.reads
                .getValue(MAPS)
                .retryOnly,
        )
        assertEquals(
            currentFromDevice(MAPS, maps(), v8),
            retried.next.packages
                .getValue(MAPS)
                .current,
        )
    }

    @Test
    fun `入口の集合は成否によらず一覧に合わせ、保った組の新しい入口だけ代わりのキーにする`() {
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
            currentFromDevice(MAPS, more, ENV),
            added.next.packages
                .getValue(MAPS)
                .current,
        )

        val failed = sync(first, device(MAPS to more), faults = Faults(label = mapOf(MAPS to DETERMINISTIC))).next.packages.getValue(MAPS)
        val apps = failed.apps().associateBy { it.id.activityName }
        assertEquals(setOf("Go", "Main"), apps.keys)
        assertEquals(
            first.packages
                .getValue(MAPS)
                .current!!
                .keysByEntry
                .getValue("Main"),
            apps.getValue("Main").keys,
        )
        assertEquals(SubstituteKeys.of("マップ Go"), apps.getValue("Go").keys)

        val fewer = maps().copy(labels = mapOf("Go" to "マップ Go"))
        val removed =
            sync(
                added.next,
                device(MAPS to fewer),
                faults = Faults(entry = mapOf(MAPS to DETERMINISTIC)),
            ).next.packages.getValue(MAPS)
        assertEquals(listOf("Go"), removed.apps().map { it.id.activityName })
    }

    @Test
    fun `アーカイブ - 補助の材料を読まずに空で成功する。復元すると読む`() {
        val archivedPkg = maps().copy(archived = true, apkKey = null)
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val archived = sync(first, device(MAPS to archivedPkg), faults = Faults(signer = mapOf(MAPS to BUG)))
        assertFalse(
            archived.plan.reads
                .getValue(MAPS)
                .auxiliary,
        )
        val state = archived.next.packages.getValue(MAPS)
        assertEquals(currentFromDevice(MAPS, archivedPkg, ENV), state.current)
        assertTrue(
            state.current!!
                .keysByEntry
                .getValue("Main")
                .none { it.kind == SearchKeyKind.PUBLISHER },
        )
        assertNull(state.attempt.issue)
        assertTrue(sync(archived.next, device(MAPS to archivedPkg)).plan.isIdle)

        val restored = sync(archived.next, device(MAPS to maps()))
        assertTrue(
            restored.plan.reads
                .getValue(MAPS)
                .auxiliary,
        )
        assertEquals(
            currentFromDevice(MAPS, maps(), ENV),
            restored.next.packages
                .getValue(MAPS)
                .current,
        )
    }

    @Test
    fun `読む前後で鍵が違えば CHANGED_DURING_READ の一時的な失敗とし、前の値を保つ`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val changed = device(MAPS to maps().copy(apkKey = "apk-2", signers = listOf("O=Other")))
        for (faults in listOf(Faults(moved = setOf(MAPS)), Faults(movedBefore = setOf(MAPS)), Faults(movedTime = setOf(MAPS)))) {
            val state = sync(first, changed, faults = faults).next.packages.getValue(MAPS)
            assertEquals(Failure(Failure.CHANGED_DURING_READ, IssueCategory.TRANSIENT), state.attempt.issue!!.failure)
            assertTrue(state.attempt.retry)
            assertEquals(first.packages.getValue(MAPS).current, state.current)
        }
        // 一覧の時点で鍵のない成分は比べない
        val keyless = device(MAPS to maps().copy(apkKey = null, updatedAt = null))
        assertNull(
            sync(first, keyless, faults = Faults(moved = setOf(MAPS)))
                .next.packages
                .getValue(MAPS)
                .attempt.issue,
        )
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
                .current!!
                .materialKey.locale,
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
    fun `材料を頼んだプロファイルの結果がなければ止め、読み終えた後の状態を一覧の状態に残す`() {
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, device(MAPS to maps()).observe(), ENV)
        assertThrows<IllegalArgumentException> { IndexRules.prepare(plan, emptyList(), TODAY) }
        for (status in listOf(ListingStatus.UNAVAILABLE, ListingStatus.FAILED)) {
            val reads = listOf(ProfileReads(plan.profileReads.getValue(0), status, failure = "x".takeIf { status == ListingStatus.FAILED }))
            val prepared = IndexRules.prepare(plan, reads, TODAY)
            assertTrue(prepared.jobs.isEmpty())
            val outcome = IndexRules.apply(prepared, noGenres(plan), emptyMap())
            assertEquals(status, outcome.snapshot.listings[0])
            assertTrue(outcome.snapshot.packages.isEmpty())
        }
    }

    @Test
    fun `表示名が一覧の入口の分そろわない成功と、KEYS の段の読み取りの失敗は、呼ぶ側の誤りとして止める`() {
        val more = maps().copy(labels = mapOf("Main" to "マップ", "Alt" to "別"))
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, device(MAPS to more).observe(), ENV)
        val seen = KeyObservation(more.apkKey, more.updatedAt)
        val partial = MaterialRead.Read(mapOf("Main" to "マップ"), seen, seen)
        assertThrows<IllegalArgumentException> { IndexRules.prepare(plan, readsOf(plan, partial), TODAY) }
        assertThrows<IllegalArgumentException> {
            IndexRules.prepare(
                plan,
                readsOf(plan, MaterialRead.Failed(Stage.KEYS, BUG, seen, seen)),
                TODAY,
            )
        }
    }

    @Test
    fun `生成の結果は、頼んだ同期の頼みとちょうど一致するものだけを受ける（入力が同じでも別の回の結果は受けない）`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val v8 = ENV.copy(generatorVersion = 8)
        val plan = IndexRules.plan(first, device.observe(), v8)
        val prepared = IndexRules.prepare(plan, emptyList(), TODAY)
        assertThrows<IllegalArgumentException> { IndexRules.apply(prepared, noGenres(plan), emptyMap()) }
        // 同じ入力を、前の版の生成器で作った別の回の頼み
        val stale = IndexRules.prepare(IndexRules.plan(first, device.observe(), ENV.copy(generatorVersion = 9)), emptyList(), TODAY)
        assertEquals(prepared.jobs.single().entry, stale.jobs.single().entry)
        assertThrows<IllegalArgumentException> {
            IndexRules.apply(prepared, noGenres(plan), mapOf(stale.jobs.single() to KeyOutcome.Built(emptyList())))
        }
    }

    @Test
    fun `呼ぶ側が渡した集まりを後で変えても、公開した写しと次の計画は変わらない`() {
        val entries = mutableSetOf("Main")
        val labels = mutableMapOf("Main" to "マップ")
        val wordStarts = mutableListOf(1)
        val keys = mutableListOf(SubstituteKeys.of("マップ").first().copy(wordStarts = wordStarts))
        val observation =
            DeviceObservation(
                mapOf(0L to ProfileListing(ListingStatus.COMPLETE, mapOf(MAPS.packageName to PackageObservation(entries, false, "k", 1)))),
            )
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, observation, ENV)
        val seen = KeyObservation("k", 1)
        val prepared = IndexRules.prepare(plan, readsOf(plan, MaterialRead.Read(labels, seen, seen)), TODAY)
        val snapshot =
            IndexRules
                .apply(
                    prepared,
                    noGenres(plan),
                    mapOf(prepared.jobs.single() to KeyOutcome.Built(keys)),
                ).snapshot
        val published = snapshot.packages.getValue(MAPS)
        entries += "Go"
        labels["Main"] = "別名"
        wordStarts += 2
        keys.clear()
        assertEquals(setOf("Main"), published.observed.entries)
        assertEquals(setOf("Main"), published.attempt.materialKey.entries)
        assertEquals("マップ", published.current!!.labels.getValue("Main"))
        assertEquals(
            listOf(1),
            published.current.keysByEntry
                .getValue("Main")
                .single()
                .wordStarts,
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
        assertEquals(setOf(CAMERA, WORK_MAIL), round.outcome.staleIcons)
        assertTrue(WORK !in round.next.listings)
    }

    @Test
    fun `知らせのある組は鍵が同じでも作り直し、当てた知らせを消せる。失敗した組の知らせも消せる`() {
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
        // 決定的な欠けの組を毎回読み直さないため
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
    fun `鍵のない組は再起動 1 回につき 1 回だけ作り直し、回数が取れなければ作り直さない`() {
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
        // 時刻のある組は時刻で拾うので、再起動では作り直さない
        val timed = device(WORK_MAIL to mail().copy(apkKey = null))
        assertTrue(sync(sync(IndexSnapshot.EMPTY, timed).next, timed, env = ENV.copy(bootCount = 6)).plan.isIdle)
    }

    @Test
    fun `欠けを書いた版が今の版と違えば、材料の欠けもキーの欠けも組を作り直す`() {
        val device = device(MAPS to maps(), CAMERA to maps())
        val faults = Faults(signer = mapOf(MAPS to DETERMINISTIC), keys = mapOf((CAMERA to "Main") to BUG))
        val first = sync(IndexSnapshot.EMPTY, device, faults = faults).next
        val updated = sync(first, device, env = ENV.copy(appVersion = 2))
        assertEquals(
            setOf(ReadReason.VERSION),
            updated.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertEquals(
            setOf(ReadReason.VERSION),
            updated.plan.reads
                .getValue(CAMERA)
                .reasons,
        )
        assertTrue(
            updated.next.packages.values
                .all { it.attempt.issue == null },
        )
    }

    @Test
    fun `生成器の版が変われば、欠けのない組はキーだけを作り直し、欠けのある組は作り直す。言語が変われば材料を読み直す`() {
        val device = device(MAPS to maps(), CAMERA to maps())
        val first = sync(IndexSnapshot.EMPTY, device, faults = Faults(signer = mapOf(CAMERA to DETERMINISTIC))).next
        val v8 = ENV.copy(generatorVersion = 8)
        val generator = sync(first, device, env = v8)
        assertEquals(setOf(MAPS), generator.plan.keys)
        assertEquals(
            setOf(ReadReason.DERIVED_CHANGED),
            generator.plan.reads
                .getValue(CAMERA)
                .reasons,
        )
        val state = generator.next.packages.getValue(MAPS)
        assertEquals(currentFromDevice(MAPS, maps(), v8), state.current)
        assertEquals(
            first.packages
                .getValue(MAPS)
                .attempt
                .copy(derivedKey = derivedKey(v8)),
            state.attempt,
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
    fun `失敗が続いても、一覧に残る入口は前の代わりの表示名を保つ`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps()), faults = Faults(signer = mapOf(MAPS to TRANSIENT))).next
        assertEquals(mapOf("Main" to "マップ"), first.packages.getValue(MAPS).substitutes)
        // 再試行で表示名を読む前に失敗しても、パッケージ名に落ちない
        val second = sync(first, device(MAPS to maps()), faults = Faults(entry = mapOf(MAPS to TRANSIENT))).next.packages.getValue(MAPS)
        assertEquals("マップ", second.apps().single().label)
        assertEquals(SubstituteKeys.of("マップ"), second.apps().single().keys)
    }

    @Test
    fun `失敗の間に一覧から消えて戻った入口は、その回の表示名の代わりのキーにし、前の提供元のキーを戻さない`() {
        val two = maps().copy(labels = mapOf("Main" to "マップ", "Alt" to "別"))
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to two)).next
        val stuck = Faults(signer = mapOf(MAPS to DETERMINISTIC))
        val gone = sync(first, device(MAPS to maps().copy(apkKey = "apk-2")), faults = stuck).next
        assertEquals(
            setOf("Main"),
            gone.packages
                .getValue(MAPS)
                .current!!
                .labels.keys,
        )
        val back =
            sync(
                gone,
                device(MAPS to two.copy(apkKey = "apk-3", labels = mapOf("Main" to "マップ", "Alt" to "新"))),
                faults = stuck,
            ).next
        val alt =
            back.packages
                .getValue(MAPS)
                .apps()
                .single { it.id.activityName == "Alt" }
        assertEquals("新", alt.label)
        assertEquals(SubstituteKeys.of("新"), alt.keys)
        // 残り続けた入口は前の値のまま
        assertEquals(
            first.packages
                .getValue(MAPS)
                .current!!
                .keysByEntry
                .getValue("Main"),
            back.packages
                .getValue(MAPS)
                .apps()
                .single {
                    it.id.activityName ==
                        "Main"
                }.keys,
        )
    }

    @Test
    fun `材料とジャンルの結果は、頼んだ計画の頼みに答えたものだけを受ける`() {
        val device = device(MAPS to maps())
        val first = sync(IndexSnapshot.EMPTY, device).next
        val ja = IndexRules.plan(first, device.observe(), ENV.copy(locale = "ja-JP-x"))
        val en = IndexRules.plan(first, device.observe(), ENV.copy(locale = "en-US"))
        val seen = KeyObservation(maps().apkKey, maps().updatedAt)
        val jaReads = readsOf(ja, MaterialRead.Read(maps().labels, seen, seen, maps().localized, maps().signers))
        val prepared = IndexRules.prepare(ja, jaReads, TODAY)
        assertThrows<IllegalArgumentException> { IndexRules.prepare(en, jaReads, TODAY) }
        val built = prepared.jobs.associateWith { KeyOutcome.Built(fakeKeys(it.entry, ENV)) }
        assertThrows<IllegalArgumentException> { IndexRules.apply(prepared, noGenres(en), built) }
        assertThrows<IllegalArgumentException> {
            IndexRules.apply(
                prepared,
                GenreResult.Found(ja.genres, mapOf(appId(PackageKey(0, "other"), "Main") to listOf(GenreId("maps")))),
                built,
            )
        }
        IndexRules.apply(prepared, noGenres(ja), built)
    }

    @Test
    fun `アーカイブ済みの組の頼みに補助の材料の入った結果が来たら止める`() {
        val archived = maps().copy(archived = true)
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, device(MAPS to archived).observe(), ENV)
        assertFalse(plan.reads.getValue(MAPS).auxiliary)
        val seen = KeyObservation(archived.apkKey, archived.updatedAt)
        assertThrows<IllegalArgumentException> {
            IndexRules.prepare(plan, readsOf(plan, MaterialRead.Read(archived.labels, seen, seen, signerDns = listOf("O=X"))), TODAY)
        }
        assertThrows<IllegalArgumentException> {
            IndexRules.prepare(
                plan,
                readsOf(plan, MaterialRead.Read(archived.labels, seen, seen, localized = mapOf("Main" to listOf("Maps")))),
                TODAY,
            )
        }
    }

    @Test
    fun `読み取りの結果は、読めなかった結果も、計画が出したプロファイルの頼みに答えたものだけを受ける`() {
        val first = sync(IndexSnapshot.EMPTY, device(WORK_MAIL to mail(), PackageKey(WORK, "gone") to mail())).next
        val updated = device(WORK_MAIL to mail().copy(apkKey = "apk-2"))
        val a = IndexRules.plan(first, updated.observe(), ENV)
        val b = IndexRules.plan(first, updated.observe(), ENV)
        val failedForA = listOf(ProfileReads(a.profileReads.getValue(WORK), ListingStatus.UNAVAILABLE))
        IndexRules.prepare(a, failedForA, TODAY)
        // 別の計画の「読めなかった」を受けると、b で読めていても更新と削除を見送ってしまう
        assertThrows<IllegalArgumentException> { IndexRules.prepare(b, failedForA, TODAY) }
        // 材料を頼まなかったプロファイルの結果も受けない（頼みのない計画には結果を渡せない）
        val idle = IndexRules.plan(first, device(WORK_MAIL to mail()).observe(), ENV)
        assertTrue(idle.profileReads.isEmpty())
        assertEquals(setOf(PackageKey(WORK, "gone")), idle.removals)
        assertThrows<IllegalArgumentException> { IndexRules.prepare(idle, failedForA, TODAY) }
        // 同じ頼みの結果を 2 つ渡しても止める
        assertThrows<IllegalArgumentException> { IndexRules.prepare(a, failedForA + failedForA, TODAY) }
    }

    @Test
    fun `プロファイルの頼みの中の組の結果も、その計画の頼みのものだけを受ける`() {
        val device = device(MAPS to maps())
        val a = IndexRules.plan(IndexSnapshot.EMPTY, device.observe(), ENV)
        val b = IndexRules.plan(IndexSnapshot.EMPTY, device.observe(), ENV)
        val seen = KeyObservation(maps().apkKey, maps().updatedAt)
        val read = MaterialRead.Read(maps().labels, seen, seen)
        // b のプロファイルの頼みに、a の組の頼みの結果を入れる
        val mixed = listOf(ProfileReads(b.profileReads.getValue(0), ListingStatus.COMPLETE, mapOf(a.reads.getValue(MAPS) to read)))
        assertThrows<IllegalArgumentException> { IndexRules.prepare(b, mixed, TODAY) }
        IndexRules.prepare(b, readsOf(b, read), TODAY)
    }

    @Test
    fun `頼みが 2 つの計画に、片方の頼みの結果を 2 つ渡すと止める`() {
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, device(MAPS to maps(), WORK_MAIL to mail()).observe(), ENV)
        assertEquals(setOf(0L, WORK), plan.profileReads.keys)
        val unavailable = ProfileReads(plan.profileReads.getValue(0), ListingStatus.UNAVAILABLE)
        assertThrows<IllegalArgumentException> { IndexRules.prepare(plan, listOf(unavailable, unavailable), TODAY) }
    }

    @Test
    fun `読めたプロファイルの結果に、頼んだ組の結果が欠けていれば止める`() {
        val plan = IndexRules.plan(IndexSnapshot.EMPTY, device(MAPS to maps(), CAMERA to maps()).observe(), ENV)
        val request = plan.profileReads.getValue(0)
        assertEquals(2, request.jobs.size)
        val seen = KeyObservation(maps().apkKey, maps().updatedAt)
        val partial =
            listOf(
                ProfileReads(
                    request,
                    ListingStatus.COMPLETE,
                    mapOf(plan.reads.getValue(MAPS) to MaterialRead.Read(maps().labels, seen, seen)),
                ),
            )
        assertThrows<IllegalArgumentException> { IndexRules.prepare(plan, partial, TODAY) }
    }

    @Test
    fun `読み取りが段で失敗しても、前後の鍵が動いていれば CHANGED_DURING_READ として再試行する`() {
        val first = sync(IndexSnapshot.EMPTY, device(MAPS to maps())).next
        val updated = maps().copy(apkKey = "apk-2")
        val plan = IndexRules.plan(first, device(MAPS to updated).observe(), ENV)
        val before = KeyObservation("apk-2", updated.updatedAt)
        // 読んだ後の鍵が取れず、同じ回に署名が決定的に失敗した
        val read = MaterialRead.Failed(Stage.SIGNER, DETERMINISTIC, before, KeyObservation(null, updated.updatedAt), updated.labels)
        val prepared = IndexRules.prepare(plan, readsOf(plan, read), TODAY)
        val state =
            IndexRules
                .apply(prepared, noGenres(plan), emptyMap())
                .snapshot.packages
                .getValue(MAPS)
        assertEquals(Failure(Failure.CHANGED_DURING_READ, IssueCategory.TRANSIENT), state.attempt.issue!!.failure)
        assertTrue(state.attempt.retry)
        assertEquals(first.packages.getValue(MAPS).current, state.current)
    }

    @Test
    fun `鍵が同じ組の知らせは、読み終えた後にプロファイルが読めなくなれば残り、読めるようになった同期で作り直して消す`() {
        val both = device(WORK_MAIL to mail())
        val first = sync(IndexSnapshot.EMPTY, both).next
        val notice = Notice(7, WORK_MAIL.packageName, WORK, 10)
        val flaky = both.copy(profiles = both.profiles + (WORK to both.profiles.getValue(WORK).copy(readsComplete = false)))
        val lost = sync(first, flaky, notices = listOf(notice))
        assertEquals(
            setOf(ReadReason.NOTICE),
            lost.plan.reads
                .getValue(WORK_MAIL)
                .reasons,
        )
        assertTrue(lost.outcome.consumableNotices.isEmpty())
        assertEquals(first.packages, lost.next.packages)
        val resumed = sync(lost.next, both, notices = listOf(notice))
        assertEquals(
            setOf(ReadReason.NOTICE),
            resumed.plan.reads
                .getValue(WORK_MAIL)
                .reasons,
        )
        assertEquals(setOf(7L), resumed.outcome.consumableNotices)
    }

    @Test
    fun `保存した写しの読み戻しは入れ子まで写し取り、検索に使う値・最後の試み・観測が合わない組を作り直す組として外す`() {
        val built = sync(IndexSnapshot.EMPTY, device(MAPS to maps(), WORK_MAIL to mail())).next
        val state = built.packages.getValue(MAPS)
        val wordStarts = mutableListOf(1)
        val keys = mutableListOf(SubstituteKeys.of("マップ").first().copy(wordStarts = wordStarts))
        val labels = mutableMapOf("Main" to "マップ")
        val source = mutableListOf(state.copy(current = state.current!!.copy(labels = labels, keysByEntry = mapOf("Main" to keys))))
        val restored = IndexSnapshot.restore(source, built.listings)
        source.clear()
        labels["Main"] = "別名"
        wordStarts += 2
        keys.clear()
        val kept = restored.packages.getValue(MAPS)
        assertEquals("マップ", kept.current!!.labels.getValue("Main"))
        assertEquals(
            listOf(1),
            kept.current.keysByEntry
                .getValue("Main")
                .single()
                .wordStarts,
        )
        assertEquals(built, IndexSnapshot.restore(built.packages.values, built.listings))

        val current = state.current
        val withAlt =
            sync(
                IndexSnapshot.EMPTY,
                device(MAPS to maps().copy(labels = mapOf("Main" to "マップ", "Alt" to "別"))),
            ).next.packages.getValue(MAPS)
        val altCurrent = withAlt.current!!
        val broken =
            listOf(
                // 成功した試みに検索に使う値がない
                state.copy(current = null, substitutes = mapOf("Main" to "マップ")),
                // 成功した試みと検索に使う値の鍵が違う
                state.copy(current = current.copy(materialKey = current.materialKey.copy(apkKey = "old"))),
                // 表示名とキーの入口が違う
                state.copy(current = current.copy(keysByEntry = emptyMap())),
                // 一覧の入口が検索に使う値にも代わりの表示名にもない
                state.copy(current = current.copy(labels = emptyMap(), keysByEntry = emptyMap())),
                // 入口が両方にある
                state.copy(substitutes = mapOf("Main" to "マップ")),
                // 成功した組の入口が代わりの表示名に落ちている
                withAlt.copy(
                    current = altCurrent.copy(labels = altCurrent.labels - "Alt", keysByEntry = altCurrent.keysByEntry - "Alt"),
                    substitutes = mapOf("Alt" to "別"),
                ),
                // 成功した組に再試行の枠が立っている
                state.copy(attempt = state.attempt.copy(retry = true)),
                // 試した鍵の観測の成分が観測と違う（APK・時刻・アーカイブ）
                state.copy(observed = state.observed.copy(apkKey = "other")),
                state.copy(observed = state.observed.copy(updatedAt = 999)),
                state.copy(observed = state.observed.copy(archived = true)),
            )
        // 合わない組は写しに入れず、次の同期でまだない組として作り直す
        for (bad in broken) {
            val restored = IndexSnapshot.restore(listOf(bad, built.packages.getValue(WORK_MAIL)), emptyMap())
            assertEquals(setOf(WORK_MAIL), restored.packages.keys)
        }
        val rebuilt = sync(IndexSnapshot.restore(listOf(broken.last()), emptyMap()), device(MAPS to maps()))
        assertEquals(
            setOf(ReadReason.MISSING),
            rebuilt.plan.reads
                .getValue(MAPS)
                .reasons,
        )
        assertThrows<IllegalArgumentException> { IndexSnapshot.restore(listOf(state, state), emptyMap()) }
    }

    @Test
    fun `代わりのキーは表示名に NFKC と正規化だけをかけ、空の表示名ならキー 0 個`() {
        assertEquals(listOf("abc"), SubstituteKeys.of("ＡＢＣ").map { it.text.value })
        assertTrue(SubstituteKeys.of("").isEmpty())
        assertTrue(IndexSnapshot.EMPTY.toSearchIndex().size == 0)
    }

    private fun readsOf(
        plan: SyncPlan,
        read: MaterialRead,
    ): List<ProfileReads> {
        val request = plan.profileReads.values.single()
        return listOf(ProfileReads(request, ListingStatus.COMPLETE, mapOf(request.jobs.single() to read)))
    }

    private fun noGenres(plan: SyncPlan): GenreResult = GenreResult.Found(plan.genres, emptyMap())

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
