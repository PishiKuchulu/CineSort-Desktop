package com.cinesort.desktop.data.files

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopFileStoreTest {

    private val store = DesktopFileStore()

    @TempDir
    lateinit var tempDir: Path

    private fun writeFile(dir: Path, name: String, bytes: ByteArray): Path {
        Files.createDirectories(dir)
        val path = dir.resolve(name)
        Files.write(path, bytes)
        return path
    }

    private fun sampleBytes(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + 7) % 251).toByte() }

    @Test
    fun walkRespectsDepthAndReportsSizes() {
        val root = tempDir.resolve("lib")
        writeFile(root, "a.mkv", sampleBytes(100))
        writeFile(root.resolve("sub"), "b.mkv", sampleBytes(200))
        writeFile(root.resolve("sub/deep"), "c.mkv", sampleBytes(300))

        val shallow = store.walk(root, maxDepth = 1)
        val shallowNames = shallow.entries.map { it.path.fileName.toString() }.toSet()
        assertEquals(setOf("a.mkv", "sub"), shallowNames)
        assertEquals(0, shallow.unreadableCount)

        val deep = store.walk(root, maxDepth = 3)
        val sizes = deep.entries.filter { !it.isDirectory }
            .associate { it.path.fileName.toString() to it.sizeBytes }
        assertEquals(mapOf("a.mkv" to 100L, "b.mkv" to 200L, "c.mkv" to 300L), sizes)
    }

    @Test
    fun walkRejectsNonDirectory() {
        val file = writeFile(tempDir, "x.mkv", sampleBytes(1))
        assertThrows(IOException::class.java) { store.walk(file, maxDepth = 2) }
    }

    @Test
    fun sameVolumeMovePreservesBytesAndRemovesSource() = runBlocking {
        val sourceDir = tempDir.resolve("in")
        val targetDir = tempDir.resolve("out")
        val bytes = sampleBytes(1_000_000)
        val source = writeFile(sourceDir, "Movie.2024.mkv", bytes)

        val moved = store.moveFile(source, targetDir, "Movie.2024.mkv")

        assertEquals(targetDir.resolve("Movie.2024.mkv"), moved)
        assertArrayEquals(bytes, Files.readAllBytes(moved))
        assertFalse(Files.exists(source))
    }

    @Test
    fun verifiedCopyPathPreservesBytesAndLeavesNoPartial() = runBlocking {
        val sourceDir = tempDir.resolve("in2")
        val targetDir = tempDir.resolve("out2")
        val bytes = sampleBytes(3_000_001)
        val source = writeFile(sourceDir, "Show.S01E02.mkv", bytes)

        val moved = store.moveFile(source, targetDir, "Show.S01E02.mkv", forceCopyPath = true)

        assertArrayEquals(bytes, Files.readAllBytes(moved))
        assertFalse(Files.exists(source))
        val leftovers = Files.list(targetDir).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        assertEquals(listOf("Show.S01E02.mkv"), leftovers)
    }

    @Test
    fun moveRefusesToOverwriteExistingDestination() = runBlocking {
        val sourceDir = tempDir.resolve("in3")
        val targetDir = tempDir.resolve("out3")
        val source = writeFile(sourceDir, "dup.mkv", sampleBytes(10))
        writeFile(targetDir, "dup.mkv", sampleBytes(20))

        assertThrows(IOException::class.java) {
            runBlocking { store.moveFile(source, targetDir, "dup.mkv") }
        }
        assertTrue(Files.exists(source))
        assertEquals(20L, Files.size(targetDir.resolve("dup.mkv")))
    }

    @Test
    fun invalidTargetNameIsRejectedAndSourceSurvives() {
        val source = writeFile(tempDir.resolve("in4"), "ok.mkv", sampleBytes(5))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.moveFile(source, tempDir.resolve("out4"), "../evil.mkv") }
        }
        assertTrue(Files.exists(source))
    }

    @Test
    fun freeSpaceIsPositiveAndEmptyDirDeleteIsGuarded() {
        assertTrue(store.freeSpaceBytes(tempDir) > 0)
        val empty = tempDir.resolve("empty")
        Files.createDirectories(empty)
        assertTrue(store.deleteEmptyDirectory(empty))
        val full = tempDir.resolve("full")
        writeFile(full, "f.mkv", sampleBytes(1))
        assertFalse(store.deleteEmptyDirectory(full))
        assertTrue(Files.isDirectory(full))
    }
}
