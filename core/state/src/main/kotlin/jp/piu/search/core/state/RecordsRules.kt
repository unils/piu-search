// SPDX-License-Identifier: Apache-2.0

package jp.piu.search.core.state

import jp.piu.search.core.search.AppId
import jp.piu.search.core.search.LaunchHistory
import jp.piu.search.core.search.LearnedPair
import jp.piu.search.core.search.LearnedRecord
import jp.piu.search.core.search.LearningDecision

/** 記録の読み込みの状態。失敗を空の記録に置き換えない（空の写しから保存すると、保存済みの記録を上書きするため）。 */
public sealed interface RecordsLoad {
    /** 読み込み中。変更の頼みは読み込みを待つ（待つのは呼ぶ側）。 */
    public data object Loading : RecordsLoad

    /**
     * 読み込みに失敗した。次の頼みと前面への復帰で読み直す。
     *
     * @property cause 理由
     */
    public data class Failed(
        val cause: String,
    ) : RecordsLoad

    /**
     * 読めた。
     *
     * @property records 写し
     */
    public data class Ready(
        val records: UserRecords,
    ) : RecordsLoad
}

/**
 * 頼みを処理するその場で、書き手が OS に問い合わせたプロファイルの存在。書き手は 1 本の列の中で順に問い合わせて渡すので、
 * 古い確認が後から届くことはない（非同期に届く確認を順不同に当てると、存在する番号を削除済みにすることがあったため）。
 */
public sealed interface ProfileCheck {
    /**
     * 有効な一覧で確かめた。
     *
     * @property serials 存在するプロファイルのシリアル番号
     */
    public data class Existing(
        val serials: Set<Long>,
    ) : ProfileCheck

    /** 確かめられなかった。最後に確認した状態を保ち、確認済みの削除は忘れず、まだ確認していない番号は拒まない。 */
    public data object Unknown : ProfileCheck
}

/**
 * 記録の側が知っているプロファイル。
 *
 * @property confirmed 最後に確かめた、存在するプロファイルの集合。まだ確かめていなければ null（空の集合とは区別する）。
 *   行のない番号の消滅も覚えるため、次の確認で消えた番号を削除済みにする
 * @property removed 存在しないと確かめた番号。確かめられなかった回も忘れない
 * @property pendingPrune 記録から消すべきで、まだ消せていない番号（終わっていない整理）
 */
public data class ProfileKnowledge(
    val confirmed: Set<Long>? = null,
    val removed: Set<Long> = emptySet(),
    val pendingPrune: Set<Long> = emptySet(),
) {
    /**
     * [serial] への書き込みを受けるか。拒むのは削除を確かめた番号だけ。
     * 確かめられなかった間と、最後に確認した集合にない新しい番号（新しいプロファイルかもしれない）は受ける。
     */
    public fun accepts(serial: Long): Boolean = serial !in removed
}

/**
 * 救済の知らせ 1 つ（「「おかね」で PayPay を出すようにしました［取り消す］」）。
 *
 * @property id 知らせの識別子。取り消すときに照合し、同じ内容の別の保存で作った知らせと取り違えない
 * @property pair 救済で記録した組
 * @property label 選んだ時点で固定したアプリの表示名
 * @property selection 同じセッションの選択。取り消しても選択の分は残す
 * @property at 記録した時刻
 */
public data class RescueNotice(
    val id: Long,
    val pair: LearnedPair,
    val label: String,
    val selection: LearnedPair?,
    val at: Long,
)

/**
 * 取り消しの材料 1 つ。
 *
 * @property notice 知らせ
 * @property before 救済を記録する前の記録。なければ null
 */
public data class UndoMaterial(
    val notice: RescueNotice,
    val before: LearnedRecord?,
)

/**
 * 記録の側の状態の全体（作った後は変えない）。知らせと取り消し材料はメモリだけに持つ。
 *
 * @property load 読み込みの状態と写し
 * @property profiles 知っているプロファイル
 * @property notices まだ出し終えていない知らせ
 * @property undo 知らせの識別子 → まだ有効な取り消し材料
 * @property nextNoticeId 次の知らせの識別子
 * @property storedSerials DB の表に行のあるプロファイルの番号（表から直に数えた値）。写しに見えない行（長すぎる語の学習など）の番号も含み、
 *   整理の対象を決めるのに使う
 */
