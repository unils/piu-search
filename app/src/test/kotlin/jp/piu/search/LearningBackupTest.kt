// SPDX-License-Identifier: Apache-2.0

package jp.piu.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LearnedMemory
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.text.NormalizedText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// 学習の書き出しの書式（M4-03）。org.json は Android の部品なので Robolectric で動かす
@RunWith(AndroidJUnit4::class)
class LearningBackupTest {
    @Test
    fun encodedRecordsDecodeToTheSameRecords() {
        val text = LearningBackup.encode(MEMORY, PROFILES, NOW)
        val decoded = LearningBackup.decode(text, PROFILES) as BackupReadResult.Success
        assertEquals(MEMORY.records.values.toSet(), decoded.records.toSet())
        assertEquals(0, decoded.skippedCount)

        val root = JSONObject(text)
        assertEquals(LearningBackup.FORMAT, root.getString("format"))
        assertEquals(1, root.getInt("version"))
        // 書くのは学習の記録の項目だけ（打った文字の列は持たない）
        val keys =
            root
                .getJSONArray("records")
                .getJSONObject(0)
                .keys()
                .asSequence()
                .toSet()
        assertEquals(
            setOf("query", "package", "activity", "profile", "profileSerial", "count", "lastUsedAt", "rescued", "suppressed"),
            keys,
        )
    }

    @Test
    fun profilesAreMappedToTheSerialsOfThisDevice() {
        val text = LearningBackup.encode(MEMORY, PROFILES, NOW)
        // 別の端末: 個人用は 10、仕事用は 11
        val decoded = LearningBackup.decode(text, BackupProfiles(own = 10, work = setOf(11))) as BackupReadResult.Success
        assertEquals(setOf(10L, 11L), decoded.records.map { it.app.profileSerial }.toSet())
        assertEquals(
            11L,
            decoded.records
                .single { it.app.packageName == "io.example.work" }
                .app.profileSerial,
        )
    }

    @Test
    fun unmappedWorkRecordsAreDiscardedEvenIfTheOldSerialCollides() {
        val text = LearningBackup.encode(MEMORY, PROFILES, NOW)
        for (work in listOf(emptySet(), setOf(11L, 12L))) {
            val decoded = LearningBackup.decode(text, BackupProfiles(own = WORK_SERIAL, work = work)) as BackupReadResult.Success
            assertEquals(2, decoded.records.size)
            assertEquals(1, decoded.skippedCount)
            assertEquals(setOf(WORK_SERIAL), decoded.records.map { it.app.profileSerial }.toSet())
        }
    }

    @Test
    fun deeplyNestedJsonIsRejectedWithoutOverflowingTheStack() {
        for ((open, close) in listOf("[" to "]", "{\"nested\":" to "}")) {
            val nested = open.repeat(20_000) + "0" + close.repeat(20_000)
            val text = """{"format":"${LearningBackup.FORMAT}","version":1,"records":[],"extra":$nested}"""
            assertEquals(BackupReadResult.Failure(BackupFailure.INVALID), LearningBackup.decode(text, PROFILES))
        }
    }

    @Test
    fun queriesLongerThan64CodePointsAreDiscardedAfterNormalization() {
        for (query in listOf("あ".repeat(65), "𠮷".repeat(65), "㍿".repeat(17))) {
            val decoded = LearningBackup.decode(recordJson(query = JSONObject.quote(query)), PROFILES) as BackupReadResult.Success
            assertEquals(listOf("ちず"), decoded.records.map { it.query.value })
            assertEquals(1, decoded.skippedCount)
        }
        for (query in listOf("あ".repeat(64), "𠮷".repeat(64), "ア・".repeat(64))) {
            val decoded = LearningBackup.decode(recordJson(query = JSONObject.quote(query)), PROFILES) as BackupReadResult.Success
            assertEquals(2, decoded.records.size)
            assertEquals(0, decoded.skippedCount)
        }
    }

    @Test
    fun queriesAreNormalizedAgainWhenRead() {
        val text = recordJson(query = "\"オカネ\"")
        val decoded = LearningBackup.decode(text, PROFILES) as BackupReadResult.Success
        assertEquals(listOf("ちず", "おかね"), decoded.records.map { it.query.value })
    }

