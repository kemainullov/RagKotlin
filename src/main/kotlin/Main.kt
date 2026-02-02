import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// ---------- Ollama API ----------

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

// ---------- Main ----------

fun main() = runBlocking {
    val dataDir = File("data")
    val outputFile = File("index.json")
    val prettyJson = Json { prettyPrint = true }

    val txtFiles = dataDir.listFiles { f -> f.extension == "txt" }
        ?.sortedBy { it.name }
        ?: emptyList()

    if (txtFiles.isEmpty()) {
        println("Нет .txt файлов в папке data/")
        return@runBlocking
    }

    val index = mutableListOf<IndexEntry>()

    OllamaClient().use { client ->
        for (file in txtFiles) {
            val content = file.readText().trim()
            if (content.isEmpty()) {
                println("Пропуск пустого файла: ${file.name}")
                continue
            }

            val chunks = splitIntoChunks(content)
            println("${file.name}: ${chunks.size} чанк(ов)")

            for ((i, chunk) in chunks.withIndex()) {
                val embedding = client.embed(chunk)
                index.add(
                    IndexEntry(
                        source = file.name,
                        chunkIndex = i,
                        text = chunk,
                        embedding = embedding.toList()
                    )
                )
                println("  чанк $i — embedding size: ${embedding.size}")
            }
        }
    }

    outputFile.writeText(prettyJson.encodeToString(index))
    println("\nИндекс сохранён в ${outputFile.name} (${index.size} записей)")
}