public data class RecordsState(
    val load: RecordsLoad = RecordsLoad.Loading,
    val profiles: ProfileKnowledge = ProfileKnowledge(),
    val notices: List<RescueNotice> = emptyList(),
    val undo: Map<Long, UndoMaterial> = emptyMap(),
    val nextNoticeId: Long = 1,
    val storedSerials: Set<Long> = emptySet(),
) {
    /** 読めていれば写し、そうでなければ null。 */
    public val records: UserRecords? get() = (load as? RecordsLoad.Ready)?.records

    /** 知らせ [id] の取り消しがまだ有効か。 */
    public fun canUndo(id: Long): Boolean = id in undo
}

/** 記録の変更の頼み。 */
public sealed interface RecordsRequest {
    /**
     * アプリを開いた: 学習の判定と起動の記録を 1 つの頼みにする（片方だけが保存されることをなくすため）。
     *
     * @property decision 学習の判定
     * @property launched 起動の記録に数えるアプリ。数えないなら null
     * @property at 時刻
     * @property labels 救済の組 → 選んだ時点で固定した表示名。ない組はパッケージ名を出す
     */
    public data class Open(
        val decision: LearningDecision,
        val launched: AppId?,
        val at: Long,
        val labels: Map<LearnedPair, String> = emptyMap(),
    ) : RecordsRequest

    /**
     * 「この語では出さない」の印を付ける。
     *
     * @property pair 組
     * @property at 時刻
     */
    public data class Suppress(
        val pair: LearnedPair,
        val at: Long,
    ) : RecordsRequest

    /**
     * 救済を取り消す。
     *
     * @property noticeId 知らせの識別子
     */
    public data class Undo(
        val noticeId: Long,
    ) : RecordsRequest

    /**
     * 組 1 つの学習を消す。
     *
     * @property pair 組
     */
    public data class Remove(
        val pair: LearnedPair,
    ) : RecordsRequest

    /**
     * 全消去。表の全行を消す。
     *
     * @property learned 学習を消すか
     * @property launches 起動の記録を消すか
     */
    public data class Clear(
        val learned: Boolean = true,
        val launches: Boolean = true,
    ) : RecordsRequest

    /**
     * 書き出した学習を読み込んで合わせる。ファイルの読み取りと解析は呼ぶ側が先に済ませる（列を長く止めないため）。
     *
     * @property records 解析した記録
     * @property at 時刻
     */
    public data class Import(
        val records: List<LearnedRecord>,
        val at: Long,
    ) : RecordsRequest

    /**
     * 最後に使ってから時間のたった学習を忘れる。
     *
     * @property now 時刻
     */
    public data class Forget(
        val now: Long,
    ) : RecordsRequest
}

/** 頼みの結果。 */
public sealed interface RecordsResult {
    /**
     * 成功した（書くものがなく、何も書かなかった場合を含む）。
     *
     * @property notices 新しく作った救済の知らせ
     * @property skipped 読み込みで、消えたプロファイルのために受けなかった記録の数
     */
    public data class Success(
        val notices: List<RescueNotice> = emptyList(),
        val skipped: Int = 0,
    ) : RecordsResult

    /**
     * 失敗した。写しは変わらない。
     *
     * @property reason 理由
     */
    public data class Failure(
        val reason: RecordsFailure,
    ) : RecordsResult
}

/** 頼みの失敗の理由。 */
public enum class RecordsFailure {
    /** 記録を読めていない。計算も書き込みもしていない。 */
    NOT_LOADED,

    /** DB に書けなかった。 */
    WRITE_FAILED,
}

/**
 * 頼み 1 つで計算した変更。[write] を 1 つのトランザクションで書けたときだけ [next] を当て、書けなければ [base] に戻す（[RecordsRules.commit]）。
 *
 * @property next 書けたときの次の状態
 * @property write 書く中身。書くものがなければ null で、[next] をそのまま当てる
 * @property result 書けたときの結果。読めていないときは失敗で、[next] は [base]
 * @property base 頼みの前の状態に、その場のプロファイルの確認だけを当てたもの。書けなくても確認した削除を忘れないため、こちらに戻す
 */
public data class RecordsChange(
    val next: RecordsState,
    val write: RecordsWrite?,
    val result: RecordsResult,
    val base: RecordsState,
)

