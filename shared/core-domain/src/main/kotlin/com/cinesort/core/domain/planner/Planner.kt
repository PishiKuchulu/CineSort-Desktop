package com.cinesort.core.domain.planner

import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.naming.ExistingFolderMatcher
import com.cinesort.core.domain.naming.FolderNamer
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.scan.ScanRules
import com.cinesort.core.domain.scan.ScanSettings
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.NormalizedKey
import com.cinesort.core.parser.ParseResult

enum class RatingSource { IMDB, TMDB, OMDB }

/** Metadata for one movie/series, already matched by the metadata layer. */
data class MetadataInfo(
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingSource: RatingSource? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val matchedTitle: String? = null,
)

/** A persisted user correction (spec section 6.6). */
data class TitleOverride(
    val normalizedKey: String,
    val type: ItemType,
    val correctedTitle: String,
    val correctedYear: Int? = null,
)

enum class ItemType { MOVIE, EPISODE }

enum class PlannedStatus {
    READY,
    ALREADY_ORGANIZED,
    CONFLICT,
    NEEDS_REVIEW,
    SKIPPED,
    RECENTLY_MODIFIED,
}

enum class ConflictChoice { SKIP, KEEP_BOTH }

data class PlannedItem(
    val id: String,
    val scanned: ScannedFile,
    val companions: List<FileRef>,
    /** Effective parse result after overrides/edits. Null when unrecognized. */
    val effective: ParseResult?,
    val type: ItemType?,
    val targetSegments: List<String>,
    val targetFileName: String,
    val status: PlannedStatus,
    val selected: Boolean,
    val warnings: List<String> = emptyList(),
    val metadata: MetadataInfo? = null,
    val crossVolume: Boolean = false,
    val skipReason: String? = null,
    /** Set when the target file already exists in the destination (conflict context). */
    val existingTarget: ExistingFileInfo? = null,
) {
    val sizeBytes: Long get() = scanned.file.size + companions.sumOf { it.size }
    val targetPath: String get() = (targetSegments + targetFileName).joinToString("/")
    val targetFolderPath: String get() = targetSegments.joinToString("/")
}

/** Size/date of a file that already exists in the destination (spec section 8.4). */
data class ExistingFileInfo(
    val size: Long,
    val lastModified: Long,
)

/** Snapshot of the destination tree used by the (pure) planner. */
data class DestinationSnapshot(
    /** Folder paths relative to the destination root, e.g. "Movie/Fall 2 Deadpoint (2026)". */
    val folders: Set<String> = emptySet(),
    /** File paths relative to the destination root including the file name. */
    val files: Set<String> = emptySet(),
    /** Optional size/date per file path, for the conflict comparison UI. */
    val fileDetails: Map<String, ExistingFileInfo> = emptyMap(),
    val volume: String = "primary",
    val freeBytes: Long? = null,
)

data class PlanInput(
    val items: List<ScannedFile>,
    val parseResults: Map<String, ParseResult>,
    val metadataByKey: Map<String, MetadataInfo> = emptyMap(),
    val naming: NamingOptions = NamingOptions(),
    val overrides: List<TitleOverride> = emptyList(),
    val destination: DestinationSnapshot = DestinationSnapshot(),
    /** Per-item conflict resolutions chosen in the preview, keyed by item id. */
    val conflictChoices: Map<String, ConflictChoice> = emptyMap(),
    /** Items the scanner flagged as still downloading (excluded by the age rule). */
    val recentlyModifiedIds: Set<String> = emptySet(),
    val scanSettings: ScanSettings = ScanSettings(),
)

/**
 * The planner is a pure function (spec section 8.2): the preview equals what execution does.
 */
class OrganizePlanner {

    fun plan(input: PlanInput): List<PlannedItem> {
        val overrideByKey = input.overrides.associateBy { it.normalizedKey }
        val staged = unifyFolderNames(
            input.items.map { stagedItem(it, input, overrideByKey) },
        )

        // Conflict pass: duplicate target file paths among planned items.
        // Needs-review items have no real target (review M1): they never conflict
        // with each other and are never resolved into a destination-root move.
        val targetCounts = staged.filter {
            it.status != PlannedStatus.SKIPPED && it.status != PlannedStatus.NEEDS_REVIEW
        }.groupingBy { it.targetPath }.eachCount()
        val allTargets = staged.map { it.targetPath }.toSet()
        val handedOut = mutableSetOf<String>()

        return staged.map { item ->
            val inactiveStatus = item.status == PlannedStatus.SKIPPED ||
                item.status == PlannedStatus.ALREADY_ORGANIZED
            val heldStatus = item.status == PlannedStatus.RECENTLY_MODIFIED ||
                item.status == PlannedStatus.NEEDS_REVIEW
            if (inactiveStatus || heldStatus) {
                return@map item
            }
            val existsInDestination = item.targetPath in input.destination.files
            val duplicated = (targetCounts[item.targetPath] ?: 0) > 1
            if (!existsInDestination && !duplicated) return@map item
            val withContext = if (existsInDestination) {
                item.copy(existingTarget = input.destination.fileDetails[item.targetPath])
            } else {
                item
            }
            // Keep-both among source duplicates only (review M2): the first item
            // keeps the plain name; only later ones are suffixed.
            val keepBothFirstDuplicate = input.conflictChoices[item.id] == ConflictChoice.KEEP_BOTH &&
                duplicated && !existsInDestination && item.targetPath !in handedOut
            if (keepBothFirstDuplicate) {
                handedOut += item.targetPath
                return@map withContext
            }
            val resolved = resolveConflict(withContext, input, existsInDestination, allTargets, handedOut)
            if (resolved.status == PlannedStatus.READY && resolved.targetFileName != item.targetFileName) {
                handedOut += resolved.targetPath
            }
            resolved
        }
    }

