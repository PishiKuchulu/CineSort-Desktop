package com.cinesort.core.domain.journal

/**
 * Item-level outcome tallies for one run, derived from its journal
 * operations (audit A.3-1..A.3-3, B.9).
 *
 * This is the single counting definition shared by the Report, History and
 * Run screens, which previously each counted differently — and all three
 * were wrong in the same ways: poster operations (`isPoster`) inflated the
 * done count past the run's total, operations were counted per-row instead
 * of per item (a retried item counted twice), and UNDONE operations fell
 * into the failed bucket, so a successfully undone run reported every file
 * as failed and kept its Retry action armed.
 *
 * Counting is per distinct item id over the item's MAIN operations only
 * (companions are part of their item; posters are tallied separately in
 * [RunOperationCounts.posters]). An item's outcome is its best recorded
 * state, in the order DONE > UNDONE > SKIPPED > FAILED: a resume attempt
 * leaves a stale FAILED row next to the DONE row that replaced it, and the
 * DONE row is the truth. Items left in non-final states count as failed
 * once the run is finished, and as in-progress while it is not.
 */
data class RunOperationCounts(
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val undone: Int = 0,
    val inProgress: Int = 0,
    /** Downloaded posters (DONE `isPoster` operations); never part of [done]. */
    val posters: Int = 0,
    /** Item ids counted in [failed] — the Report's failure list keys off this. */
    val failedItemIds: Set<String> = emptySet(),
)

fun countRunOperations(
    ops: List<OperationRecord>,
    runFinished: Boolean,
): RunOperationCounts {
    val posters = ops.count { it.isPoster && it.state == OperationState.DONE }
    val mainByItem = ops
        .filter { !it.isCompanion && !it.isPoster }
        .groupBy { it.itemId }
    var done = 0
    var failed = 0
    var skipped = 0
    var undone = 0
    var inProgress = 0
    val failedIds = mutableSetOf<String>()
    for ((itemId, itemOps) in mainByItem) {
        val states = itemOps.mapTo(HashSet()) { it.state }
        when {
            OperationState.DONE in states -> done++
            OperationState.UNDONE in states -> undone++
            OperationState.SKIPPED in states -> skipped++
            OperationState.FAILED in states -> {
                failed++
                failedIds += itemId
            }
            runFinished -> {
                failed++
                failedIds += itemId
            }
            else -> inProgress++
        }
    }
    return RunOperationCounts(
        done = done,
        failed = failed,
        skipped = skipped,
        undone = undone,
        inProgress = inProgress,
        posters = posters,
        failedItemIds = failedIds,
    )
}

/**
 * A run's journal collapsed to counting size (section-3 audit): the best
 * main-operation state per item plus the DONE poster tally. Producing it
 * needs only the distinct (item, state) pairs of the run — a few small
 * rows per item — instead of materializing every operation record, which
 * is what the Run screen's progress polling used to do once a second for
 * the whole lifetime of a run.
 */
data class RunOperationSummary(
    /** Best main-operation state per item id (see [bestStateOf]). */
    val bestStateByItem: Map<String, OperationState> = emptyMap(),
    /** Downloaded posters (DONE `isPoster` operations); never item progress. */
    val postersDone: Int = 0,
)

/**
 * Outcome priority when collapsing an item's operations to its single
 * best state: DONE > UNDONE > SKIPPED > FAILED > any non-final state.
 * Mirrors the bucket order of [countRunOperations] exactly — a stale
 * FAILED row beside the DONE row that replaced it must not flip the item
 * back to failed, and a FAILED row beside still-open rows is a failure,
 * not in-progress.
 */
fun OperationState.outcomePriority(): Int = when (this) {
    OperationState.DONE -> 4
    OperationState.UNDONE -> 3
    OperationState.SKIPPED -> 2
    OperationState.FAILED -> 1
    else -> 0
}

/** The highest-priority state among [states], or null when there are none. */
fun bestStateOf(states: Iterable<OperationState>): OperationState? =
    states.maxByOrNull { it.outcomePriority() }

/**
 * The same tallies as [countRunOperations], computed from a
 * [RunOperationSummary] instead of the full operation list. The two
 * definitions agree on every journal by construction: per item both look
 * at the same bucket order, and posters are tallied apart in both.
 */
fun countFromItemBestStates(
    summary: RunOperationSummary,
    runFinished: Boolean,
): RunOperationCounts {
    var done = 0
    var failed = 0
    var skipped = 0
    var undone = 0
    var inProgress = 0
    val failedIds = mutableSetOf<String>()
    for ((itemId, state) in summary.bestStateByItem) {
        when (state) {
            OperationState.DONE -> done++
            OperationState.UNDONE -> undone++
            OperationState.SKIPPED -> skipped++
            OperationState.FAILED -> {
                failed++
                failedIds += itemId
            }
            else -> if (runFinished) {
                failed++
                failedIds += itemId
            } else {
                inProgress++
            }
        }
    }
    return RunOperationCounts(
        done = done,
        failed = failed,
        skipped = skipped,
        undone = undone,
        inProgress = inProgress,
        posters = summary.postersDone,
        failedItemIds = failedIds,
    )
}

/**
 * Item ids a retry run may re-organize (audit A.3-3): items with a FAILED
 * or never-finished main operation — minus anything an Undo already
 * restored. An item carrying an UNDONE operation was deliberately put back
 * by the user; it is never silently re-organized by a retry, and a fully
 * undone run yields an empty set (retry is a no-op for it).
 */
fun retryableItemIds(ops: List<OperationRecord>): Set<String> {
    val mainByItem = ops
        .filter { !it.isCompanion && !it.isPoster }
        .groupBy { it.itemId }
    return mainByItem.filterValues { itemOps ->
        val states = itemOps.mapTo(HashSet()) { it.state }
        OperationState.UNDONE !in states && states.any {
            it == OperationState.FAILED || !it.isTerminal
        }
    }.keys
}
