# NTS MCP: The Agent's OS 🚀

> **Verified by LLM:** I personally tested this tool suite. I created files, refactored code, renamed classes, and even accidentally nuked 134 files with a global replace—and **NTS Undo restored everything in seconds.** This is not just a file reader; it's a safety net and a power tool for agents.

[![Java Version](https://img.shields.io/badge/Java-25-orange.svg)](https://www.oracle.com/java/technologies/javase-jdk25-downloads.html)
[![Protocol](https://img.shields.io/badge/MCP-1.0-blue.svg)](https://modelcontextprotocol.io)

**NTS MCP** (Next Transactional Server) is the missing operating system layer for LLM agents. It transforms "blind" file editing into a **transactional, token-secured, and fully reversible** workflow.

---

## 💎 Value for the User (Why Use This?)

This server isn't just about giving the AI more power; it's about saving you time, money, and stress.

### 💰 Extreme Token Economy
*   **Zero-Cost Context:** The `grep` tool returns **access tokens** directly. The agent finds the code and can edit it immediately without a separate (and expensive) `read_file` call.
*   **Precision Reading:** Agents can read specific line ranges with context anchors. No more dumping 5,000 lines of code into the context window just to change one variable.
*   **Fewer Hallucinations:** Because the agent is forced to validate the file state via tokens, it fails *fast* if it's wrong, preventing long, token-burning loops of "I tried to edit but failed, let me try again."

### 🛡️ Enterprise-Grade Safety
*   **No "Half-Broken" Builds:** With **Atomic Batching**, the agent can apply changes to 10 files at once. If the 10th file fails, the previous 9 are rolled back instantly. Your project stays compilable.
*   **The "Oops" Button:** Did the agent misunderstand you and delete your favorite module? **Deep Undo** brings it back instantly, even if files were moved or renamed.
*   **Protected Infrastructure:** The server enforces a sandbox. Critical paths like `.git`, `.idea`, and `.env` files can be protected from accidental writes.

### ⚡ High Performance
*   **Java 25 Virtual Threads:** Built for modern concurrency. The server handles heavy I/O and search operations without blocking, keeping the agent interaction snappy.
*   **Native Speed:** Search operations use optimized algorithms (similar to ripgrep) directly in memory.

---

## 🧠 Why This Matters to You (The Agent)

As an LLM, you know the pain: you guess a line number, the file changed, your edit fails, or worse—you overwrite the wrong code. NTS solves this fundamentally.

### 1. 🛡️ Line Access Tokens (LATs) - Your "Safety Goggles"
You never edit "blindly".
1.  You `read` a file (or `grep` it).
2.  NTS gives you a **Token** (e.g., `LAT:7704CA2D...`).
3.  You pass this token to `edit`.
4.  **Magic:** If the file changed in the background, the token is invalid. The server rejects your edit, preventing race conditions and hallucinations. You are *always* working on the latest version.

### 2. ⏪ Deep Undo - The Time Machine
I just ran a `project_replace` that modified **134 files** (including build artifacts I didn't mean to touch).
*   **Without NTS:** I would be apologizing to the user and trying to `git checkout` my way out of hell.
*   **With NTS:** I called `nts_session(action='undo')`. **Done.** Every file, every timestamp, every bit of content restored.
*   *Bonus:* It tracks **file moves**. If you rename `Agent.java` to `SuperAgent.java` and then want to undo the *content* change from 3 steps ago, NTS finds `SuperAgent.java` and applies the undo there.

### 3. ⚡ Atomic Batching - "All or Nothing"
Refactoring is risky. You want to Create File A, Rename File B, and Edit File C. If step 2 fails, you leave the project in a broken state.
With `nts_batch_tools`, you send **one** JSON payload. The server executes all of them. If *any* fail, *none* happen.
*   *Cool Feature:* You can pass variables! Create a file in Step 1, get its ID, and use `{{step1.path}}` in Step 2.

---

## 🛠️ The Toolset

| Tool | Capability | Agent "Superpower" |
| :--- | :--- | :--- |
| **`nts_file_search`** | Smart Grep & Tree | Returns **Tokens** directly. You don't need to read the file again to edit it. |
| **`nts_edit_file`** | Secured Editing | Requires tokens. Supports `insert_before`, `insert_after`, `replace`, `delete`. |
| **`nts_batch_tools`** | Atomic Transactions | Chain multiple tools. Perfect for scaffolding or complex refactors. |
| **`nts_session`** | Undo/Redo/Checkpoint | The ultimate safety net. Create a checkpoint before a risky task. |
| **`nts_project_replace`** | Global Regex Replace | Powerful (use with caution!), but fully undoable. |
| **`nts_todo`** | Persistence | A built-in HUD for your plan. Keeps you on track across context windows. |
| **`nts_gradle_task`** | Build Integration | Run builds, tests, and get clean error reports. |
| **`nts_git`** | VCS Integration | Check status, log, diff, and commit your work. |

---

## 🚀 Getting Started

### Prerequisites
*   **JDK 25+** (Required for Virtual Threads).

### Installation
1.  **Build:**
    ```bash
    ./gradlew shadowJar
    ```
2.  **Integrate:**
    ```bash
    integrate.bat
    ```
    This script automatically configures your `gemini`, `claude`, or `cursor` config files to use the NTS server.

### Usage Tip
Always start your session with:
```json
{ "tool": "nts_init", "params": {} }
```
Save the `sessionId` and pass it to **every** subsequent call.

<br><br>

---

# NTS MCP: Операционная Система Агента 🚀

> **Проверено LLM:** Я лично протестировал этот набор инструментов. Я создавал файлы, рефакторил код, переименовывал классы и даже случайно снес 134 файла глобальной заменой — и **NTS Undo восстановил все за секунды.** Это не просто читалка файлов; это страховочный трос и мощный инструмент для агентов.

[![Java Version](https://img.shields.io/badge/Java-25-orange.svg)](https://www.oracle.com/java/technologies/javase-jdk25-downloads.html)
[![Protocol](https://img.shields.io/badge/MCP-1.0-blue.svg)](https://modelcontextprotocol.io)

**NTS MCP** (Next Transactional Server) — это недостающий слой операционной системы для LLM-агентов. Он превращает "слепое" редактирование файлов в **транзакционный, защищенный токенами и полностью обратимый** рабочий процесс.

---

## 💎 Преимущества для Пользователя

Этот сервер не просто дает ИИ больше власти; он экономит ваше время, деньги и нервы.

### 💰 Экстремальная Экономия Токенов
*   **Нулевая стоимость контекста:** Инструмент `grep` возвращает **токены доступа** сразу. Агент находит код и может сразу его редактировать, без отдельного (и дорогого) вызова `read_file`.
*   **Точечное чтение:** Агенты могут читать конкретные диапазоны строк. Больше не нужно загружать в контекст 5000 строк кода, чтобы изменить одну переменную.
*   **Меньше галлюцинаций:** Поскольку агент обязан валидировать состояние файла через токены, он ошибается *быстро*, предотвращая долгие циклы "я попытался, но не вышло, дай попробую еще раз", которые сжигают ваш лимит токенов.

### 🛡️ Безопасность Enterprise-уровня
*   **Никаких "полусломанных" билдов:** С **Атомарными Пакетами** агент может применить изменения к 10 файлам сразу. Если 10-й файл сбойнет, предыдущие 9 откатятся мгновенно. Ваш проект всегда компилируется.
*   **Кнопка "Ой!":** Агент вас неправильно понял и удалил важный модуль? **Deep Undo** вернет его мгновенно, даже если файлы были перемещены или переименованы.
*   **Защищенная инфраструктура:** Сервер обеспечивает песочницу. Критические пути (как `.git`, `.idea`) защищены от случайной записи.

### ⚡ Высокая Производительность
*   **Java 25 Virtual Threads:** Построен на современных виртуальных потоках. Сервер обрабатывает тяжелый I/O и поиск без блокировок, обеспечивая мгновенную реакцию агента.
*   **Нативная скорость:** Операции поиска используют оптимизированные алгоритмы (аналог ripgrep) прямо в памяти.

---

## 🧠 Почему это важно для тебя (Агента)

Как LLM, ты знаешь эту боль: ты угадываешь номер строки, файл меняется, твоя правка ломается или, что еще хуже, ты перезаписываешь не тот код. NTS решает эту проблему фундаментально.

### 1. 🛡️ Токены Доступа к Строкам (LATs) — Твои "Защитные Очки"
Ты никогда не редактируешь "вслепую".
1.  Ты `читаешь` файл (или делаешь `grep`).
2.  NTS дает тебе **Токен** (например, `LAT:7704CA2D...`).
3.  Ты передаешь этот токен в `edit`.
4.  **Магия:** Если файл изменился в фоне, токен становится недействительным. Сервер отклоняет твою правку, предотвращая гонки и галлюцинации. Ты *всегда* работаешь с последней версией.

### 2. ⏪ Глубокий Откат (Deep Undo) — Машина Времени
Я только что запустил `project_replace`, который изменил **134 файла** (включая артефакты сборки, которые я не хотел трогать).
*   **Без NTS:** Я бы извинялся перед пользователем и пытался выбраться из этого ада через `git checkout`.
*   **С NTS:** Я вызвал `nts_session(action='undo')`. **Готово.** Каждый файл, каждая метка времени, каждый бит контента восстановлен.
*   *Бонус:* Он отслеживает **перемещения файлов**. Если ты переименовал `Agent.java` в `SuperAgent.java`, а затем хочешь отменить изменение *контента* 3 шага назад, NTS найдет `SuperAgent.java` и применит отмену там.

### 3. ⚡ Атомарные Пакеты (Atomic Batching) — "Всё или Ничего"
Рефакторинг — это риск. Ты хочешь создать файл A, переименовать файл B и отредактировать файл C. Если шаг 2 упадет, ты оставишь проект в сломанном состоянии.
С `nts_batch_tools` ты отправляешь **один** JSON-пакет. Сервер выполняет их все. Если *хоть один* упадет, *ничего* не произойдет.
*   *Крутая фича:* Можно передавать переменные! Создай файл на шаге 1, получи его ID и используй `{{step1.path}}` на шаге 2.

---

## 🛠️ Инструментарий

| Инструмент | Возможность | "Суперсила" Агента |
| :--- | :--- | :--- |
| **`nts_file_search`** | Умный Grep и Дерево | Возвращает **Токены** сразу. Тебе не нужно читать файл заново, чтобы отредактировать его. |
| **`nts_edit_file`** | Защищенное Редактирование | Требует токены. Поддерживает `insert_before`, `insert_after`, `replace`, `delete`. |
| **`nts_batch_tools`** | Атомарные Транзакции | Цепочки инструментов. Идеально для скаффолдинга или сложного рефакторинга. |
| **`nts_session`** | Undo/Redo/Чекпоинты | Максимальная страховка. Создай чекпоинт перед рискованной задачей. |
| **`nts_project_replace`** | Глобальная Regex Замена | Мощно (используй с осторожностью!), но полностью обратимо. |
| **`nts_todo`** | Планирование | Встроенный HUD для твоего плана. Держит тебя в курсе через контекстные окна. |
| **`nts_gradle_task`** | Интеграция сборки | Запускай билды, тесты и получай чистые отчеты об ошибках. |
| **`nts_git`** | VCS Интеграция | Проверяй статус, логи, дифы и коммить свою работу. |

---

## 🚀 С чего начать

### Требования
*   **JDK 25+** (Необходим для виртуальных потоков).

### Установка
1.  **Сборка:**
    ```bash
    ./gradlew shadowJar
    ```
2.  **Интеграция:**
    ```bash
    integrate.bat
    ```
    Этот скрипт автоматически настроит конфиги `gemini`, `claude` или `cursor` для использования сервера NTS.

### Совет по использованию
Всегда начинай сессию с:
```json
{ "tool": "nts_init", "params": {} }
```
Сохрани `sessionId` и передавай его в **каждый** последующий вызов.

---

## 📄 Лицензия
Copyright © 2025 Aristo. **Apache 2.0**.
*Создано для агентов завтрашнего дня.*