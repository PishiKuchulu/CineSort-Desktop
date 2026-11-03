package com.cinesort.desktop.data.session

import com.cinesort.core.domain.execute.ExecutionProgress
import com.cinesort.core.domain.execute.ExecutionReport
import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.execute.UndoReport
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.JournalStore
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.planner.DestinationSnapshot
import com.cinesort.core.domain.planner.OrganizePlanner
import com.cinesort.core.domain.planner.PlanInput
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.scan.ScanDisposition
import com.cinesort.core.domain.scan.ScanRules
import com.cinesort.core.domain.scan.ScanSettings
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.parser.NormalizedKey
import com.cinesort.core.parser.ParseResult
import com.cinesort.desktop.data.metadata.ApiKeys
import com.cinesort.desktop.data.metadata.DesktopMetadataService
import com.cinesort.desktop.data.metadata.MetadataQuery
import com.cinesort.core.parser.AnchorMediaNameParser
import com.cinesort.core.parser.PathContext
import com.cinesort.core.parser.TitleCaseMode
import java.nio.file.Path
import java.util.Calendar
import java.util.UUID

/** The outcome of one scan+plan pass over the chosen sources. */
data class SessionPlan(
    val items: List<PlannedItem>,
    val videosFound: Int,
    val scannedEntries: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val issueSamples: List<String>,
    val destinationName: String,
)

/**
 * The desktop organizing pipeline, mirroring the Android session's
 * proven sequence without its UI state: scan each source through the
 * gateway, classify with the shared scan rules, parse with the anchor
 * parser, snapshot the destination, and run the pure planner — so the
 * preview is exactly what execution will do.
 *
 * Metadata enrichment (TMDB/OMDb) layers on top in Phase 3 by feeding
 * `metadataByKey` into a re-plan; the core pipeline needs no network.
 */
