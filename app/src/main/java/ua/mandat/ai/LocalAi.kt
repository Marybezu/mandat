package ua.mandat.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One piece the model found in pasted text; the owner confirms each before anything is saved. */
data class Found(val kind: String, val text: String, val person: String, val due: String)

/**
 * The optional on-device model (Gemma via LiteRT-LM). Nothing leaves the phone: no internet
 * permission, prompts and answers live in memory only, and the model only suggests — every
 * suggestion is shown to the owner and saved only on confirmation.
 */
class LocalAi(private val context: Context, val models: ModelStore, private val scope: CoroutineScope) {
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private val mutex = Mutex()
    private var idleJob: Job? = null

    /** "GPU" or "CPU", for the settings screen. */
    var backend: String = "?"
        private set

    fun ready() = models.modelFile() != null

    private fun engineLocked(): Engine {
        val path = models.modelFile()?.absolutePath ?: error("no model")
        engine?.takeIf { loadedPath == path }?.let { return it }
        engine?.close()
        engine = null
        val created = runCatching { create(path, gpu = true).also { backend = "GPU" } }
            .getOrElse { create(path, gpu = false).also { backend = "CPU" } }
        engine = created
        loadedPath = path
        return created
    }

    private fun create(path: String, gpu: Boolean): Engine =
        Engine(EngineConfig(modelPath = path, backend = if (gpu) Backend.GPU() else Backend.CPU(), maxNumTokens = 8192, cacheDir = context.cacheDir.absolutePath))
            .also { it.initialize() }

    private suspend fun <T> withEngine(block: (Engine) -> T): T {
        idleJob?.cancel()
        try {
            return mutex.withLock { withContext(Dispatchers.Default) { block(engineLocked()) } }
        } finally {
            // Memory back to the phone after five minutes without use
            idleJob = scope.launch { delay(IDLE_MS); release() }
        }
    }

    /** Frees the model's memory (also on lock); waits for a running answer to finish first. */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            loadedPath = null
        }
    }

    private fun ask(e: Engine, system: String, prompt: String, maxTokens: Int, temperature: Double, json: String? = null): String =
        e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature),
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = maxTokens,
                enableResponseFormat = json != null,
            ),
        ).use { conv ->
            if (json != null) conv.sendMessage(prompt, responseFormat = ResponseFormat.json(json)).toString()
            else conv.sendMessage(prompt).toString()
        }.trim()

    /** Splits pasted text (a letter, notes from a meeting) into matters, delegations, promises, deadlines and notes. */
    suspend fun split(text: String, people: List<String>, today: String): List<Found> {
        val prompt = buildString {
            append("Today is ").append(today).append(".\n")
            if (people.isNotEmpty()) append("Known people: ").append(people.take(200).joinToString("; ")).append("\n")
            append("Text:\n").append(text.take(MAX_INPUT))
        }
        val raw = withEngine { e ->
            runCatching { ask(e, SYSTEM_SPLIT, prompt, 900, 0.2, SPLIT_SCHEMA) }.getOrElse { ask(e, SYSTEM_SPLIT, prompt, 900, 0.2) }
        }
        return parseFound(raw)
    }

    /** A short free text from facts given (a brief, a week's summary, a shorter speech). */
    suspend fun write(task: Task, facts: String): String = withEngine { e -> ask(e, task.system, facts.take(MAX_INPUT), task.maxTokens, 0.5) }

    enum class Task(val system: String, val maxTokens: Int) {
        BRIEF(SYSTEM_BRIEF, 450),
        WEEK(SYSTEM_WEEK, 500),
        SHORTEN(SYSTEM_SHORTEN, 1200),
        FILL(SYSTEM_FILL, 1500),
    }

    companion object {
        private const val IDLE_MS = 5 * 60 * 1000L
        const val MAX_INPUT = 6000

        private const val UK = "Відповідай лише українською мовою, без жодного російського слова. Не вигадуй фактів, імен, дат, цифр чи правових норм: використовуй лише те, що дано."

        private const val SYSTEM_SPLIT = "You help a public official sort notes. From the text, list every task, promise, deadline and matter. " +
            "kind is one of: matter (a new piece of work), delegation (something asked of another person), promise (something the official promised), " +
            "deadline (something due by a date), note (anything else worth keeping). text is a short line in the text's own language. " +
            "person is a name from the text (or a known person) or empty. due is a date YYYY-MM-DD if one is given or clearly implied, else empty. " +
            "Do not invent anything. Answer with ONLY JSON: {\"items\":[{\"kind\":\"delegation\",\"text\":\"…\",\"person\":\"…\",\"due\":\"\"}]}"

        private const val SPLIT_SCHEMA = """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object",
"properties":{"kind":{"type":"string","enum":["matter","delegation","promise","deadline","note"]},"text":{"type":"string"},"person":{"type":"string"},"due":{"type":"string"}},
"required":["kind","text"]}}},"required":["items"]}"""

        private const val SYSTEM_BRIEF = "Ти помічник перед зустріччю. З наданих фактів про людину склади короткий бриф: 3–5 пунктів, кожен одним реченням — " +
            "що важливо пам'ятати, що ми їй обіцяли, що вона має зробити, про що варто запитати. $UK"

        private const val SYSTEM_WEEK = "Ти помічник з підсумку тижня. З наданих фактів напиши стислий підсумок: що зрушило, що застрягло, на що звернути увагу наступного тижня. " +
            "4–6 пунктів, кожен одним реченням, без оцінок людини. $UK"

        private const val SYSTEM_SHORTEN = "Ти редактор виступів. Скороти поданий текст до вказаної кількості слів, зберігши головну думку, аргументи й заклик. " +
            "Зберігай позначки рядків «# », «## », «- ». $UK Відповідай лише готовим текстом."

        private const val SYSTEM_FILL = "Ти помічник з листів. У тексті є поля в квадратних дужках. Заповни ті, для яких є відомості в довідці; решту лиши як є, у дужках. " +
            "Не змінюй інших слів. $UK Відповідай лише готовим текстом."

        /** Accepts {"items":[...]}, a bare [...] or a cut-off answer (objects read one by one). */
        fun parseFound(raw: String): List<Found> {
            val text = raw.replace("```json", "").replace("```", "")
            val objects: List<JSONObject> = runCatching {
                JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).getJSONArray("items")
            }.recoverCatching { JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1)) }
                .map { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                .getOrElse {
                    Regex("\\{[^{}]*\"kind\"[^{}]*\\}").findAll(text).mapNotNull { m -> runCatching { JSONObject(m.value) }.getOrNull() }.toList()
                }
            return objects.mapNotNull { o ->
                val kind = o.optString("kind").trim().lowercase().takeIf { it in setOf("matter", "delegation", "promise", "deadline", "note") } ?: return@mapNotNull null
                val t = o.optString("text").trim().take(500)
                if (t.isEmpty()) return@mapNotNull null
                Found(kind, t, o.optString("person").trim().take(200), o.optString("due").trim().take(10))
            }.take(50)
        }
    }
}