    /**
     * Bytes by which the selected READY items exceed the destination's
     * known free space, or null when the free space is unknown or
     * sufficient (audit A.3-5). The snapshot already carries `freeBytes`;
     * before this the planner defined the field and never read it, so a
     * too-full destination only surfaced mid-run, per file, after part of
     * the library had already moved.
     *
     * Only CROSS-VOLUME items count: a same-volume move is a provider
     * reparent that consumes no destination space at all, so charging
     * those bytes made the warning claim a run would fail on exactly
     * the full-disk, single-volume libraries that move for free (owner
     * report 2026-11-05). Same-volume items that still take the copy
     * path (renamed target, move-less provider) need only one file's
     * transient space, which the gateway pre-checks per file at
     * execution time.
     */
    fun freeSpaceShortfall(
        planned: List<PlannedItem>,
        destination: DestinationSnapshot,
    ): Long? {
        val free = destination.freeBytes ?: return null
        val needed = planned
            .filter { it.selected && it.status == PlannedStatus.READY && it.crossVolume }
            .sumOf { it.sizeBytes }
        return if (needed > free) needed - free else null
    }

    /**
     * Applies the persisted title overrides to raw parse results, keyed exactly
     * as [plan] applies them internally. Exposed so the session can build
     * metadata queries from the same effective titles the planner will use
     * (review M-D): without this, corrected items were queried under their
     * uncorrected titles and got no decoration.
     */
    fun applyPersistedOverrides(
        results: Map<String, ParseResult>,
        overrides: List<TitleOverride>,
    ): Map<String, ParseResult> {
        if (overrides.isEmpty()) return results
        val overrideByKey = overrides.associateBy { it.normalizedKey }
        return results.mapValues { (_, raw) -> applyOverride(raw, overrideByKey) }
    }

    /**
     * One folder per work within a plan (review M3): items whose titles differ
     * only in punctuation/casing ("Spider-Man" vs "Spider.Man") share a normalized
     * key, so the first item's folder name becomes canonical for the group.
     *
     * Movies key on title + year (different years are different works). Episodes
     * key on the series title ONLY (review L-A): an episode's own year hint must
     * not split one series into two folders — the group's canonical folder is
     * the one built by the first episode that carries a hint (with "add year to
     * series folder" on, that folder carries the group's canonical year), or
     * the first episode's folder when no episode carries a hint.
     */
    private fun unifyKey(item: PlannedItem): String? {
        if (item.targetSegments.size < 2) return null
        return when (val e = item.effective) {
            is ParseResult.Movie ->
                item.targetSegments[0] + "|" + NormalizedKey.of(e.title) + "|" +
                    (e.year?.toString() ?: "")
            is ParseResult.Episode ->
                item.targetSegments[0] + "|" + NormalizedKey.of(e.seriesTitle)
            else -> null
        }
    }

    /**
     * Pass 1 of folder unification: the canonical folder per group. For series
     * groups the first HINTED episode's folder wins over an unhinted one
     * wherever it appears in the group, so this cannot fold into the emit pass.
     */
    private fun canonicalFolders(items: List<PlannedItem>): Map<String, String> {
        val canonical = mutableMapOf<String, String>()
        val hintedSeries = mutableSetOf<String>()
        for (item in items) {
            val key = unifyKey(item) ?: continue
            val e = item.effective
            val hinted = e is ParseResult.Episode && e.seriesYearHint != null
            if (key !in canonical || (hinted && key !in hintedSeries)) {
                canonical[key] = item.targetSegments[1]
                if (hinted) hintedSeries += key
            }
        }
        return canonical
    }

