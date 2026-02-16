import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

// ---------- GitHub API модели ----------

@Serializable
data class GitHubCommit(
    val sha: String,
    val commit: CommitInfo
)

@Serializable
data class CommitInfo(
    val message: String,
    val author: CommitAuthor? = null
)

@Serializable
data class CommitAuthor(
    val name: String? = null,
    val date: String? = null
)

@Serializable
data class GitHubTag(
    val name: String,
    val commit: TagCommit
)

@Serializable
data class TagCommit(
    val sha: String
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null
)

@Serializable
data class CreateReleaseRequest(
    @SerialName("tag_name") val tagName: String,
    val name: String,
    val body: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

// ---------- GitHub клиент ----------

class GitHubClient(
    private val token: String,
    private val owner: String,
    private val repo: String
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
    }
    private val baseUrl = "https://api.github.com/repos/$owner/$repo"

    suspend fun getCommitsBetweenTags(fromTag: String, toTag: String): List<GitHubCommit> {
        val response: HttpResponse = http.get("$baseUrl/compare/$fromTag...$toTag") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
        }
        val body = response.bodyAsText()
        val parsed = json.decodeFromString<CompareResponse>(body)
        return parsed.commits
    }

    suspend fun listTags(): List<GitHubTag> {
        return http.get("$baseUrl/tags") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
        }.body()
    }

    suspend fun createRelease(request: CreateReleaseRequest): GitHubRelease {
        return http.post("$baseUrl/releases") {
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.github.v3+json")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
    }

    override fun close() = http.close()
}

@Serializable
data class CompareResponse(
    val commits: List<GitHubCommit>
)

// ---------- Release Notes генератор ----------

class ReleaseNotesGenerator(
    private val ollama: OllamaClient?,
    private val deepseek: DeepSeekClient,
    private val github: GitHubClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(fromTag: String, toTag: String, publish: Boolean = false): String {
        println("Получение коммитов между $fromTag и $toTag...")
        val commits = github.getCommitsBetweenTags(fromTag, toTag)
        println("Найдено коммитов: ${commits.size}")

        if (commits.isEmpty()) {
            println("Нет коммитов между тегами.")
            return "Нет изменений."
        }

        val commitsText = commits.joinToString("\n") { commit ->
            val msg = commit.commit.message.lines().first()
            val author = commit.commit.author?.name ?: "unknown"
            val sha = commit.sha.take(7)
            "- [$sha] $msg ($author)"
        }
        println("\nКоммиты:\n$commitsText")

        println("\nПоиск контекста в документации...")
        val context = findDocsContext(commitsText)

        println("Генерация release notes...")
        val releaseNotes = generateWithAI(commitsText, context, fromTag, toTag)

        println("\n${"=".repeat(80)}")
        println(releaseNotes)
        println("=".repeat(80))

        val outputFile = File("RELEASE_NOTES_${toTag}.md")
        outputFile.writeText(releaseNotes)
        println("\nСохранено в ${outputFile.name}")

        if (publish) {
            publishRelease(toTag, releaseNotes)
        }

        return releaseNotes
    }

    private suspend fun findDocsContext(commitsText: String): String {
        if (ollama == null) {
            println("Ollama не настроена, загружаем документацию напрямую")
            return loadDocsDirectly()
        }

        return try {
            val indexFile = File("release-index.json")
            val index = if (indexFile.exists()) {
                val loaded = json.decodeFromString<List<IndexEntry>>(indexFile.readText())
                println("Загружен индекс: ${loaded.size} чанков")
                loaded
            } else {
                println("Индекс не найден, создаём...")
                buildReleaseIndex(ollama)
            }

            val queryEmbedding = ollama.embed(commitsText.take(1000))
            val relevantChunks = findRelevantChunks(index, queryEmbedding, topN = 5)
            val filtered = filterByThreshold(relevantChunks, threshold = 0.3f)

            if (filtered.isNotEmpty()) {
                println("RAG: найдено ${filtered.size} релевантных чанков")
                filtered.joinToString("\n\n") { (entry, score) ->
                    "[${entry.source}, score=${"%.2f".format(score)}]\n${entry.text}"
                }
            } else {
                println("RAG: релевантные чанки не найдены, загружаем документацию напрямую")
                loadDocsDirectly()
            }
        } catch (e: Exception) {
            println("RAG недоступен (${e.message}), загружаем документацию напрямую")
            loadDocsDirectly()
        }
    }

    private suspend fun buildReleaseIndex(ollama: OllamaClient): List<IndexEntry> {
        val entries = mutableListOf<IndexEntry>()
        val docsDir = File("docs")
        if (!docsDir.isDirectory) return entries

        val files = docsDir.listFiles()?.filter { it.extension in listOf("md", "txt") } ?: emptyList()
        println("Индексация ${files.size} файлов из docs/")

        for (file in files) {
            val chunks = splitIntoChunks(file.readText())
            for ((i, chunk) in chunks.withIndex()) {
                val embedding = ollama.embed(chunk)
                entries.add(IndexEntry(file.name, i, chunk, embedding.toList()))
            }
        }

        val jsonOut = Json { prettyPrint = true }
        File("release-index.json").writeText(jsonOut.encodeToString(entries))
        println("Индекс сохранён: ${entries.size} чанков")
        return entries
    }

    private fun loadDocsDirectly(): String {
        val docsDir = File("docs")
        if (!docsDir.isDirectory) return ""
        val docs = docsDir.listFiles()
            ?.filter { it.extension in listOf("md", "txt") }
            ?.joinToString("\n\n") { file ->
                "[${file.name}]\n${file.readText().take(2000)}"
            } ?: ""
        if (docs.isNotBlank()) println("Загружено из docs/")
        return docs
    }

    private suspend fun generateWithAI(
        commitsText: String,
        docsContext: String,
        fromTag: String,
        toTag: String
    ): String {
        val contextSection = if (docsContext.isNotBlank()) {
            """

Контекст проекта (из документации):
$docsContext
"""
        } else ""

        val systemPrompt = """Ты — технический писатель, который генерирует Release Notes для проекта.

Правила:
1. Группируй изменения по категориям:
   - **Features** (новые возможности)
   - **Bug Fixes** (исправления ошибок)
   - **Improvements** (улучшения)
   - **Refactoring** (рефакторинг кода)
   - **Documentation** (документация)
   - **Other** (прочее)
2. Определяй категорию по содержимому коммит-сообщения
3. Перепиши каждый коммит человекочитаемым языком (1 строка на изменение)
4. Добавь краткое summary (2-3 предложения) в начале
5. Формат — Markdown
6. Пиши на русском языке
7. Если есть контекст из документации, используй его для более точных описаний
8. Не добавляй изменения, которых нет в списке коммитов"""

        val userPrompt = """Сгенерируй Release Notes для версии $toTag (изменения с $fromTag).
$contextSection
Список коммитов:
$commitsText"""

        return deepseek.chat(userPrompt, systemPrompt)
    }

    private suspend fun publishRelease(tag: String, body: String) {
        println("\nПубликация релиза на GitHub...")
        try {
            val release = github.createRelease(
                CreateReleaseRequest(
                    tagName = tag,
                    name = "Release $tag",
                    body = body
                )
            )
            println("Релиз опубликован: ${release.htmlUrl}")
        } catch (e: Exception) {
            println("Ошибка публикации релиза: ${e.message}")
            println("Release notes сохранены локально в файле.")
        }
    }
}