/**
 * 記録の規則。学習の積み方は `LearnedMemory` の純粋な操作に任せ、ここでは読み込みの状態・プロファイル・取り消しの材料と、
 * DB に書く中身を決める。どの関数も状態を変えず、新しい状態を返す。
 *
 * 書き手は 1 つずつ、OS にプロファイルを問い合わせ、[change] で計算し、[RecordsChange.write] を DB に書き、[commit] で当てる。
 * 書けなければ記録を変えない。画面に出した記録が保存されていない状態を作らないため。
 */
public object RecordsRules {
    /**
     * 読み込みが成功した。その場の確認 [check] を当て、消えたプロファイルの記録を整理の対象にする。
     *
     * @param storedSerials 同じ読み込みのトランザクションで、両方の表から直に数えた行のある番号。写しに見えない行の番号も整理するため、
     *   写しの番号と別に必ず渡す（写しの番号で代えると、長すぎる語の学習の行だけを持つ番号を見落とす）
     * @param check 読み込みの時点で問い合わせたプロファイルの存在
     */
    public fun loaded(
        state: RecordsState,
        records: UserRecords,
        storedSerials: Set<Long>,
        check: ProfileCheck,
    ): RecordsState {
        val stored = storedSerials.toSet()
        val ready = state.copy(load = RecordsLoad.Ready(records), storedSerials = stored)
        // 削除を確かめた番号の行が残っていれば、整理し直す（整理の後に読み戻した行も残さない）
        val leftover = (records.serials + stored).filter { it in state.profiles.removed }
        return checked(ready.copy(profiles = ready.profiles.copy(pendingPrune = ready.profiles.pendingPrune + leftover)), check)
    }

    /** 読み込みが失敗した。前に読めた写しがあっても、ここでは失敗として持つ（保存先の内容と合っているか分からないため）。 */
    public fun loadFailed(
        state: RecordsState,
        cause: String,
    ): RecordsState = state.copy(load = RecordsLoad.Failed(cause))

    /**
     * 頼みのほか（前面への復帰・プロファイルの変化の知らせ）で、その場で問い合わせた存在 [check] を当てる。
     * 有効な一覧なら、前に確認した集合・記録・表の行にあって今ない番号を、行がなくても削除済みとし、
     * 取り消しの材料と知らせを無効にして、整理の対象にする。確かめられなければ何も変えない。
     */
    public fun checked(
        state: RecordsState,
        check: ProfileCheck,
    ): RecordsState = checked(state, check, emptySet())

    // 頼みの番号 [asked] も既知に入れる。今ないと分かれば、行がなくても削除済みにして、その頼みの記録を拒む
    private fun checked(
        state: RecordsState,
        check: ProfileCheck,
        asked: Set<Long>,
    ): RecordsState {
        if (check !is ProfileCheck.Existing) return state
        // 呼ぶ側の集合を写し取る。後で呼ぶ側が中身を変えても、確認した事実（行のない番号が存在したこと）を失わないため
        val existing = check.serials.toSet()
        val known = state.profiles.confirmed.orEmpty() + state.records?.serials.orEmpty() + state.storedSerials + asked
        return state.copy(profiles = state.profiles.copy(confirmed = existing)).withRemoved(known - existing)
    }

    private fun serialsOf(request: RecordsRequest): Set<Long> =
        when (request) {
            is RecordsRequest.Open -> {
                val decision = request.decision
                (listOfNotNull(decision.selection, decision.used) + decision.rescues).mapTo(HashSet()) { it.app.profileSerial } +
                    listOfNotNull(request.launched?.profileSerial)
            }

            is RecordsRequest.Suppress -> {
                setOf(request.pair.app.profileSerial)
            }

            is RecordsRequest.Import -> {
                request.records.mapTo(HashSet()) { it.app.profileSerial }
            }

            is RecordsRequest.Undo, is RecordsRequest.Remove, is RecordsRequest.Clear, is RecordsRequest.Forget -> {
                emptySet()
            }
        }

