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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import kotlin.math.sqrt

private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }
private val JSON_PRETTY = Json { ignoreUnknownKeys = true; prettyPrint = true }

// ─── Ollama ───────────────────────────────────────────────────────────────────

@Serializable
data class OllamaEmbedRequest(val model: String = "nomic-embed-text", val input: String)

@Serializable
data class OllamaEmbedResponse(val embeddings: List<List<Double>>)

class OllamaClient(private val baseUrl: String = "http://localhost:11434") : AutoCloseable {
    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(JSON)
        }
    }

    suspend fun embed(text: String): FloatArray {
        val resp: OllamaEmbedResponse = http.post("$baseUrl/api/embed") {
            contentType(ContentType.Application.Json)
            setBody(OllamaEmbedRequest(input = text))
        }.body()
        return resp.embeddings.first().map { it.toFloat() }.toFloatArray()
    }

    override fun close() = http.close()
}

// ─── DeepSeek (tool calling) ──────────────────────────────────────────────────

class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com"
) : AutoCloseable {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 120_000 }
    }

    // Raw API call: accepts pre-built JsonObjects, returns parsed response
    suspend fun chatRaw(
        messages: List<JsonObject>,
        tools: List<JsonObject>? = null
    ): JsonObject {
        val body = buildJsonObject {
            put("model", "deepseek-chat")
            put("messages", JsonArray(messages))
            if (!tools.isNullOrEmpty()) {
                put("tools", JsonArray(tools))
                put("tool_choice", "auto")
            }
        }
        val responseText: String = http.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(body.toString())
        }.body()
        val parsed = JSON.parseToJsonElement(responseText).jsonObject
        // Surface API errors early
        parsed["error"]?.let { err ->
            val msg = (err as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: err.toString()
            error("DeepSeek API error: $msg")
        }
        return parsed
    }

    override fun close() = http.close()
}

// ─── Index ────────────────────────────────────────────────────────────────────

@Serializable
data class IndexEntry(
    val source: String,
    val chunkIndex: Int,
    val text: String,
    val embedding: List<Float>
)

// ─── Chunking ─────────────────────────────────────────────────────────────────

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

// ─── Cosine similarity ────────────────────────────────────────────────────────

fun cosineSimilarity(a: List<Float>, b: FloatArray): Float {
    var dot = 0f; var normA = 0f; var normB = 0f
    for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
    return dot / (sqrt(normA) * sqrt(normB))
}

fun findRelevantChunks(index: List<IndexEntry>, queryEmbedding: FloatArray, topN: Int = 5) =
    index.map { it to cosineSimilarity(it.embedding, queryEmbedding) }
        .sortedByDescending { it.second }
        .take(topN)

fun filterByThreshold(chunks: List<Pair<IndexEntry, Float>>, threshold: Float = 0.5f) =
    chunks.filter { it.second >= threshold }

// ─── Index building ───────────────────────────────────────────────────────────

val INDEXABLE_EXTENSIONS = setOf(
    "txt", "md", "kt", "java", "ts", "tsx", "js", "jsx",
    "py", "go", "rs", "toml", "yaml", "yml", "kts", "properties"
)
val SKIP_DIRS = setOf(
    "build", ".gradle", ".idea", "node_modules", ".git",
    "dist", "out", "__pycache__", ".venv", "venv", ".cache"
)

suspend fun buildIndex(ollama: OllamaClient, projectRoot: File): List<IndexEntry> {
    val entries = mutableListOf<IndexEntry>()
    val files = projectRoot.walkTopDown()
        .onEnter { dir -> dir.name !in SKIP_DIRS }
        .filter { it.isFile && it.extension in INDEXABLE_EXTENSIONS }
        .toList()

    println("Файлов для индексации: ${files.size}")
    for (file in files) {
        try {
            val text = file.readText()
            if (text.isBlank()) continue
            val relPath = file.relativeTo(projectRoot).path
            val chunks = splitIntoChunks(text)
            for ((i, chunk) in chunks.withIndex()) {
                val embedding = ollama.embed(chunk)
                entries.add(IndexEntry(relPath, i, chunk, embedding.toList()))
            }
            print(".")
        } catch (_: Exception) { /* skip binary or unreadable */ }
    }
    println()

    val indexFile = indexFileFor(projectRoot)
    indexFile.parentFile.mkdirs()
    indexFile.writeText(JSON_PRETTY.encodeToString(entries))
    println("Индекс сохранён: ${entries.size} чанков → ${indexFile.absolutePath}")
    return entries
}

