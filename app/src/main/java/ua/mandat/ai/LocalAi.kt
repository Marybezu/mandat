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

    fun ready() = models.ready.value

    /** The conversation being answered, so locking can ask it to stop. */
    @Volatile private var current: Any? = null

    /** Set by lock: an answer still being written is thrown away and the engine closed right after. */
    @Volatile private var abandoned = false

    private val prefs get() = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    private fun engineLocked(): Engine {
        val path = models.modelFile()?.absolutePath ?: error("no model")
        engine?.takeIf { loadedPath == path }?.let { return it }
        engine?.close()
        engine = null
        // A GPU that once failed (or crashed the app while starting) is not tried again
        val gpuOk = !prefs.getBoolean(KEY_GPU_BAD, false) && !prefs.getBoolean(KEY_GPU_TRYING, false)
        val created = if (gpuOk) {
            prefs.edit().putBoolean(KEY_GPU_TRYING, true).commit()
            val gpu = runCatching { create(path, gpu = true) }.getOrNull()
            prefs.edit().putBoolean(KEY_GPU_TRYING, false).putBoolean(KEY_GPU_BAD, gpu == null).commit()
            gpu?.also { backend = "GPU" } ?: create(path, gpu = false).also { backend = "CPU" }
        } else create(path, gpu = false).also { backend = "CPU" }
        engine = created
        loadedPath = path
        return created
    }

    private fun create(path: String, gpu: Boolean): Engine {
        val e = Engine(EngineConfig(modelPath = path, backend = if (gpu) Backend.GPU() else Backend.CPU(), maxNumTokens = 8192,
            cacheDir = models.cacheDir.absolutePath))
        // A failed start must not leave native memory behind
        try { e.initialize() } catch (t: Throwable) { runCatching { e.close() }; throw t }
        return e
    }

    private suspend fun <T> withEngine(block: (Engine) -> T): T {
        synchronized(this) { idleJob?.cancel() }
        abandoned = false
        try {
            val r = mutex.withLock { withContext(Dispatchers.Default) { block(engineLocked()) } }
            if (abandoned) error("locked")
            return r
        } finally {
            // Memory back to the phone after five minutes without use
            synchronized(this) { idleJob = scope.launch { delay(IDLE_MS); release() } }
        }
    }

    /** On lock: asks a running answer to stop (when the runtime allows it), then frees the engine. */
    suspend fun stopAndRelease() {
        abandoned = true
        current?.let { c -> runCatching { c.javaClass.getMethod("cancelProcess").invoke(c) } }
        release()
        // What the engine may have cached from the last answers goes with the lock
        withContext(Dispatchers.IO) { runCatching { java.io.File(context.cacheDir, "ai").deleteRecursively() } }
    }

    /** Frees the model's memory (also on lock); waits for a running answer to finish first. */
    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.Default) {
            engine?.close()
            engine = null
            loadedPath = null
        }
    }

    /** Removes the model and everything the engine cached for it. */
    suspend fun deleteModel() {
        release()
        withContext(Dispatchers.IO) { models.delete() }
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
            current = conv
            try { if (json != null) conv.sendMessage(prompt, responseFormat = ResponseFormat.json(json)).toString()
            else conv.sendMessage(prompt).toString() } finally { current = null }
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

    /**
     * Sorts Inbox notes: for each note, what it most likely is. The index ties an answer back to
     * its note, so the note can be removed from the Inbox once its item is saved.
     */
    suspend fun sortInbox(notes: List<String>, people: List<String>, today: String): List<Pair<Int, Found>> {
        val prompt = buildString {
            append("Today is ").append(today).append(".\n")
            if (people.isNotEmpty()) append("Known people: ").append(people.take(200).joinToString("; ")).append("\n")
            append("Notes:\n")
            notes.take(40).forEachIndexed { i, n -> append(i).append(". ").append(n.replace('\n', ' ').take(300)).append("\n") }
        }
        val raw = withEngine { e ->
            runCatching { ask(e, SYSTEM_SORT, prompt.take(MAX_INPUT), 1500, 0.2, SORT_SCHEMA) }.getOrElse { ask(e, SYSTEM_SORT, prompt.take(MAX_INPUT), 1500, 0.2) }
        }
        return parseSorted(raw, notes.size.coerceAtMost(40))
    }

    enum class Task(val system: String, val maxTokens: Int) {
        BRIEF(SYSTEM_BRIEF, 450),
        WEEK(SYSTEM_WEEK, 500),
        SHORTEN(SYSTEM_SHORTEN, 1200),
        FILL(SYSTEM_FILL, 1500),
        ANSWER(SYSTEM_ANSWER, 500),
        MORNING(SYSTEM_MORNING, 450),
        EVENING(SYSTEM_EVENING, 450),
        NEXT_STEP(SYSTEM_NEXT, 120),
        MATTER_SUMMARY(SYSTEM_MATTER, 450),
        FORMAL(SYSTEM_FORMAL, 1500),
        SIMPLER(SYSTEM_SIMPLER, 1500),
        LANGUAGE(SYSTEM_LANGUAGE, 700),
        REBUTTAL(SYSTEM_REBUTTAL, 700),
        MESSAGES(SYSTEM_MESSAGES, 450),
    }

    companion object {
        private const val IDLE_MS = 5 * 60 * 1000L
        private const val KEY_GPU_BAD = "gpu_bad"
        private const val KEY_GPU_TRYING = "gpu_trying"
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

        private const val SYSTEM_SORT = "You help a public official sort quick notes. For each numbered note give: i (its number), " +
            "kind (matter, delegation, promise, deadline or note), text (a short line in the note's own language), person (a name or empty), " +
            "due (YYYY-MM-DD if given or clearly implied, else empty). Do not invent anything. Answer with ONLY JSON: {\"items\":[{\"i\":0,\"kind\":\"note\",\"text\":\"…\",\"person\":\"\",\"due\":\"\"}]}"

        private const val SORT_SCHEMA = """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object",
"properties":{"i":{"type":"integer"},"kind":{"type":"string","enum":["matter","delegation","promise","deadline","note"]},"text":{"type":"string"},"person":{"type":"string"},"due":{"type":"string"}},
"required":["i","kind","text"]}}},"required":["items"]}"""

        private const val SYSTEM_ANSWER = "Ти помічник, що відповідає на питання лише за записами, поданими нижче. Відповідай коротко: 1–5 речень або пунктів; після кожного твердження вкажи номер запису в квадратних дужках, наприклад [2]. " +
            "Якщо в записах немає відповіді, так і скажи. Не додавай нічого поза записами. $UK"

        private const val SYSTEM_MORNING = "Ти помічник на початку дня. З наданих фактів назви три найважливіші речі на сьогодні, ризики (строки, обіцянки, зустрічі без підготовки) " +
            "і з ким зустрічі та що ми їм обіцяли. До 6 пунктів, кожен одним реченням. $UK"

        private const val SYSTEM_EVENING = "Ти помічник для запису в щоденник. З наданих фактів про день напиши стислий запис від першої особи без зазначення роду: що зроблено, що зрушило, що лишилося. " +
            "3–5 речень, без оцінок. $UK"

        private const val SYSTEM_NEXT = "Ти помічник зі справами. З історії справи запропонуй один наступний конкретний крок: одне коротке речення, що починається з дієслова. " +
            "Відповідай лише цим реченням. $UK"

        private const val SYSTEM_MATTER = "Ти помічник зі справами. З наданих фактів напиши стислий підсумок справи: де вона зараз, що зроблено, що заважає, що далі. " +
            "3–5 пунктів, кожен одним реченням. $UK"

        private const val SYSTEM_FORMAL = "Ти редактор. Перепиши поданий текст в офіційно-діловому стилі, зберігши зміст, факти й позначки рядків «# », «## », «- ». $UK Відповідай лише готовим текстом."

        private const val SYSTEM_SIMPLER = "Ти редактор. Перепиши поданий текст простіше й коротшими реченнями, зрозуміло для широкого читача, зберігши зміст, факти й позначки рядків «# », «## », «- ». " +
            "$UK Відповідай лише готовим текстом."

        private const val SYSTEM_LANGUAGE = "Ти мовний редактор української. Знайди в поданому тексті русизми, суржик, кальки й помилки слововживання. " +
            "Для кожного випадку напиши рядок: «було → як правильно — коротке пояснення». Якщо помилок немає, так і скажи. Не переписуй увесь текст. " +
            "Відповідай лише українською мовою, без жодного російського слова."

        private const val SYSTEM_REBUTTAL = "Ти помічник з комунікацій. З наданих фактів напиши чернетку спростування: спокійно, про дії, а не про особу, лише перевірені факти з поданого, " +
            "без образ і без вигаданих цифр. 4–8 речень. $UK"

        private const val SYSTEM_MESSAGES = "Ти помічник з комунікацій. З наданих фактів запропонуй 3 ключові меседжі: кожен — одне коротке речення й доказ із поданих фактів. $UK"

        private const val SYSTEM_BRIEF = "Ти помічник перед зустріччю. З наданих фактів про людину склади короткий бриф: 3–5 пунктів, кожен одним реченням — " +
            "що важливо пам'ятати, що ми їй обіцяли, що вона має зробити, про що варто запитати. $UK"

        private const val SYSTEM_WEEK = "Ти помічник з підсумку тижня. З наданих фактів напиши стислий підсумок: що зрушило, що застрягло, на що звернути увагу наступного тижня. " +
            "4–6 пунктів, кожен одним реченням, без оцінок людини. $UK"

        private const val SYSTEM_SHORTEN = "Ти редактор виступів. Скороти поданий текст до вказаної кількості слів, зберігши головну думку, аргументи й заклик. " +
            "Зберігай позначки рядків «# », «## », «- ». $UK Відповідай лише готовим текстом."

        private const val SYSTEM_FILL = "Ти помічник з листів. У тексті є поля в квадратних дужках. Заповни ті, для яких є відомості в довідці; решту лиши як є, у дужках. " +
            "Не змінюй інших слів. $UK Відповідай лише готовим текстом."

        /** Items of a sorted Inbox: each with the number of its note; unknown numbers and kinds are dropped. */
        fun parseSorted(raw: String, count: Int): List<Pair<Int, Found>> {
            val text = raw.replace("```json", "").replace("```", "")
            val objects: List<JSONObject> = runCatching {
                JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).getJSONArray("items")
            }.map { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                .getOrElse { Regex("\\{[^{}]*\"kind\"[^{}]*\\}").findAll(text).mapNotNull { m -> runCatching { JSONObject(m.value) }.getOrNull() }.toList() }
            val seen = HashSet<Int>()
            return objects.mapNotNull { o ->
                val i = o.optInt("i", -1).takeIf { it in 0 until count && seen.add(it) } ?: return@mapNotNull null
                val f = parseFound(JSONObject().put("items", org.json.JSONArray().put(o)).toString()).firstOrNull() ?: return@mapNotNull null
                i to f
            }
        }

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
