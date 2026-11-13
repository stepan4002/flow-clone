package com.flowkey.ime

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Optional, user-initiated download of the higher-accuracy Whisper small model.
 *
 * This is the ONLY code in FlowKey that uses the network. It fetches a fixed list of HTTPS
 * URLs (nothing comes from user input), resumes interrupted downloads from `.part` files,
 * and only accepts a file whose SHA-256 matches the hash pinned here.
 */
object ModelDownloader {

    private const val TAG = "FlowKeyDownload"
    private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/"
    private const val SPARE_BYTES = 50L * 1024 * 1024
    private const val PROGRESS_INTERVAL_MS = 250L

    data class RemoteFile(val name: String, val size: Long, val sha256: String) {
        val url: String get() = BASE_URL + name
    }

    /** Pinned from the HuggingFace tree API (LFS oid = SHA-256). */
    val WHISPER_SMALL = listOf(
        RemoteFile("small-encoder.int8.onnx", 112_442_483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
        RemoteFile("small-decoder.int8.onnx", 262_226_114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
        RemoteFile("small-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
    )

    val TOTAL_BYTES: Long = WHISPER_SMALL.sumOf { it.size }

    sealed class DlState {
        data object Idle : DlState()
        data class Running(val done: Long, val total: Long) : DlState()
        data object Verifying : DlState()
        data object Done : DlState()
        data class Failed(val reason: Reason, val detail: String = "") : DlState()
    }

    enum class Reason { STORAGE, SPACE, NETWORK, CHECKSUM }

    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** All files present with the expected size. Hashes were checked before each rename. */
    fun isInstalled(context: Context): Boolean {
        val dir = ModelLocator.whisperSmallDir(context) ?: return false
        return WHISPER_SMALL.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.size } }
    }

    /** A download was started earlier and stopped before it finished. */
    fun hasPartial(context: Context): Boolean {
        val dir = ModelLocator.whisperSmallDir(context) ?: return false
        return WHISPER_SMALL.any { File(dir, it.name + ".part").isFile } ||
            (WHISPER_SMALL.any { File(dir, it.name).isFile } && !isInstalled(context))
    }

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return // a second tap must not append to the same .part
        val appContext = context.applicationContext
        job = scope.launch { run(appContext) }
    }

    /** Stops the download; `.part` files are kept so the next start resumes. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = DlState.Idle
    }

    /** Removes the model. If it was in use, FlowKey switches back to Whisper base. */
    fun delete(context: Context) {
        cancel()
        ModelLocator.whisperSmallDir(context)?.deleteRecursively()
        if (Prefs.getWhisperVariant(context) == Prefs.VARIANT_SMALL) {
            Prefs.setWhisperVariant(context, Prefs.VARIANT_BASE)
        }
        RecognizerManager.initialize(context, force = true)
    }

    private suspend fun run(context: Context) {
        try {
            download(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = DlState.Idle
            throw e
        }
    }

    private suspend fun download(context: Context) {
        val dir = ModelLocator.whisperSmallDir(context)
        if (dir == null || !(dir.isDirectory || dir.mkdirs())) {
            _state.value = DlState.Failed(Reason.STORAGE)
            return
        }
        val remaining = WHISPER_SMALL.sumOf { f ->
            val out = File(dir, f.name)
            if (out.isFile && out.length() == f.size) 0L else f.size - partLength(dir, f).coerceAtMost(f.size)
        }
        val free = try {
            StatFs(dir.path).availableBytes
        } catch (e: IllegalArgumentException) {
            0L
        }
        if (free < remaining + SPARE_BYTES) {
            _state.value = DlState.Failed(Reason.SPACE, "${(remaining + SPARE_BYTES) / 1_000_000} MB")
            return
        }

        var done = TOTAL_BYTES - remaining
        _state.value = DlState.Running(done, TOTAL_BYTES)
        for (file in WHISPER_SMALL) {
            val out = File(dir, file.name)
            if (out.isFile && out.length() == file.size) continue
            val part = File(dir, file.name + ".part")
            try {
                done = fetch(file, part, done)
            } catch (e: IOException) {
                Log.w(TAG, "Download of ${file.name} failed", e)
                _state.value = DlState.Failed(Reason.NETWORK, describe(e))
                return
            }
            _state.value = DlState.Verifying
            if (!sha256(part).equals(file.sha256, ignoreCase = true)) {
                Log.w(TAG, "Checksum mismatch for ${file.name}; deleting")
                part.delete()
                _state.value = DlState.Failed(Reason.CHECKSUM, file.name)
                return
            }
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) {
                _state.value = DlState.Failed(Reason.STORAGE)
                return
            }
            _state.value = DlState.Running(done, TOTAL_BYTES)
        }
        _state.value = DlState.Done
    }

