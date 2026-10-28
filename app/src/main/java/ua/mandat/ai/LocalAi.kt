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

/** What a quick note most likely is; a role and a matter only when they match one exactly. */
data class Hint(val kind: String, val role: String?, val matter: String?)

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
    suspend fun write(task: Task, facts: String): String =
        tidy(withEngine { e -> ask(e, task.system, facts.take(MAX_INPUT), task.maxTokens, 0.5) }, task.keepsMarks)

    /**
     * Sorts Inbox notes: for each note, what it most likely is. The index ties an answer back to
     * its note, so the note can be removed from the Inbox once its item is saved.
     */
    suspend fun sortInbox(notes: List<String>, people: List<String>, today: String): List<Pair<Int, Found>> {
        // Only notes that fit whole are sent, and only their numbers are accepted back, so a note the
        // model never saw cannot be answered for (and later removed from the Inbox)
        var sent = 0
        val prompt = buildString {
            append("Today is ").append(today).append(".\n")
            if (people.isNotEmpty()) append("Known people: ").append(people.take(60).joinToString("; ").take(1500)).append("\n")
            append("Notes:\n")
            for (n in notes.take(SORT_BATCH)) {
                val line = sent.toString() + ". " + n.replace('\n', ' ').take(NOTE_CHARS) + "\n"
                if (length + line.length > MAX_INPUT) break
                append(line); sent++
            }
        }
        val raw = withEngine { e ->
            runCatching { ask(e, SYSTEM_SORT, prompt, 1500, 0.2, SORT_SCHEMA) }.getOrElse { ask(e, SYSTEM_SORT, prompt, 1500, 0.2) }
        }
        return parseSorted(raw, sent)
    }

    /** [keepsMarks]: the answer is a text whose line marks («# », «- ») are kept as they are. */
    enum class Task(val system: String, val maxTokens: Int, val keepsMarks: Boolean = false) {
        BRIEF(SYSTEM_BRIEF, 450),
        WEEK(SYSTEM_WEEK, 500),
        SHORTEN(SYSTEM_SHORTEN, 1200, true),
        FILL(SYSTEM_FILL, 1500, true),
        ANSWER(SYSTEM_ANSWER, 500),
        MORNING(SYSTEM_MORNING, 500),
        EVENING(SYSTEM_EVENING, 450),
        NEXT_STEP(SYSTEM_NEXT, 120),
        MATTER_SUMMARY(SYSTEM_MATTER, 450),
        FORMAL(SYSTEM_FORMAL, 1500, true),
        SIMPLER(SYSTEM_SIMPLER, 1500, true),
        LANGUAGE(SYSTEM_LANGUAGE, 700),
        REBUTTAL(SYSTEM_REBUTTAL, 700),
        MESSAGES(SYSTEM_MESSAGES, 450),
        MEETING(SYSTEM_MEETING, 600),
        REPLY(SYSTEM_REPLY, 900, true),
        REMINDER(SYSTEM_REMINDER, 200),
        RISK(SYSTEM_RISK, 600),
        PLAN(SYSTEM_PLAN, 500),
        DIGEST(SYSTEM_DIGEST, 400),
        DIGEST_JOIN(SYSTEM_DIGEST_JOIN, 700),
    }

    /**
     * A long document in parts: each part is cut down to theses, then the theses are joined into
     * one short summary. [onPart] tells how far it got.
     */
    suspend fun digest(text: String, onPart: (Int, Int) -> Unit = { _, _ -> }): String {
        val parts = chunks(text, MAX_INPUT - 400).take(DIGEST_PARTS)
        val theses = parts.mapIndexed { i, part ->
            onPart(i + 1, parts.size)
            write(Task.DIGEST, part)
        }
        return if (theses.size == 1) theses[0] else write(Task.DIGEST_JOIN, theses.mapIndexed { i, t -> "Частина ${i + 1}:\n$t" }.joinToString("\n\n"))
    }

    /** What a quick note most likely is, with a role and a matter picked only from the names given. */
    suspend fun hint(note: String, roles: List<String>, matters: List<String>): Hint? {
        val prompt = buildString {
            if (roles.isNotEmpty()) append("Roles: ").append(roles.take(30).joinToString(" | ")).append("\n")
            if (matters.isNotEmpty()) append("Matters: ").append(matters.take(80).joinToString(" | ").take(3500)).append("\n")
            append("Note: ").append(note.take(NOTE_CHARS))
        }
        val raw = withEngine { e -> runCatching { ask(e, SYSTEM_HINT, prompt, 120, 0.1, HINT_SCHEMA) }.getOrElse { ask(e, SYSTEM_HINT, prompt, 120, 0.1) } }
        return parseHint(raw, roles, matters)
    }

    companion object {
        private const val IDLE_MS = 5 * 60 * 1000L
        private const val KEY_GPU_BAD = "gpu_bad"
        private const val KEY_GPU_TRYING = "gpu_trying"
        const val MAX_INPUT = 6000
        /** How much of one Inbox note the model reads when sorting. */
        const val NOTE_CHARS = 300
        /** How many Inbox notes one sorting reads at most. */
        const val SORT_BATCH = 15

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
            "due (YYYY-MM-DD if given or clearly implied, else empty). If a note holds several things, give one item for each, with the same i. Do not invent anything. Answer with ONLY JSON: {\"items\":[{\"i\":0,\"kind\":\"note\",\"text\":\"…\",\"person\":\"\",\"due\":\"\"}]}"

        private const val SORT_SCHEMA = """{"type":"object","properties":{"items":{"type":"array","items":{"type":"object",
"properties":{"i":{"type":"integer"},"kind":{"type":"string","enum":["matter","delegation","promise","deadline","note"]},"text":{"type":"string"},"person":{"type":"string"},"due":{"type":"string"}},
"required":["i","kind","text"]}}},"required":["items"]}"""

        /** How every answer reads: no opening line, no markup, each heading once. */
        private const val FMT = "Пиши без вступу (не починай з «Ось») і без підсумкової фрази. Без розмітки: ні зірочок, ні решіток. " +
            "Якщо є розділи, кожен заголовок пиши лише один раз окремим рядком із двокрапкою, а під ним пункти, що починаються з «• »; не повторюй заголовок у пунктах. " +
            "Пропускай розділ, для якого немає фактів. Імена людей пиши так, як їх подано. Дати пиши так, як у фактах, з тим самим роком; не вигадуй інших дат."

        /** Whom it speaks to, and what the owner does at an event. */
        private const val ROLE = "Звертайся до власника застосунку на «ви». Про подію пиши «провести», лише якщо у фактах сказано, що власник головує (посада «голова»); " +
            "інакше пиши «взяти участь у…»."

        private const val SYSTEM_ANSWER = "Ти помічник, що відповідає на питання лише за записами, поданими нижче. Відповідай коротко: 1–5 речень або пунктів; " +
            "після кожного твердження вкажи номер запису в квадратних дужках, наприклад [2]. Якщо в записах немає відповіді, так і скажи. Не додавай нічого поза записами. $FMT $UK"

        private const val SYSTEM_MORNING = "Ти помічник на початку дня. З наданих фактів склади ранковий огляд із розділів «Головне сьогодні:» (до 3 пунктів), " +
            "«Ризики:» (прострочені й близькі строки та обіцянки; до 3 пунктів) і «Зустрічі:» — лише події з календаря на сьогодні: час, що це, з ким і що ви обіцяли цим людям. " +
            "Обіцянки й доручення не називай зустрічами. Кожен пункт — одне речення. $ROLE $FMT $UK"

        private const val SYSTEM_EVENING = "Ти помічник для запису в щоденник. З наданих фактів про день напиши стислий запис від першої особи без зазначення роду: " +
            "що зроблено, що зрушило, що лишилося. 3–5 речень звичайним текстом, без оцінок. $FMT $UK"

        private const val SYSTEM_NEXT = "Ти помічник зі справами. З історії справи запропонуй один наступний конкретний крок: одне коротке речення, що починається з дієслова. " +
            "Відповідай лише цим реченням. $UK"

        private const val SYSTEM_MATTER = "Ти помічник зі справами. З наданих фактів напиши стислий підсумок справи з розділів «Де зараз:», «Зроблено:», «Заважає:», «Далі:» — " +
            "у кожному 1–2 пункти по одному реченню. $ROLE $FMT $UK"

        private const val SYSTEM_FORMAL = "Ти редактор. Перепиши поданий текст в офіційно-діловому стилі, зберігши зміст, факти й позначки рядків «# », «## », «- ». $UK Відповідай лише готовим текстом."

        private const val SYSTEM_SIMPLER = "Ти редактор. Перепиши поданий текст простіше й коротшими реченнями, зрозуміло для широкого читача, зберігши зміст, факти й позначки рядків «# », «## », «- ». " +
            "$UK Відповідай лише готовим текстом."

        private const val SYSTEM_LANGUAGE = "Ти мовний редактор української. Знайди в поданому тексті русизми, суржик, кальки й помилки слововживання. " +
            "Для кожного випадку напиши рядок «• було → як правильно — коротке пояснення». Якщо помилок немає, так і скажи одним реченням. Не переписуй увесь текст. " +
            "Без вступу й без розмітки. Відповідай лише українською мовою, без жодного російського слова."

        private const val SYSTEM_REBUTTAL = "Ти помічник з комунікацій. З наданих фактів напиши чернетку спростування звичайним текстом: спокійно, про дії, а не про особу, " +
            "лише перевірені факти з поданого, без образ і без вигаданих цифр. 4–8 речень. $FMT $UK"

        private const val SYSTEM_MESSAGES = "Ти помічник з комунікацій. З наданих фактів запропонуй 3 ключові меседжі, кожен окремим пунктом «• меседж — доказ із фактів». $FMT $UK"

        private const val SYSTEM_BRIEF = "Ти помічник перед зустріччю власника застосунку з людиною, описаною у фактах. Бриф — для власника, а не для цієї людини. " +
            "Розділи: «Пам'ятати:», «Ви обіцяли:», «Чекаєте від людини:» (доручення їй), «Запитати:». У кожному до 3 пунктів по одному реченню. $ROLE $FMT $UK"

        private const val SYSTEM_WEEK = "Ти помічник з підсумку тижня. З наданих фактів напиши підсумок із розділів «Зрушило:», «Застрягло:», «Увага наступного тижня:» — " +
            "у кожному до 3 пунктів по одному реченню, без оцінок людей. $FMT $UK"

        private const val SYSTEM_SHORTEN = "Ти редактор виступів. Скороти поданий текст до вказаної кількості слів, зберігши головну думку, аргументи й заклик. " +
            "Зберігай позначки рядків «# », «## », «- ». $UK Відповідай лише готовим текстом."

        private const val SYSTEM_FILL = "Ти помічник з листів. У тексті є поля в квадратних дужках. Заповни ті, для яких є відомості в довідці; решту лиши як є, у дужках. " +
            "Не змінюй інших слів. $UK Відповідай лише готовим текстом."

        private const val SYSTEM_MEETING = "Ти помічник перед зустріччю. З фактів про подію, її справу й учасників склади аркуш підготовки з розділів «Мета:», " +
            "«Учасники:» (для кожного: позиція у справі й що ви обіцяли цій людині), «Що запитати:», «Ключові тези:». До 4 пунктів у розділі. $ROLE $FMT $UK"

        private const val SYSTEM_REPLY = "Ти помічник з листування. Напиши чернетку відповіді на поданий вхідний лист від імені власника застосунку: ввічливо, по суті, " +
            "лише з фактів справи. Чого не знаєш, лиши полем у квадратних дужках, наприклад [дата]. Не посилайся на закони чи норми, яких немає в листі чи фактах. " +
            "$UK Відповідай лише текстом листа."

        private const val SYSTEM_REMINDER = "Напиши коротке ввічливе нагадування людині про доручення, для месенджера: 2–3 речення без докору, з проханням повідомити, " +
            "коли буде готово чи що заважає. Звертання на «ви». $UK Відповідай лише текстом повідомлення."

        private const val SYSTEM_RISK = "Ти помічник з обіцянок. Для кожної обіцянки з фактів (до 6, найризиковіші першими) напиши три рядки: " +
            "перший — коротка назва обіцянки з двокрапкою; другий — «• Чому: …» (строк минув чи близько, високий ризик, публічна); третій — «• Крок: …» " +
            "(одна конкретна дія цього тижня). Між обіцянками порожній рядок. $FMT $UK"

        private const val SYSTEM_PLAN = "Ти помічник з планування. З часу за ролями (бажана частка й факт) і справ у фокусі запропонуй, як розподілити увагу наступного тижня: " +
            "розділи «Додати уваги:», «Тримати:», «Відкласти:», у кожному до 3 пунктів із конкретними справами з фактів. $FMT $UK"

        private const val SYSTEM_DIGEST = "Стисни поданий фрагмент документа до тез: до 6 пунктів «• », лише те, що є в тексті; збережи числа, строки й назви. $FMT $UK"

        private const val SYSTEM_DIGEST_JOIN = "З тез частин одного документа склади стислий виклад із розділів «Суть:» (2–3 речення), «Головні положення:» (до 8 пунктів) " +
            "і «Строки й цифри:» (якщо є). Лише те, що є в тезах. $FMT $UK"

        private const val SYSTEM_HINT = "You help sort a quick note. kind is one of: matter, delegation, promise, deadline, note. role is one of the given roles, copied exactly, or empty. " +
            "matter is one of the given matters, copied exactly, only if the note is clearly about it, else empty. Answer with ONLY JSON: {\"kind\":\"note\",\"role\":\"\",\"matter\":\"\"}"

        private const val HINT_SCHEMA = """{"type":"object","properties":{"kind":{"type":"string","enum":["matter","delegation","promise","deadline","note"]},
"role":{"type":"string"},"matter":{"type":"string"}},"required":["kind"]}"""

        /** How many parts of a long document are read at most (each about one model input). */
        const val DIGEST_PARTS = 8

        /** Cuts a long text into parts of at most [size] characters, at paragraph or sentence ends where possible. */
        fun chunks(text: String, size: Int): List<String> {
            val out = ArrayList<String>()
            var rest = text.trim()
            while (rest.length > size) {
                val window = rest.substring(0, size)
                // At a paragraph, a line, or just after a sentence's full stop
                val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf('\n'), window.lastIndexOf(". ").let { if (it < 0) it else it + 1 })
                    .firstOrNull { it > size / 2 } ?: size
                out.add(rest.substring(0, cut).trim())
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }

        /**
         * Makes an answer plain: no opening "Here is…" line, no stars or hashes, bullets as «• »,
         * no heading repeated at the start of each point, no runs of empty lines. A text meant to
         * replace the owner's own ([keepMarks]) keeps its line marks.
         */
        fun tidy(raw: String, keepMarks: Boolean = false): String {
            var lines = raw.replace("\r", "").lines()
            if (!keepMarks) {
                lines = lines.map { l ->
                    var t = l.replace("**", "").replace("__", "")
                    t = t.replace(Regex("^\\s*#{1,6}\\s+"), "")
                    t = t.replace(Regex("^\\s*[*\\-–]\\s+"), "• ")
                    t.trimEnd()
                }
                // An opening line like "Ось короткий бриф…:" says nothing
                val first = lines.indexOfFirst { it.isNotBlank() }
                if (first >= 0 && lines[first].trim().let { it.startsWith("Ось") && it.endsWith(":") && it.length < 120 }) lines = lines.drop(first + 1)
                // "1. Heading: text" repeated in every numbered point: the heading is said once, above its points
                // (bullets like "• Why: …" under each item are a chosen format and stay as they are)
                val out = ArrayList<String>()
                var lastHead: String? = null
                val numbered = Regex("^\\s*\\d+[.)]\\s*([^:]{2,60}):\\s+(.+)$")
                val repeated = lines.mapNotNull { numbered.find(it)?.groupValues?.get(1)?.trim() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                for (l in lines) {
                    val m = numbered.find(l)?.takeIf { it.groupValues[1].trim() in repeated }
                    if (m != null) {
                        val head = m.groupValues[1].trim()
                        if (head != lastHead) { if (out.isNotEmpty() && out.last().isNotBlank()) out.add(""); out.add("$head:"); lastHead = head }
                        out.add("• " + m.groupValues[2].trim())
                    } else {
                        if (l.isNotBlank() && !l.startsWith("•")) lastHead = null
                        out.add(l)
                    }
                }
                lines = out
            }
            return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        }

        /** Reads a hint; a role or matter not among those given is dropped. */
        fun parseHint(raw: String, roles: List<String>, matters: List<String>): Hint? = runCatching {
            val text = raw.replace("```json", "").replace("```", "")
            val o = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
            val kind = o.optString("kind").takeIf { it in KINDS } ?: return null
            Hint(kind, o.optString("role").trim().takeIf { it in roles }, o.optString("matter").trim().takeIf { it in matters })
        }.getOrNull()

        private val KINDS = setOf("matter", "delegation", "promise", "deadline", "note")

        /** Items of a sorted Inbox: each with the number of its note; unknown numbers and kinds are dropped. */
        fun parseSorted(raw: String, count: Int): List<Pair<Int, Found>> {
            val text = raw.replace("```json", "").replace("```", "")
            val objects: List<JSONObject> = runCatching {
                JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).getJSONArray("items")
            }.map { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                .getOrElse { Regex("\\{[^{}]*\"kind\"[^{}]*\\}").findAll(text).mapNotNull { m -> runCatching { JSONObject(m.value) }.getOrNull() }.toList() }
            val seen = HashSet<String>()
            return objects.mapNotNull { o ->
                // A note may hold several things (a call and a deadline): each becomes its own suggestion
                val i = o.optInt("i", -1).takeIf { it in 0 until count } ?: return@mapNotNull null
                val f = parseFound(JSONObject().put("items", org.json.JSONArray().put(o)).toString()).firstOrNull() ?: return@mapNotNull null
                if (!seen.add(i.toString() + "|" + f.kind + "|" + f.text)) return@mapNotNull null
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
