import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

// ---------- Извлечение параметров из запроса ----------

fun extractTaskId(input: String): Int? {
    val match = Regex("""(?:задача|task|#)\s*(\d+)""", RegexOption.IGNORE_CASE).find(input)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

fun extractPriority(input: String): String? {
    val match = Regex("""(?:приоритет|priority)\s+(low|medium|high|critical)""", RegexOption.IGNORE_CASE).find(input)
    return match?.groupValues?.get(1)?.lowercase()
        ?: Regex("""\b(low|medium|high|critical)\b""", RegexOption.IGNORE_CASE).find(input)?.value?.lowercase()
}

fun extractStatus(input: String): String? {
    val match = Regex("""(?:статус|status)\s+(todo|in_progress|review|done)""", RegexOption.IGNORE_CASE).find(input)
    return match?.groupValues?.get(1)?.lowercase()
}

fun extractAssigneeId(input: String): Int? {
    val match = Regex("""(?:assignee_id|исполнитель_id)\s*(\d+)""", RegexOption.IGNORE_CASE).find(input)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

// ---------- Определение типа действия ----------

enum class ActionType {
    GET_TASK,
    CREATE_TASK,
    UPDATE_TASK,
    LIST_TASKS,
    PROJECT_SUMMARY,
    GENERAL_QUESTION
}

fun detectAction(input: String): ActionType {
    val lower = input.lowercase()
    return when {
        lower.contains("создай задачу") || lower.contains("create task") ||
                lower.contains("новая задача") || lower.contains("добавь задачу") -> ActionType.CREATE_TASK

        (lower.contains("обнови") || lower.contains("update") || lower.contains("измени")) &&
                (lower.contains("задач") || lower.contains("task")) -> ActionType.UPDATE_TASK

        lower.contains("статус проекта") || lower.contains("сводка") || lower.contains("summary") ||
                lower.contains("прогресс") || lower.contains("обзор проекта") -> ActionType.PROJECT_SUMMARY

        lower.contains("задачи") || lower.contains("tasks") ||
                lower.contains("список задач") || lower.contains("приоритет") ||
                lower.contains("priority") -> ActionType.LIST_TASKS

        Regex("""(?:задача|task|#)\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(input) -> ActionType.GET_TASK

        else -> ActionType.GENERAL_QUESTION
    }
}

// ---------- Поиск исполнителя по имени/роли ----------

fun findAssigneeByName(input: String, members: JsonArray): Int? {
    val lower = input.lowercase()
    for (member in members) {
        val obj = member.jsonObject
        val name = obj["name"]?.jsonPrimitive?.content ?: continue
        val role = obj["role"]?.jsonPrimitive?.content ?: ""
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue

        // Поиск по имени (Ивана, Марии, Алексея, Елены — включая склонения)
        val firstName = name.split(" ").first()
        val nameVariants = listOf(
            firstName.lowercase(),
            firstName.dropLast(1).lowercase(), // Иван -> Ива (для "Ивана")
            firstName.lowercase().dropLast(1) + "у", // Ивану
            firstName.lowercase().dropLast(1) + "е"  // Иване
        )
        if (nameVariants.any { lower.contains(it) }) return id
        if (lower.contains(role)) return id
    }
    return null
}

// ---------- Индексация для команды (data/ + docs/) ----------

suspend fun buildTeamIndex(ollama: OllamaClient, dataDirs: List<String>): List<IndexEntry> {
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
    File("team-index.json").writeText(json.encodeToString(entries))
    println("Индекс команды сохранён: ${entries.size} чанков")
    return entries
}

// ---------- LLM-парсинг параметров создания задачи ----------

suspend fun parseCreateTaskParams(
    input: String,
    deepseek: DeepSeekClient,
    members: JsonArray
): JsonObject? {
    val membersList = members.joinToString(", ") { m ->
        val obj = m.jsonObject
        "${obj["id"]?.jsonPrimitive?.content}: ${obj["name"]?.jsonPrimitive?.content} (${obj["role"]?.jsonPrimitive?.content})"
    }

    val prompt = """Извлеки параметры задачи из текста пользователя. Верни ТОЛЬКО JSON без пояснений.

Члены команды: $membersList

Текст: "$input"

Формат ответа (JSON):
{
  "title": "краткое название задачи",
  "description": "подробное описание",
  "priority": "low|medium|high|critical",
  "assignee_id": число (ID члена команды),
  "deadline": "YYYY-MM-DD или null",
  "tags": ["тег1", "тег2"]
}"""

    val response = deepseek.chat(prompt, "Ты — парсер задач. Возвращай только валидный JSON.")

    return try {
        // Извлечь JSON из ответа (может быть обёрнут в markdown)
        val jsonStr = Regex("""\{[\s\S]*}""").find(response)?.value ?: response
        Json.parseToJsonElement(jsonStr).jsonObject
    } catch (e: Exception) {
        println("[Ошибка] Не удалось распарсить параметры задачи: ${e.message}")
        null
    }
}

// ---------- Main ----------

object TeamAssistant {
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

        // Подключение к MCP PM-серверу
        println("Подключение к PM (MCP)...")
        val pmClient = McpClient(listOf("node", "scripts/mcp-pm-server.mjs"))
        pmClient.initialize()
        val pmTools = pmClient.listTools()
        println("PM tools: ${pmTools.joinToString(", ")}")

        // Подключение к MCP CRM-серверу
        println("Подключение к CRM (MCP)...")
        val crmClient = McpClient(listOf("node", "scripts/mcp-crm-server.mjs"))
        crmClient.initialize()
        val crmTools = crmClient.listTools()
        println("CRM tools: ${crmTools.joinToString(", ")}")

        // Загрузка данных о членах команды для маппинга имён
        val tasksData = jsonParser.parseToJsonElement(File("data/tasks.json").readText()).jsonObject
        val members = tasksData["members"]?.jsonArray ?: buildJsonArray {}

        val threshold = 0.5f

        OllamaClient().use { ollama ->
            DeepSeekClient(apiKey).use { deepseek ->

                // Загрузка или построение индекса
                val indexFile = File("team-index.json")
                var index = if (indexFile.exists()) {
                    val loaded = jsonParser.decodeFromString<List<IndexEntry>>(indexFile.readText())
                    println("Загружен индекс команды: ${loaded.size} чанков")
                    loaded
                } else {
                    println("Индексация документации команды...")
                    buildTeamIndex(ollama, listOf("data", "docs"))
                }

                println("\n" + "=".repeat(70))
                println("  КОМАНДНЫЙ АССИСТЕНТ RagKotlin")
                println("  Команды:")
                println("    статус проекта       — сводка по спринту")
                println("    задачи [фильтр]      — список задач (high, todo, Иван...)")
                println("    задача <ID>          — детали задачи")
                println("    создай задачу: ...   — создать новую задачу")
                println("    обнови задачу <ID>   — обновить задачу")
                println("    <вопрос>             — вопрос о команде/проекте (RAG)")
                println("    /index               — переиндексация документов")
                println("    очистить             — сбросить историю диалога")
                println("    выход                — завершить")
                println("=".repeat(70))

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
                        println("\nПереиндексация...")
                        index = buildTeamIndex(ollama, listOf("data", "docs"))
                        continue
                    }

                    val action = detectAction(input)

                    // --- MCP PM: данные задач ---
                    var pmContext = ""
                    var actionResult = ""

                    when (action) {
                        ActionType.GET_TASK -> {
                            val taskId = extractTaskId(input)
                            if (taskId != null) {
                                val data = pmClient.callTool("get_task", buildJsonObject {
                                    put("task_id", taskId)
                                })
                                pmContext = "Детали задачи #$taskId:\n$data"
                                println("[MCP PM] Загружена задача #$taskId")
                            }
                        }

                        ActionType.CREATE_TASK -> {
                            println("[LLM] Парсинг параметров задачи...")
                            val params = parseCreateTaskParams(input, deepseek, members)
                            if (params != null) {
                                val createArgs = buildJsonObject {
                                    put("title", params["title"]?.jsonPrimitive?.content ?: "Без названия")
                                    put("description", params["description"]?.jsonPrimitive?.content ?: "")
                                    put("priority", params["priority"]?.jsonPrimitive?.content ?: "medium")
                                    put("assignee_id", params["assignee_id"]?.jsonPrimitive?.intOrNull ?: 1)
                                    val deadline = params["deadline"]
                                    if (deadline != null && deadline !is JsonNull) {
                                        put("deadline", deadline.jsonPrimitive.content)
                                    }
                                    val tags = params["tags"]
                                    if (tags != null && tags is JsonArray) {
                                        put("tags", tags)
                                    }
                                }
                                val result = pmClient.callTool("create_task", createArgs)
                                actionResult = result
                                pmContext = "Результат создания задачи:\n$result"
                                println("[MCP PM] Задача создана")
                            } else {
                                pmContext = "Не удалось распарсить параметры задачи из запроса."
                            }
                        }

                        ActionType.UPDATE_TASK -> {
                            val taskId = extractTaskId(input)
                            if (taskId != null) {
                                val updateArgs = buildJsonObject {
                                    put("task_id", taskId)
                                    extractStatus(input)?.let { put("status", it) }
                                    extractPriority(input)?.let { put("priority", it) }
                                    extractAssigneeId(input)?.let { put("assignee_id", it) }
                                    // Если статус не найден регулярками, попробовать простые паттерны
                                    if (extractStatus(input) == null) {
                                        val lower = input.lowercase()
                                        when {
                                            lower.contains("done") || lower.contains("готово") ->
                                                put("status", "done")
                                            lower.contains("in_progress") || lower.contains("в работу") ||
                                                    lower.contains("в работе") ->
                                                put("status", "in_progress")
                                            lower.contains("review") || lower.contains("ревью") ->
                                                put("status", "review")
                                            lower.contains("todo") ->
                                                put("status", "todo")
                                        }
                                    }
                                }
                                val result = pmClient.callTool("update_task", updateArgs)
                                actionResult = result
                                pmContext = "Результат обновления задачи #$taskId:\n$result"
                                println("[MCP PM] Задача #$taskId обновлена")
                            }
                        }

                        ActionType.LIST_TASKS -> {
                            val listArgs = buildJsonObject {
                                extractPriority(input)?.let { put("priority", it) }
                                extractStatus(input)?.let { put("status", it) }
                                // Поиск по имени/роли
                                val assigneeId = findAssigneeByName(input, members)
                                if (assigneeId != null) put("assignee_id", assigneeId)
                            }
                            val data = pmClient.callTool("list_tasks", listArgs)
                            pmContext = "Список задач:\n$data"
                            println("[MCP PM] Загружены задачи")
                        }

                        ActionType.PROJECT_SUMMARY -> {
                            val data = pmClient.callTool("get_project_summary", buildJsonObject {})
                            pmContext = "Сводка по проекту:\n$data"
                            println("[MCP PM] Загружена сводка проекта")
                        }

                        ActionType.GENERAL_QUESTION -> {
                            // Для общих вопросов тоже загрузим сводку, если полезно
                        }
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

                    // --- MCP CRM: при необходимости ---
                    var crmContext = ""
                    val ticketId = extractTicketId(input)
                    if (ticketId != null) {
                        val ticketData = crmClient.callTool("get_ticket", buildJsonObject {
                            put("ticket_id", ticketId)
                        })
                        crmContext += "\nДанные тикета #$ticketId:\n$ticketData\n"
                        println("[MCP CRM] Загружен тикет #$ticketId")
                    }

                    // --- Формирование промпта ---
                    val historySection = if (history.isNotEmpty()) {
                        val formatted = history.takeLast(5).joinToString("\n") { (q, a) ->
                            "Пользователь: $q\nАссистент: $a"
                        }
                        "\nИстория диалога:\n$formatted\n"
                    } else ""

                    val systemPrompt = buildString {
                        appendLine("Ты — командный ассистент проекта RagKotlin.")
                        appendLine("Помогаешь команде управлять задачами, отвечаешь на вопросы о проекте,")
                        appendLine("даёшь рекомендации по приоритетам.")
                        appendLine("Отвечай на русском языке в формате Markdown.")
                        appendLine()
                        appendLine("При рекомендациях учитывай:")
                        appendLine("- Дедлайны (ближайшие — в приоритете)")
                        appendLine("- Блокирующие зависимости (задача, которая блокирует другие — важнее)")
                        appendLine("- Приоритет (critical > high > medium > low)")
                        appendLine("- Загруженность членов команды")
                        if (pmContext.isNotBlank()) {
                            appendLine()
                            appendLine("Данные из системы управления задачами:")
                            appendLine(pmContext)
                        }
                        if (crmContext.isNotBlank()) {
                            appendLine()
                            appendLine("Данные из CRM:")
                            appendLine(crmContext)
                        }
                        if (ragContext.isNotBlank()) {
                            appendLine()
                            appendLine("Документация команды (релевантные фрагменты):")
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
        pmClient.close()
        crmClient.close()
        println("\nЗавершено.")
    }
}