// Store indexes in ~/.god-agent/ keyed by project, to avoid polluting analyzed projects
fun indexFileFor(projectRoot: File): File {
    val cacheDir = File(System.getProperty("user.home"), ".god-agent")
    val key = "${projectRoot.canonicalFile.name}-${projectRoot.canonicalPath.hashCode().toUInt()}"
    return File(cacheDir, "$key.json")
}

// ─── Shell helper ─────────────────────────────────────────────────────────────

fun runCommand(vararg cmd: String): String = try {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out.ifBlank { "(empty)" }
} catch (e: Exception) { "Error: ${e.message}" }

// ─── Tool definitions (JSON Schema) ──────────────────────────────────────────

fun toolDefs(): List<JsonObject> = listOf(
    buildToolDef(
        name = "search_docs",
        description = "Semantic search (RAG) over indexed project files. Use to find relevant code, docs, config by meaning.",
        params = mapOf("query" to "Search query")
    ),
    buildToolDef(
        name = "read_file",
        description = "Read contents of a file in the project. Use path relative to project root.",
        params = mapOf("path" to "File path relative to project root")
    ),
    buildToolDef(
        name = "list_dir",
        description = "List files and subdirectories. Use path relative to project root (default: '.').",
        params = mapOf("path" to "Directory path relative to project root"),
        required = emptyList()
    ),
    buildToolDef(
        name = "git_info",
        description = "Get git info: current branch, last 10 commits, working tree status.",
        params = emptyMap(),
        required = emptyList()
    ),
    buildToolDef(
        name = "grep_code",
        description = "Search for a text pattern across source files. Returns file:line:content matches.",
        params = mapOf(
            "pattern" to "Text pattern to search for (case-insensitive)",
            "path" to "Directory to search in, relative to project root (default: '.')"
        ),
        required = listOf("pattern")
    )
)

private fun buildToolDef(
    name: String,
    description: String,
    params: Map<String, String>,
    required: List<String> = params.keys.toList()
): JsonObject = buildJsonObject {
    put("type", "function")
    put("function", buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                params.forEach { (key, desc) ->
                    put(key, buildJsonObject {
                        put("type", "string")
                        put("description", desc)
                    })
                }
            })
            put("required", buildJsonArray { required.forEach { add(it) } })
        })
    })
}

// ─── Tool executor ────────────────────────────────────────────────────────────