class DesktopOrganizeSession(
    private val gateway: StorageGateway,
    private val journal: JournalStore,
    private val metadataService: DesktopMetadataService? = null,
) {
    private val planner = OrganizePlanner()

    suspend fun scanAndPlan(
        sources: List<Path>,
        destination: Path,
        scanSettings: ScanSettings = ScanSettings(),
        naming: NamingOptions = NamingOptions(),
        extraNoiseWords: Set<String> = emptySet(),
        titleCaseMode: TitleCaseMode = TitleCaseMode.SMART,
        apiKeys: ApiKeys = ApiKeys(),
        now: Long = System.currentTimeMillis(),
    ): SessionPlan {
        val destinationRef = destination.toDirRef()
        val exclude = if (sources.any { destination.startsWith(it) }) setOf(destinationRef) else emptySet()

        val found = mutableListOf<ScannedFile>()
        val rootNames = mutableMapOf<String, String>()
        val recentlyModified = mutableSetOf<String>()
        var scannedEntries = 0
        var skipped = 0
        var failed = 0
        val issues = mutableListOf<String>()

        for (source in sources) {
            val root = source.toDirRef()
            var lastProgress = 0
            gateway.scan(
                root = root,
                recursive = scanSettings.includeSubfolders,
                maxDepth = scanSettings.maxDepth,
                exclude = exclude,
            ).collect { event ->
                when (event) {
                    is ScanEvent.Progress -> lastProgress = event.scannedEntries
                    is ScanEvent.Found -> {
                        val scanned = event.item
                        when (
                            ScanRules.classify(
                                scanned.file.name,
                                scanned.file.size,
                                scanned.file.lastModified,
                                now,
                                scanSettings,
                            )
                        ) {
                            ScanDisposition.VIDEO -> {
                                found += scanned
                                rootNames[scanned.file.id] = root.name
                            }
                            ScanDisposition.RECENTLY_MODIFIED -> {
                                found += scanned
                                recentlyModified += scanned.file.id
                                rootNames[scanned.file.id] = root.name
                            }
                            else -> Unit
                        }
                    }
                    is ScanEvent.Skipped -> {
                        skipped++
                        if (issues.size < MAX_ISSUE_SAMPLES) issues += "${event.name} — ${event.reason}"
                    }
                    is ScanEvent.Failed -> {
                        failed++
                        if (issues.size < MAX_ISSUE_SAMPLES) issues += "${event.name} — ${event.reason}"
                    }
                }
            }
            scannedEntries += lastProgress
        }

        val parser = AnchorMediaNameParser(
            currentYear = Calendar.getInstance().get(Calendar.YEAR),
            extraNoiseWords = extraNoiseWords,
            titleCaseMode = titleCaseMode,
        )
        val parseResults = found.associate { scanned ->
            scanned.file.id to parser.parse(
                scanned.file.name,
                PathContext(ancestors = scanned.ancestors, sourceRootName = rootNames[scanned.file.id]),
            )
        }

        val snapshot = buildDestinationSnapshot(destinationRef)
        val planInput = PlanInput(
            items = found,
            parseResults = parseResults,
            naming = naming,
            destination = snapshot,
            recentlyModifiedIds = recentlyModified,
            scanSettings = scanSettings,
        )
        var planned = planner.plan(planInput)
        // Metadata enrichment (spec section 9): with keys and a service,
        // look every unique title up and re-plan — the planner decorates
        // folder names with genres/rating and attaches poster URLs.
        val service = metadataService
        if (service != null && (apiKeys.hasTmdb || apiKeys.hasOmdb)) {
            val queries = buildQueries(parseResults)
            if (queries.isNotEmpty()) {
                val metadataByKey = runCatching {
                    service.lookupAll(queries, apiKeys)
                }.getOrDefault(emptyMap())
                if (metadataByKey.isNotEmpty()) {
                    planned = planner.plan(planInput.copy(metadataByKey = metadataByKey))
                }
            }
        }
        return SessionPlan(
            items = planned,
            videosFound = found.size,
            scannedEntries = scannedEntries,
            skippedCount = skipped,
            failedCount = failed,
            issueSamples = issues,
            destinationName = destinationRef.name,
        )
    }

    /**
     * Executes a confirmed plan. The executor journals the run itself
     * (the run record remembers this destination), making the run
     * resumable and undoable.
     */
    suspend fun execute(
        plan: List<PlannedItem>,
        destination: Path,
        runId: String = UUID.randomUUID().toString(),
        optionsSnapshot: String = "",
        onProgress: (ExecutionProgress) -> Unit = {},
    ): ExecutionReport {
        val destinationRef = destination.toDirRef()
        return OrganizeExecutor(gateway, journal, destinationRef).execute(
            runId = runId,
            items = plan,
            optionsSnapshot = optionsSnapshot,
            destinationId = destinationRef.id,
            destinationName = destinationRef.name,
            onProgress = onProgress,
        )
    }

    /** Reverses a finished run from its journal, under its own destination. */
    suspend fun undo(runId: String): UndoReport {
        val run = journal.run(runId)
            ?: return UndoReport(runId, 0, listOf("run not found"), 0)
        val destinationRef = DirRef(run.destinationId, run.destinationName)
        return UndoExecutor(gateway, journal, destinationRef).undo(runId)
    }

    /** Bounded destination walk for the planner's conflict detection. */
    private suspend fun buildDestinationSnapshot(destination: DirRef): DestinationSnapshot {
        val folders = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        suspend fun walk(dir: DirRef, prefix: String, depth: Int) {
            val children = runCatching { gateway.listChildren(dir) }.getOrDefault(emptyList())
            for (child in children) {
                val name = child.ref.name
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    folders += rel
                    if (depth < SNAPSHOT_MAX_DEPTH) {
                        walk(DirRef(child.ref.id, name), rel, depth + 1)
                    }
                } else {
                    files += rel
                }
            }
        }
        walk(destination, prefix = "", depth = 0)
        return DestinationSnapshot(
            folders = folders,
            files = files,
            volume = gateway.volumeIdOf(destination),
            freeBytes = runCatching { gateway.freeBytes(destination) }.getOrNull(),
        )
    }

    /** Unique provider lookups for the parsed items (mirrors Android). */
    private fun buildQueries(
        results: Map<String, ParseResult>,
    ): List<MetadataQuery> {
        val seen = mutableSetOf<Triple<ItemType, String, Int?>>()
        val queries = mutableListOf<MetadataQuery>()
        for (result in results.values) {
            when (result) {
                is ParseResult.Movie -> {
                    val key = NormalizedKey.movieKey(result.title, result.year)
                    if (seen.add(Triple(ItemType.MOVIE, key, result.year))) {
                        queries += MetadataQuery(ItemType.MOVIE, result.title, result.year, key)
                    }
                }
                is ParseResult.Episode -> {
                    val key = NormalizedKey.seriesKey(result.seriesTitle)
                    if (seen.add(Triple(ItemType.EPISODE, key, result.seriesYearHint))) {
                        queries += MetadataQuery(
                            ItemType.EPISODE,
                            result.seriesTitle,
                            result.seriesYearHint,
                            key,
                        )
                    }
                }
                is ParseResult.Unrecognized -> Unit
            }
        }
        return queries
    }

    private fun Path.toDirRef(): DirRef =
        DirRef(toString(), fileName?.toString() ?: toString())

    private companion object {
        const val MAX_ISSUE_SAMPLES = 20

        /** Library root → title folders → their files is all the planner needs. */
        const val SNAPSHOT_MAX_DEPTH = 3
    }
}
