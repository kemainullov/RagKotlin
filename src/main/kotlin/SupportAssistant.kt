import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File

// ---------- MCP-клиент (JSON-RPC over stdio) ----------

class McpClient(serverCommand: List<String>) : AutoCloseable {
    private val process: Process
    private val writer: BufferedWriter
    private val reader: BufferedReader
    private val jsonParser = Json { ignoreUnknownKeys = true }
    private var nextId = 1

    init {
        val pb = ProcessBuilder(serverCommand)
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        process = pb.start()
        writer = process.outputStream.bufferedWriter()
        reader = process.inputStream.bufferedReader()
    }

    private fun sendRequest(method: String, params: JsonObject = buildJsonObject {}): JsonElement {
        val id = nextId++
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        writer.write(Json.encodeToString(request))
        writer.newLine()
        writer.flush()

        while (true) {
            val line = reader.readLine() ?: error("MCP-сервер отключился")
            val json = jsonParser.parseToJsonElement(line).jsonObject
            val responseId = json["id"]?.jsonPrimitive?.intOrNull
            if (responseId == id) {
                val err = json["error"]
                if (err != null && err !is JsonNull) {
                    error("MCP ошибка: $err")
                }
                return json["result"] ?: JsonNull
            }
            // Пропускаем нотификации (сообщения без id или с другим id)
        }
    }