suspend fun executeTool(
    name: String,
    arguments: String,
    projectRoot: File,
    index: List<IndexEntry>,
    ollama: OllamaClient,
    threshold: Float
): String {
    val args = try {
        Json.parseToJsonElement(arguments).jsonObject
    } catch (_: Exception) {
        buildJsonObject {}
    }

    fun str(key: String, default: String? = null): String? =
        args[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content ?: default

    return when (name) {
        "search_docs" -> {
            val query = str("query") ?: return "Error: missing query"
            val emb = ollama.embed(query)
            val chunks = filterByThreshold(findRelevantChunks(index, emb), threshold)
            if (chunks.isEmpty()) "No relevant results found."
            else chunks.joinToString("\n\n---\n\n") { (entry, score) ->
                "[${entry.source} chunk #${entry.chunkIndex} score=${"%.2f".format(score)}]\n${entry.text}"
            }
        }

        "read_file" -> {
            val path = str("path") ?: return "Error: missing path"
            val file = File(projectRoot, path)
            if (!file.exists()) return "File not found: $path"
            if (!file.canonicalPath.startsWith(projectRoot.canonicalPath)) return "Access denied"
            val content = file.readText()
            if (content.length > 12_000) content.substring(0, 12_000) + "\n...[truncated at 12000 chars]"
            else content
        }

        "list_dir" -> {
            val path = str("path", ".")!!
            val dir = File(projectRoot, path)
            if (!dir.exists() || !dir.isDirectory) return "Directory not found: $path"
            if (!dir.canonicalPath.startsWith(projectRoot.canonicalPath)) return "Access denied"
            dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?.joinToString("\n") { f ->
                    val size = if (f.isFile) " (${f.length()} bytes)" else ""
                    if (f.isDirectory) "[DIR]  ${f.name}/" else "[FILE] ${f.name}$size"
                }
                ?: "Empty directory"
        }

        "git_info" -> buildString {
            appendLine("Branch: ${runCommand("git", "-C", projectRoot.absolutePath, "rev-parse", "--abbrev-ref", "HEAD")}")
            appendLine("\nRecent commits (last 10):")
            appendLine(runCommand("git", "-C", projectRoot.absolutePath, "log", "--oneline", "-10"))
            appendLine("\nStatus:")
            append(runCommand("git", "-C", projectRoot.absolutePath, "status", "--short"))
        }

        "grep_code" -> {
            val pattern = str("pattern") ?: return "Error: missing pattern"
            val searchPath = str("path", ".")!!
            val searchDir = File(projectRoot, searchPath)
            if (!searchDir.canonicalPath.startsWith(projectRoot.canonicalPath)) return "Access denied"
            val results = searchDir.walkTopDown()
                .onEnter { it.name !in SKIP_DIRS }
                .filter { it.isFile && it.extension in INDEXABLE_EXTENSIONS }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { i, line ->
                        if (line.contains(pattern, ignoreCase = true))
                            "${file.relativeTo(projectRoot)}:${i + 1}: ${line.trim()}"
                        else null
                    }
                }
                .take(40)
                .toList()
            if (results.isEmpty()) "No matches found for: $pattern"
            else results.joinToString("\n")
        }

        else -> "Unknown tool: $name"
    }
}

// ─── Agent loop (ReAct via tool calling) ─────────────────────────────────────

suspend fun agentLoop(
    question: String,
    systemPrompt: String,
    history: List<Pair<String, String>>,
    deepseek: DeepSeekClient,
    projectRoot: File,
    index: List<IndexEntry>,
    ollama: OllamaClient,
    threshold: Float,
    maxIterations: Int = 8
): String {
    val messages = mutableListOf<JsonObject>()

    messages.add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })

    // Include last 4 history turns for context
    history.takeLast(4).forEach { (q, a) ->
        messages.add(buildJsonObject { put("role", "user"); put("content", q) })
        messages.add(buildJsonObject { put("role", "assistant"); put("content", a) })
    }

    messages.add(buildJsonObject { put("role", "user"); put("content", question) })

    val tools = toolDefs()

    repeat(maxIterations) {
        val response = deepseek.chatRaw(messages, tools)
        val choice = response["choices"]!!.jsonArray[0].jsonObject
        val message = choice["message"]!!.jsonObject
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.content

        val toolCalls = message["tool_calls"]
            ?.takeIf { it !is JsonNull }
            ?.jsonArray
            ?.takeIf { it.isNotEmpty() }

        if (finishReason == "stop" || toolCalls == null) {
            val content = message["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            return content ?: "No response from model."
        }

        // Add assistant message with tool_calls to history
        messages.add(message)

        // Execute all tool calls in this round
        for (toolCall in toolCalls) {
            val tc = toolCall.jsonObject
            val callId = tc["id"]!!.jsonPrimitive.content
            val funcObj = tc["function"]!!.jsonObject
            val funcName = funcObj["name"]!!.jsonPrimitive.content
            val funcArgs = funcObj["arguments"]!!.jsonPrimitive.content

            print("  [tool: $funcName] ")
            val result = executeTool(funcName, funcArgs, projectRoot, index, ollama, threshold)
            println("→ ${result.length} chars")

            messages.add(buildJsonObject {
                put("role", "tool")
                put("tool_call_id", callId)
                put("content", result)
            })
        }
    }

    // Max iterations reached — ask for final answer without tools
    messages.add(buildJsonObject {
        put("role", "user")
        put("content", "Summarize your findings and give a final answer.")
    })
    val finalResp = deepseek.chatRaw(messages)
    return finalResp["choices"]!!.jsonArray[0].jsonObject["message"]!!
        .jsonObject["content"]!!.jsonPrimitive.content
}

