package com.cinesort.core.domain

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.execute.UndoExecutor
import com.cinesort.core.domain.gateway.InMemoryStorageGateway
import com.cinesort.core.domain.gateway.StorageGateway
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.journal.OperationRecord
import com.cinesort.core.domain.journal.OperationState
import com.cinesort.core.domain.journal.RunRecord
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScanEvent
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.naming.ExistingFolderMatcher
import com.cinesort.core.domain.naming.FolderNamer
import com.cinesort.core.domain.naming.NameSanitizer
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.planner.DestinationSnapshot
import com.cinesort.core.domain.planner.MetadataInfo
import com.cinesort.core.domain.planner.OrganizePlanner
import com.cinesort.core.domain.planner.PlanInput
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.RatingSource
import com.cinesort.core.domain.planner.TitleOverride
import com.cinesort.core.domain.scan.ScanRules
import com.cinesort.core.domain.scan.ScanDisposition
import com.cinesort.core.domain.scan.ScanSettings
import com.cinesort.core.domain.usecase.EnsureLibraryRootsUseCase
import com.cinesort.core.parser.AnchorMediaNameParser
import com.cinesort.core.parser.NormalizedKey
import com.cinesort.core.parser.PathContext
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DomainAcceptanceTest {

    private val parser = AnchorMediaNameParser(currentYear = 2026)
    private val planner = OrganizePlanner()

    private fun scanned(
        gateway: InMemoryStorageGateway,
        path: String,
        ancestors: List<String> = emptyList(),
        size: Long = 2_000_000_000,
    ): ScannedFile {
        val file = gateway.addFile(path, size = size, lastModified = 0)
        val parentPath = path.substringBeforeLast('/')
        return ScannedFile(
            file = file,
            parentDir = DirRef(parentPath, parentPath.substringAfterLast('/')),
            ancestors = ancestors,
            siblings = emptyList(),
            sourceVolume = "primary",
        )
    }

    private fun planFor(
        items: List<ScannedFile>,
        destination: DestinationSnapshot = DestinationSnapshot(),
        naming: NamingOptions = NamingOptions(),
        metadata: Map<String, MetadataInfo> = emptyMap(),
        extras: PlanInput.() -> PlanInput = { this },
    ): List<com.cinesort.core.domain.planner.PlannedItem> {
        val parseResults = items.associate { item ->
            item.file.id to parser.parse(
                item.file.name,
                PathContext(item.ancestors),
            )
        }
        return planner.plan(
            PlanInput(
                items = items,
                parseResults = parseResults,
                destination = destination,
                naming = naming,
                metadataByKey = metadata,
            ).extras(),
        )
    }

    // ------------------------------------------------------------ section 15.2

    @Test
    fun acceptancePlannedPaths() {
        val gateway = InMemoryStorageGateway()
        val movie = scanned(
            gateway,
            "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.10bit.WEB-DL.x265.PSA.Farsi.Sub.Film2Media.mkv",
        )
        val episode = scanned(
            gateway,
            "primary:/Downloads/Dexter.Resurrection.S02E01.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
        )
        val planned = planFor(listOf(movie, episode))
        val movieItem = planned.first { it.id == movie.file.id }
        assertEquals(
            "Movie/Fall 2 Deadpoint (2026)/Fall.2.Deadpoint.2026.1080p.10bit.WEB-DL.x265.PSA.Farsi.Sub.Film2Media.mkv",
            movieItem.targetPath,
        )
        val episodeItem = planned.first { it.id == episode.file.id }
        assertEquals(
            "Tv series/Dexter Resurrection/S02/Dexter.Resurrection.S02E01.1080p.10bit.WEB-DL.6CH.x265.PSA.Farsi.Sub.Film2Media.mkv",
            episodeItem.targetPath,
        )
    }

    @Test
    fun acceptanceDecoratedFolder() {
        val gateway = InMemoryStorageGateway()
        val movie = scanned(
            gateway,
            "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv",
        )
        val key = NormalizedKey.movieKey("Fall 2 Deadpoint", 2026)
        val planned = planFor(
            listOf(movie),
            naming = NamingOptions(movieAddGenres = true, movieAddRating = true),
            metadata = mapOf(
                key to MetadataInfo(
                    genres = listOf("Survival", "Adventure", "Drama", "Thriller"),
                    rating = 5.3,
                    ratingSource = RatingSource.IMDB,
                ),
            ),
        )
        assertEquals(
            "Fall 2 Deadpoint (2026) (Survival, Adventure, Drama, Thriller) 5.3",
            planned.single().targetSegments[1],
        )
        // The same folder is produced directly by the namer.
        assertEquals(
            "Fall 2 Deadpoint (2026) (Survival, Adventure, Drama, Thriller) 5.3",
            FolderNamer.movieFolderName(
                "Fall 2 Deadpoint", 2026,
                listOf("Survival", "Adventure", "Drama", "Thriller"), 5.3,
                NamingOptions(movieAddGenres = true, movieAddRating = true),
            ),
        )
    }

    @Test
    fun acceptanceSeriesGrouping() {
        val gateway = InMemoryStorageGateway()
        val episodes = (1..3).map { n ->
            scanned(gateway, "primary:/Downloads/The.Gentlemen.S02E0$n.1080p.WEB-DL.mkv")
        }
        val planned = planFor(episodes)
        assertEquals(
            setOf("Tv series/The Gentlemen/S02"),
            planned.map { it.targetFolderPath }.toSet(),
        )
    }

    // L-A: an episode's own year hint must not split one series into two
    // folders; the group folder carries the canonical (first hinted) year.
    @Test
    fun seriesYearHintDoesNotSplitSeriesFolder() {
        val gateway = InMemoryStorageGateway()
        val unhinted = scanned(gateway, "primary:/Downloads/Some.Show.S01E01.1080p.WEB-DL.mkv")
        val hinted = scanned(gateway, "primary:/Downloads/Some.Show.2024.S01E02.1080p.WEB-DL.mkv")
        val naming = NamingOptions(seriesAddYear = true)
        // Unhinted episode first: the hinted one's folder must still win.
        val planned = planFor(listOf(unhinted, hinted), naming = naming)
        assertEquals(
            setOf("Tv series/Some Show (2024)/S01"),
            planned.map { it.targetFolderPath }.toSet(),
        )
        // Order independence: hinted first yields the same single folder.
        val plannedReversed = planFor(listOf(hinted, unhinted), naming = naming)
        assertEquals(
            setOf("Tv series/Some Show (2024)/S01"),
            plannedReversed.map { it.targetFolderPath }.toSet(),
        )
    }

    @Test
    fun acceptanceDestinationBootstrapIsIdempotent() = runTest {
        val gateway = InMemoryStorageGateway()
        val dest = gateway.mkdirs("primary:/Library")
        val useCase = EnsureLibraryRootsUseCase(gateway)
        val first = useCase(dest)
        assertEquals(listOf("Movie", "Tv series"), first.created)
        val second = useCase(dest)
        assertTrue(second.created.isEmpty())
        assertEquals(listOf("Movie", "Tv series"), second.found)
    }

    // ------------------------------------------------------------ planner rules

    @Test
    fun existingDecoratedFolderIsReusedNotRenamed() {
        val gateway = InMemoryStorageGateway()
        val movie = scanned(gateway, "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv")
        val destination = DestinationSnapshot(
            folders = setOf("Movie", "Movie/Fall 2 Deadpoint (2026) (Drama) 7.1"),
        )
        val planned = planFor(listOf(movie), destination = destination)
        assertEquals(
            "Fall 2 Deadpoint (2026) (Drama) 7.1",
            planned.single().targetSegments[1],
        )
    }

    @Test
    fun decorationStrippingKeepsTitleYearLikeNumbers() {
        assertEquals("Fall 2 Deadpoint", ExistingFolderMatcher.stripDecoration("Fall 2 Deadpoint (2026)"))
        assertEquals("Movie 5.5", ExistingFolderMatcher.stripDecoration("Movie 5.5 (2020)"))
        assertEquals(
            "Fall 2 Deadpoint",
            ExistingFolderMatcher.stripDecoration("Fall 2 Deadpoint (2026) (Drama) 7.1"),
        )
    }

    @Test
    fun conflictWhenTargetExistsAndKeepBothSuffixes() {
        val gateway = InMemoryStorageGateway()
        val name = "Evolution.2001.720p.BluRay.mkv"
        val item = scanned(gateway, "primary:/Downloads/$name")
        val destination = DestinationSnapshot(
            files = setOf("Movie/Evolution (2001)/$name"),
            folders = setOf("Movie", "Movie/Evolution (2001)"),
        )
        val unresolved = planFor(listOf(item), destination)
        assertEquals(PlannedStatus.CONFLICT, unresolved.single().status)
        assertEquals(false, unresolved.single().selected)

        val withDetails = planFor(
            listOf(item),
            destination.copy(
                fileDetails = mapOf(
                    "Movie/Evolution (2001)/$name" to
                        com.cinesort.core.domain.planner.ExistingFileInfo(123L, 456L),
                ),
            ),
        )
        assertEquals(123L, withDetails.single().existingTarget?.size)
        assertEquals(456L, withDetails.single().existingTarget?.lastModified)

        val kept = planFor(listOf(item), destination) {
            copy(
                conflictChoices = mapOf(
                    item.file.id to com.cinesort.core.domain.planner.ConflictChoice.KEEP_BOTH,
                ),
            )
        }
        assertEquals(PlannedStatus.READY, kept.single().status)
        assertEquals("Evolution.2001.720p.BluRay (1).mkv", kept.single().targetFileName)
    }

    @Test
    fun unrecognizedGoesToNeedsReviewUnselected() {
        val gateway = InMemoryStorageGateway()
        val item = scanned(gateway, "primary:/Downloads/random_clip.mkv")
        val planned = planFor(listOf(item))
        assertEquals(PlannedStatus.NEEDS_REVIEW, planned.single().status)
        assertEquals(false, planned.single().selected)
    }

    @Test
    fun sanitizerRemovesIllegalCharacters() {
        assertEquals("Movie - Title", NameSanitizer.sanitize("Movie: Title"))
        assertEquals("A-B", NameSanitizer.sanitize("A/B"))
        assertEquals("AB", NameSanitizer.sanitize("A*B?"))
    }

    @Test
    fun seasonFolderPadding() {
        assertEquals("S01", FolderNamer.seasonFolderName(1))
        assertEquals("S00", FolderNamer.seasonFolderName(0))
        assertEquals("S100", FolderNamer.seasonFolderName(100))
    }

    // ------------------------------------------------------------ scan rules

    @Test
    fun scanRuleCases() {
        val settings = ScanSettings()
        val now = 1_000_000L
        assertEquals(
            ScanDisposition.IGNORED_TEMP,
            ScanRules.classify("Movie.2020.1080p.mkv.part", 10, 0, now, settings),
        )
        assertEquals(
            ScanDisposition.IGNORED_SAMPLE,
            ScanRules.classify("Movie.2020.sample.mkv", 10 * 1024 * 1024, 0, now, settings),
        )
        assertEquals(
            ScanDisposition.RECENTLY_MODIFIED,
            ScanRules.classify("Movie.2020.1080p.mkv", 10, now - 30_000, now, settings),
        )
        assertEquals(
            ScanDisposition.VIDEO,
            ScanRules.classify("Movie.2020.1080p.mkv", 10, 0, now, settings),
        )
    }

    @Test
    fun `a remembered movie correction rescues an unrecognized file on the next scan`() {
        val gateway = InMemoryStorageGateway()
        val item = scanned(gateway, "primary:/Downloads/strange name.mkv")
        val planned = planFor(listOf(item)) {
            copy(
                overrides = listOf(
                    TitleOverride(
                        normalizedKey = NormalizedKey.of("strange name"),
                        type = ItemType.MOVIE,
                        correctedTitle = "Strange Tales",
                        correctedYear = 1999,
                    ),
                ),
            )
        }.single()
        assertEquals(PlannedStatus.READY, planned.status)
        assertEquals(ItemType.MOVIE, planned.type)
        assertEquals(listOf("Movie", "Strange Tales (1999)"), planned.targetSegments)
    }

    @Test
    fun `an episode override cannot rescue an unrecognized file without season data`() {
        val gateway = InMemoryStorageGateway()
        val item = scanned(gateway, "primary:/Downloads/strange name.mkv")
        val planned = planFor(listOf(item)) {
            copy(
                overrides = listOf(
                    TitleOverride(
                        normalizedKey = NormalizedKey.of("strange name"),
                        type = ItemType.EPISODE,
                        correctedTitle = "Strange Tales",
                        correctedYear = null,
                    ),
                ),
            )
        }.single()
        assertEquals(PlannedStatus.NEEDS_REVIEW, planned.status)
    }

    // ------------------------------------------------------------ execute + undo

    @Test
    fun executeThenUndoRoundTrip() = runTest {
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val src = scanned(
            gateway,
            "primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv",
        )
        val planned = planFor(listOf(src))
        assertEquals(PlannedStatus.READY, planned.single().status)

        val journal = InMemoryJournalStore()
        val executor = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
        val report = executor.execute("run-1", planned)
        assertEquals(1, report.done)
        assertTrue(report.failed.isEmpty(), report.failed.toString())
        assertTrue(
            gateway.exists("primary:/Library/Movie/Fall 2 Deadpoint (2026)/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv"),
        )
        assertTrue(!gateway.exists("primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv"))

        val undo = UndoExecutor(gateway, journal, destRoot).undo("run-1")
        assertEquals(1, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), undo.problems.toString())
        assertTrue(gateway.exists("primary:/Downloads/Fall.2.Deadpoint.2026.1080p.WEB-DL.mkv"))
        // Run-created folders are removed once empty.
        assertTrue(!gateway.exists("primary:/Library/Movie/Fall 2 Deadpoint (2026)"))
    }

    @Test
    fun undoRecreatesDeletedSourceFolder() = runTest {
        // Review M6 regression: the post-run cleanup can delete the emptied
        // source folder; undo must recreate it instead of failing.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val src = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(src))
        val journal = InMemoryJournalStore()
        val executor = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
        executor.execute("run-1", planned)
        gateway.delete(DirRef("primary:/Downloads", "Downloads"))
        assertTrue(!gateway.exists("primary:/Downloads"))

        val undo = UndoExecutor(gateway, journal, destRoot).undo("run-1")
        assertEquals(1, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), undo.problems.toString())
        assertTrue(gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv"))
    }

    @Test
    fun needsReviewItemsNeverConflictWithEachOther() {
        // Review M1 regression: unrecognized files share the empty target path;
        // they must stay NEEDS_REVIEW, not bogus-conflict with each other.
        val gateway = InMemoryStorageGateway()
        val a = scanned(gateway, "primary:/Downloads/qwerty12345.mkv")
        val b = scanned(gateway, "primary:/Downloads/asdfgh98765.mkv")
        val planned = planFor(listOf(a, b))
        assertTrue(
            planned.all { it.status == PlannedStatus.NEEDS_REVIEW },
            planned.map { it.status }.toString(),
        )
    }

    @Test
    fun reportCountsSkippedItems() = runTest {
        // Review L8 regression: unselected items count as skipped in the report.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val a = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val b = scanned(gateway, "primary:/Downloads/Beta.2021.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(a, b)).mapIndexed { index, item ->
            if (index == 0) item.copy(selected = false) else item
        }
        val journal = InMemoryJournalStore()
        val executor = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
        val report = executor.execute("run-1", planned)
        assertEquals(1, report.done)
        assertEquals(1, report.skipped)
    }

    @Test
    fun undoRemovesAllSameDepthCreatedDirs() = runTest {
        // Review H1 regression: several created folders share the same depth
        // ("Movie/<Title>" for each movie). A depth-only TreeSet comparator used
        // to keep just one of them, leaving the rest behind after undo.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val srcA = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val srcB = scanned(gateway, "primary:/Downloads/Beta.2021.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(srcA, srcB))
        assertTrue(planned.all { it.status == PlannedStatus.READY }, planned.toString())

        val journal = InMemoryJournalStore()
        val executor = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
        val report = executor.execute("run-1", planned)
        assertEquals(2, report.done)
        assertTrue(gateway.exists("primary:/Library/Movie/Alpha (2020)"))
        assertTrue(gateway.exists("primary:/Library/Movie/Beta (2021)"))

        val undo = UndoExecutor(gateway, journal, destRoot).undo("run-1")
        assertEquals(2, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), undo.problems.toString())
        assertTrue(!gateway.exists("primary:/Library/Movie/Alpha (2020)"))
        assertTrue(!gateway.exists("primary:/Library/Movie/Beta (2021)"))
    }

    // ------------------------------------------------------------ review #5

    @Test
    fun unexpectedExceptionInOneItemDoesNotAbortTheRun() = runTest {
        // Review H-A regression: a non-IO runtime failure from the provider
        // (here an IllegalStateException out of move) fails only that item;
        // the run continues with the next item and is marked finished.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val bad = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val good = scanned(gateway, "primary:/Downloads/Beta.2021.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(bad, good))
        val flaky = object : StorageGateway by gateway {
            override suspend fun move(file: FileRef, fromDir: DirRef, toDir: DirRef): FileRef {
                if (file.name.startsWith("Alpha")) error("provider exploded")
                return gateway.move(file, fromDir, toDir)
            }
        }
        val journal = InMemoryJournalStore()
        val report = OrganizeExecutor(flaky, journal, destRoot, clock = { 42L })
            .execute("run-1", planned)
        assertEquals(1, report.done)
        assertEquals(1, report.failed.size)
        assertEquals("provider exploded", report.failed.single().second)
        assertTrue(gateway.exists("primary:/Library/Movie/Beta (2021)/Beta.2021.1080p.WEB-DL.mkv"))
        assertTrue(gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv"))
        assertEquals(42L, journal.run("run-1")!!.finishedAt)
        val badOps = journal.operationsOf("run-1").filter { it.fileName.startsWith("Alpha") }
        assertTrue(badOps.isNotEmpty() && badOps.all { it.state == OperationState.FAILED })
    }

    @Test
    fun executeResumesPerOperationSkippingDoneOps() = runTest {
        // Review M-B regression: an item whose video op completed but whose
        // companion op failed in an earlier attempt is resumed per operation —
        // the DONE video op is skipped (its source is gone) and only the
        // companion is executed.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val src = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val subtitle = gateway.addFile(
            "primary:/Downloads/Alpha.2020.1080p.WEB-DL.srt", size = 50_000, lastModified = 0,
        )
        val item = planFor(listOf(src)).single().copy(companions = listOf(subtitle))

        val journal = InMemoryJournalStore()
        journal.createRun(RunRecord(id = "run-1", startedAt = 1L, totalItems = 1))
        journal.insertOperation(
            OperationRecord(
                id = "op-video",
                runId = "run-1",
                itemId = item.id,
                sourceParentId = src.file.parentId,
                sourceParentName = "Downloads",
                sourceFileId = src.file.id,
                fileName = src.file.name,
                finalName = item.targetFileName,
                destinationParentId = destRoot.id,
                destinationSegments = item.targetSegments,
                state = OperationState.DONE,
                bytesTotal = src.file.size,
                bytesDone = src.file.size,
                createdDirs = listOf("Movie", "Movie/Alpha (2020)"),
            ),
        )
        journal.insertOperation(
            OperationRecord(
                id = "op-sub",
                runId = "run-1",
                itemId = item.id,
                sourceParentId = subtitle.parentId,
                sourceParentName = "Downloads",
                sourceFileId = subtitle.id,
                fileName = subtitle.name,
                finalName = subtitle.name,
                destinationParentId = destRoot.id,
                destinationSegments = item.targetSegments,
                state = OperationState.FAILED,
                bytesTotal = subtitle.size,
                isCompanion = true,
                failureReason = "interrupted",
            ),
        )
        // The first attempt's physical result: the video already sits in the
        // destination; only the subtitle is still in the source folder.
        val destDir = gateway.mkdirs("primary:/Library/Movie/Alpha (2020)")
        gateway.move(src.file, src.parentDir, destDir)
        assertTrue(!gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv"))

        val report = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
            .execute("run-1", listOf(item))
        assertEquals(1, report.done)
        assertTrue(report.failed.isEmpty(), report.failed.toString())
        assertTrue(gateway.exists("primary:/Library/Movie/Alpha (2020)/Alpha.2020.1080p.WEB-DL.srt"))
        assertTrue(!gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.srt"))
        // The video was not re-executed: still exactly one op for it, still DONE.
        val videoOps = journal.operationsOf("run-1").filter { it.sourceFileId == src.file.id }
        assertEquals(1, videoOps.size)
        assertEquals(OperationState.DONE, videoOps.single().state)
    }

    @Test
    fun undoTwiceIsACleanNoOp() = runTest {
        // Review M-C regression: a successful undo persists UNDONE per op, so a
        // second undo walks nothing and reports no phantom missing files.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val src = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(src))
        val journal = InMemoryJournalStore()
        OrganizeExecutor(gateway, journal, destRoot, clock = { 42L }).execute("run-1", planned)

        val undoExecutor = UndoExecutor(gateway, journal, destRoot)
        val first = undoExecutor.undo("run-1")
        assertEquals(1, first.movedBack)
        assertTrue(first.problems.isEmpty(), first.problems.toString())
        assertEquals(OperationState.UNDONE, journal.operationsOf("run-1").single().state)
        assertTrue(journal.run("run-1")!!.undone)

        val second = undoExecutor.undo("run-1")
        assertEquals(0, second.movedBack)
        assertTrue(
            second.problems.none { it.contains("no longer in destination") },
            second.problems.toString(),
        )
        assertTrue(gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv"))
    }

    @Test
    fun partialUndoThenSecondUndoCompletesTheRest() = runTest {
        // Review M-C regression: after a partial undo (one file missing from
        // the destination), the restored op keeps its UNDONE marker; once the
        // problem is fixed, a second undo completes only the remaining op and
        // the run reaches the fully-undone state.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val srcA = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val srcB = scanned(gateway, "primary:/Downloads/Beta.2021.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(srcA, srcB))
        val journal = InMemoryJournalStore()
        OrganizeExecutor(gateway, journal, destRoot, clock = { 42L }).execute("run-1", planned)

        // Sabotage: Beta's file vanishes from the destination.
        val betaDestDir = DirRef("primary:/Library/Movie/Beta (2021)", "Beta (2021)")
        val betaDest = gateway.listChildren(betaDestDir).single { !it.isDirectory }.ref
        gateway.delete(betaDest)
        assertTrue(!gateway.exists("primary:/Library/Movie/Beta (2021)/Beta.2021.1080p.WEB-DL.mkv"))

        val undoExecutor = UndoExecutor(gateway, journal, destRoot)
        val first = undoExecutor.undo("run-1")
        assertEquals(1, first.movedBack)
        assertEquals(1, first.problems.size, first.problems.toString())
        assertTrue(!journal.run("run-1")!!.undone)
        val states = journal.operationsOf("run-1").associate { it.fileName to it.state }
        assertEquals(OperationState.UNDONE, states.getValue("Alpha.2020.1080p.WEB-DL.mkv"))
        assertEquals(OperationState.DONE, states.getValue("Beta.2021.1080p.WEB-DL.mkv"))

        // The file reappears; the second undo moves back only Beta and does
        // not complain about the already-restored Alpha again.
        gateway.addFile(
            "primary:/Library/Movie/Beta (2021)/Beta.2021.1080p.WEB-DL.mkv",
            size = 2_000_000_000, lastModified = 0,
        )
        val second = undoExecutor.undo("run-1")
        assertEquals(1, second.movedBack)
        assertTrue(second.problems.isEmpty(), second.problems.toString())
        assertTrue(journal.run("run-1")!!.undone)
        assertTrue(gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv"))
        assertTrue(gateway.exists("primary:/Downloads/Beta.2021.1080p.WEB-DL.mkv"))
    }

    @Test
    fun undoDeletesJournaledPosterAndRemovesCreatedFolder() = runTest {
        // Review M-A regression: a downloaded poster is journaled as an
        // isPoster operation; undo deletes the poster (marking the op UNDONE)
        // before the created-dir cleanup, so the run-created folder — which
        // the poster alone kept non-empty — is removed too.
        val gateway = InMemoryStorageGateway()
        gateway.mkdirs("primary:/Library")
        val destRoot = DirRef("primary:/Library", "Library")
        val src = scanned(gateway, "primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv")
        val planned = planFor(listOf(src))
        val item = planned.single()
        val journal = InMemoryJournalStore()
        val report = OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
            .execute("run-1", planned)
        assertEquals(1, report.done)

        // Mirror OrganizeWorker.downloadPosters: write the poster into the
        // item's folder and journal it as a DONE isPoster operation.
        val folderPath = "primary:/Library/Movie/Alpha (2020)"
        val folder = DirRef(folderPath, "Alpha (2020)")
        val poster = gateway.createFile(
            folder, "poster.jpg", "image/jpeg",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00),
        )
        val mainOp = journal.operationsOf("run-1").single { !it.isCompanion && !it.isPoster }
        journal.insertOperation(
            OperationRecord(
                id = "poster-op-1",
                runId = "run-1",
                itemId = item.id,
                sourceParentId = folder.id,
                sourceParentName = folder.name,
                sourceFileId = poster.id,
                fileName = "poster.jpg",
                finalName = "poster.jpg",
                destinationParentId = folder.id,
                destinationSegments = item.targetSegments,
                state = OperationState.DONE,
                bytesTotal = 4,
                bytesDone = 4,
                isPoster = true,
                createdDirs = mainOp.createdDirs,
            ),
        )
        assertTrue(gateway.exists("$folderPath/poster.jpg"))

        val undo = UndoExecutor(gateway, journal, destRoot).undo("run-1")
        assertEquals(1, undo.movedBack)
        assertTrue(undo.problems.isEmpty(), undo.problems.toString())
        assertTrue(gateway.exists("primary:/Downloads/Alpha.2020.1080p.WEB-DL.mkv"))
        // The poster is gone and the created folder was removed as empty.
        assertTrue(!gateway.exists("$folderPath/poster.jpg"))
        assertTrue(!gateway.exists(folderPath))
        val posterOp = journal.operationsOf("run-1").single { it.isPoster }
        assertEquals(OperationState.UNDONE, posterOp.state)
        assertTrue(journal.run("run-1")!!.undone)
    }

    @Test
    fun scanExcludesDestinationTree() = runTest {
        val gateway = InMemoryStorageGateway()
        gateway.addFile("primary:/Downloads/a/Film.2020.1080p.mkv", size = 5, lastModified = 0)
        gateway.addFile("primary:/Downloads/Library/Movie/Old.2019.1080p.mkv", size = 5, lastModified = 0)
        val root = DirRef("primary:/Downloads", "Downloads")
        val dest = DirRef("primary:/Downloads/Library", "Library")
        val found = gateway.scan(root, recursive = true, maxDepth = 5, exclude = setOf(dest))
            .filterIsInstance<ScanEvent.Found>().toList()
        assertEquals(1, found.size)
        assertEquals("Film.2020.1080p.mkv", found.single().item.file.name)
        assertEquals(listOf("a"), found.single().item.ancestors)
    }
}
