/*
 * Not in offline-translator: the checks Language adds around its PackInstaller, each a small pure function
 * (no Android, no native library) so the JVM tests can feed them hostile input.
 *
 * Sources:
 * - Hash and size of a downloaded file checked before it is used, a mismatch refusing the file: Firefox,
 *   services/settings/Attachments.sys.mjs (Downloader._readAttachmentDownload / checkContentHash(buffer, size, hash)),
 *   https://github.com/mozilla-firefox/firefox/blob/main/services/settings/Attachments.sys.mjs
 * - OWASP MASVS-NETWORK-1 (secure channel, TLS only), MASVS-NETWORK-2 (pinned to the one expected origin),
 *   MASVS-CODE-4 (input validation, untrusted data checked before use), MASVS-STORAGE-2 (no path chosen by data),
 *   https://mas.owasp.org/MASVS/ ; MASTG-TEST-0019 / MASTG-TEST-0020 style hostile inputs for the tests,
 *   https://mas.owasp.org/MASTG/
 * - Path containment by canonical path: OWASP Path Traversal, https://owasp.org/www-community/attacks/Path_Traversal
 *   (Android's "Zip Path Traversal" advice uses the same canonicalPath startsWith check,
 *   https://developer.android.com/privacy-and-security/risks/zip-path-traversal)
 * - Write to a temp name, verify, atomic rename: java.nio.file.Files.move with ATOMIC_MOVE,
 *   https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/StandardCopyOption.html
 * - The catalogue's file entries (installPath, sizeBytes, sha256, url): Language's engine/catalog/languages.py,
 *   the format of offline-translator's app/src/main/assets/index_v7.json.
 */
package com.yaz.language.translate

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What a pack file may be fetched from, and how long the connection may wait. */
object PackPolicy {
    /** Firefox Remote Settings' attachment host: the only origin the catalogue uses (engine/catalog/languages.py). */
    const val ATTACHMENTS_HOST = "firefox-settings-attachments.cdn.mozilla.net"
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000

    /** Redirects followed, each one checked as strictly as the first address. */
    const val MAX_REDIRECTS = 3
    const val COPY_BUFFER_BYTES = 16 * 1024
}

/** A refused pack, address, path or file. */
class PackSecurityException(message: String) : IOException(message)

/** The Network switch is off: nothing is fetched. */
class NetworkNotAllowedException : IOException("Network is off in Language's settings")

/**
 * The address must be https, on the attachments host, with no user info and no port other than 443.
 * Parsed by java.net.URI, which rejects spaces, backslashes and a missing host.
 */
fun checkDownloadUrl(url: String): URI {
    val uri =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            throw PackSecurityException("Refused address: not valid")
        }
    if (!"https".equals(uri.scheme, ignoreCase = true)) throw PackSecurityException("Refused address: not https")
    if (uri.rawUserInfo != null) throw PackSecurityException("Refused address: user info")
    if (uri.port != -1 && uri.port != 443) throw PackSecurityException("Refused address: port")
    val host = uri.host ?: throw PackSecurityException("Refused address: no host")
    if (!PackPolicy.ATTACHMENTS_HOST.equals(host, ignoreCase = true)) throw PackSecurityException("Refused address: other host")
    return uri
}

/** A redirect's Location is resolved against the address it came from and must pass [checkDownloadUrl] too. */
fun checkRedirect(from: URI, location: String?): URI {
    if (location.isNullOrBlank()) throw PackSecurityException("Refused redirect: no target")
    val target =
        try {
            from.resolve(location)
        } catch (_: IllegalArgumentException) {
            throw PackSecurityException("Refused redirect: not valid")
        }
    return checkDownloadUrl(target.toString())
}

private val SEGMENT = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}")
private const val MAX_SEGMENTS = 4

/**
 * A relative path from the catalogue, resolved under [base]: no empty, "." or ".." part, no absolute path, no
 * backslash, no part starting with a dot (the staging folder is ".staging"), a plain name per part, at most four
 * parts, then the canonical path (symbolic links followed) must still lie inside the canonical [base].
 */
