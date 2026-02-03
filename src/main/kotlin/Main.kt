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
    println("Загружен индекс: ${index.size} чанков\n")

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

    OllamaClient().use { ollama ->
        DeepSeekClient(apiKey).use { deepseek ->
            while (true) {
                print("\nВведите вопрос (или 'выход' для завершения): ")
                val question = readlnOrNull()?.trim()
                if (question.isNullOrBlank() || question == "выход") break

                println("\n" + "=".repeat(80))
                println("ВОПРОС: $question")
                println("=".repeat(80))

                // --- Режим без RAG ---
                println("\n--- Ответ БЕЗ RAG ---\n")
                val answerNoRag = deepseek.chat(question)
                println(answerNoRag)

                // --- Режим с RAG ---
                println("\n--- Ответ С RAG ---\n")

                val queryEmbedding = ollama.embed(question)
                val relevant = findRelevantChunks(index, queryEmbedding)

                println("Найденные чанки:")
                for ((entry, score) in relevant) {
                    println("  [${entry.source} #${entry.chunkIndex}] score=%.4f".format(score))
                }
                println()

                val context = relevant.joinToString("\n\n") { it.first.text }
                val ragPrompt = """На основе приведённого контекста ответь на вопрос.
Используй только информацию из контекста. Если в контексте нет ответа, скажи об этом.

Контекст:
$context

Вопрос: $question"""

                val answerWithRag = deepseek.chat(ragPrompt)
                println(answerWithRag)
            }
        }
    }
    println("\nЗавершено.")
}