    private fun unifyFolderNames(items: List<PlannedItem>): List<PlannedItem> {
        val canonical = canonicalFolders(items)
        return items.map { item ->
            val key = unifyKey(item) ?: return@map item
            val folder = canonical[key] ?: return@map item
            if (folder == item.targetSegments[1]) {
                item
            } else {
                item.copy(
                    targetSegments = listOf(item.targetSegments[0], folder) +
                        item.targetSegments.drop(2),
                )
            }
        }
    }

    /** Renames companions to the suffixed base of a keep-both rename (review M2). */
    private fun renameCompanions(item: PlannedItem, candidate: String): List<FileRef> {
        val base = item.scanned.file.name.substringBeforeLast('.', "")
        if (base.isEmpty()) return item.companions
        val newBase = candidate.substringBeforeLast('.', "")
        return item.companions.map { companion ->
            if (companion.name.startsWith("$base.")) {
                companion.copy(name = newBase + companion.name.substring(base.length))
            } else {
                companion
            }
        }
    }

    private fun resolveConflict(
        item: PlannedItem,
        input: PlanInput,
        existsInDestination: Boolean,
        allTargets: Set<String>,
        handedOut: Set<String>,
    ): PlannedItem {
        return when (input.conflictChoices[item.id]) {
            ConflictChoice.SKIP -> item.copy(
                status = PlannedStatus.SKIPPED,
                selected = false,
                skipReason = "conflict: skipped by user",
            )
            ConflictChoice.KEEP_BOTH -> {
                val taken = buildSet {
                    addAll(input.destination.files)
                    addAll(allTargets)
                    addAll(handedOut)
                }
                var n = 1
                var candidate: String
                do {
                    candidate = suffixedName(item.scanned.file.name, n)
                    n++
                } while ((item.targetSegments + candidate).joinToString("/") in taken)
                if (existsInDestination || candidate != item.scanned.file.name) {
                    item.copy(
                        targetFileName = candidate,
                        // Companions follow the main file's new base name (review M2),
                        // so subtitles stay attached to the renamed video.
                        companions = renameCompanions(item, candidate),
                        status = PlannedStatus.READY,
                        selected = true,
                        warnings = item.warnings + "conflict: kept both as $candidate",
                    )
                } else {
                    item.copy(status = PlannedStatus.CONFLICT, selected = false)
                }
            }
            null -> item.copy(status = PlannedStatus.CONFLICT, selected = false)
        }
    }