fun resolveUnder(base: File, relative: String): File {
    if (relative.isEmpty() || relative.length > 512) throw PackSecurityException("Refused path: size")
    if (relative.startsWith("/")) throw PackSecurityException("Refused path: absolute")
    val parts = relative.split('/')
    if (parts.size > MAX_SEGMENTS) throw PackSecurityException("Refused path: too deep")
    for (part in parts) {
        if (part == "." || part == "..") throw PackSecurityException("Refused path: dot part")
        if (!SEGMENT.matches(part)) throw PackSecurityException("Refused path: unexpected characters")
    }
    val root = base.canonicalFile
    val target = File(root, relative).canonicalFile
    // Compared name by name (java.nio.file.Path.startsWith, https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html#startsWith(java.nio.file.Path)):
    // a string prefix of root + "/" refused everything under "/" (the app process's working folder), "//".
    if (target == root || !target.toPath().startsWith(root.toPath())) throw PackSecurityException("Refused path: outside the folder")
    return target
}

/** One file of a pack as the bundled catalogue states it. */
data class ExpectedFile(
    val installPath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * The catalogue's files by installPath, read from the bundled index (never from the network). An entry that
 * fails a check (address, path, hash shape, size) is left out, so a task needing it is refused; one installPath
 * given twice with different content is left out too.
 */
fun parseCatalogueFiles(json: String): Map<String, ExpectedFile> {
    val packs = Json.parseToJsonElement(json).jsonObject["packs"] as? JsonObject ?: return emptyMap()
    val found = HashMap<String, ExpectedFile>()
    val conflicting = HashSet<String>()
    for (pack in packs.values) {
        val files = (pack as? JsonObject)?.get("files") as? JsonArray ?: continue
        for (element in files) {
            val file = element as? JsonObject ?: continue
            val installPath = (file["installPath"] as? JsonPrimitive)?.contentOrNull ?: continue
            val url = (file["url"] as? JsonPrimitive)?.contentOrNull ?: continue
            val size = (file["sizeBytes"] as? JsonPrimitive)?.longOrNull ?: continue
            val sha = (file["sha256"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: continue
            if (size <= 0 || !SHA256_HEX.matches(sha)) continue
            try {
                checkDownloadUrl(url)
                resolveUnder(File("."), installPath)
            } catch (_: PackSecurityException) {
                continue
            }
            val entry = ExpectedFile(installPath, url, size, sha)
            val before = found.put(installPath, entry)
            if (before != null && before != entry) conflicting.add(installPath)
        }
    }
    conflicting.forEach(found::remove)
    return found
}

/** The catalogue's entry for an engine task; the task must agree with it on address and size. */
fun expectedFor(files: Map<String, ExpectedFile>, installPath: String, url: String, sizeBytes: Long): ExpectedFile {
    val expected = files[installPath] ?: throw PackSecurityException("Refused file: not in the catalogue")
    if (expected.url != url) throw PackSecurityException("Refused file: address differs from the catalogue")
    if (expected.sizeBytes != sizeBytes) throw PackSecurityException("Refused file: size differs from the catalogue")
    return expected
}

/**
 * Reads [input] into "<target>.part" while hashing it, stops as soon as it holds more than the catalogue's size,
 * refuses a size or SHA-256 that differs, and only then renames the temp file onto [target] (atomic move). On any
 * failure the temp file is deleted and nothing is left at [target]. Returns the bytes read.
 */
fun writeVerified(
    input: InputStream,
    target: File,
    expected: ExpectedFile,
    onBytes: (Int) -> Unit = {},
    checkActive: () -> Unit = {}
): Long {
    val parent = target.parentFile ?: throw PackSecurityException("Refused path: no folder")
    val part = File(parent, target.name + ".part")
    part.delete()
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        part.outputStream().use { output ->
            val buffer = ByteArray(PackPolicy.COPY_BUFFER_BYTES)
            while (true) {
                checkActive()
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > expected.sizeBytes) throw PackSecurityException("Refused file: larger than the catalogue says")
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
                onBytes(read)
            }
        }
        if (total != expected.sizeBytes) throw PackSecurityException("Refused file: shorter than the catalogue says")
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expected.sha256.toByteArray(Charsets.US_ASCII))) {
            throw PackSecurityException("Refused file: SHA-256 differs from the catalogue")
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return total
    } finally {
        part.delete()
    }
}