// ---------- CLI ----------

fun parseArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--from" -> { map["from"] = args.getOrElse(i + 1) { "" }; i += 2 }
            "--to" -> { map["to"] = args.getOrElse(i + 1) { "" }; i += 2 }
            "--owner" -> { map["owner"] = args.getOrElse(i + 1) { "" }; i += 2 }
            "--repo" -> { map["repo"] = args.getOrElse(i + 1) { "" }; i += 2 }
            "--ollama" -> { map["ollama"] = args.getOrElse(i + 1) { "" }; i += 2 }
            "--publish" -> { map["publish"] = "true"; i += 1 }
            else -> i++
        }
    }
    return map
}

fun loadConfig(): Map<String, String> {
    return File("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        ?.filter { it.contains("=") }
        ?.associate { line ->
            val (k, v) = line.split("=", limit = 2)
            k.trim() to v.trim()
        } ?: emptyMap()
}

object ReleaseNotesMain {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val cliArgs = parseArgs(args)
        val config = loadConfig()

    val deepseekKey = config["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")
    if (deepseekKey.isNullOrBlank()) {
        println("DEEPSEEK_API_KEY не задан (local.properties или env)")
        return@runBlocking
    }

    val githubToken = config["GITHUB_TOKEN"]?.takeIf { it.isNotBlank() }
        ?: System.getenv("GITHUB_TOKEN")
    if (githubToken.isNullOrBlank()) {
        println("GITHUB_TOKEN не задан (local.properties или env)")
        return@runBlocking
    }

    val owner = cliArgs["owner"]
        ?: System.getenv("REPO_OWNER")
        ?: config["REPO_OWNER"]
    val repo = cliArgs["repo"]
        ?: System.getenv("REPO_NAME")
        ?: config["REPO_NAME"]

    if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
        println("Укажите --owner и --repo или задайте REPO_OWNER/REPO_NAME")
        return@runBlocking
    }

    val fromTag = cliArgs["from"] ?: System.getenv("FROM_TAG")
    val toTag = cliArgs["to"] ?: System.getenv("TO_TAG")

    if (fromTag.isNullOrBlank() || toTag.isNullOrBlank()) {
        println("Укажите --from <тег> --to <тег> или задайте FROM_TAG/TO_TAG")
        println("\nИспользование:")
        println("  ./gradlew runReleaseNotes --args='--owner <owner> --repo <repo> --from <tag1> --to <tag2> [--publish]'")
        return@runBlocking
    }

    val publish = cliArgs["publish"] == "true"
    val ollamaUrl = cliArgs["ollama"]
        ?: System.getenv("OLLAMA_URL")
        ?: config["OLLAMA_URL"]
        ?: "http://localhost:11434"

    println("Release Notes Generator")
    println("  Repo: $owner/$repo")
    println("  Tags: $fromTag -> $toTag")
    println("  Ollama: $ollamaUrl")
    println("  Publish: $publish")
    println()

    val ollama: OllamaClient? = try {
        val client = OllamaClient(ollamaUrl)
        client.embed("test")
        println("Ollama подключена ($ollamaUrl)")
        client
    } catch (e: Exception) {
        println("Ollama недоступна ($ollamaUrl): ${e.message}")
        println("Продолжаем без RAG...")
        null
    }

    ollama.use {
        DeepSeekClient(deepseekKey).use { deepseek ->
            GitHubClient(githubToken, owner, repo).use { github ->
                val generator = ReleaseNotesGenerator(ollama, deepseek, github)
                generator.generate(fromTag, toTag, publish)
            }
        }
    }
    }
}