    fun initialize() {
        sendRequest("initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05")
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "support-assistant")
                put("version", "1.0.0")
            }
        })
        // Отправляем нотификацию initialized (без id — не ждём ответа)
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        writer.write(Json.encodeToString(notification))
        writer.newLine()
        writer.flush()
    }

    fun listTools(): List<String> {
        val result = sendRequest("tools/list")
        return result.jsonObject["tools"]?.jsonArray?.map {
            it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
        } ?: emptyList()
    }

    fun callTool(name: String, arguments: JsonObject): String {
        val result = sendRequest("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        return result.jsonObject["content"]?.jsonArray
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
            ?: ""
    }

    override fun close() {
        try { writer.close() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        process.destroy()
    }
}

// ---------- Извлечение ID тикета и пользователя из вопроса ----------

fun extractTicketId(input: String): Int? {
    val match = Regex("""(?:тикет|ticket|#)\s*(\d+)""", RegexOption.IGNORE_CASE).find(input)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

fun extractUserId(input: String): Int? {
    val match = Regex("""(?:пользователь|user|userId)\s*(\d+)""", RegexOption.IGNORE_CASE).find(input)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

// ---------- Индексация для поддержки (data/ + docs/) ----------

suspend fun buildSupportIndex(ollama: OllamaClient, dataDirs: List<String>): List<IndexEntry> {
    val entries = mutableListOf<IndexEntry>()
    val extensions = listOf("txt", "md")

    val files = dataDirs.flatMap { dir ->
        val dirFile = File(dir)
        if (dirFile.isDirectory) {
            dirFile.listFiles()?.filter { it.extension in extensions }?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    println("Найдено файлов для индексации: ${files.size}")
    for (file in files) {
        val text = file.readText()
        val chunks = splitIntoChunks(text)
        println("  ${file.name}: ${chunks.size} чанков")
        for ((i, chunk) in chunks.withIndex()) {
            val embedding = ollama.embed(chunk)
            entries.add(IndexEntry(file.name, i, chunk, embedding.toList()))
        }
    }

    val json = Json { prettyPrint = true }
    File("support-index.json").writeText(json.encodeToString(entries))
    println("Индекс поддержки сохранён: ${entries.size} чанков")
    return entries
}

// ---------- Main ----------

object SupportAssistant {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val jsonParser = Json { ignoreUnknownKeys = true }

        // Загрузка API-ключа
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

        // Подключение к MCP CRM-серверу
        println("Подключение к CRM (MCP)...")
        val mcp = McpClient(listOf("node", "scripts/mcp-crm-server.mjs"))
        mcp.initialize()
        val tools = mcp.listTools()
        println("Доступные MCP tools: ${tools.joinToString(", ")}")

        val threshold = 0.5f

        OllamaClient().use { ollama ->
            DeepSeekClient(apiKey).use { deepseek ->

                // Загрузка или построение индекса
                val indexFile = File("support-index.json")
                var index = if (indexFile.exists()) {
                    val loaded = jsonParser.decodeFromString<List<IndexEntry>>(indexFile.readText())
                    println("Загружен индекс поддержки: ${loaded.size} чанков")
                    loaded
                } else {
                    println("Индексация FAQ и документации...")
                    buildSupportIndex(ollama, listOf("data", "docs"))
                }

                println("\n" + "=".repeat(70))
                println("  АССИСТЕНТ ПОДДЕРЖКИ RagKotlin")
                println("  Команды:")
                println("    <вопрос>         — задать вопрос (с RAG-поиском)")
                println("    тикет <ID>       — упомяните тикет для контекста из CRM")
                println("    /index           — переиндексация документов")
                println("    очистить         — сбросить историю диалога")
                println("    выход            — завершить")
                println("=".repeat(70))

                val history = mutableListOf<Pair<String, String>>()

                while (true) {
                    print("\nВопрос: ")
                    val input = readlnOrNull()?.trim()
                    if (input.isNullOrBlank() || input == "выход") break

                    if (input == "очистить") {
                        history.clear()
                        println("\n>> История диалога очищена.")
                        continue
                    }

                    if (input == "/index") {
                        println("\nПереиндексация...")
                        index = buildSupportIndex(ollama, listOf("data", "docs"))
                        continue
                    }

                    // --- RAG: поиск релевантных чанков ---
                    val queryEmbedding = ollama.embed(input)
                    val allChunks = findRelevantChunks(index, queryEmbedding)
                    val filtered = filterByThreshold(allChunks, threshold)

                    val ragContext = if (filtered.isNotEmpty()) {
                        println("[RAG] Найдено ${filtered.size} релевантных чанков")
                        filtered.mapIndexed { i, (entry, score) ->
                            "[Источник ${i + 1}: ${entry.source}, чанк #${entry.chunkIndex}, score=${String.format("%.2f", score)}]\n${entry.text}"
                        }.joinToString("\n\n")
                    } else {
                        println("[RAG] Релевантные чанки не найдены")
                        ""
                    }

                    // --- MCP: получение данных из CRM ---
                    var crmContext = ""
                    val ticketId = extractTicketId(input)
                    val userId = extractUserId(input)

                    if (ticketId != null) {
                        val ticketData = mcp.callTool("get_ticket", buildJsonObject {
                            put("ticket_id", ticketId)
                        })
                        crmContext += "\nДанные тикета #$ticketId:\n$ticketData\n"
                        println("[MCP] Загружен тикет #$ticketId")
                    }

                    if (userId != null) {
                        val userData = mcp.callTool("get_user", buildJsonObject {
                            put("user_id", userId)
                        })
                        crmContext += "\nДанные пользователя #$userId:\n$userData\n"
                        println("[MCP] Загружен пользователь #$userId")
                    }

                    // Если упомянут тикет, но не пользователь — загрузить пользователя из тикета
                    if (ticketId != null && userId == null && crmContext.contains("\"userId\"")) {
                        try {
                            val ticketJson = jsonParser.parseToJsonElement(
                                crmContext.substringAfter("Данные тикета")
                                    .substringAfter("\n").substringBefore("\n\n")
                                    .trim()
                            ).jsonObject
                            val ticketUserId = ticketJson["userId"]?.jsonPrimitive?.intOrNull
                            if (ticketUserId != null) {
                                val userData = mcp.callTool("get_user", buildJsonObject {
                                    put("user_id", ticketUserId)
                                })
                                crmContext += "\nДанные пользователя:\n$userData\n"
                                println("[MCP] Загружен пользователь тикета #$ticketUserId")
                            }
                        } catch (_: Exception) {
                            // Не критично — продолжаем без данных пользователя
                        }
                    }

                    // Если нет конкретного тикета, но вопрос похож на проблему — поиск по тикетам
                    if (ticketId == null) {
                        val keywords = input.split(" ")
                            .filter { it.length > 3 }
                            .take(3)
                        for (kw in keywords) {
                            val searchResult = mcp.callTool("search_tickets", buildJsonObject {
                                put("query", kw)
                            })
                            if (searchResult.isNotBlank() && !searchResult.contains("не найдены")) {
                                crmContext += "\nПохожие тикеты (поиск по \"$kw\"):\n$searchResult\n"
                                println("[MCP] Найдены похожие тикеты по запросу \"$kw\"")
                                break
                            }
                        }
                    }

                    // --- Формирование промпта ---
                    val historySection = if (history.isNotEmpty()) {
                        val formatted = history.takeLast(5).joinToString("\n") { (q, a) ->
                            "Пользователь: $q\nАссистент: $a"
                        }
                        "\nИстория диалога:\n$formatted\n"
                    } else ""

                    val systemPrompt = buildString {
                        appendLine("Ты — агент поддержки продукта RagKotlin.")
                        appendLine("Помогай пользователям решать проблемы, используя документацию и данные из CRM.")
                        appendLine("Отвечай на русском языке в формате Markdown.")
                        appendLine("Если есть данные тикета — учитывай контекст проблемы пользователя.")
                        appendLine("Указывай источники информации.")
                        if (crmContext.isNotBlank()) {
                            appendLine()
                            appendLine("Данные из CRM:")
                            appendLine(crmContext)
                        }
                        if (ragContext.isNotBlank()) {
                            appendLine()
                            appendLine("Документация (релевантные фрагменты):")
                            appendLine(ragContext)
                        }
                        if (historySection.isNotBlank()) {
                            appendLine(historySection)
                        }
                    }

                    // --- Запрос к LLM ---
                    val answer = deepseek.chat(input, systemPrompt)
                    println("\nОтвет:\n$answer")

                    history.add(input to answer)
                }
            }
        }
        mcp.close()
        println("\nЗавершено.")
    }
}