// ─── System prompt ────────────────────────────────────────────────────────────

fun buildSystemPrompt(projectRoot: File, gitBranch: String): String {
    val name = projectRoot.canonicalFile.name
    return """You are a senior developer assistant for the "$name" project.
Project root: ${projectRoot.canonicalPath}
Current git branch: $gitBranch

Available tools:
- search_docs(query)       — semantic search across all indexed files
- read_file(path)          — read a file by path (relative to project root)
- list_dir(path?)          — list directory contents
- git_info()               — branch, recent commits, working tree status
- grep_code(pattern, path?) — find pattern in source files

Strategy:
1. For code/architecture questions — use search_docs first, then read_file for details
2. For history/changes — use git_info
3. For "where is X defined" — use grep_code
4. Provide specific file paths and line numbers when possible
5. Respond in the same language the user writes in

Be concise and accurate. Cite files you reference."""
}

// ─── RAG-only /help (no agent loop, fast) ────────────────────────────────────

suspend fun helpCommand(
    question: String,
    index: List<IndexEntry>,
    ollama: OllamaClient,
    deepseek: DeepSeekClient,
    threshold: Float,
    history: List<Pair<String, String>>,
    gitBranch: String,
    projectName: String
): String {
    val emb = ollama.embed(question)
    val chunks = filterByThreshold(findRelevantChunks(index, emb), threshold)
    if (chunks.isEmpty()) return "Релевантные документы не найдены."

    val context = chunks.mapIndexed { i, (entry, score) ->
        "[Source ${i + 1}: ${entry.source} chunk #${entry.chunkIndex} score=${"%.2f".format(score)}]\n${entry.text}"
    }.joinToString("\n\n")

    val historyText = if (history.isNotEmpty()) {
        "Conversation history:\n" +
                history.takeLast(4).joinToString("\n") { (q, a) -> "User: $q\nAssistant: $a" } + "\n\n"
    } else ""

    val systemPrompt = "You are a developer assistant for the $projectName project (branch: $gitBranch). Answer concisely and cite sources as [Source N]."
    val userMsg = "${historyText}Context:\n$context\n\nQuestion: $question"

    val messages = listOf(
        buildJsonObject { put("role", "system"); put("content", systemPrompt) },
        buildJsonObject { put("role", "user"); put("content", userMsg) }
    )
    val resp = deepseek.chatRaw(messages)
    return resp["choices"]!!.jsonArray[0].jsonObject["message"]!!
        .jsonObject["content"]!!.jsonPrimitive.content
}

// ─── Main ─────────────────────────────────────────────────────────────────────

