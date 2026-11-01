package com.yaz.language.translate

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hostile inputs for the pack checks. offline-translator has no JVM tests for these files (its tests are
 * instrumented, app/src/androidTest), so each check follows OWASP's MASTG idea: feed the control the data an attacker
 * controls (a catalogue entry, a redirect, a body) and see it refused, https://mas.owasp.org/MASTG/
 * (MASTG-TEST-0019 data in the network, MASTG-TEST-0030 and 0028 style input validation).
 */
class PackChecksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val host = PackPolicy.ATTACHMENTS_HOST

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("expected a PackSecurityException")
        } catch (_: PackSecurityException) {
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // --- address ---

    @Test
    fun goodAddressesPass() {
        checkDownloadUrl("https://$host/main-workspace/translations-models/a.bin")
        checkDownloadUrl("https://$host:443/x")
        checkDownloadUrl("HTTPS://${host.uppercase()}/x")
    }

    @Test
    fun hostileAddressesAreRefused() {
        val hostile =
            listOf(
                "",
                "http://",
                "https://",
                "http://$host/x",
                "ftp://$host/x",
                "file:///etc/passwd",
                "javascript:alert(1)",
                "//$host/x",
                "/main-workspace/x",
                "https://evil.example/x",
                "https://$host.evil.example/x",
                "https://evil.example/$host",
                "https://${host}evil.example/x",
                "https://$host@evil.example/x",
                "https://user@$host/x",
                "https://user:pw@$host/x",
                "https://evil.example#@$host/x",
                "https://$host:8443/x",
                "https://$host:80/x",
                "https://127.0.0.1/x",
                "https://localhost/x",
                "https://$host\\@evil.example/x",
                "https:// $host/x",
                "https://$host/ x"
            )
        hostile.forEach { url -> refused { checkDownloadUrl(url) } }
    }

    @Test
    fun redirectsAreCheckedLikeTheFirstAddress() {
        val from = checkDownloadUrl("https://$host/a/b.bin")
        assertEquals("https://$host/c.bin", checkRedirect(from, "/c.bin").toString())
        assertEquals("https://$host/a/d.bin", checkRedirect(from, "d.bin").toString())
        listOf(null, "", "  ", "http://$host/x", "https://evil.example/x", "//evil.example/x", "https://$host@evil.example/", "file:///etc/passwd")
            .forEach { location -> refused { checkRedirect(from, location) } }
    }

    // --- paths ---

    @Test
    fun goodPathResolvesInsideTheBaseFolder() {
        val base = tmp.newFolder("base")
        val file = resolveUnder(base, "bin/model.afen.intgemm.alphas.bin")
        assertEquals(File(base.canonicalFile, "bin/model.afen.intgemm.alphas.bin"), file)
    }

    @Test
    fun hostilePathsAreRefused() {
        val base = tmp.newFolder("base")
        val hostile =
            listOf(
                "",
                "../x",
                "bin/../../x",
                "bin/..",
                "..",
                ".",
                "bin/./x",
                "/etc/x",
                "/",
                "bin//x",
                "bin/",
                "bin\\x",
                "..\\x",
                "bin/x\u0000.bin",
                "bin/x y",
                "bin/x\n",
                ".staging/x",
                "bin/.hidden",
                "a/b/c/d/e",
                "file:///etc/x",
                "~/x",
                "bin/%2e%2e/x",
                "x".repeat(600)
            )
        hostile.forEach { path -> refused { resolveUnder(base, path) } }
    }

    @Test
    fun aSymbolicLinkOutOfTheFolderIsRefused() {
        val base = tmp.newFolder("base")
        val outside = tmp.newFolder("outside")
        Files.createSymbolicLink(File(base, "bin").toPath(), outside.toPath())
        refused { resolveUnder(base, "bin/model.bin") }
    }

    // --- catalogue ---

    private val good = "https://$host/main-workspace/translations-models/a.bin"
    private val hash = "a".repeat(64)

    private fun entry(path: String = "bin/a.bin", url: String = good, size: String = "10", sha: String = hash) =
        """{"installPath":"$path","name":"a","role":"model","sha256":"$sha","sizeBytes":$size,"url":"$url"}"""

    private fun catalogue(vararg entries: String) = """{"packs":{"p":{"files":[${entries.joinToString(",")}]}}}"""

    @Test
    fun theCatalogueKeepsOnlyEntriesThatPassEveryCheck() {
        val parsed =
            parseCatalogueFiles(
                catalogue(
                    entry("bin/ok.bin"),
                    entry("bin/http.bin", url = "http://$host/a.bin"),
                    entry("bin/other.bin", url = "https://evil.example/a.bin"),
                    entry("../escape.bin"),
                    entry("/abs.bin"),
                    entry("bin/shortsha.bin", sha = "abc"),
                    entry("bin/upper.bin", sha = "A".repeat(64)),
                    entry("bin/zero.bin", size = "0"),
                    entry("bin/neg.bin", size = "-5"),
                    entry("bin/text.bin", size = "\"big\"")
                )
            )
        assertEquals(setOf("bin/ok.bin", "bin/upper.bin"), parsed.keys)
        // an upper case hash is read as lower case
        assertEquals(hash, parsed.getValue("bin/upper.bin").sha256)
    }

    @Test
    fun oneInstallPathWithTwoDifferentContentsIsDropped() {
        val parsed =
            parseCatalogueFiles(
                catalogue(entry("bin/dup.bin"), entry("bin/dup.bin", sha = "b".repeat(64)), entry("bin/same.bin"), entry("bin/same.bin"))
            )
        assertEquals(setOf("bin/same.bin"), parsed.keys)
    }

    @Test
    fun brokenCatalogueShapesGiveNothing() {
        assertTrue(parseCatalogueFiles("""{}""").isEmpty())
        assertTrue(parseCatalogueFiles("""{"packs":[]}""").isEmpty())
        assertTrue(parseCatalogueFiles("""{"packs":{"p":{"files":3}}}""").isEmpty())
        assertTrue(parseCatalogueFiles("""{"packs":{"p":{"files":[1,"x",null]}}}""").isEmpty())
    }

    @Test
    fun aTaskMustAgreeWithTheCatalogue() {
        val files = parseCatalogueFiles(catalogue(entry()))
        expectedFor(files, "bin/a.bin", good, 10)
        refused { expectedFor(files, "bin/missing.bin", good, 10) }
        refused { expectedFor(files, "bin/a.bin", "https://$host/other.bin", 10) }
        refused { expectedFor(files, "bin/a.bin", "https://evil.example/a.bin", 10) }
        refused { expectedFor(files, "bin/a.bin", good, 11) }
    }

    @Test
    fun theBundledCatalogueIsFullyAccepted() {
        // Gradle runs unit tests from the module folder.
        val text = File("src/main/assets/index_v7.json").readText()
        val parsed = parseCatalogueFiles(text)
        assertEquals(Regex("\"installPath\"").findAll(text).count(), parsed.size)
        assertTrue(parsed.size > 100)
        parsed.values.forEach {
            checkDownloadUrl(it.url)
            assertTrue(it.sizeBytes > 0)
        }
    }

    // --- body ---

    private fun expectedFor(bytes: ByteArray) = ExpectedFile("bin/a.bin", good, bytes.size.toLong(), sha(bytes))

    @Test
    fun aMatchingBodyIsMovedIntoPlaceWithNoTempLeft() {
        val dir = tmp.newFolder("stage")
        val body = ByteArray(100_000) { (it % 251).toByte() }
        val target = File(dir, "a.bin")
        val seen = intArrayOf(0)
        val read = writeVerified(ByteArrayInputStream(body), target, expectedFor(body), onBytes = { seen[0] += it })
        assertEquals(body.size.toLong(), read)
        assertEquals(body.size, seen[0])
        assertArrayEquals(body, target.readBytes())
        assertEquals(listOf("a.bin"), dir.list()!!.toList())
    }

    @Test
    fun aWrongHashDeletesTheTempFileAndLeavesNothing() {
        val dir = tmp.newFolder("stage")
        val body = ByteArray(5000) { 7 }
        val forged = body.copyOf().also { it[4999] = 8 }
        val target = File(dir, "a.bin")
        refused { writeVerified(ByteArrayInputStream(forged), target, expectedFor(body)) }
        assertFalse(target.exists())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aFailedCheckKeepsTheFileAlreadyInPlace() {
        val dir = tmp.newFolder("stage")
        val target = File(dir, "a.bin").also { it.writeText("old") }
        val body = ByteArray(10) { 1 }
        refused { writeVerified(ByteArrayInputStream(ByteArray(10) { 2 }), target, expectedFor(body)) }
        assertEquals("old", target.readText())
        assertEquals(listOf("a.bin"), dir.list()!!.toList())
    }

    @Test
    fun aBodyLongerThanTheCatalogueStopsAtOnce() {
        val dir = tmp.newFolder("stage")
        val body = ByteArray(1000) { 3 }
        var served = 0L
        val endless =
            object : InputStream() {
                override fun read() = 3.also { served++ }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    served += len
                    b.fill(3, off, off + len)
                    return len
                }
            }
        val target = File(dir, "a.bin")
        refused { writeVerified(endless, target, expectedFor(body)) }
        assertTrue("read $served bytes", served < body.size + 2 * PackPolicy.COPY_BUFFER_BYTES)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aBodyShorterThanTheCatalogueIsRefused() {
        val dir = tmp.newFolder("stage")
        val body = ByteArray(1000) { 3 }
        val target = File(dir, "a.bin")
        refused { writeVerified(ByteArrayInputStream(body.copyOf(999)), target, expectedFor(body)) }
        refused { writeVerified(ByteArrayInputStream(ByteArray(0)), target, expectedFor(body)) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aReadFailureDeletesTheTempFile() {
        val dir = tmp.newFolder("stage")
        val broken =
            object : InputStream() {
                override fun read() = throw java.io.IOException("connection reset")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    b[off] = 1
                    if (served++ > 0) throw java.io.IOException("connection reset")
                    return 1
                }

                var served = 0
            }
        try {
            writeVerified(broken, File(dir, "a.bin"), ExpectedFile("bin/a.bin", good, 100, hash))
            fail("expected an IOException")
        } catch (_: java.io.IOException) {
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aCancelledDownloadDeletesTheTempFile() {
        val dir = tmp.newFolder("stage")
        val body = ByteArray(100_000) { 5 }
        var calls = 0
        try {
            writeVerified(ByteArrayInputStream(body), File(dir, "a.bin"), expectedFor(body), checkActive = { if (calls++ > 1) throw IllegalStateException("cancelled") })
            fail("expected cancellation")
        } catch (_: IllegalStateException) {
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