    /**
     * 終わっていない整理の変更。読めていて、整理の対象があるときだけ返す。
     * 書き手は頼みのたびと前面への復帰のたびに試す。プロファイルの変化の知らせだけに頼ると、再試行の機会がないため。
     */
    public fun pruneChange(state: RecordsState): RecordsChange? {
        val records = state.records ?: return null
        val targets = state.profiles.pendingPrune
        if (targets.isEmpty()) return null
        val next =
            state.copy(
                load = RecordsLoad.Ready(records.without(targets)),
                profiles = state.profiles.copy(pendingPrune = emptySet()),
                storedSerials = state.storedSerials - targets,
            )
        return RecordsChange(next, RecordsWrite(deleteSerials = targets), RecordsResult.Success(), state)
    }

    /**
     * 頼み [request] の変更を計算する。先にその場の確認 [check] を当て、頼みの番号が今ないと分かれば拒む。
     * 読めていなければ、計算も書き込みもせずに [RecordsFailure.NOT_LOADED] の結果を返す（確認は当てる）。
     *
     * @param check 頼みを処理するその場で問い合わせたプロファイルの存在
     */
    public fun change(
        state: RecordsState,
        request: RecordsRequest,
        check: ProfileCheck,
    ): RecordsChange {
        val base = checked(state, check, serialsOf(request))
        val records = base.records ?: return RecordsChange(base, null, RecordsResult.Failure(RecordsFailure.NOT_LOADED), base)
        return compute(base, records, request)
    }

    /** [change] を当てた次の状態。書くものがあって [written] が偽（書けなかった）なら、確認だけを当てた状態に戻す。 */
    public fun commit(
        change: RecordsChange,
        written: Boolean,
    ): RecordsState = if (written || change.write == null) change.next else change.base

    /** [change] を当てたときに頼み手へ返す結果。 */
    public fun resultOf(
        change: RecordsChange,
        written: Boolean,
    ): RecordsResult = if (change.write != null && !written) RecordsResult.Failure(RecordsFailure.WRITE_FAILED) else change.result

    /** 知らせ [id] を出し終えた。取り消しの材料は保つ（取り消しの保存は別の操作のため）。 */
    public fun consumeNotice(
        state: RecordsState,
        id: Long,
    ): RecordsState = state.copy(notices = state.notices.filterNot { it.id == id })

    private fun compute(
        state: RecordsState,
        records: UserRecords,
        request: RecordsRequest,
    ): RecordsChange {
        val accepts = state.profiles::accepts
        return when (request) {
            is RecordsRequest.Open -> {
                open(state, records, request)
            }

            is RecordsRequest.Suppress -> {
                val learned =
                    if (accepts(
                            request.pair.app.profileSerial,
                        )
                    ) {
                        records.learned.suppressed(request.pair, request.at)
                    } else {
                        records.learned
                    }
                saved(state, records, UserRecords(learned, records.launches))
            }

            is RecordsRequest.Undo -> {
                undo(state, records, request.noticeId)
            }

            is RecordsRequest.Remove -> {
                saved(state, records, UserRecords(records.learned.removed(request.pair), records.launches))
            }

            is RecordsRequest.Clear -> {
                clear(state, records, request)
            }

            is RecordsRequest.Import -> {
                val accepted = request.records.filter { accepts(it.app.profileSerial) }
                saved(
                    state,
                    records,
                    UserRecords(records.learned.merged(accepted, request.at), records.launches),
                    skipped = request.records.size - accepted.size,
                )
            }

            is RecordsRequest.Forget -> {
                saved(state, records, UserRecords(records.learned.forgotten(request.now), records.launches))
            }
        }
    }

    private fun open(
        state: RecordsState,
        records: UserRecords,
        request: RecordsRequest.Open,
    ): RecordsChange {
        val accepts = state.profiles::accepts
        val decision = request.decision
        // 削除を確認した番号への遅い記録は受けない
        val accepted =
            decision.copy(
                selection = decision.selection?.takeIf { accepts(it.app.profileSerial) },
                rescues = decision.rescues.filter { accepts(it.app.profileSerial) },
                used = decision.used?.takeIf { accepts(it.app.profileSerial) },
            )
        val launched = request.launched?.takeIf { accepts(it.profileSerial) }
        val next =
            UserRecords(
                records.learned.recorded(accepted, request.at),
                launched?.let { records.launches.recorded(it, request.at) } ?: records.launches,
            )
        var id = state.nextNoticeId
        val made =
            accepted.rescues.map {
                RescueNotice(id++, it, request.labels[it] ?: it.app.packageName, accepted.selection, request.at)
            }
        // 回数が上限で値が変わらない組も、後の保存を古い取り消しで巻き戻さないよう無効にする
        val recorded = (accepted.rescues + listOfNotNull(accepted.selection, accepted.used)).toSet()
        val base = saved(state, records, next)
        val invalidated = base.next.invalidate { it.pair in recorded }
        val withNotices =
            invalidated.copy(
                notices = invalidated.notices + made,
                undo = invalidated.undo + made.associate { it.id to UndoMaterial(it, records.learned.records[it.pair]) },
                nextNoticeId = id,
            )
        return base.copy(next = withNotices, result = RecordsResult.Success(made))
    }

