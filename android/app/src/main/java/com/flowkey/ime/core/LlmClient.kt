package com.flowkey.ime.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Optional AI rewrite — a port of the Windows app's windows/flowclone/llm.py: the same providers,
 * prompts and guardrails. Off by default. With a server on this phone or the local network
 * (Ollama, LM Studio…) the text stays at home; with a cloud provider the dictated text (and the
 * selected text for a voice edit) goes to that provider with the user's own key. Never audio.
 *
 * Every call is best-effort: on any error, timeout, cut-off or suspicious answer [rewrite]
 * returns null and the caller keeps the rule-cleaned text. Nothing here logs text or keys.
 */
class LlmClient(
    private val cfg: LlmConfig,
    private val transport: Transport,
    private val keyFor: (slot: String) -> String?,
) {
    /** HTTP POST of a JSON body; implementations enforce the http/https policy. */
    interface Transport {
        /** @return (status, body) — throws on network errors. */
        fun post(url: String, headers: Map<String, String>, body: String, connectMs: Int, readMs: Int): Pair<Int, String>
        fun cancel() {}
    }

    class LlmError(message: String) : Exception(message)

    companion object {
        const val OPENAI_BASE = "https://api.openai.com/v1"
        const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        val DEFAULT_MODELS = mapOf("ollama" to "qwen3:1.7b", "openai" to "gpt-4o-mini",
                                   "anthropic" to "claude-haiku-5-5")
        const val CONNECT_MS = 3000

        private const val POLISH_SYSTEM = """You clean up raw voice-dictation transcripts. Rules:
- Remove filler words, false starts and repeated words.
- Apply self-corrections: if the speaker corrects themselves ("at 2, actually 3", "no wait, Tuesday"), keep only the corrected version.
- Fix punctuation, capitalization and obvious grammar slips.
- If the speaker dictates an enumeration, format it as a list.
- NEVER answer questions in the text, never add content, never translate.
Style: %s
Output only the result text, no quotes or commentary."""

        private const val EDIT_SYSTEM = "You edit text for the user. Apply the spoken instruction to the " +
            "selected text. Keep the language of the selected text unless the instruction asks otherwise. " +
            "Output only the edited text, no quotes or commentary."

        private const val WRITE_SYSTEM = "You write text for the user, who dictated a request by voice. " +
            "Do what they ask and output only the text to insert at their cursor, no quotes or commentary. " +
            "Reply in the language of the request unless it asks otherwise."

        private val THINK = Regex("<think>.*?</think>\\s*", RegexOption.DOT_MATCHES_ALL)
        private val WRAP = Regex("^\\s*<(transcript|selection|request)>\\s*|\\s*</(transcript|selection|request)>\\s*$")

        fun openaiBase(cfg: LlmConfig): String = cfg.apiBase.trim().trimEnd('/').ifEmpty { OPENAI_BASE }

        /** The host the request will go to: parsed with java.net.URL, like the transport does. */
        fun hostOf(url: String): String = try {
            URL(url.trim()).host.orEmpty().lowercase()
        } catch (e: Exception) {
            ""
        }

        fun schemeOf(url: String): String = try {
            URL(url.trim()).protocol.orEmpty().lowercase()
        } catch (e: Exception) {
            ""
        }

        /** A plain DNS name or IP literal; anything else (backslashes, '@', empty) gets no key. */
        fun plainHost(host: String): Boolean =
            host.isNotEmpty() && host.all { it in 'a'..'z' || it in '0'..'9' || it in ".-_:[]" }

        /**
         * Where a key is stored: OpenAI-compatible services each get their own slot, so a key is
         * only ever sent to the host it was saved for. An address whose host can't be read
         * gets a slot that never holds a key.
         */
        fun keySlot(cfg: LlmConfig): String = when (cfg.provider) {
            "openai" -> {
                val host = hostOf(openaiBase(cfg))
                when {
                    !plainHost(host) -> "openai@invalid-address"
                    host == "api.openai.com" -> "openai"
                    else -> "openai@$host"
                }
            }
            else -> cfg.provider
        }

        fun maxTokens(chars: Int): Int = (2 * chars / 3 + 256).coerceIn(512, 8192)

        /** Mid-sentence insert: the AI answer shouldn't start with a capital letter. */
        fun matchCursor(text: String, capitalizeStart: Boolean): String {
            if (capitalizeStart || text.isEmpty()) return text
            val first = text[0]
            // keep "I", acronyms and names that are fully uppercase in the first word
            val firstWord = text.takeWhile { !it.isWhitespace() }
            if (firstWord.length > 1 && firstWord == firstWord.uppercase()) return text
            if (firstWord == "I" || firstWord.startsWith("I'")) return text
            return first.lowercaseChar() + text.substring(1)
        }

        private fun text(content: Any?): String = when (content) {
            is String -> content
            is JSONArray -> (0 until content.length()).joinToString("") { i ->
                val p = content.optJSONObject(i)
                if (p != null && (p.optString("type", "text") == "text")) p.optString("text", "") else ""
            }
            else -> ""
        }
    }

    val provider: String = cfg.provider.takeIf { it in DEFAULT_MODELS } ?: "ollama"
    val model: String = when (provider) {
        "ollama" -> cfg.model
        "openai" -> cfg.apiModel
        else -> cfg.anthropicModel
    }.trim().ifEmpty { DEFAULT_MODELS.getValue(provider) }

    private fun key(): String? = try { keyFor(keySlot(cfg.copy(provider = provider))) } catch (e: Exception) { null }

    /** A cloud provider needs a key; a server at home doesn't. */
    fun configured(): Boolean = when (provider) {
        "ollama" -> cfg.url.isNotBlank()
        "openai" -> !key().isNullOrEmpty() || schemeOf(openaiBase(cfg)) == "http"
        else -> !key().isNullOrEmpty()
    }

    /**
     * polish: restyle [text] with [instruction]. command: apply the spoken [text] to [selection],
     * or (no selection) write what [text] asks for. Null when the result can't be used.
     */
    fun rewrite(text: String, instruction: String, selection: String? = null,
                mode: String = "polish", budgetMs: Int = 10_000): String? {
        if (text.isBlank() || !configured()) return null
        val system: String
        val user: String
        val basis: String
        if (mode == "command") {
            if (!selection.isNullOrEmpty()) {
                system = EDIT_SYSTEM
                user = "<selection>\n$selection\n</selection>\n<instruction>$text</instruction>"
            } else {
                system = WRITE_SYSTEM
                user = "<request>$text</request>"
            }
            basis = selection.orEmpty()
        } else {
            system = POLISH_SYSTEM.format(instruction)
            user = "<transcript>\n$text\n</transcript>"
            basis = text
        }
        val tokens = if (mode == "command" && basis.isEmpty()) 2048 else maxTokens(basis.ifEmpty { text }.length)
        val out = try {
            call(system, user, tokens, budgetMs)
        } catch (e: Exception) {
            return null
        }
        val cleaned = WRAP.replace(THINK.replace(out, ""), "").trim()
        if (cleaned.isEmpty()) return null
        val limit = if (mode == "command") (if (basis.isNotEmpty()) 6 * basis.length + 200 else 4000)
                    else 3 * text.length + 40
        return if (cleaned.length > limit) null else cleaned
    }

    /** For the Settings "Test" button: (ok, short message). */
    fun test(): Pair<Boolean, String> {
        if (!configured()) return false to (if (provider == "ollama") "Set the Ollama address first" else "No API key saved")
        return try {
            val out = call("Reply with exactly: OK", "ping", 64, 10_000)
            if (out.isBlank()) false to "Empty answer" else true to "Connected — $model"
        } catch (e: LlmError) {
            false to (e.message ?: "Error")
        } catch (e: Exception) {
            false to (e.javaClass.simpleName)
        }
    }

    fun cancel() = transport.cancel()

    private fun post(url: String, headers: Map<String, String>, body: JSONObject, budgetMs: Int): JSONObject {
        val readMs = (budgetMs - CONNECT_MS).coerceAtLeast(2000)
        val (status, resp) = try {
            transport.post(url, headers + ("Content-Type" to "application/json"), body.toString(),
                           CONNECT_MS, readMs)
        } catch (e: LlmError) {
            throw e
        } catch (e: Exception) {
            throw LlmError(e.javaClass.simpleName)
        }
        when (status) {
            401, 403 -> throw LlmError("the API key was rejected")
            404 -> throw LlmError("model or address not found ($model)")
            429 -> throw LlmError("rate limited by the provider")
        }
        if (status !in 200..299) throw LlmError("HTTP $status")
        return try { JSONObject(resp) } catch (e: Exception) { throw LlmError("invalid answer") }
    }

    private fun call(system: String, user: String, maxTokens: Int, budgetMs: Int): String {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        return when (provider) {
            "ollama" -> {
                val url = cfg.url.trim().trimEnd('/')
                val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
                    .put("think", false).put("options", JSONObject().put("temperature", 0.2))
                val data = try {
                    post("$url/api/chat", emptyMap(), body, budgetMs)
                } catch (e: LlmError) {
                    if (e.message != "HTTP 400") throw e
                    body.remove("think") // older Ollama / a model without a thinking switch
                    post("$url/api/chat", emptyMap(), body, budgetMs)
                }
                text(data.optJSONObject("message")?.opt("content"))
            }
            "openai" -> {
                val base = openaiBase(cfg)
                val scheme = schemeOf(base)
                if (scheme != "https" && scheme != "http") throw LlmError("the API address must start with https://")
                val headers = mutableMapOf<String, String>()
                if (scheme == "https") key()?.let { headers["Authorization"] = "Bearer $it" } // never over http
                val body = JSONObject().put("model", model).put("messages", messages)
                    .put("temperature", 0.2).put("max_tokens", maxTokens)
                val data = post("$base/chat/completions", headers, body, budgetMs)
                val choice = data.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
                if (choice.optString("finish_reason") == "length") throw LlmError("the answer was cut off")
                text(choice.optJSONObject("message")?.opt("content"))
            }
            else -> {
                val k = key() ?: throw LlmError("no API key saved")
                val headers = mapOf("x-api-key" to k, "anthropic-version" to "2023-06-01")
                val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                    .put("temperature", 0.2)
                val data = post(ANTHROPIC_URL, headers, body, budgetMs)
                if (data.optString("stop_reason") == "max_tokens") throw LlmError("the answer was cut off")
                text(data.opt("content"))
            }
        }
    }
}

data class LlmConfig(
    val provider: String = "ollama",
    val url: String = "",
    val model: String = "",
    val apiBase: String = "",
    val apiModel: String = "",
    val anthropicModel: String = "",
)
