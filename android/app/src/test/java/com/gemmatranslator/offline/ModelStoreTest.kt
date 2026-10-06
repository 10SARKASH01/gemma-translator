package com.gemmatranslator.offline

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

class ModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Request(val path: String, val headers: Map<String, String>)
    private data class Reply(
        val body: ByteArray,
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val declaredSize: Long = body.size.toLong(),
    )

    private class LocalServer(private val respond: (Request) -> Reply) : Closeable {
        private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/asset"
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        private val worker = thread(name = "ModelStoreTestHTTP", isDaemon = true) {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val line = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val header = input.readLine() ?: break
                            if (header.isEmpty()) break
                            val colon = header.indexOf(':')
                            if (colon > 0) headers[header.substring(0, colon).lowercase()] = header.substring(colon + 1).trim()
                        }
                        val request = Request(line.split(' ')[1], headers)
                        requests.add(request)
                        val reply = respond(request)
                        val responseHeaders = buildString {
                            append("HTTP/1.1 ${reply.status} OK\r\nContent-Length: ${reply.declaredSize}\r\nConnection: close\r\n")
                            reply.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                            append("\r\n")
                        }
                        socket.getOutputStream().apply {
                            write(responseHeaders.toByteArray(Charsets.ISO_8859_1))
                            write(reply.body)
                            flush()
                        }
                    }
                } catch (_: SocketException) {
                    // Cancellation closes an active client; close() stops accept().
                }
            }
        }
        override fun close() {
            listener.close()
            worker.join(3000)
        }
    }

    private fun sha256(bytes: ByteArray) = ModelStore.digestHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun spec(url: String, bytes: ByteArray, archive: Boolean = false, required: List<String> = listOf("model.bin")) = AssetSpec(
        "test-model", "Test model", url, sha256(bytes), bytes.size.toLong(), archive, required,
    )
    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        BZip2CompressorOutputStream(output).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                entries.forEach { (name, bytes) ->
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
    }
    private fun resumedReply(request: Request, bytes: ByteArray): Reply {
        val range = request.headers["range"] ?: return Reply(bytes)
        val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
        return Reply(bytes.copyOfRange(offset, bytes.size), 206, mapOf("Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}"))
    }

    @Test fun verifiedPlainModelIsMarkedReadyAndReusedWithoutNetwork() {
        val bytes = "offline model data".toByteArray()
        val root = temporary.newFolder()
        val store = ModelStore(root, allowLocalHttpForTests = true)
        lateinit var asset: AssetSpec
        LocalServer { Reply(bytes) }.use { server ->
            asset = spec(server.url, bytes)
            store.install(asset)
            assertTrue(store.isReady(asset))
            assertArrayEquals(bytes, File(store.directory(asset.id), "model.bin").readBytes())
            assertTrue(File(store.directory(asset.id), ".installed").isFile)
            assertFalse(File(root, ".downloads/${asset.id}.part").exists())
            assertEquals(1, server.requests.size)
        }
        store.install(asset) // The server is closed; readiness must avoid another request.
    }

    @Test fun wrongShaRejectsDownloadAndDeletesCorruptFullCopy() {
        val bytes = "bad model data".toByteArray()
        val root = temporary.newFolder()
        val store = ModelStore(root, allowLocalHttpForTests = true)
        LocalServer { Reply(bytes) }.use { server ->
            val asset = spec(server.url, bytes).copy(sha256 = sha256("different bytes".toByteArray()))
            val error = assertThrows(IOException::class.java) { store.install(asset) }
            assertTrue(error.message!!.contains("SHA-256"))
            assertFalse(store.isReady(asset))
            assertFalse(File(root, ".downloads/${asset.id}.part").exists())
            assertFalse(store.directory("${asset.id}_installing").exists())
        }
    }

    @Test fun partialDownloadResumesWithCorrectHttpRangeAndRehashesPrefix() {
        val bytes = "0123456789ABCDEF".toByteArray()
        val root = temporary.newFolder()
        LocalServer { request -> resumedReply(request, bytes) }.use { server ->
            val asset = spec(server.url, bytes)
            val part = File(root, ".downloads/${asset.id}.part")
            part.parentFile!!.mkdirs()
            part.writeBytes(bytes.copyOfRange(0, 5))
            val store = ModelStore(root, allowLocalHttpForTests = true)
            store.install(asset)
            assertEquals("bytes=5-", server.requests.single().headers["range"])
            assertTrue(store.isReady(asset))
            assertArrayEquals(bytes, File(store.directory(asset.id), "model.bin").readBytes())
        }
    }

    @Test fun serverIgnoringRangeSafelyRestartsInsteadOfAppending() {
        val bytes = "0123456789ABCDEF".toByteArray()
        val root = temporary.newFolder()
        LocalServer { Reply(bytes) }.use { server ->
            val asset = spec(server.url, bytes)
            File(root, ".downloads/${asset.id}.part").apply { parentFile!!.mkdirs(); writeBytes(bytes.copyOfRange(0, 5)) }
            val store = ModelStore(root, allowLocalHttpForTests = true)
            store.install(asset)
            assertTrue(store.isReady(asset))
            assertArrayEquals(bytes, File(store.directory(asset.id), "model.bin").readBytes())
        }
    }

    @Test fun invalidRangeResponsePreservesPrefixAndDoesNotInstall() {
        val bytes = "0123456789ABCDEF".toByteArray()
        val root = temporary.newFolder()
        LocalServer { Reply(bytes.copyOfRange(5, bytes.size), 206, mapOf("Content-Range" to "bytes 0-10/${bytes.size}")) }.use { server ->
            val asset = spec(server.url, bytes)
            val part = File(root, ".downloads/${asset.id}.part").apply { parentFile!!.mkdirs(); writeBytes(bytes.copyOfRange(0, 5)) }
            val store = ModelStore(root, allowLocalHttpForTests = true)
            assertThrows(IOException::class.java) { store.install(asset) }
            assertArrayEquals(bytes.copyOfRange(0, 5), part.readBytes())
            assertFalse(store.isReady(asset))
        }
    }

    @Test fun interruptedShortDownloadKeepsProgressAndResumesNextAttempt() {
        val bytes = "0123456789ABCDEF".toByteArray()
        val root = temporary.newFolder()
        var first = true
        LocalServer { request ->
            if (first) { first = false; Reply(bytes.copyOfRange(0, 6), declaredSize = bytes.size.toLong()) }
            else resumedReply(request, bytes)
        }.use { server ->
            val asset = spec(server.url, bytes)
            val store = ModelStore(root, allowLocalHttpForTests = true)
            assertThrows(IOException::class.java) { store.install(asset) }
            val part = File(root, ".downloads/${asset.id}.part")
            assertTrue(part.isFile)
            assertEquals(6, part.length())
            store.install(asset)
            assertEquals("bytes=6-", server.requests.last().headers["range"])
            assertTrue(store.isReady(asset))
        }
    }

    @Test fun tarBzipExtractsOnlyRequiredFilesAndLicenses() {
        val bytes = archive(linkedMapOf(
            "bundle/model.onnx" to "quantized".toByteArray(),
            "bundle/model.fp32.onnx" to "unused".toByteArray(),
            "bundle/styles/voice.json" to "style".toByteArray(),
            "bundle/LICENSE" to "license notice".toByteArray(),
        ))
        val store = ModelStore(temporary.newFolder(), allowLocalHttpForTests = true)
        LocalServer { Reply(bytes) }.use { server ->
            val asset = spec(server.url, bytes, archive = true, required = listOf("model.onnx", "styles"))
            store.install(asset)
            val directory = store.directory(asset.id)
            assertTrue(store.isReady(asset))
            assertTrue(File(directory, "model.onnx").isFile)
            assertTrue(File(directory, "styles/voice.json").isFile)
            assertTrue(File(directory, "LICENSE").isFile)
            assertFalse(File(directory, "model.fp32.onnx").exists())
        }
    }

    @Test fun archiveTraversalAndMissingRequiredFilesNeverPublishMarker() {
        for (entries in listOf(
            mapOf("bundle/../../escaped.txt" to "escape".toByteArray()),
            mapOf("bundle/other.bin" to "no required model".toByteArray()),
        )) {
            val bytes = archive(entries)
            val root = temporary.newFolder()
            val store = ModelStore(root, allowLocalHttpForTests = true)
            LocalServer { Reply(bytes) }.use { server ->
                val asset = spec(server.url, bytes, archive = true)
                assertThrows(Exception::class.java) { store.install(asset) }
                assertFalse(store.isReady(asset))
                assertFalse(File(root.parentFile, "escaped.txt").exists())
                assertFalse(store.directory("${asset.id}_installing").exists())
            }
        }
    }

    @Test fun truncatedCompressedArchiveNeverPublishesInstalledModel() {
        val payload = ByteArray(8192).also { Random(42).nextBytes(it) }
        val complete = archive(mapOf("bundle/model.bin" to payload))
        val bytes = complete.copyOf(complete.size / 2)
        val store = ModelStore(temporary.newFolder(), allowLocalHttpForTests = true)
        LocalServer { Reply(bytes) }.use { server ->
            val asset = spec(server.url, bytes, archive = true)
            assertThrows(IOException::class.java) { store.install(asset) }
            assertFalse(store.isReady(asset))
        }
    }

    @Test fun readinessRejectsTruncatedFilesAndChangedManifestSha() {
        val bytes = "verified model".toByteArray()
        val store = ModelStore(temporary.newFolder(), allowLocalHttpForTests = true)
        LocalServer { Reply(bytes) }.use { server ->
            val asset = spec(server.url, bytes)
            store.install(asset)
            assertFalse(store.isReady(asset.copy(sha256 = "0".repeat(64))))
            File(store.directory(asset.id), "model.bin").writeBytes(bytes.copyOf(2))
            assertFalse(store.isReady(asset))
        }
    }

    @Test fun requiredDirectoryReadinessChecksEveryRecordedLeafFile() {
        val bytes = archive(linkedMapOf(
            "bundle/model.bin" to "model".toByteArray(),
            "bundle/styles/one.json" to "first".toByteArray(),
            "bundle/styles/two.json" to "second".toByteArray(),
        ))
        val store = ModelStore(temporary.newFolder(), allowLocalHttpForTests = true)
        LocalServer { Reply(bytes) }.use { server ->
            val asset = spec(server.url, bytes, archive = true, required = listOf("model.bin", "styles"))
            store.install(asset)
            val first = File(store.directory(asset.id), "styles/one.json")
            first.writeBytes(byteArrayOf(1))
            assertFalse(store.isReady(asset))
            first.writeBytes("first".toByteArray())
            assertTrue(store.isReady(asset))
            assertTrue(first.delete())
            assertFalse(store.isReady(asset))
        }
    }

    @Test fun cancellationKeepsPartialDownloadForNewSetupSession() {
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val root = temporary.newFolder()
        val store = ModelStore(root, allowLocalHttpForTests = true)
        LocalServer { request -> resumedReply(request, bytes) }.use { server ->
            val asset = spec(server.url, bytes)
            assertThrows(CancellationException::class.java) {
                store.install(asset) { completed, stage ->
                    if (completed > 0 && stage.startsWith("Downloading")) store.cancel()
                }
            }
            val part = File(root, ".downloads/${asset.id}.part")
            assertTrue(part.length() in 1 until bytes.size.toLong())
            assertFalse(store.isReady(asset))
            val resumed = ModelStore(root, allowLocalHttpForTests = true)
            resumed.install(asset)
            assertTrue(resumed.isReady(asset))
            assertTrue(server.requests.last().headers.containsKey("range"))
        }
    }

    @Test fun productionDownloadsRequireHttpsAndPathsStayInAppStorage() {
        val bytes = "model".toByteArray()
        val store = ModelStore(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.install(spec("http://127.0.0.1:1/model", bytes)) }
        for (path in listOf("../outside", "/outside", "sub/../../outside", "sub\\outside")) {
            assertThrows(IllegalArgumentException::class.java) { ModelStore.safeFile(store.root, path) }
        }
    }
}