    // 知らせの識別子で照合する。取り消しの材料が無効なら、何も変えずに成功を返す（その間に同じ組を保存したので、戻すと新しい記録を失う）
    private fun undo(
        state: RecordsState,
        records: UserRecords,
        noticeId: Long,
    ): RecordsChange {
        val material = state.undo[noticeId] ?: return RecordsChange(state, null, RecordsResult.Success(), state)
        val notice = material.notice
        val learned = records.learned.rescueUndone(notice.pair, material.before, notice.selection, notice.at)
        val base = saved(state, records, UserRecords(learned, records.launches))
        return base.copy(next = base.next.invalidate { it.id == noticeId })
    }

    private fun clear(
        state: RecordsState,
        records: UserRecords,
        request: RecordsRequest.Clear,
    ): RecordsChange {
        if (!request.learned && !request.launches) return RecordsChange(state, null, RecordsResult.Success(), state)
        val next =
            UserRecords(
                if (request.learned) records.learned.cleared() else records.learned,
                if (request.launches) LaunchHistory.EMPTY else records.launches,
            )
        // 差分ではなく全行を消す。旧版などの写しに見えない行も残さないため
        val write = RecordsWrite(clearLearned = request.learned, clearLaunches = request.launches)
        // 片方の表だけを消したときは、残る表の行の番号が分からないので、表の番号を保つ（整理で余分に消しても害はない）
        val stored = if (request.learned && request.launches) emptySet() else state.storedSerials
        val ready = state.copy(load = RecordsLoad.Ready(next), storedSerials = stored)
        return RecordsChange(if (request.learned) ready.invalidate { true } else ready, write, RecordsResult.Success(), state)
    }

    // 差分を書く変更。書くものがなければ書き込みをせず、同じ状態を返す。値が変わった組の取り消しの材料を無効にする
    private fun saved(
        state: RecordsState,
        old: UserRecords,
        next: UserRecords,
        skipped: Int = 0,
    ): RecordsChange {
        val write = RecordsWrite.diff(old, next)
        if (write.isEmpty) return RecordsChange(state, null, RecordsResult.Success(skipped = skipped), state)
        // 書いた番号は表に行がある。読み込みに失敗して写しがない間の確認でも、消えたプロファイルの行を見落とさないため
        val changed =
            state
                .copy(load = RecordsLoad.Ready(next), storedSerials = state.storedSerials + next.serials)
                .invalidate { old.learned.records[it.pair] != next.learned.records[it.pair] }
        return RecordsChange(changed, write, RecordsResult.Success(skipped = skipped), state)
    }

    private fun RecordsState.invalidate(affected: (RescueNotice) -> Boolean): RecordsState {
        val removed = undo.values.filter { affected(it.notice) }.mapTo(HashSet()) { it.notice.id }
        if (removed.isEmpty()) return this
        return copy(notices = notices.filterNot { it.id in removed }, undo = undo - removed)
    }

    // 削除を確認した番号を覚え、取り消しの材料と知らせを無効にし、整理の対象に足す。削除より前に作った取り消しで、
    // 消えたプロファイルの記録を書き戻さないため
    private fun RecordsState.withRemoved(serials: Set<Long>): RecordsState {
        val fresh = serials - profiles.removed
        if (fresh.isEmpty()) return this
        val gone = { notice: RescueNotice -> notice.pair.app.profileSerial in fresh || notice.selection?.app?.profileSerial in fresh }
        return copy(
            profiles = profiles.copy(removed = profiles.removed + fresh, pendingPrune = profiles.pendingPrune + fresh),
        ).invalidate(gone)
    }
}
