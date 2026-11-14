package com.flowkey.ime.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.flowkey.ime.core.LlmClient
import com.flowkey.ime.core.LlmConfig
import com.flowkey.ime.core.LocalAddress
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Inet6Address
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AI settings (optional rewrite). Off by default. */
object AiPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("flowkey_settings", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getBoolean("ai_enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("ai_enabled", on).apply()
    fun toneMatching(ctx: Context) = p(ctx).getBoolean("ai_tone_matching", true)
    fun setToneMatching(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("ai_tone_matching", on).apply()

    fun config(ctx: Context) = LlmConfig(
        provider = p(ctx).getString("ai_provider", "ollama") ?: "ollama",
        url = p(ctx).getString("ai_url", "") ?: "",
        model = p(ctx).getString("ai_model", "") ?: "",
        apiBase = p(ctx).getString("ai_api_base", "") ?: "",
        apiModel = p(ctx).getString("ai_api_model", "") ?: "",
        anthropicModel = p(ctx).getString("ai_anthropic_model", "") ?: "",
    )

    fun save(ctx: Context, cfg: LlmConfig) = p(ctx).edit()
        .putString("ai_provider", cfg.provider).putString("ai_url", cfg.url.trim())
        .putString("ai_model", cfg.model.trim()).putString("ai_api_base", cfg.apiBase.trim())
        .putString("ai_api_model", cfg.apiModel.trim())
        .putString("ai_anthropic_model", cfg.anthropicModel.trim()).apply()

    fun client(ctx: Context, transport: HttpTransport = HttpTransport(ctx)): LlmClient =
        LlmClient(config(ctx), transport) { slot -> KeyVault.get(ctx, slot) }

    /**
     * Like LlmClient.configured(), from the settings alone: no Keystore decrypt, so it is safe
     * on the main thread (keyboard mic start, app screens). The real key is read when sending.
     */
    fun ready(ctx: Context): Boolean {
        val cfg = config(ctx)
        return when (cfg.provider.takeIf { it in LlmClient.DEFAULT_MODELS } ?: "ollama") {
            "ollama" -> cfg.url.isNotBlank()
            "openai" -> LlmClient.schemeOf(LlmClient.openaiBase(cfg)) == "http" ||
                KeyVault.stored(ctx, LlmClient.keySlot(cfg.copy(provider = "openai")))
            else -> KeyVault.stored(ctx, LlmClient.keySlot(cfg.copy(provider = "anthropic")))
        }
    }

    /** AI on, but a cloud provider has no key (e.g. after restoring a backup on a new phone). */
    fun missingKey(ctx: Context): Boolean {
        if (!enabled(ctx)) return false
        val provider = config(ctx).provider
        return provider in setOf("openai", "anthropic") && !ready(ctx)
    }
}

/**
 * API keys, encrypted with an Android Keystore key (AES-256-GCM) and stored in the
 * `flowkey_secrets` preferences, which are excluded from backup and device transfer: a restored
 * ciphertext couldn't be decrypted anyway. No unlock prompt: the keyboard must work at any time.
 */
object KeyVault {
    private const val ALIAS = "flowkey_llm"
    private const val FILE = "flowkey_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** False when the Keystore refuses (broken on some devices); nothing is stored then. */
    fun set(ctx: Context, slot: String, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) { delete(ctx, slot); return true }
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val blob = c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8))
            prefs(ctx).edit().putString(slot, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("FlowKeyAI", "Keystore could not encrypt the key: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Null when missing or undecryptable (then the user re-enters it). */
    fun get(ctx: Context, slot: String): String? {
        val b64 = prefs(ctx).getString(slot, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(ctx: Context, slot: String) = get(ctx, slot) != null

    /** A key is saved in [slot] (no decrypt; get() can still fail if the Keystore lost it). */
    fun stored(ctx: Context, slot: String) = prefs(ctx).contains(slot)
    fun delete(ctx: Context, slot: String) = prefs(ctx).edit().remove(slot).apply()
}

/**
 * HTTP for the AI providers. https anywhere; plain http only to a server on this phone or the
 * local network (checked after resolving the name, and the resolved address is the one we
 * connect to). A local server that didn't answer is skipped for a while (e.g. on mobile data).
 */
class HttpTransport(ctx: Context) : LlmClient.Transport {
    @Volatile private var current: HttpURLConnection? = null
    @Volatile private var cancelled = false

    companion object {
        private val unreachable = ConcurrentHashMap<String, Long>()
        private const val SKIP_MS = 120_000L
        @Volatile private var watching = false

        private fun watchNetwork(ctx: Context) {
            if (watching) return
            watching = true
            try {
                val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
                cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = unreachable.clear()
                })
            } catch (e: Exception) {
                watching = false
            }
        }
    }

    init { watchNetwork(ctx) }

    override fun cancel() {
        cancelled = true
        current?.disconnect()
    }

    override fun post(url: String, headers: Map<String, String>, body: String, connectMs: Int, readMs: Int): Pair<Int, String> {
        if (cancelled) throw LlmClient.LlmError("cancelled")
        val u = URL(url)
        val scheme = u.protocol.lowercase()
        val host = u.host
        // The key slot was chosen for exactly this host (LlmClient.keySlot); refuse anything odd.
        if (!LlmClient.plainHost(host.orEmpty().lowercase())) throw LlmClient.LlmError("unsupported address")
        var target = u
        if (scheme == "http") {
            val skipUntil = unreachable[host]
            if (skipUntil != null && skipUntil > System.currentTimeMillis()) throw LlmClient.LlmError("server not reachable")
            val addr = InetAddress.getByName(host)
            if (!LocalAddress.isPrivateOrLoopback(addr)) {
                throw LlmClient.LlmError("http:// is only allowed for a server on your network — use https://")
            }
            val ip = if (addr is Inet6Address) "[${addr.hostAddress?.substringBefore('%')}]" else addr.hostAddress
            target = URL(scheme, ip, u.port, u.file)
        } else if (scheme != "https") {
            throw LlmClient.LlmError("unsupported address")
        }
        // A connection that drops mid-answer (a pooled keep-alive connection the server already
        // closed: "unexpected end of stream", "connection reset") is retried once. Timeouts,
        // refused connections and cancellation are not.
        try {
            return attempt(target, u, scheme, headers, body, connectMs, readMs)
        } catch (e: java.io.IOException) {
            val dropped = e is java.net.ProtocolException || e is java.io.EOFException ||
                (e is java.net.SocketException && !unreachableError(e))
            // The exception only (address and error), never the request or answer text.
            android.util.Log.w("FlowKeyAI", "POST to ${u.host} failed" + if (dropped) ", retrying" else "", e)
            if (!dropped || cancelled) throw e
        }
        if (cancelled) throw LlmClient.LlmError("cancelled")
        return attempt(target, u, scheme, headers, body, connectMs, readMs)
    }

    private fun attempt(target: URL, u: URL, scheme: String, headers: Map<String, String>, body: String,
                        connectMs: Int, readMs: Int): Pair<Int, String> {
        val host = u.host
        val conn = target.openConnection() as HttpURLConnection
        current = conn
        try {
            // A cancel() that came before `current` was set had nothing to disconnect.
            if (cancelled) throw LlmClient.LlmError("cancelled")
            conn.requestMethod = "POST"
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.doOutput = true
            if (scheme == "http") conn.setRequestProperty("Host", if (u.port > 0) "$host:${u.port}" else host)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } catch (e: java.net.SocketTimeoutException) {
                if (scheme == "http") unreachable[host] = System.currentTimeMillis() + SKIP_MS
                throw e
            } catch (e: java.net.SocketException) {
                if (scheme == "http" && unreachableError(e)) unreachable[host] = System.currentTimeMillis() + SKIP_MS
                throw e
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (cancelled) throw LlmClient.LlmError("cancelled")
            return code to text
        } finally {
            conn.disconnect()
            current = null
        }
    }

    /** The server can't be reached at all (refused, no route): skip it for a while, don't retry. */
    private fun unreachableError(e: java.net.SocketException) =
        e is java.net.ConnectException || e is java.net.NoRouteToHostException ||
            e is java.net.BindException || e is java.net.PortUnreachableException
}
