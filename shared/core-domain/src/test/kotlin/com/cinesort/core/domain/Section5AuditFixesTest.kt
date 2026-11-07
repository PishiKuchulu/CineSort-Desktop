package com.cinesort.core.domain

import com.cinesort.core.domain.execute.OrganizeExecutor
import com.cinesort.core.domain.gateway.InMemoryStorageGateway
import com.cinesort.core.domain.journal.InMemoryJournalStore
import com.cinesort.core.domain.model.DirRef
import com.cinesort.core.domain.model.FileRef
import com.cinesort.core.domain.model.ScannedFile
import com.cinesort.core.domain.naming.FolderNamer
import com.cinesort.core.domain.naming.NamingOptions
import com.cinesort.core.domain.planner.OrganizePlanner
import com.cinesort.core.domain.planner.PlanInput
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.parser.AnchorKind
import com.cinesort.core.parser.AnchorMediaNameParser
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.ParseResult
import com.cinesort.core.parser.ParseTrace
import com.cinesort.core.parser.PathContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the external audit of 2026-11-07 (AUD-002, AUD-003).
 */
class Section5AuditFixesTest {
    private val parser = AnchorMediaNameParser(currentYear = 2026)
    private val planner = OrganizePlanner()

    private fun scanned(
        file: FileRef,
        parentDir: DirRef,
        siblings: List<FileRef>,
    ): ScannedFile =
        ScannedFile(
            file = file,
            parentDir = parentDir,
            ancestors = emptyList(),
            siblings = siblings,
            sourceVolume = "primary",
        )

    private fun prefixRelatedVideos(gateway: InMemoryStorageGateway): List<ScannedFile> {
        val dir = "primary:/Downloads"
        val v1 = gateway.addFile("$dir/Show.S01E01.mkv", size = 1_000_000, lastModified = 0)
        val v2 = gateway.addFile("$dir/Show.S01E01.1080p.mkv", size = 2_000_000, lastModified = 0)
        val sub = gateway.addFile("$dir/Show.S01E01.1080p.fa.srt", size = 50_000, lastModified = 0)
        val parentDir = DirRef(dir, "Downloads")
        return listOf(
            scanned(v1, parentDir, listOf(v2, sub)),
            scanned(v2, parentDir, listOf(v1, sub)),
        )
    }

    private fun planFor(items: List<ScannedFile>): List<com.cinesort.core.domain.planner.PlannedItem> {
        val parseResults =
            items.associate { item ->
                item.file.id to parser.parse(item.file.name, PathContext(item.ancestors))
            }
        return planner.plan(PlanInput(items = items, parseResults = parseResults))
    }

    @Test
    fun companionIsOwnedByLongestMatchingVideoOnly() {
        val gateway = InMemoryStorageGateway()
        val planned = planFor(prefixRelatedVideos(gateway))
        val short = planned.first { it.scanned.file.name == "Show.S01E01.mkv" }
        val long = planned.first { it.scanned.file.name == "Show.S01E01.1080p.mkv" }
        assertTrue(
            short.companions.isEmpty(),
            "the shorter-base video must not claim the shared subtitle: ${short.companions}",
        )
        assertEquals(
            listOf("Show.S01E01.1080p.fa.srt"),
            long.companions.map { it.name },
        )
    }

    @Test
    fun sharedCompanionExecutionReportsNoFalseFailure() =
        runTest {
            val gateway = InMemoryStorageGateway()
            gateway.mkdirs("primary:/Library")
            val planned = planFor(prefixRelatedVideos(gateway))
            val journal = InMemoryJournalStore()
            val destRoot = DirRef("primary:/Library", "Library")
            val report =
                OrganizeExecutor(gateway, journal, destRoot, clock = { 42L })
                    .execute("run-1", planned)
            assertEquals(2, report.done)
            assertTrue(report.failed.isEmpty(), report.failed.toString())
            assertTrue(gateway.exists("primary:/Library/Tv series/Show/S01/Show.S01E01.mkv"))
            assertTrue(gateway.exists("primary:/Library/Tv series/Show/S01/Show.S01E01.1080p.mkv"))
            assertTrue(gateway.exists("primary:/Library/Tv series/Show/S01/Show.S01E01.1080p.fa.srt"))
        }

    @Test
    fun punctuationOnlyTitleIsHeldForReview() {
        // The formatter itself yields an empty folder name for such titles;
        // the planner must hold the item instead of planning an empty segment.
        assertEquals(
            "",
            FolderNamer.movieFolderName(".", null, emptyList(), null, NamingOptions()),
        )
        val gateway = InMemoryStorageGateway()
        val file = gateway.addFile("primary:/Downloads/Dot.2024.mkv", size = 10, lastModified = 0)
        val item = scanned(file, DirRef("primary:/Downloads", "Downloads"), emptyList())
        val fabricated =
            ParseResult.Movie(
                fileName = file.name,
                title = ".",
                year = null,
                confidence = Confidence.HIGH,
                trace = ParseTrace(null, null, AnchorKind.NONE, listOf("manual-edit")),
            )
        val planned =
            planner.plan(
                PlanInput(items = listOf(item), parseResults = mapOf(file.id to fabricated)),
            )
        val only = planned.single()
        assertEquals(PlannedStatus.NEEDS_REVIEW, only.status)
        assertTrue(!only.selected)
        assertTrue(only.warnings.any { it.contains("empty folder name") }, only.warnings.toString())
    }
}