    /** A short, human reason for the status line (the full exception is in logcat). */
    private fun describe(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "no internet connection"
        is java.net.SocketTimeoutException -> "the connection timed out"
        is javax.net.ssl.SSLException -> "secure connection refused; a network filter may be blocking it"
        else -> e.message?.take(80) ?: "network error"
    }

    private fun partLength(dir: File, file: RemoteFile): Long =
        File(dir, file.name + ".part").let { if (it.isFile) it.length() else 0L }

    /** Downloads (or resumes) [file] into [part]. Returns the new overall byte count. */
    private suspend fun fetch(file: RemoteFile, part: File, doneBefore: Long): Long {
        var partLen = if (part.isFile) part.length() else 0L
        when (DownloadDecision.beforeRequest(partLen, file.size)) {
            DownloadDecision.Before.VERIFY -> return doneBefore + (file.size - partLen)
            DownloadDecision.Before.DISCARD -> {
                part.delete()
                partLen = 0
            }
            DownloadDecision.Before.REQUEST -> Unit
        }
        val conn = URL(file.url).openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            // Byte offsets in Range must refer to the file itself, never a gzip stream.
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (partLen > 0) conn.setRequestProperty("Range", "bytes=$partLen-")
            val code = conn.responseCode
            val rangeStart = conn.getHeaderField("Content-Range")?.let { DownloadDecision.contentRangeStart(it) }
            val append = when (DownloadDecision.onResponse(code, partLen, rangeStart)) {
                DownloadDecision.After.APPEND -> true
                DownloadDecision.After.TRUNCATE -> {
                    partLen = 0
                    false
                }
                DownloadDecision.After.VERIFY -> return doneBefore + (file.size - partLen)
                DownloadDecision.After.FAIL -> {
                    if (code == 206) part.delete() // wrong offset: start clean next time
                    throw IOException("HTTP $code")
                }
            }
            var done = doneBefore
            var lastEmit = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = partLen
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        done += n
                        if (written > file.size) throw IOException("${file.name}: more data than expected")
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            lastEmit = now
                            _state.value = DlState.Running(done, TOTAL_BYTES)
                        }
                    }
                    if (written != file.size) throw IOException("${file.name}: incomplete ($written of ${file.size})")
                }
            }
            return done
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Pure resume/restart decisions of [ModelDownloader], unit-tested on the JVM. */
object DownloadDecision {

    enum class Before { REQUEST, VERIFY, DISCARD }
    enum class After { APPEND, TRUNCATE, VERIFY, FAIL }

    fun beforeRequest(partLen: Long, expected: Long): Before = when {
        partLen == expected -> Before.VERIFY
        partLen > expected -> Before.DISCARD
        else -> Before.REQUEST
    }

    /**
     * 206 must continue exactly where the `.part` file ends; 200 means the server ignored Range
     * (start over); 416 means there is nothing more to send (verify what we have).
     */
    fun onResponse(code: Int, partLen: Long, contentRangeStart: Long?): After = when (code) {
        206 -> if (partLen > 0 && contentRangeStart == partLen) After.APPEND else After.TRUNCATE
        200 -> After.TRUNCATE
        416 -> if (partLen > 0) After.VERIFY else After.FAIL
        else -> After.FAIL
    }

    /** "bytes 1000-2999/3000" -> 1000. */
    fun contentRangeStart(header: String): Long? =
        Regex("bytes\\s+(\\d+)-").find(header)?.groupValues?.get(1)?.toLongOrNull()
}
