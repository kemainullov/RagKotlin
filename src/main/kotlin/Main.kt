import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

// ---------- Ollama API (эмбеддинги) ----------

@Serializable
data class OllamaEmbedRequest(
    val model: String = "nomic-embed-text",
    val input: String
)

@Serializable
data class OllamaEmbedResponse(
    val embeddings: List<List<Double>>
)

class OllamaClient(
    private val baseUrl: String = "http://localhost:11434"
) : AutoCloseable {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    suspend fun embed(text: String): FloatArray {
        val response: OllamaEmbedResponse = http.post("$baseUrl/api/embed") {
            contentType(ContentType.Application.Json)
            setBody(OllamaEmbedRequest(input = text))
        }.body()
        return response.embeddings.first().map { it.toFloat() }.toFloatArray()
    }

    override fun close() = http.close()
}

// ---------- DeepSeek API (LLM) ----------

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatRequest(
    val model: String = "deepseek-chat",
    val messages: List<ChatMessage>
)

@Serializable
data class ChatChoice(
    val message: ChatMessage
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>
)

class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com"
) : AutoCloseable {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
        }
    }

    suspend fun chat(userMessage: String, systemMessage: String? = null): String {
        val messages = buildList {
            if (systemMessage != null) add(ChatMessage("system", systemMessage))
            add(ChatMessage("user", userMessage))
        }
        val response: ChatResponse = http.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(ChatRequest(messages = messages))
        }.body()
        return response.choices.first().message.content
    }

    override fun close() = http.close()
}

// ---------- Индекс ----------

@Serializable
data class IndexEntry(
    val source: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: List<Float>
)

// ---------- Чанкинг ----------

fun splitIntoChunks(text: String, chunkSize: Int = 500, overlap: Int = 50): List<String> {
    val trimmed = text.trim()
    if (trimmed.length <= chunkSize) return listOf(trimmed)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < trimmed.length) {
        val end = minOf(start + chunkSize, trimmed.length)
        chunks.add(trimmed.substring(start, end))
        start += chunkSize - overlap
    }
    return chunks
}

// ---------- Косинусное сходство ----------

fun cosineSimilarity(a: List<Float>, b: FloatArray): Float {
    var dot = 0f
    var normA = 0f
    var normB = 0f
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    return dot / (sqrt(normA) * sqrt(normB))
}

// ---------- Поиск релевантных чанков ----------

fun findRelevantChunks(
    index: List<IndexEntry>,
    queryEmbedding: FloatArray,
    topN: Int = 5
): List<Pair<IndexEntry, Float>> {
    return index
        .map { it to cosineSimilarity(it.embedding, queryEmbedding) }
        .sortedByDescending { it.second }
        .take(topN)
}

// ---------- Фильтрация по порогу ----------

fun filterByThreshold(
    chunks: List<Pair<IndexEntry, Float>>,
    threshold: Float = 0.78f
): List<Pair<IndexEntry, Float>> {
    return chunks.filter { it.second >= threshold }
}

// ---------- Индексация документов ----------

suspend fun buildIndex(ollama: OllamaClient, dataDirs: List<String>): List<IndexEntry> {
    val entries = mutableListOf<IndexEntry>()
    val extensions = listOf("txt", "md")

    // Собираем файлы из указанных директорий
    val files = dataDirs.flatMap { dir ->
        val dirFile = File(dir)
        if (dirFile.isDirectory) {
            dirFile.listFiles()?.filter { it.extension in extensions }?.toList() ?: emptyList()
        } else if (dirFile.isFile && dirFile.extension in extensions) {
            listOf(dirFile)
        } else {
            emptyList()
        }
    }

    // Добавляем README.md из корня
    val readme = File("README.md")
    val allFiles = if (readme.exists() && files.none { it.absolutePath == readme.absolutePath }) {
        files + readme
    } else {
        files
    }

    println("Найдено файлов для индексации: ${allFiles.size}")
    for (file in allFiles) {
        val text = file.readText()
        val chunks = splitIntoChunks(text)
        println("  ${file.name}: ${chunks.size} чанков")
        for ((i, chunk) in chunks.withIndex()) {
            val embedding = ollama.embed(chunk)
            entries.add(IndexEntry(file.name, i, chunk, embedding.toList()))
        }
    }

    val json = Json { prettyPrint = true }
    File("index.json").writeText(json.encodeToString(entries))
    println("Индекс сохранён: ${entries.size} чанков")
    return entries
}

// ---------- MCP: Git-интеграция ----------

fun getCurrentGitBranch(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
            .redirectErrorStream(true)
            .start()
        val result = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0) result else "неизвестна"
    } catch (_: Exception) {
        "неизвестна"
    }
}

// ---------- Вспомогательная функция для RAG-запроса ----------

fun buildSourceLabel(entry: IndexEntry, index: Int): String =
    "Источник ${index + 1}: ${entry.source}, чанк #${entry.chunkIndex}"