fun main(args: Array<String>) = runBlocking {
    // Project to analyze (defaults to current directory)
    val projectPath = args.firstOrNull() ?: "."
    val projectRoot = File(projectPath).canonicalFile

    if (!projectRoot.exists() || !projectRoot.isDirectory) {
        println("Проект не найден: ${projectRoot.absolutePath}")
        return@runBlocking
    }

    // API key from local.properties (RagKotlin's own config) or env
    val props = File("local.properties").takeIf { it.exists() }
        ?.readLines()
        ?.associate { line -> val (k, v) = line.split("=", limit = 2); k.trim() to v.trim() }
        ?: emptyMap()
    val apiKey = props["DEEPSEEK_API_KEY"]?.takeIf { it.isNotBlank() }
        ?: System.getenv("DEEPSEEK_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Укажите DEEPSEEK_API_KEY в local.properties или DEEPSEEK_API_KEY в окружении")
        return@runBlocking
    }

    val threshold = 0.45f
    val gitBranch = runCommand("git", "-C", projectRoot.absolutePath, "rev-parse", "--abbrev-ref", "HEAD")

    OllamaClient().use { ollama ->
        DeepSeekClient(apiKey).use { deepseek ->

            val indexFile = indexFileFor(projectRoot)
            var index = if (indexFile.exists()) {
                val loaded = JSON.decodeFromString<List<IndexEntry>>(indexFile.readText())
                println("Загружен индекс: ${loaded.size} чанков [${indexFile.name}]")
                loaded
            } else {
                println("Первый запуск — индексирую проект: ${projectRoot.absolutePath}")
                buildIndex(ollama, projectRoot)
            }

            val systemPrompt = buildSystemPrompt(projectRoot, gitBranch)

            println("\n" + "═".repeat(72))
            println("  GOD AGENT — ${projectRoot.name}")
            println("  Путь:   ${projectRoot.absolutePath}")
            println("  Ветка:  $gitBranch")
            println("  Индекс: ${index.size} чанков")
            println("─".repeat(72))
            println("  /help <вопрос>    RAG-поиск по документации (быстро)")
            println("  /review <файл>    Код-ревью файла")
            println("  /analyze          Анализ архитектуры проекта")
            println("  /git              Сводка по git")
            println("  /index            Переиндексировать проект")
            println("  очистить          Сбросить историю диалога")
            println("  выход             Завершить")
            println("  <вопрос>          Агентный режим (LLM использует инструменты)")
            println("═".repeat(72))

            val history = mutableListOf<Pair<String, String>>()

            while (true) {
                print("\nВы: ")
                val input = readlnOrNull()?.trim()
                if (input.isNullOrBlank() || input == "выход") break

                when {
                    input == "очистить" -> {
                        history.clear()
                        println(">> История диалога очищена.")
                    }

                    input == "/index" -> {
                        println("Переиндексация: ${projectRoot.absolutePath}")
                        index = buildIndex(ollama, projectRoot)
                    }

                    input == "/git" -> {
                        println()
                        println(runCommand("git", "-C", projectRoot.absolutePath, "log", "--oneline", "-10"))
                        println()
                        println(runCommand("git", "-C", projectRoot.absolutePath, "status"))
                    }

                    input == "/analyze" -> {
                        val q = "Analyse the project structure: main components, architecture, technology stack, entry points. Use list_dir and read_file for key files. Be thorough."
                        println("\nАгент:")
                        val answer = agentLoop(q, systemPrompt, history, deepseek, projectRoot, index, ollama, threshold)
                        println(answer)
                        history.add(q to answer)
                    }

                    input.startsWith("/review ") -> {
                        val filePath = input.removePrefix("/review ").trim()
                        val q = "Perform a thorough code review of '$filePath': find bugs, potential issues, style violations, and suggest improvements. Read the file first."
                        println("\nАгент:")
                        val answer = agentLoop(q, systemPrompt, history, deepseek, projectRoot, index, ollama, threshold)
                        println(answer)
                        history.add(q to answer)
                    }

                    input.startsWith("/help ") -> {
                        val question = input.removePrefix("/help ").trim()
                        if (question.isEmpty()) { println("Использование: /help <вопрос>"); continue }
                        println("\nАссистент (RAG):")
                        val answer = helpCommand(question, index, ollama, deepseek, threshold, history, gitBranch, projectRoot.name)
                        println(answer)
                        history.add(question to answer)
                    }

                    else -> {
                        println("\nАгент:")
                        val answer = agentLoop(input, systemPrompt, history, deepseek, projectRoot, index, ollama, threshold)
                        println(answer)
                        history.add(input to answer)
                    }
                }
            }
        }
    }
    println("\nЗавершено.")
}
