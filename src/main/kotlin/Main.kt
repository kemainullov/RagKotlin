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

fun findRelevantChunks(index: List<IndexEntry>, queryEmbedding: FloatArray, topN: Int = 3): List<Pair<IndexEntry, Float>> {
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

// ---------- LLM-реранкинг ----------

suspend fun rerankWithLLM(
    chunks: List<Pair<IndexEntry, Float>>,
    question: String,
    llm: DeepSeekClient
): List<Pair<IndexEntry, Float>> {
    val scored = mutableListOf<Pair<IndexEntry, Float>>()
    for ((entry, cosineScore) in chunks) {
        val prompt = """Оцени релевантность текста для ответа на вопрос.
Ответь ТОЛЬКО одним числом от 0 до 10, где 0 — совсем не релевантен, 10 — идеально релевантен.

Вопрос: $question

Текст: ${entry.text}"""

        val response = llm.chat(prompt)
        val llmScore = response.trim().filter { it.isDigit() || it == '.' }
            .toFloatOrNull() ?: 0f
        // Нормализуем LLM-оценку к диапазону 0..1 и комбинируем с cosine score
        val combinedScore = cosineScore * 0.4f + (llmScore / 10f) * 0.6f
        scored.add(entry to combinedScore)
    }
    return scored
        .filter { it.second >= 0.5f }
        .sortedByDescending { it.second }
}

// ---------- Вспомогательная функция для RAG-запроса ----------

suspend fun askWithContext(
    chunks: List<Pair<IndexEntry, Float>>,
    question: String,
    llm: DeepSeekClient
): String {
    if (chunks.isEmpty()) return "Релевантные документы не найдены — ответ невозможен."

    val context = chunks.joinToString("\n\n") { it.first.text }
    val ragPrompt = """На основе приведённого контекста ответь на вопрос.
Используй только информацию из контекста. Если в контексте нет ответа, скажи об этом.

Контекст:
$context

Вопрос: $question"""
    return llm.chat(ragPrompt)
}

fun printChunks(label: String, chunks: List<Pair<IndexEntry, Float>>) {
    if (chunks.isEmpty()) {
        println("  Все чанки отсечены фильтром.")
    } else {
        for ((entry, score) in chunks) {
            println("  [$label] [${entry.source} #${entry.chunkIndex}] score=%.4f".format(score))
        }
    }
}

// ---------- Main ----------

fun main() = runBlocking {
    val indexFile = File("index.json")
    val jsonParser = Json { ignoreUnknownKeys = true }

    // Загрузка индекса
    if (!indexFile.exists()) {
        println("Файл index.json не найден. Сначала запустите индексацию (День 16).")
        return@runBlocking
    }
    val index = jsonParser.decodeFromString<List<IndexEntry>>(indexFile.readText())
    println("Загружен индекс: ${index.size} чанков")

    // API ключ: сначала local.properties, потом переменная окружения
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

    val threshold = 0.78f
    println("Порог фильтрации: $threshold")

    OllamaClient().use { ollama ->
        DeepSeekClient(apiKey).use { deepseek ->
            while (true) {
                print("\nВведите вопрос (или 'выход' для завершения): ")
                val question = readlnOrNull()?.trim()
                if (question.isNullOrBlank() || question == "выход") break

                println("\n" + "=".repeat(80))
                println("ВОПРОС: $question")
                println("=".repeat(80))

                // Общий поиск чанков
                val queryEmbedding = ollama.embed(question)
                val allChunks = findRelevantChunks(index, queryEmbedding)

                println("\nНайденные чанки (cosine similarity):")
                printChunks("cosine", allChunks)

                // ===== 1. RAG без фильтра =====
                println("\n--- 1. RAG без фильтра (${allChunks.size} чанков) ---\n")
                val answer1 = askWithContext(allChunks, question, deepseek)
                println(answer1)

                // ===== 2. RAG + порог =====
                val filtered = filterByThreshold(allChunks, threshold)
                println("\n--- 2. RAG + порог $threshold (${filtered.size} из ${allChunks.size} чанков) ---\n")
                printChunks("порог", filtered)
                println()
                val answer2 = askWithContext(filtered, question, deepseek)
                println(answer2)

                // ===== 3. RAG + LLM-реранкинг =====
                println("\n--- 3. RAG + LLM-реранкинг ---\n")
                val reranked = rerankWithLLM(allChunks, question, deepseek)
                println("После реранкинга (${reranked.size} из ${allChunks.size} чанков):")
                printChunks("rerank", reranked)
                println()
                val answer3 = askWithContext(reranked, question, deepseek)
                println(answer3)
            }
        }
    }
    println("\nЗавершено.")
}