    private fun suffixedName(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) {
            name.substring(0, dot) + " ($n)" + name.substring(dot)
        } else {
            "$name ($n)"
        }
    }

    private fun stagedItem(
        scanned: ScannedFile,
        input: PlanInput,
        overrideByKey: Map<String, TitleOverride>,
    ): PlannedItem {
        val raw = input.parseResults[scanned.file.id]
        val companions = if (input.scanSettings.moveCompanions) {
            scanned.siblings.filter {
                ScanRules.isCompanionOf(it.name, scanned.file.name, input.scanSettings)
            }
        } else {
            emptyList()
        }
        val base = PlannedItem(
            id = scanned.file.id,
            scanned = scanned,
            companions = companions,
            effective = null,
            type = null,
            targetSegments = emptyList(),
            targetFileName = scanned.file.name,
            status = PlannedStatus.NEEDS_REVIEW,
            selected = false,
            crossVolume = scanned.sourceVolume != input.destination.volume,
        )

        if (scanned.file.id in input.recentlyModifiedIds) {
            return base.copy(status = PlannedStatus.RECENTLY_MODIFIED)
        }
        if (raw == null || raw is ParseResult.Unrecognized) {
            // A remembered correction can rescue an unrecognized file: the session
            // keys such overrides by the normalized file-name base (spec 6.6). Only
            // movie overrides can be synthesized — an episode override carries no
            // season/episode numbers, so those stay in Needs review.
            val rescueKey = NormalizedKey.of(
                scanned.file.name.substringBeforeLast('.', scanned.file.name),
            )
            val rescue = overrideByKey[rescueKey]
            if (raw != null && rescue != null && rescue.type == ItemType.MOVIE) {
                val rescued = ParseResult.Movie(
                    fileName = raw.fileName,
                    title = rescue.correctedTitle,
                    year = rescue.correctedYear,
                    confidence = com.cinesort.core.parser.Confidence.MEDIUM,
                    trace = raw.trace,
                )
                return stageMovie(base, scanned, rescued, input)
            }
            return base.copy(warnings = listOf("unrecognized"))
        }

        val effective = applyOverride(raw, overrideByKey)
        return when (effective) {
            is ParseResult.Movie -> stageMovie(base, scanned, effective, input)
            is ParseResult.Episode -> stageEpisode(base, scanned, effective, input)
            is ParseResult.Unrecognized -> base
        }
    }

    private fun applyOverride(
        raw: ParseResult,
        overrideByKey: Map<String, TitleOverride>,
    ): ParseResult {
        val key: String
        val type: ItemType
        when (raw) {
            is ParseResult.Movie -> {
                key = NormalizedKey.movieKey(raw.title, raw.year)
                type = ItemType.MOVIE
            }
            is ParseResult.Episode -> {
                key = NormalizedKey.seriesKey(raw.seriesTitle)
                type = ItemType.EPISODE
            }
            is ParseResult.Unrecognized -> return raw
        }
        val override = overrideByKey[key] ?: return raw
        if (override.type != type) return raw
        return when (raw) {
            is ParseResult.Movie -> raw.copy(
                title = override.correctedTitle,
                year = override.correctedYear ?: raw.year,
            )
            is ParseResult.Episode -> raw.copy(
                seriesTitle = override.correctedTitle,
                seriesYearHint = override.correctedYear ?: raw.seriesYearHint,
            )
            is ParseResult.Unrecognized -> raw
        }
    }

    private fun stageMovie(
        base: PlannedItem,
        scanned: ScannedFile,
        movie: ParseResult.Movie,
        input: PlanInput,
    ): PlannedItem {
        val key = NormalizedKey.movieKey(movie.title, movie.year)
        val metadata = input.metadataByKey[key]
        val effectiveYear = movie.year
            ?: if (input.naming.fillMissingYear) metadata?.year else null
        val folderName = FolderNamer.movieFolderName(
            title = movie.title,
            year = effectiveYear,
            genres = metadata?.genres.orEmpty(),
            rating = metadata?.rating,
            options = input.naming,
        )
        // Reuse an existing folder whose decoration-stripped key matches (spec 7.3).
        // Movie keys include the year, so the year is compared separately against the
        // year decoration stripped from the existing folder name.
        val existing = findExistingMovie(input.destination, movie.title, effectiveYear)
        val segments = listOf(FolderNamer.MOVIE_ROOT, existing ?: folderName)
        return finishStaging(base, scanned, movie, ItemType.MOVIE, segments, metadata)
    }

    private fun stageEpisode(
        base: PlannedItem,
        scanned: ScannedFile,
        episode: ParseResult.Episode,
        input: PlanInput,
    ): PlannedItem {
        val key = NormalizedKey.seriesKey(episode.seriesTitle)
        val metadata = input.metadataByKey[key]
        val folderName = FolderNamer.seriesFolderName(
            title = episode.seriesTitle,
            year = episode.seriesYearHint,
            genres = metadata?.genres.orEmpty(),
            rating = metadata?.rating,
            options = input.naming,
        )
        val existing = findExisting(input.destination, FolderNamer.SERIES_ROOT, key)
        val segments = listOf(
            FolderNamer.SERIES_ROOT,
            existing ?: folderName,
            FolderNamer.seasonFolderName(episode.season),
        )
        return finishStaging(base, scanned, episode, ItemType.EPISODE, segments, metadata)
    }

    private fun finishStaging(
        base: PlannedItem,
        scanned: ScannedFile,
        effective: ParseResult,
        type: ItemType,
        segments: List<String>,
        metadata: MetadataInfo?,
    ): PlannedItem {
        val confidence = effective.confidence
        val alreadyThere = scanned.locationInDestination != null &&
            scanned.locationInDestination == segments
        val status = when {
            alreadyThere -> PlannedStatus.ALREADY_ORGANIZED
            confidence == Confidence.LOW -> PlannedStatus.NEEDS_REVIEW
            else -> PlannedStatus.READY
        }
        return base.copy(
            effective = effective,
            type = type,
            targetSegments = segments,
            status = status,
            selected = status == PlannedStatus.READY,
            metadata = metadata,
        )
    }

    /** Finds an existing library folder under [root] whose stripped key equals [key]. */
    private fun findExisting(
        destination: DestinationSnapshot,
        root: String,
        key: String,
    ): String? {
        return destination.folders
            .map { it.split('/') }
            .filter { it.size == 2 && it[0] == root }
            .map { it[1] }
            .firstOrNull { ExistingFolderMatcher.matches(it, key) }
    }

    private val folderYearRegex = Regex("""\(((?:19|20)\d{2})\)""")

    private fun findExistingMovie(
        destination: DestinationSnapshot,
        title: String,
        year: Int?,
    ): String? {
        val titleKey = NormalizedKey.of(title)
        return destination.folders
            .map { it.split('/') }
            .filter { it.size == 2 && it[0] == FolderNamer.MOVIE_ROOT }
            .map { it[1] }
            // Deterministic pick (review M3): the snapshot is a Set, so without
            // sorting the "first" match would be arbitrary when the year is unknown.
            .sorted()
            .firstOrNull { folderName ->
                if (!ExistingFolderMatcher.matches(folderName, titleKey)) {
                    false
                } else {
                    val folderYear = folderYearRegex.find(folderName)?.groupValues?.get(1)?.toInt()
                    folderYear == null || year == null || folderYear == year
                }
            }
    }
}
