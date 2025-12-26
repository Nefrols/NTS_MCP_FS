# 🛡️ NTS MCP FileSystem Server
### Next Transactional Server for Model Context Protocol

[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=for-the-badge)](LICENSE)
[![Status](https://img.shields.io/badge/Status-Stable-green?style=for-the-badge)]()
[![Tools](https://img.shields.io/badge/MCP%20Tools-15-purple?style=for-the-badge)]()
[![Languages](https://img.shields.io/badge/Languages-12-blue?style=for-the-badge)]()

> **[English](#-english)** | **[Русский](#-russian)**

---

<a name="-english"></a>
## 🇬🇧 English

**NTS_MCP_FS** is an enterprise-grade File System server implementation for the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/).

It transforms standard file operations into a **Transactional OS for AI Agents**. Unlike basic tools that allow "blind" overwrites, NTS enforces **Optimistic Locking**, provides a **Persistent HUD**, and enables **Atomic Scripting** via programmable batches.

### 🚀 Key Differentiators

| Feature | Standard MCP Server | NTS_MCP_FS |
| :--- | :--- | :--- |
| **Integrity** | Blind Overwrites (Last Write Wins) | **Line Access Tokens (LATs)** - Optimistic Locking |
| **Operations** | One file at a time | **Programmable Atomic Batches** (Multi-file Scripting) |
| **Context** | Stateless (Agent forgets plan) | **AI-HUD & Built-in TODOs** (Persistent Context) |
| **Safety** | Basic Ctrl+Z (if any) | **Deep Undo & Checkpoints** (Tracks file moves) |
| **Code Intelligence** | None | **LSP Navigation & Semantic Refactoring** (12 languages) |
| **Performance** | Blocking I/O | **Java Virtual Threads** & Memory-Mapped I/O |

---

### 🧠 Advanced Features Deep Dive

#### 1. 📟 Agent HUD (Heads-Up Display)
The server injects a status header into *every* tool response. The Agent never loses context.
```text
[HUD sid:a1b2] Plan: Refactor Auth [✓2 ○1] → #3: Update Login | Session: 5 edits | Unlocked: 3 files
```
*   **Session Context:** Reminds the agent of the active Session ID.
*   **Progress Tracking:** Shows current TODO status (Done/Pending) and the *next* active task.
*   **Safety Stats:** Shows how many files are currently unlocked for editing.

#### 2. 📜 Programmable Atomic Batches (Scripting)
The `nts_batch_tools` is not just a list of commands; it's a scripting engine for the file system.
*   **Atomic Transactions:** 10 operations in one request. If the 10th fails, the previous 9 are rolled back instantly. The project is never left in a broken state.
*   **Variable Interpolation:** Pass data between steps. Create a file in Step 1, then reference its path in Step 2 using `{{step1.path}}`.
*   **Virtual Addressing:** Use variables like `$LAST` or `$PREV_END+1` to insert code relative to previous edits without calculating line numbers.

**Example Script:** "Create a service, rename it, and add a method"
```json
"actions": [
  { "id": "cre", "tool": "nts_file_manage", "params": { "action": "create", "path": "Temp.java", "content": "class Svc {}" } },
  { "tool": "nts_file_manage", "params": { "action": "rename", "path": "{{cre.path}}", "newName": "UserService.java" } },
  { "tool": "nts_edit_file", "params": { "path": "{{cre.path}}", "startLine": "$LAST", "operation": "insert_after", "content": "void login() {}", "accessToken": "{{cre.token}}" } }
]
```
*Note: `{{cre.path}}` automatically resolves to `UserService.java` after the rename step!*

#### 3. 🔒 Enterprise Security & Sandboxing
*   **Optimistic Locking (LATs):** Agents *must* read a file to get a token (`LAT:...`) before editing. If the file changes externally, the token expires. No more race conditions.
*   **Strict Sandboxing:** All paths are normalized and pinned to the project root. Impossible to escape via `../../`.
*   **Infrastructure Protection:** Automatically blocks modification of `.git`, `.env`, and build configs unless explicitly allowed.
*   **OOM Protection:** Prevents reading massive files (>10MB) that would crash the context window.

#### 4. ⏪ State Management: Checkpoints & Deep Undo
*   **Session Journal:** Logs every logical step (not just file IO).
*   **Checkpoints:** Agent can run `nts_session checkpoint('pre-refactor')` and safely `rollback` if the approach fails.
*   **Deep Undo:** The system tracks **File Lineage**. If you move `FileA -> FileB` and then hit Undo, NTS knows to restore content to `FileA`.
*   **Git Integration:** Can create Git stashes as emergency fallbacks (`git_checkpoint`).

#### 5. ✅ Built-in TODO System
A specialized tool (`nts_todo`) allows the agent to maintain a Markdown-based plan.
*   The active plan state is fed into the **HUD**.
*   Keeps the agent focused on one task at a time.
*   Auto-updates status (`todo`, `done`, `failed`) in the file system.

#### 6. 🧭 LSP Navigation (Tree-sitter)
The `nts_code_navigate` tool provides IDE-like code intelligence powered by Tree-sitter.
*   **Go to Definition:** Jump to where a symbol is defined.
*   **Find References:** Locate all usages across the project.
*   **Hover:** Get type, signature, and documentation for any symbol.
*   **List Symbols:** File outline with all definitions.
*   **12 Languages:** Java, Kotlin, JS/TS/TSX, Python, Go, Rust, C/C++, C#, PHP, HTML.

#### 7. 🔄 Semantic Refactoring
The `nts_code_refactor` tool performs intelligent code transformations.
*   **Rename:** Updates ALL references across the entire project automatically.
*   **Generate:** Create getters, setters, constructors, builders, toString, equals/hashCode.
*   **Extract Method:** Pull code into a new method with proper parameters.
*   **Inline:** Replace method/variable with its body/value.
*   **Preview Mode:** Review diff before applying (`preview: true`).

```json
{
  "action": "rename",
  "path": "src/User.java",
  "symbol": "getName",
  "newName": "getFullName",
  "preview": true
}
```

---

### 🛠️ Available Tools (15)

| Category | Tool | Description |
| :--- | :--- | :--- |
| **Session** | `nts_init` | Initialize session (call FIRST) |
| | `nts_session` | Undo/Redo, Checkpoints, Rollback |
| **File System** | `nts_file_read` | Read files with LAT tokens |
| | `nts_file_manage` | Create, delete, move, rename |
| | `nts_file_search` | Glob, grep, project structure |
| | `nts_compare_files` | Unified diff between files |
| **Editing** | `nts_edit_file` | Line-based editing with tokens |
| | `nts_project_replace` | Global search & replace |
| **Navigation** | `nts_code_navigate` | Go to definition, find references |
| **Refactoring** | `nts_code_refactor` | Rename, generate, extract, inline |
| **External** | `nts_git` | Git operations (status, diff, commit) |
| | `nts_gradle_task` | Build automation |
| **Planning** | `nts_todo` | Task tracking with HUD integration |
| **System** | `nts_batch_tools` | Atomic multi-tool transactions |
| | `nts_task` | Background task monitoring |

---

### 📦 Installation & Usage

**Prerequisites:** Java 25+ (Virtual Threads, enhanced performance).

#### 1. Quick Start (Auto-Integration)
Build and run the integrator to automatically configure Claude Desktop, Cursor, or other clients.

```bash
./gradlew shadowJar
java -jar app/build/libs/app-all.jar --integrate
```

#### 2. Manual Configuration
Add to your `mcp-config.json`:
```json
{
  "mcpServers": {
    "NTS-FileSystem": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/nts-mcp-fs/app/build/libs/app-all.jar"
      ]
    }
  }
}
```

---

<a name="-russian"></a>
## 🇷🇺 Русский

**NTS_MCP_FS** — это сервер реализации [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) уровня Enterprise.

Он превращает работу с файлами в **Транзакционную ОС для ИИ-агентов**. В отличие от простых инструментов, допускающих "слепую" перезапись, NTS обеспечивает **Оптимистичную блокировку**, предоставляет **Постоянный HUD** и позволяет создавать **Атомарные скрипты** через программируемые батчи.

### 🚀 Ключевые отличия

| Функция | Обычный MCP Сервер | NTS_MCP_FS |
| :--- | :--- | :--- |
| **Целостность** | Слепая перезапись (кто последний, тот и прав) | **Line Access Tokens (LATs)** - Оптимистичная блокировка |
| **Операции** | По одному файлу за раз | **Программируемые Атомарные Батчи** (Скриптинг) |
| **Контекст** | Нет памяти (Агент забывает план) | **AI-HUD и Встроенный TODO** (Постоянный контекст) |
| **Безопасность** | Ctrl+Z (если повезет) | **Deep Undo и Чекпоинты** (Учет перемещений файлов) |
| **Интеллект кода** | Отсутствует | **LSP-навигация и Семантический рефакторинг** (12 языков) |
| **Скорость** | Блокирующий I/O | **Java Virtual Threads** и Memory-Mapped I/O |

---

### 🧠 Подробный обзор функций

#### 1. 📟 HUD для Агента (Heads-Up Display)
Сервер внедряет строку статуса в *каждый* ответ инструмента. Агент никогда не теряет контекст.
```text
[HUD sid:a1b2] Plan: Refactor Auth [✓2 ○1] → #3: Update Login | Session: 5 edits | Unlocked: 3 files
```
*   **Контекст сессии:** Напоминает агенту ID активной сессии.
*   **Трекинг прогресса:** Показывает состояние TODO (Готово/В ожидании) и *следующую* задачу.
*   **Статус безопасности:** Показывает, сколько файлов открыто для редактирования.

#### 2. 📜 Программируемые Атомарные Батчи (Скриптинг)
Инструмент `nts_batch_tools` — это не просто список команд, это движок скриптинга файловой системы.
*   **Атомарные транзакции:** 10 действий в одном запросе. Если 10-е упадет, предыдущие 9 откатятся мгновенно. Проект никогда не останется "сломанным".
*   **Интерполяция переменных:** Передача данных между шагами. Создайте файл на Шаге 1 и используйте его путь на Шаге 2 через `{{step1.path}}`.
*   **Виртуальная адресация:** Используйте переменные `$LAST` (конец файла) или `$PREV_END+1` (вставка сразу после предыдущей правки), чтобы не высчитывать номера строк вручную.

**Пример скрипта:** "Создать сервис, переименовать и добавить метод"
```json
"actions": [
  { "id": "cre", "tool": "nts_file_manage", "params": { "action": "create", "path": "Temp.java", "content": "class Svc {}" } },
  { "tool": "nts_file_manage", "params": { "action": "rename", "path": "{{cre.path}}", "newName": "UserService.java" } },
  { "tool": "nts_edit_file", "params": { "path": "{{cre.path}}", "startLine": "$LAST", "operation": "insert_after", "content": "void login() {}", "accessToken": "{{cre.token}}" } }
]
```
*Заметьте: `{{cre.path}}` автоматически превратится в `UserService.java` после шага переименования!*

#### 3. 🔒 Корпоративная безопасность и Песочница
*   **Оптимистичная блокировка (LATs):** Агент *обязан* прочитать файл и получить токен (`LAT:...`) перед правкой. Если файл изменился извне — токен сгорает. Никаких состояний гонки (Race Conditions).
*   **Строгая песочница:** Все пути нормализуются и привязываются к корню проекта. Выход через `../../` невозможен.
*   **Защита инфраструктуры:** Блокировка изменений `.git`, `.env` и конфигов сборки (можно настроить).
*   **Защита от OOM:** Блокировка чтения гигантских файлов (>10MB), способных обрушить контекстное окно модели.

#### 4. ⏪ Управление состоянием: Чекпоинты и Deep Undo
*   **Журнал сессии:** Логирует каждый логический шаг.
*   **Чекпоинты:** Агент может создать `nts_session checkpoint('pre-refactor')` и безопасно сделать `rollback`, если гипотеза не сработала.
*   **Deep Undo (Умный откат):** Система отслеживает **Родословную файлов (Lineage)**. Если переместить `FileA -> FileB` и нажать Undo, NTS поймет, что контент нужно вернуть в `FileA`.
*   **Git интеграция:** Возможность создавать Git stashes как аварийные точки сохранения (`git_checkpoint`).

#### 5. ✅ Встроенная система TODO
Специальный инструмент `nts_todo` позволяет агенту вести план в формате Markdown.
*   Активный план транслируется в **HUD**.
*   Удерживает фокус агента на одной задаче.
*   Автоматически обновляет статусы (`todo`, `done`, `failed`) в файле.

#### 6. 🧭 LSP-навигация (Tree-sitter)
Инструмент `nts_code_navigate` обеспечивает IDE-подобную навигацию на базе Tree-sitter.
*   **Go to Definition:** Переход к определению символа.
*   **Find References:** Поиск всех использований по проекту.
*   **Hover:** Информация о типе, сигнатуре и документации.
*   **List Symbols:** Структура файла со всеми определениями.
*   **12 языков:** Java, Kotlin, JS/TS/TSX, Python, Go, Rust, C/C++, C#, PHP, HTML.

#### 7. 🔄 Семантический рефакторинг
Инструмент `nts_code_refactor` выполняет интеллектуальные преобразования кода.
*   **Rename:** Переименование с автоматическим обновлением ВСЕХ ссылок по проекту.
*   **Generate:** Генерация getters, setters, конструкторов, builder, toString, equals/hashCode.
*   **Extract Method:** Извлечение кода в метод с правильными параметрами.
*   **Inline:** Встраивание метода/переменной.
*   **Preview Mode:** Просмотр изменений перед применением (`preview: true`).

```json
{
  "action": "rename",
  "path": "src/User.java",
  "symbol": "getName",
  "newName": "getFullName",
  "preview": true
}
```

---

### 🛠️ Доступные инструменты (15)

| Категория | Инструмент | Описание |
| :--- | :--- | :--- |
| **Сессия** | `nts_init` | Инициализация сессии (вызвать ПЕРВЫМ) |
| | `nts_session` | Undo/Redo, Чекпоинты, Откат |
| **Файлы** | `nts_file_read` | Чтение файлов с LAT-токенами |
| | `nts_file_manage` | Создание, удаление, перемещение |
| | `nts_file_search` | Glob, grep, структура проекта |
| | `nts_compare_files` | Unified diff между файлами |
| **Редактирование** | `nts_edit_file` | Построчное редактирование с токенами |
| | `nts_project_replace` | Глобальный поиск и замена |
| **Навигация** | `nts_code_navigate` | Go to definition, find references |
| **Рефакторинг** | `nts_code_refactor` | Rename, generate, extract, inline |
| **Внешние** | `nts_git` | Git операции (status, diff, commit) |
| | `nts_gradle_task` | Автоматизация сборки |
| **Планирование** | `nts_todo` | Трекинг задач с HUD-интеграцией |
| **Система** | `nts_batch_tools` | Атомарные мульти-операции |
| | `nts_task` | Мониторинг фоновых задач |

---

### 📦 Установка и запуск

**Требования:** Java 25+ (Virtual Threads, улучшенная производительность).

#### 1. Быстрый старт (Авто-интеграция)
Соберите проект и запустите интегратор для автоматической настройки клиентов (Claude Desktop, Cursor и др.).

```bash
./gradlew shadowJar
java -jar app/build/libs/app-all.jar --integrate
```

#### 2. Ручная конфигурация
Добавьте этот блок в ваш `mcp-config.json`:
```json
{
  "mcpServers": {
    "NTS-FileSystem": {
      "command": "java",
      "args": [
        "-jar",
        "/абсолютный/путь/к/nts-mcp-fs/app/build/libs/app-all.jar"
      ]
    }
  }
}
```

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://github.com/Nefrols">Nefrols</a></sub>
</p>