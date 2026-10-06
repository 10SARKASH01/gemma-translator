package com.gemmatranslator.offline

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

data class AssetSpec(
    val id: String,
    val title: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val archive: Boolean,
    val requiredFiles: List<String>,
)

object ModelCatalog {
    val GEMMA = AssetSpec(
        "gemma", "Gemma 4 E2B",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm",
        "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42",
        2_583_085_056L, false, listOf("gemma-4-E2B-it.litertlm"),
    )
    fun all(): List<AssetSpec> = (listOf(GEMMA, SpeechAssets.STT) + SpeechAssets.VOICES.values).distinctBy { it.id }
}

data class DownloadProgress(val title: String, val completed: Long, val total: Long, val stage: String)

/** Network access belongs exclusively to explicit setup, never to inference. */
class ModelStore(val root: File, private val allowLocalHttpForTests: Boolean = false) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null

    fun directory(id: String): File {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]*"))) { "Invalid model identifier" }
        return File(root, id)
    }

    fun isReady(spec: AssetSpec): Boolean {
        return try {
            val folder = directory(spec.id)
            val marker = Properties().apply { File(folder, ".installed").inputStream().use { load(it) } }
            val installedFiles = marker.stringPropertyNames().filter { it.startsWith("file.") }
            marker.getProperty("sha256") == spec.sha256 && installedFiles.isNotEmpty() && installedFiles.all { key ->
                val file = safeFile(folder, key.removePrefix("file."))
                file.isFile && file.length() > 0 && file.length().toString() == marker.getProperty(key)
            } && spec.requiredFiles.all { name ->
                val file = safeFile(folder, name)
                if (file.isDirectory) file.walkTopDown().any { it.isFile && it.length() > 0 }
                else file.isFile && file.length() > 0 && file.length().toString() == marker.getProperty("size.$name")
            }
        } catch (_: Exception) { false }
    }

    fun missing(): List<AssetSpec> = ModelCatalog.all().filterNot(::isReady)
    fun cancel() { cancelled.set(true); connection?.disconnect() }

    fun installAll(progress: (DownloadProgress) -> Unit) {
        cancelled.set(false)
        root.mkdirs()
        val specs = missing()
        val total = specs.sumOf { it.bytes }
        val headroom = total + 512L * 1024 * 1024
        if (root.usableSpace < headroom) throw IOException("Free at least ${formatBytes(headroom)} of phone storage before setup.")
        var completed = 0L
        for (spec in specs) {
            checkCancelled()
            install(spec) { bytes, stage -> progress(DownloadProgress(spec.title, completed + bytes, total, stage)) }
            completed += spec.bytes
        }
        progress(DownloadProgress("All models installed", total, total, "Ready for offline use"))
    }

    fun install(spec: AssetSpec, progress: (Long, String) -> Unit = { _, _ -> }) {
        require(spec.bytes > 0 && spec.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid asset metadata" }
        require(spec.requiredFiles.isNotEmpty()) { "Model has no required files" }
        if (isReady(spec)) return
        root.mkdirs()
        val downloads = File(root, ".downloads").apply { mkdirs() }
        val part = File(downloads, "${spec.id}.part")
        val staging = directory("${spec.id}_installing")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        try {
            download(spec, part, progress)
            checkCancelled()
            progress(spec.bytes, "Installing verified files")
            if (spec.archive) unpack(part, staging, spec.requiredFiles)
            else {
                val file = safeFile(staging, spec.requiredFiles.single())
                file.parentFile?.mkdirs()
                if (!part.renameTo(file)) throw IOException("Cannot move downloaded model into app storage")
            }
            val marker = Properties().apply { setProperty("sha256", spec.sha256) }
            for (name in spec.requiredFiles) {
                val file = safeFile(staging, name)
                if (!file.exists() || (file.isFile && file.length() == 0L) ||
                    (file.isDirectory && !file.walkTopDown().any { it.isFile && it.length() > 0 })) {
                    throw IOException("${spec.title} is missing required file: $name")
                }
                if (file.isFile) marker.setProperty("size.$name", file.length().toString())
            }
            staging.walkTopDown().filter { it.isFile && it.length() > 0 }.forEach { file ->
                marker.setProperty("file.${file.relativeTo(staging).invariantSeparatorsPath}", file.length().toString())
            }
            File(staging, ".installed").outputStream().use { marker.store(it, "Verified offline model") }
            val destination = directory(spec.id)
            if (destination.exists() && !destination.deleteRecursively()) throw IOException("Cannot replace ${spec.title}")
            if (!staging.renameTo(destination)) throw IOException("Cannot finish installing ${spec.title}")
            part.delete()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun download(spec: AssetSpec, part: File, progress: (Long, String) -> Unit) {
        val url = URL(spec.url)
        require(url.protocol == "https" || (allowLocalHttpForTests && url.host in listOf("127.0.0.1", "localhost"))) {
            "Model downloads require HTTPS"
        }
        var offset = if (part.exists()) part.length() else 0L
        if (offset > spec.bytes) { part.delete(); offset = 0 }
        var digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) {
            progress(offset, "Checking saved download")
            part.inputStream().buffered().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    checkCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        if (offset < spec.bytes) {
            val http = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "GemmaTranslator-Android/1.0")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            connection = http
            try {
                checkCancelled()
                val status = http.responseCode
                if (status != 200 && status != 206) throw IOException("${spec.title} download failed (HTTP $status). Check your connection and retry setup.")
                if (offset > 0 && status == 200) { offset = 0; digest = MessageDigest.getInstance("SHA-256") }
                if (status == 206) {
                    val range = http.getHeaderField("Content-Range") ?: ""
                    if (!range.startsWith("bytes $offset-") || !range.endsWith("/${spec.bytes}")) throw IOException("Invalid resumed model download")
                }
                val expectedLength = spec.bytes - offset
                if (http.contentLengthLong >= 0 && http.contentLengthLong != expectedLength) {
                    throw IOException("${spec.title} download has an unexpected size")
                }
                var received = offset
                var lastUpdate = 0L
                http.inputStream.buffered().use { input ->
                    FileOutputStream(part, offset > 0).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            checkCancelled()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (received + n > spec.bytes) throw IOException("Model download exceeded its expected size")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            received += n
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 400) {
                                progress(received, "Downloading; setup can be paused and resumed")
                                lastUpdate = now
                            }
                        }
                        output.fd.sync()
                    }
                }
            } finally {
                http.disconnect()
                connection = null
            }
        }
        checkCancelled()
        if (part.length() < spec.bytes) throw IOException("${spec.title} download was interrupted. Retry setup to resume the saved progress.")
        if (part.length() != spec.bytes || digestHex(digest.digest()) != spec.sha256) {
            part.delete()
            throw IOException("${spec.title} failed its SHA-256 verification. Retry setup to download a clean copy.")
        }
    }

    private fun unpack(archive: File, target: File, required: List<String>) {
        archive.inputStream().buffered().use { raw ->
            BZip2CompressorInputStream(raw).use { compressed ->
                TarArchiveInputStream(compressed).use { tar ->
                    var commonRoot: String? = null
                    var extractedBytes = 0L
                    while (true) {
                        checkCancelled()
                        val entry = tar.nextTarEntry ?: break
                        val path = entry.name.removePrefix("./")
                        require(!path.startsWith("/") && !path.contains('\\') && path.split('/').none { it == ".." }) { "Unsafe model archive path" }
                        val top = path.substringBefore('/')
                        if (commonRoot == null) commonRoot = top
                        require(top == commonRoot) { "Model archive must have one root directory" }
                        val relative = path.substringAfter('/', "").trimEnd('/')
                        if (relative.isEmpty()) continue
                        if (entry.isSymbolicLink || entry.isLink) throw IOException("Model archives may not contain links")
                        val allowed = required.any { relative == it || relative.startsWith("$it/") } ||
                            relative.substringAfterLast('/').uppercase().let { it.startsWith("LICENSE") || it.startsWith("COPYING") || it == "MODEL_CARD" || it.startsWith("NOTICE") }
                        if (!allowed || entry.isDirectory) continue
                        if (!entry.isFile) throw IOException("Unsupported model archive entry")
                        extractedBytes += entry.size
                        if (extractedBytes > 3L * 1024 * 1024 * 1024) throw IOException("Model archive is unexpectedly large")
                        val file = safeFile(target, relative)
                        file.parentFile?.mkdirs()
                        file.outputStream().buffered().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkCancelled()
                                val n = tar.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Setup paused. Downloaded progress is saved.")
    }

    companion object {
        fun safeFile(root: File, relative: String): File {
            require(relative.isNotBlank() && !relative.startsWith('/') && !relative.contains('\\') &&
                relative.split('/').none { it == ".." }) { "Invalid model file path" }
            val candidate = File(root, relative).canonicalFile
            require(candidate.path.startsWith(root.canonicalPath + File.separator)) { "Model file escapes app storage" }
            return candidate
        }
        fun digestHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1_000_000_000.0) else "%.0f MB".format(bytes / 1_000_000.0)
    }
}
