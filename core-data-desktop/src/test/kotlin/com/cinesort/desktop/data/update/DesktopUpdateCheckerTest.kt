package com.cinesort.desktop.data.update

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The desktop updater against a local MockWebServer: release-list
 * selection rules and the failure contract (a failed check is an
 * error with its HTTP code, never a silent "up to date").
 */
class DesktopUpdateCheckerTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun releasesJson(vararg releases: String): String =
        releases.joinToString(",", "[", "]")

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false): String {
        val assetJson = assets.joinToString(",") { name ->
            """{"name":"$name","browser_download_url":"https://github.com/PishiKuchulu/CineSort-Desktop/releases/download/$tag/$name","size":123}"""
        }
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[$assetJson]}"""
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `highest strict version wins and rolling tags are ignored`() = runTest {
        enqueue(
            releasesJson(
                release("latest", "CineSort-9.9.9.exe"),
                release("v1.1.0", "CineSort-1.1.0.exe", "CineSort-1.1.0.exe.sha256"),
                release("v1.3.0", "CineSort-1.3.0.exe", "CineSort-1.3.0.exe.sha256"),
                release("v1.2.0", "CineSort-1.2.0.exe"),
            ),
        )
        val info = DesktopUpdateChecker.fetchLatestNewerThan(
            currentVersion = "1.0.0",
            assetExtension = ".exe",
            releasesUrl = server.url("/releases").toString(),
        )
        assertNotNull(info)
        assertEquals("1.3.0", info!!.version)
        assertEquals("CineSort-1.3.0.exe", info.assetName)
        assertNotNull(info.sha256Url)
    }

    @Test
    fun `drafts prereleases and other-OS assets are skipped`() = runTest {
        enqueue(
            releasesJson(
                release("v2.0.0", "CineSort-2.0.0.exe", draft = true),
                release("v1.9.0", "CineSort-1.9.0.exe", prerelease = true),
                release("v1.5.0", "CineSort-1.5.0.dmg"),
                release("v1.4.0", "CineSort-1.4.0.exe"),
            ),
        )
        val info = DesktopUpdateChecker.fetchLatestNewerThan(
            "1.0.0", ".exe", server.url("/releases").toString(),
        )
        assertEquals("1.4.0", info?.version)
    }

    @Test
    fun `nothing newer returns null`() = runTest {
        enqueue(releasesJson(release("v1.0.0", "CineSort-1.0.0.exe")))
        assertNull(
            DesktopUpdateChecker.fetchLatestNewerThan(
                "1.0.0", ".exe", server.url("/releases").toString(),
            ),
        )
    }

    @Test
    fun `http failure surfaces its code instead of up-to-date`() = runTest {
        enqueue("rate limited", code = 403)
        val e = assertThrows(UpdateException::class.java) {
            kotlinx.coroutines.runBlocking {
                DesktopUpdateChecker.fetchLatestNewerThan(
                    "1.0.0", ".exe", server.url("/releases").toString(),
                )
            }
        }
        assertEquals(403, DesktopUpdateChecker.httpCodeIn(e.message))
    }

    @Test
    fun `download without a checksum is refused before any request`() = runTest {
        val e = assertThrows(UpdateException::class.java) {
            kotlinx.coroutines.runBlocking {
                DesktopUpdateChecker.download(
                    url = "https://github.com/PishiKuchulu/CineSort-Desktop/releases/download/v1.1.0/CineSort-1.1.0.exe",
                    dest = File(tempDir, "update.exe"),
                    sha256Url = null,
                ) { _, _ -> }
            }
        }
        assertEquals(UpdateFailure.CHECKSUM, e.failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `download from a foreign URL is refused`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                DesktopUpdateChecker.download(
                    url = "https://evil.example.com/PishiKuchulu/CineSort-Desktop/releases/download/v1/x.exe",
                    dest = File(tempDir, "update.exe"),
                    sha256Url = "https://github.com/PishiKuchulu/CineSort-Desktop/releases/download/v1/x.exe.sha256",
                ) { _, _ -> }
            }
        }
    }

    @Test
    fun `auto check is due once a day and never on clock skew`() {
        val day = DesktopUpdateChecker.AUTO_CHECK_INTERVAL_MS
        assertTrue(DesktopUpdateChecker.isAutoCheckDue(0L, 1_000L))
        assertTrue(DesktopUpdateChecker.isAutoCheckDue(1_000L, 1_000L + day))
        assertTrue(!DesktopUpdateChecker.isAutoCheckDue(1_000L, 1_000L + day - 1))
        assertTrue(!DesktopUpdateChecker.isAutoCheckDue(5_000L, 1_000L))
    }
}