suspend fun askWithContext(
    chunks: List<Pair<IndexEntry, Float>>,
    question: String,
    llm: DeepSeekClient,
    history: List<Pair<String, String>> = emptyList(),
    gitBranch: String = ""
): String {
    if (chunks.isEmpty()) return "Релевантные документы не найдены — ответ невозможен."

    val context = chunks.mapIndexed { i, (entry, _) ->
        "[${buildSourceLabel(entry, i)}]\n${entry.text}"
    }.joinToString("\n\n")

    val historySection = if (history.isNotEmpty()) {
        val formatted = history.takeLast(5).joinToString("\n") { (q, a) ->
            "Пользователь: $q\nАссистент: $a"
        }
        """
История диалога:
$formatted

"""
    } else ""

    val branchInfo = if (gitBranch.isNotBlank()) "Текущая git-ветка: $gitBranch\n" else ""

    val ragPrompt =
        """Ты — ассистент разработчика проекта RagKotlin. ${branchInfo}Отвечай на вопросы, используя контекст и историю диалога.
Помогай с кодом, архитектурой и стилем кода проекта. Подсказывай фрагменты кода и правила стиля, когда это уместно.
$historySection
На основе приведённого контекста ответь на вопрос.
Используй только информацию из контекста. Если в контексте нет ответа, скажи об этом.
Учитывай историю диалога для понимания местоимений и ссылок на предыдущие темы.

ВАЖНО: В ответе обязательно:
1. Указывай источники в формате [Источник N] после каждого утверждения или абзаца.
2. В конце ответа добавь раздел "Источники:" со списком всех использованных источников.
3. Приводи краткие цитаты из контекста, подтверждающие твои утверждения.

Контекст:
$context

Вопрос: $question"""
    return llm.chat(ragPrompt)
}

// ---------- Main ----------

suspend fun processQuestion(
    question: String,
    index: List<IndexEntry>,
    ollama: OllamaClient,
    deepseek: DeepSeekClient,
    threshold: Float,
    history: List<Pair<String, String>> = emptyList(),
    gitBranch: String = ""
): String {
    val queryEmbedding = ollama.embed(question)
    val allChunks = findRelevantChunks(index, queryEmbedding)
    val filtered = filterByThreshold(allChunks, threshold)
    val answer = askWithContext(filtered, question, deepseek, history, gitBranch)
    println(answer)
    return answer
}

fun main() = runBlocking {
    val jsonParser = Json { ignoreUnknownKeys = true }

    val props = File("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.associate { line ->
            val (k, v) = line.split("=", limit = 2)
            k.trim() to v.trim()
        } ?: emptyMap()
    val apiKey = props["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Укажите DEEPSEEK_API_KEY в local.properties или в переменной окружения")
        return@runBlocking
    }

    val threshold = 0.5f
    val gitBranch = getCurrentGitBranch()

    OllamaClient().use { ollama ->
        DeepSeekClient(apiKey).use { deepseek ->

            // Загрузка или построение индекса документации (docs/ + README.md)
            val indexFile = File("index.json")
            var index = if (indexFile.exists()) {
                val loaded = jsonParser.decodeFromString<List<IndexEntry>>(indexFile.readText())
                println("Загружен индекс: ${loaded.size} чанков")
                loaded
            } else {
                println("Индексация документации проекта...")
                buildIndex(ollama, listOf("docs"))
            }

            println("\n" + "#".repeat(80))
            println("# АССИСТЕНТ РАЗРАБОТЧИКА RagKotlin")
            println("# Ветка: $gitBranch")
            println("# Команды:")
            println("#   /help <вопрос>  — поиск по документации проекта")
            println("#   /index          — переиндексация документов")
            println("#   очистить        — сбросить историю диалога")
            println("#   выход           — завершить")
            println("#".repeat(80))

            val history = mutableListOf<Pair<String, String>>()

            while (true) {
                print("\nВы: ")
                val input = readlnOrNull()?.trim()
                if (input.isNullOrBlank() || input == "выход") break

                if (input == "очистить") {
                    history.clear()
                    println("\n>> История диалога очищена.")
                    continue
                }

                if (input == "/index") {
                    println("\nПереиндексация документов...")
                    index = buildIndex(ollama, listOf("docs"))
                    continue
                }

                if (input.startsWith("/help")) {
                    val helpQuestion = input.removePrefix("/help").trim()
                    if (helpQuestion.isEmpty()) {
                        println("\nИспользование: /help <вопрос о проекте>")
                        continue
                    }
                    val answer = processQuestion(
                        helpQuestion, index, ollama, deepseek, threshold,
                        history, gitBranch
                    )
                    history.add(helpQuestion to answer)
                    continue
                }

                println("Неизвестная команда. Используйте /help <вопрос> для поиска по документации.")
            }
        }
    }
    println("\nЗавершено.")
}