    @Test
    fun brokenFilesAreRejectedAsAWhole() {
        val broken =
            listOf(
                "",
                "{",
                "[]",
                "not json",
                // ほかの JSON
                """{"records": []}""",
                """{"format": "other", "version": 1, "records": []}""",
                """{"format": "${LearningBackup.FORMAT}", "version": 0, "records": []}""",
                """{"format": "${LearningBackup.FORMAT}", "version": "1", "records": []}""",
                """{"format": "${LearningBackup.FORMAT}", "version": 1}""",
                // 1 件だけ壊れていても、全体を読まない
                recordJson(query = "\"\""),
                recordJson(query = "1"),
                recordJson(count = "\"2\""),
                recordJson(count = "-1"),
                recordJson(count = "2.5"),
                recordJson(profile = "\"guest\""),
                recordJson(rescued = "\"true\""),
                recordJson(packageName = "null"),
            )
        for (text in broken) {
            assertEquals(text, BackupReadResult.Failure(BackupFailure.INVALID), LearningBackup.decode(text, PROFILES))
        }
    }

    @Test
    fun newerVersionsAreNotRead() {
        val text = """{"format": "${LearningBackup.FORMAT}", "version": 2, "records": [{"unknown": true}]}"""
        assertEquals(BackupReadResult.Failure(BackupFailure.NEWER_VERSION), LearningBackup.decode(text, PROFILES))
    }

    @Test
    fun depthLimitIncludesUnknownFieldsButNotBracketsOrEscapedQuotesInStrings() {
        val base = JSONObject(recordJson())
        for (depth in listOf(8, 9)) {
            val nested = "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1)
            val text = base.toString().dropLast(1) + ",\"extra\":" + nested + "}"
            val decoded = LearningBackup.decode(text, PROFILES)
            if (depth == 8) {
                assertEquals(2, (decoded as BackupReadResult.Success).records.size)
            } else {
                assertEquals(BackupReadResult.Failure(BackupFailure.INVALID), decoded)
            }
        }
        base.put("extra", "[{}]\"\\".repeat(100))
        assertEquals(2, (LearningBackup.decode(base.toString(), PROFILES) as BackupReadResult.Success).records.size)
    }

    @Test
    fun invalidRecordsStillRejectTheWholeFileEvenWhenTheyWouldBeSkipped() {
        for (text in listOf(
            recordJson(query = JSONObject.quote("あ".repeat(65)), count = "-1"),
            recordJson(profile = "\"work\"", rescued = "1"),
        )) {
            assertEquals(BackupReadResult.Failure(BackupFailure.INVALID), LearningBackup.decode(text, BackupProfiles(0, emptySet())))
        }
    }

    @Test
    fun aRecordRejectedForBothReasonsIsCountedOnce() {
        val decoded =
            LearningBackup.decode(
                recordJson(query = JSONObject.quote("あ".repeat(65)), profile = "\"work\""),
                BackupProfiles(0, emptySet()),
            ) as BackupReadResult.Success
        assertEquals(1, decoded.records.size)
        assertEquals(1, decoded.skippedCount)
    }

    @Test
    fun jsonExtensionsCannotHideDeepNestingFromThePrecheck() {
        val nested = "[".repeat(20_000) + "0" + "]".repeat(20_000)
        for (value in listOf("/* \" */$nested", "unquoted \",$nested", "'\"',$nested", "# \"\n$nested")) {
            val text = """{"format":"${LearningBackup.FORMAT}","version":1,"records":[],"extra":$value}"""
            assertEquals(BackupReadResult.Failure(BackupFailure.INVALID), LearningBackup.decode(text, PROFILES))
        }
    }

    private fun recordJson(
        query: String = "\"おかね\"",
        packageName: String = "\"io.example.transit\"",
        profile: String = "\"personal\"",
        count: String = "2",
        rescued: String = "true",
    ): String =
        """
        {"format": "${LearningBackup.FORMAT}", "version": 1, "exportedAt": $NOW, "records": [
          {"query": "ちず", "package": "io.example.maps", "activity": "Main", "profile": "personal", "profileSerial": 0,
           "count": 1, "lastUsedAt": $NOW, "rescued": false, "suppressed": false},
          {"query": $query, "package": $packageName, "activity": "Main", "profile": $profile, "profileSerial": 0,
           "count": $count, "lastUsedAt": $NOW, "rescued": $rescued, "suppressed": false}
        ]}
        """.trimIndent()

    private companion object {
        const val NOW = 1_793_232_000_000L
        const val WORK_SERIAL = 7L
        val PROFILES = BackupProfiles(own = 0, work = setOf(WORK_SERIAL))
        val MEMORY =
            LearnedMemory(
                listOf(
                    LearnedRecord(NormalizedText("おかね"), AppId("io.example.transit", "Main", 0), 2, NOW, rescued = true),
                    LearnedRecord(
                        NormalizedText("ちず"),
                        AppId("io.example.maps", "Main", 0),
                        0,
                        NOW - 1,
                        rescued = false,
                        suppressed = true,
                    ),
                    LearnedRecord(NormalizedText("きんたい"), AppId("io.example.work", "Main", WORK_SERIAL), 5, NOW - 2, rescued = true),
                ),
            )
    }
}
