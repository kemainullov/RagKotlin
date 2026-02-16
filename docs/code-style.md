# Правила стиля кода RagKotlin

## Именование

- Классы: `PascalCase` — `OllamaClient`, `DeepSeekClient`, `IndexEntry`
- Функции: `camelCase` — `findRelevantChunks`, `askWithContext`, `buildIndex`
- Константы: `camelCase` с `val` — `val threshold = 0.72f`
- Дата-классы: описательные имена полей — `source`, `chunkIndex`, `text`, `embedding`

## Форматирование

- Отступы: 4 пробела
- Максимальная длина строки: 120 символов
- Фигурные скобки: на той же строке (`Egyptian style`)
- Пустые строки между логическими секциями, отмечены комментариями `// ----------`

## Корутины и асинхронность

- Suspend-функции для всех операций ввода-вывода (`embed`, `chat`, `processQuestion`)
- Точка входа: `fun main() = runBlocking { ... }`
- HTTP-клиенты с Ktor CIO engine

## Паттерны проекта

- **AutoCloseable клиенты**: `OllamaClient` и `DeepSeekClient` реализуют `AutoCloseable`, используются через `.use { }`
- **Serializable дата-классы**: все модели API аннотированы `@Serializable` для kotlinx.serialization
- **Конфигурация через local.properties**: API-ключи читаются из файла или переменных окружения
- **JSON с ignoreUnknownKeys**: все парсеры JSON настроены на игнорирование неизвестных полей

## Обработка ошибок

- API-ключ проверяется при запуске, программа завершается с сообщением при отсутствии
- Файлы проверяются на существование перед чтением
- HTTP-таймаут для DeepSeek: 120 секунд
