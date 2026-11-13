package com.flowkey.ime

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Finds STT model files on disk. Expected layout under `<external files>/model/`:
 *
 *   model/whisper/    -> Whisper encoder/decoder/tokens (default multilingual engine)
 *   model/zipformer/  -> streaming Zipformer encoder/decoder/joiner/tokens (optional EN engine)
 *
 * Files dropped directly into `model/` are also detected: a joiner file marks the
 * directory as a Zipformer model, otherwise it is treated as Whisper.
 *
 * If the APK was built with models bundled under assets/model/, they are copied
 * out to the external files dir on first access so both cases share one code path.
 */
object ModelLocator {

    data class WhisperModel(val encoder: String, val decoder: String, val tokens: String)

    data class ZipformerModel(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
    )

    fun modelRoot(context: Context): File = File(context.getExternalFilesDir(null), "model")

    fun whisperDir(context: Context): File = File(modelRoot(context), "whisper")

    fun zipformerDir(context: Context): File = File(modelRoot(context), "zipformer")

    fun locateWhisper(context: Context): WhisperModel? {
        ensureAssetsCopied(context)
        for (dir in listOf(whisperDir(context), modelRoot(context))) {
            if (dir == modelRoot(context) && pick(dir, "joiner") != null) continue
            val encoder = pick(dir, "encoder") ?: continue
            val decoder = pick(dir, "decoder") ?: continue
            val tokens = pickTokens(dir) ?: continue
            return WhisperModel(encoder.absolutePath, decoder.absolutePath, tokens.absolutePath)
        }
        return null
    }

    fun locateZipformer(context: Context): ZipformerModel? {
        ensureAssetsCopied(context)
        for (dir in listOf(zipformerDir(context), modelRoot(context))) {
            val joiner = pick(dir, "joiner") ?: continue
            val encoder = pick(dir, "encoder") ?: continue
            val decoder = pick(dir, "decoder") ?: continue
            val tokens = pickTokens(dir) ?: continue
            return ZipformerModel(
                encoder.absolutePath,
                decoder.absolutePath,
                joiner.absolutePath,
                tokens.absolutePath,
            )
        }
        return null
    }

    /** Picks a .onnx file whose name contains [keyword], preferring int8-quantized variants. */
    private fun pick(dir: File, keyword: String): File? {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".onnx") && f.name.contains(keyword, ignoreCase = true)
        } ?: return null
        return candidates.sortedWith(
            compareByDescending<File> { it.name.contains("int8", ignoreCase = true) }
                .thenBy { it.name }
        ).firstOrNull()
    }

    private fun pickTokens(dir: File): File? {
        val exact = File(dir, "tokens.txt")
        if (exact.isFile) return exact
        return dir.listFiles { f ->
            f.isFile && f.name.endsWith(".txt") && f.name.contains("tokens", ignoreCase = true)
        }?.minByOrNull { it.name }
    }

    @Volatile
    private var assetsCopied = false

    @Synchronized
    private fun ensureAssetsCopied(context: Context) {
        if (assetsCopied) return
        assetsCopied = true
        // Re-copy bundled models once per install/update: replaces files from older builds
        // and any copy that was cut short before the copy became atomic.
        val stamp = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        } catch (_: Exception) {
            ""
        }
        val marker = File(modelRoot(context), ".bundled-copy")
        val fresh = stamp.isNotEmpty() && marker.isFile && marker.readText() == stamp
        try {
            copyAssetDir(context, "model", modelRoot(context), overwrite = !fresh)
            if (stamp.isNotEmpty()) marker.writeText(stamp)
        } catch (_: IOException) {
            // No bundled models, or copy failed; on-disk models may still exist.
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File, overwrite: Boolean) {
        val names = context.assets.list(assetPath) ?: return
        if (names.isEmpty()) return
        target.mkdirs()
        for (name in names) {
            val childAsset = "$assetPath/$name"
            val children = context.assets.list(childAsset)
            if (children != null && children.isNotEmpty()) {
                copyAssetDir(context, childAsset, File(target, name), overwrite)
            } else {
                val out = File(target, name)
                if (!overwrite && out.exists() && out.length() > 0) continue
                // Copy to a temp file and rename, so a process killed mid-copy never
                // leaves a truncated model that later looks complete.
                val tmp = File(target, "$name.part")
                try {
                    context.assets.open(childAsset).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) tmp.delete()
                } catch (_: IOException) {
                    // Entry was an empty directory or unreadable; skip it.
                    tmp.delete()
                }
            }
        }
    }
}
