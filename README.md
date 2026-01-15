# 🛡️ NTS MCP FileSystem Server
### Next Transactional Server for Model Context Protocol

[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
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

### 🎯 The Philosophy: Disciplined AI Through Intentional Friction

> **"The goal is not to make the agent's job easier — it's to make the agent's work reliable."**

Most MCP servers optimize for **convenience**: fewer calls, shorter responses, maximum automation. NTS takes the opposite approach. It introduces **intentional friction** that forces AI agents to work with surgical precision.

#### The Problem: Catastrophic Drift in Long Sessions

When an AI agent works on a complex task (1-2M+ tokens), context summarization inevitably loses details. The agent "forgets" what it read 50 messages ago. Then:

1. 🔴 Agent edits line 347 based on stale memory
2. 🔴 Edit breaks something — agent panics
3. 🔴 Agent enters an uncontrolled fix-loop
4. 🔴 **Hours of work destroyed in seconds**

This isn't a bug — it's an emergent property of how LLMs handle long contexts. **NTS is designed to prevent this failure mode.**

#### The Solution: Forced Concentration via LAT

**Line Access Tokens (LATs)** are not just a security feature — they're a **cognitive constraint**.

```
┌─────────────────────────────────────────────────────────────────┐
│  Without LAT:                                                   │
│  "I'll just read the whole file... it's only 400 lines"        │
│   → Context bloated with "just in case" data                    │
│   → Summarization drops critical details                        │
│   → Agent edits wrong line from fuzzy memory                    │
│   → Catastrophic error                                          │
├─────────────────────────────────────────────────────────────────┤
│  With LAT:                                                      │
│  "I need to edit line 47. Let me read lines 40-55."            │
│   → Agent explicitly decides what it needs                      │
│   → Token proves agent saw current state                        │
│   → Context stays clean and precise                             │
│   → Edits are surgical and verified                             │
└─────────────────────────────────────────────────────────────────┘
```

The agent **cannot** read an entire file in one lazy command. It must specify ranges. This forces the agent to **think before acting** — exactly the discipline that prevents drift.

#### Why Verbose Responses Matter

Every `nts_edit_file` response includes a full unified diff. This isn't optional verbosity — it's **mandatory validation**.

```diff
--- User.java (original)
+++ User.java (modified)
@@ -15,7 +15,7 @@
     }
 
-    public String getName() {
+    public String getFullName() {
         return name;
     }
```

The agent sees the result **immediately**, in the same response. No separate "verify" step needed. No chance to "forget" to check. The diff is the proof.

#### Real-World Impact

| Scenario | Standard Tools | NTS |
| :--- | :--- | :--- |
| 2-hour refactoring session | 40% chance of catastrophic error | Near-zero (checkpoint + undo) |
| Multi-file rename | Silent corruption possible | Atomic batch or full rollback |
| External file change mid-work | Agent overwrites user's edits | Token expires, agent warned |
| Agent "panics" after error | Uncontrolled fix spiral | Undo → stable state → retry |

#### The Counterintuitive Truth

> **Spending 10% more tokens on discipline saves 100% of wasted work.**

A 2-hour agent session costs ~$5-15 in API calls. A catastrophic error that destroys that work costs the same amount **again** to redo — plus human time to diagnose what went wrong.

NTS trades micro-efficiency for macro-reliability. The agent works slightly harder per-operation, but the **entire session succeeds** instead of collapsing at hour 1:45.



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
*   **Virtual FS Context:** When you edit a file in Step 1 and run `nts_code_refactor` in Step 2, the refactoring sees the **modified content** from Step 1, not the disk version. Enables complex chains like "edit class → rename symbol across project".

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
*   **Optimistic Locking (LATs):** Agents *must* read a file to get a token (`LAT:...`) before editing. If the file changes externally, the token expires and the external change is automatically recorded in file history. No more race conditions.
*   **Smart Token Invalidation:** Tokens track **Range CRC** instead of file CRC. Edits outside your token's range don't invalidate it — only changes to the specific lines you're working on trigger re-read. This dramatically reduces unnecessary token refreshes in large files.
*   **Path Aliasing:** Tokens remain valid after `move`/`rename` operations. The system tracks file identity through path aliases with transitive resolution — even chains like `A → B → C` preserve token validity.
*   **Strict Sandboxing:** All paths are normalized and pinned to the project root. Impossible to escape via `../../`.
*   **Infrastructure Protection:** Automatically blocks modification of `.git`, `.env`, and build configs unless explicitly allowed.
*   **OOM Protection:** Prevents reading massive files (>10MB) that would crash the context window.
*   **Structured Error Codes:** All errors include machine-readable codes (`FILE_NOT_FOUND`, `TOKEN_EXPIRED`, etc.) with human-readable solutions. No more cryptic exceptions — every error tells you exactly what went wrong and how to fix it.

#### 4. ⏪ State Management: Checkpoints & Deep Undo
*   **Session Journal:** Logs every logical step (not just file IO).
*   **Checkpoints:** Agent can run `nts_session checkpoint('pre-refactor')` and safely `rollback` if the approach fails.
*   **Deep Undo:** The system tracks **File Lineage**. If you move `FileA -> FileB` and then hit Undo, NTS knows to restore content to `FileA`.
*   **Git Integration:** Can create Git stashes as emergency fallbacks (`git_checkpoint`).

#### 4.1. 👁️ External Change Tracking
The server automatically detects when files are modified **outside of MCP** (by user, linter, IDE, or other tools).
*   **CRC-based Detection:** Each file read creates a snapshot. On next access, if the CRC differs, the change is detected.
*   **File History:** External changes are recorded in file history and can be reviewed via `nts_session journal`.
*   **Smart Prompts:** When an external change is detected, the agent receives a TIP recommending to review changes before proceeding, as they may be intentional user edits.
*   **Undo Support:** If needed, external changes can be undone through the standard undo mechanism.

#### 4.2. 💡 Smart Contextual TIPs
Every tool response includes intelligent contextual hints that guide the agent through optimal workflows.
*   **Workflow Guidance:** After each operation, TIPs suggest the logical next step (e.g., "Token ready for editing → nts_edit_file(...)").
*   **Performance Hints:** Large range reads trigger suggestions to use symbol-based navigation or grep for precision.
*   **Error Prevention:** Pattern analysis detects regex-like queries used without `isRegex=true` and warns proactively.
*   **Token Management:** When line counts change after edit, TIPs remind to use the NEW token for subsequent operations.
*   **Refactoring Awareness:** Signature changes trigger suggestions to check call sites via `nts_code_navigate(action='references')`.
*   **Import Updates:** After move/rename of Java/Kotlin files, TIPs suggest searching for import statements that need updating.

**Example TIPs in action:**
```
[WORKFLOW: Token ready for editing -> nts_edit_file(path, startLine, content, accessToken)]
[TIP: Large range read (150 lines). Consider using 'symbol' parameter for precise symbol boundaries.]
[TIP: Pattern contains regex-like characters (.*). If you intended regex search, add isRegex=true parameter.]
[TIP: Line count changed (+5). Use NEW TOKEN above for subsequent edits to this file.]
```

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
*   **Change Signature:** Add, remove, rename, retype, or reorder method parameters with automatic call site updates.
*   **Generate:** Create getters, setters, constructors, builders, toString, equals/hashCode.
*   **Extract Method:** Pull code into a new method with proper parameters.
*   **Inline:** Replace method/variable with its body/value.
*   **Preview Mode:** Review diff before applying (`preview: true`).
*   **Parallel Reference Search:** Both `nts_code_navigate` and `nts_code_refactor` use parallel file scanning with pre-filtering, searching up to 15 levels deep for maximum coverage.
*   **Batch Integration:** Returns `affectedFiles` array with tokens for each modified file — enables chaining like `refactor → edit` in `nts_batch_tools`.

```json
{
  "action": "rename",
  "path": "src/User.java",
  "symbol": "getName",
  "newName": "getFullName",
  "preview": true
}
```
**Response includes tokens for batch chaining:**
```json
{
  "affectedFiles": [
    { "path": "src/User.java", "accessToken": "LAT:...", "crc32c": "A1B2C3D4", "lineCount": 50 },
    { "path": "src/UserService.java", "accessToken": "LAT:...", "crc32c": "E5F6G7H8", "lineCount": 120 }
  ]
}
```

---

### 🛠️ The Toolchain: A Discipline System, Not Just Utilities

Each tool in NTS is designed as part of an **interconnected discipline system**. They don't just perform operations — they enforce a workflow that keeps the agent focused, verified, and recoverable.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        THE NTS DISCIPLINE LOOP                              │
│                                                                             │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐          │
│   │  INIT    │────▶│  READ    │────▶│  EDIT    │────▶│  VERIFY  │          │
│   │ Session  │     │ + Token  │     │ + Token  │     │  (Diff)  │          │
│   └──────────┘     └──────────┘     └──────────┘     └────┬─────┘          │
│        │                                                   │                │
│        │              ┌──────────┐                         │                │
│        └─────────────▶│  UNDO    │◀────────────────────────┘                │
│         (if panic)    │ Recovery │    (if wrong)                            │
│                       └──────────┘                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### 🔐 `nts_init` — The Accountability Boundary

**Why it exists:** Creates an isolated session with its own undo history, checkpoints, and token registry.

**Discipline role:** Everything the agent does is tracked. There's no "anonymous" editing. If something breaks, the session journal knows exactly what happened and when.

**Session Reactivation:** If the server restarts or connection drops, the session can be reactivated:
```json
{ "sessionId": "your-previous-uuid" }
```
This restores the session directory with todos and file history. In-memory state (tokens, undo stack) starts fresh, but disk-persisted data is preserved.

**Connection:** All other tools require `sessionId`. This isn't bureaucracy — it's **traceability**.

---

#### 📖 `nts_file_read` — The Attention Gate

**Why it exists:** Reads file content and issues a **Line Access Token (LAT)**.

**Discipline role:** The agent must **explicitly decide** which lines it needs. No "just read everything" shortcut.

```
❌ read({ path: "file.java" })                    // NOT ALLOWED
✅ read({ path: "file.java", startLine: 10, endLine: 30 })  // Forced precision
```

**Connection:** The token returned here is **required** for `nts_edit_file`. Read → Token → Edit. No shortcuts.

**Smart TIPs:** Responses include workflow hints (e.g., "Token ready for editing") and suggest symbol-based reading for large ranges.

**Bulk Read:** Read multiple related files in a single request:
```json
{
  "bulk": [
    { "path": "UserService.java", "symbol": "createUser" },
    { "path": "UserRepository.java", "symbol": "save" },
    { "path": "User.java", "startLine": 1, "endLine": 30 }
  ]
}
```
Each file is separated in output with its own TOKEN. Errors in one file don't affect others.

---

#### ✏️ `nts_edit_file` — The Verified Mutation

**Why it exists:** Applies line-based edits with mandatory token validation.

**Discipline role:**
1. **Token required** — proves agent read the current state
2. **Diff in response** — agent immediately sees what changed
3. **CRC check** — if file changed externally, edit fails safely
4. **Smart TIPs** — contextual hints for common issues:
   - Multi-line content replacing single line without `endLine` → suggests `insert_after` or range
   - Line count changed → reminds to use NEW token for subsequent edits
   - Signature change detected → suggests checking call sites with `nts_code_navigate`
   - Significant changes → reminds to run tests

**Connection:** Consumes token from `nts_file_read`, produces new token for subsequent edits. Chain of custody is unbroken.

---

#### 📁 `nts_file_manage` — Structure with Memory

**Why it exists:** Create, delete, move, rename files and directories.

**Discipline role:**
- `create` returns a token — new files are immediately editable
- `rename`/`move` **transfers tokens via path aliasing** — tokens remain valid even after the file is moved (transitive chains like `A → B → C` work)
- `delete` **invalidates tokens** — no editing ghosts

**Connection:** Works with `nts_batch_tools` for atomic multi-file restructuring. Path aliases persist across the session.

---

#### 🔍 `nts_file_search` — Discovery with Intent

**Why it exists:** Find files (`glob`), search content (`grep`), view structure.

**Discipline role:** `grep` returns **tokens for matched ranges**. The agent can search and immediately edit without a separate read step.

```
grep("TODO") → finds line 47 → returns TOKEN for lines 45-50
           → agent can edit lines 45-50 directly
```

**Smart TIPs:** After grep, workflow hints remind that tokens are ready for direct editing. If pattern looks like regex but `isRegex=false`, suggests enabling it.

**Connection:** Bridges discovery and action. Reduces round-trips while maintaining token discipline.

---

#### ⏪ `nts_session` — The Panic Button

**Why it exists:** Undo, redo, checkpoints, rollback, and session journal.

**Discipline role:** When the agent makes a mistake, it has **structured recovery** instead of uncontrolled fix-spiraling.

```
checkpoint("before-risky-refactor")
  → try dangerous changes
  → if wrong: rollback("before-risky-refactor")
  → project restored in one command
```

**Connection:** This is the safety net that makes aggressive refactoring possible. Agents can be bold because recovery is guaranteed.

---

#### 🔗 `nts_batch_tools` — Atomic Scripting

**Why it exists:** Execute multiple tools as a single atomic transaction.

**Discipline role:** Complex operations either **fully succeed or fully rollback**. No half-broken states.

```json
{
  "actions": [
    { "id": "svc", "tool": "nts_file_manage", "params": { "action": "create", "path": "Service.java" }},
    { "tool": "nts_edit_file", "params": { "path": "{{svc.path}}", "accessToken": "{{svc.token}}", ... }}
  ]
}
// If edit fails → create is rolled back → project untouched
```

**Connection:** Uses `{{step.token}}` interpolation. Tokens flow between steps automatically. This is the culmination of the discipline system.

---

#### 🔄 `nts_project_replace` — Controlled Mass Mutation

**Why it exists:** Global search and replace across the entire project.

**Discipline role:** 
- `dryRun: true` shows **all changes before applying**
- Atomic: all files changed or none
- Creates automatic checkpoint before execution

**Connection:** High-risk operation with maximum safeguards.

---

#### 🧭 `nts_code_navigate` — Semantic Understanding

**Why it exists:** Go to definition, find references, hover info, symbol listing.

**Discipline role:** Agent can understand code structure **before editing**. Reduces guesswork, increases precision.

**Connection:** Returns tokens for found locations. Navigate → understand → edit with confidence.

---

#### 🔧 `nts_code_refactor` — Intelligent Transformation

**Why it exists:** Rename symbols, change signatures, generate code, extract methods — with automatic reference updates.

**Discipline role:**
- `preview: true` shows **all affected files** before applying
- Semantic rename updates ALL references, not just text matches
- Atomic: entire refactoring succeeds or fails together
- **Returns tokens** for all modified files — enables `refactor → edit` chains in batches

**Connection:** Uses tree-sitter for precision. Integrates with `nts_batch_tools` via `{{step.affectedFiles[0].accessToken}}` interpolation. Safer than manual multi-file editing.

---

#### 📋 `nts_todo` — The Focus Anchor

**Why it exists:** Maintains a Markdown-based task list integrated with the HUD.

**Discipline role:** Keeps the agent focused on **one task at a time**. The HUD constantly reminds what's next.

```
[HUD] Plan: Auth Refactor [✓2 ○3] → #3: Update Login Controller
```

**Connection:** Prevents scope creep. Agent always knows the current objective even after context summarization.

---

#### 🔀 `nts_git` — Version Control Integration

**Why it exists:** Git status, diff, add, commit — without leaving NTS.

**Discipline role:** 
- `git_checkpoint` creates stash as emergency backup
- `commit_session` auto-generates commit message from TODO progress
- Safe operations only (no push/force)

**Connection:** Integrates with session journal. Commits can reference completed tasks.

---

#### 📊 `nts_compare_files` — Visual Verification

**Why it exists:** Shows unified diff between any two files.

**Discipline role:** Agent can verify changes by comparing before/after states explicitly.

**Connection:** Useful for reviewing results of batch operations or refactoring.

---

#### ⚙️ `nts_gradle_task` — Build Feedback Loop

**Why it exists:** Run Gradle tasks (build, test, check) with parsed output.

**Discipline role:** Agent gets immediate feedback on whether changes broke the build. Errors are parsed and actionable.

**Connection:** Closes the loop: Edit → Build → Fix → Repeat.

---

#### 🖥️ `nts_task` — Background Awareness

**Why it exists:** Monitor and control long-running background tasks.

**Discipline role:** Agent can check progress of slow operations without blocking.

**Connection:** Works with `nts_gradle_task` for long builds.

---

### The System as a Whole

These tools aren't independent utilities. They form a **closed discipline loop**:

1. **Session** establishes accountability
2. **Read** forces attention and issues tokens
3. **Edit** requires tokens and shows results
4. **Session** provides recovery when needed
5. **Batch** enables complex operations atomically
6. **HUD + TODO** maintains focus across long sessions

**Every tool reinforces the others.** There's no escape hatch to "just edit blindly." The discipline is architectural.

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

#### 3. Docker (No Java Required)

Docker eliminates the need to install Java 25+ locally. The server runs in a container with project directories mounted as volumes.

> **Important: Docker Mode and Roots**
>
> In Docker, you must explicitly mount directories and specify them via `NTS_DOCKER_ROOTS`. These roots **override** any roots sent by the MCP client, because the client sends host paths that don't exist inside the container.

**Option A: Use pre-built image (recommended)**

```bash
docker pull ghcr.io/nefrols/nts-mcp-fs:latest
```

**Single project:**
```json
{
  "mcpServers": {
    "NTS-FileSystem": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/home/user/myproject:/mnt/project",
        "-e", "NTS_DOCKER_ROOTS=/mnt/project",
        "ghcr.io/nefrols/nts-mcp-fs:latest"
      ]
    }
  }
}
```

**Multiple projects:**
```json
{
  "mcpServers": {
    "NTS-FileSystem": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/home/user/project1:/mnt/p1",
        "-v", "/home/user/project2:/mnt/p2",
        "-e", "NTS_DOCKER_ROOTS=/mnt/p1:/mnt/p2",
        "ghcr.io/nefrols/nts-mcp-fs:latest"
      ]
    }
  }
}
```

**Option B: Build locally**
```bash
docker build -t nts-mcp-fs .
docker run -i --rm \
  -v /path/to/project:/mnt/project \
  -e NTS_DOCKER_ROOTS=/mnt/project \
  nts-mcp-fs
```

**Environment variables:**
| Variable | Description |
|----------|-------------|
| `NTS_DOCKER_ROOTS` | **Required.** Colon-separated list of root paths inside the container. Must match your `-v` mount points. Overrides client roots. |
| `JAVA_OPTS` | JVM options (default: `-XX:+UseZGC -Xmx512m`) |
| `MCP_DEBUG` | Set to `true` for debug logging |

**Available image tags:**
| Tag | Description |
|-----|-------------|
| `latest` | Latest stable release |
| `1.2.3` | Specific version |
| `1.2` | Latest patch of minor version |
| `edge` | Latest development build (main branch) |

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

### 🎯 Философия: Дисциплина ИИ через осознанное усложнение

> **«Цель не в том, чтобы облегчить работу агента — а в том, чтобы сделать его работу надёжной.»**

Большинство MCP-серверов оптимизируют **удобство**: меньше вызовов, короче ответы, максимум автоматизации. NTS идёт противоположным путём. Он создаёт **осознанное трение**, которое заставляет ИИ-агента работать с хирургической точностью.

#### Проблема: Катастрофический дрейф в длинных сессиях

Когда ИИ-агент работает над сложной задачей (1-2М+ токенов), суммаризация контекста неизбежно теряет детали. Агент «забывает», что читал 50 сообщений назад. Дальше:

1. 🔴 Агент редактирует строку 347 по устаревшей памяти
2. 🔴 Правка что-то ломает — агент паникует
3. 🔴 Агент входит в неконтролируемый цикл исправлений
4. 🔴 **Часы работы уничтожены за секунды**

Это не баг — это эмерджентное свойство работы LLM с длинным контекстом. **NTS спроектирован для предотвращения этого сценария.**

#### Решение: Принудительная концентрация через LAT

**Line Access Tokens (LATs)** — это не просто механизм безопасности, это **когнитивное ограничение**.

```
┌─────────────────────────────────────────────────────────────────┐
│  Без LAT:                                                       │
│  «Прочитаю весь файл... там всего 400 строк»                   │
│   → Контекст раздут данными «на всякий случай»                 │
│   → Суммаризация теряет критичные детали                       │
│   → Агент правит не ту строку по размытой памяти               │
│   → Катастрофическая ошибка                                    │
├─────────────────────────────────────────────────────────────────┤
│  С LAT:                                                         │
│  «Мне нужно править строку 47. Прочитаю строки 40-55.»         │
│   → Агент явно решает, что ему нужно                           │
│   → Токен доказывает, что агент видел актуальное состояние     │
│   → Контекст остаётся чистым и точным                          │
│   → Правки хирургически точны и верифицированы                 │
└─────────────────────────────────────────────────────────────────┘
```

Агент **не может** прочитать весь файл одной ленивой командой. Он обязан указать диапазон строк. Это заставляет агента **думать перед действием** — именно та дисциплина, которая предотвращает дрейф.

#### Почему подробные ответы важны

Каждый ответ `nts_edit_file` содержит полный unified diff. Это не опциональная многословность — это **обязательная валидация**.

```diff
--- User.java (original)
+++ User.java (modified)
@@ -15,7 +15,7 @@
     }
 
-    public String getName() {
+    public String getFullName() {
         return name;
     }
```

Агент видит результат **немедленно**, в том же ответе. Не нужен отдельный шаг «проверить». Нет шанса «забыть» посмотреть. Diff — это доказательство.

#### Реальное влияние

| Сценарий | Стандартные инструменты | NTS |
| :--- | :--- | :--- |
| 2-часовая сессия рефакторинга | 40% шанс катастрофы | Около нуля (checkpoint + undo) |
| Переименование в нескольких файлах | Возможна тихая порча | Атомарный batch или полный откат |
| Внешнее изменение файла во время работы | Агент затрёт правки пользователя | Токен сгорает, агент предупреждён |
| Агент «паникует» после ошибки | Неконтролируемая спираль фиксов | Undo → стабильное состояние → повтор |

#### Контринтуитивная истина

> **Потратить на 10% больше токенов на дисциплину — значит сохранить 100% работы.**

2-часовая сессия агента стоит ~$5-15 в API-вызовах. Катастрофическая ошибка, уничтожившая эту работу, стоит столько же **повторно** — плюс время человека на диагностику.

NTS меняет микро-эффективность на макро-надёжность. Агент работает чуть усерднее над каждой операцией, но **вся сессия завершается успешно**, а не рушится на 1:45.



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
*   **Виртуальный контекст FS:** Когда вы редактируете файл на Шаге 1 и запускаете `nts_code_refactor` на Шаге 2, рефакторинг видит **изменённый контент** из Шага 1, а не версию с диска. Позволяет создавать сложные цепочки вроде «правка класса → переименование символа по всему проекту».

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
*   **Оптимистичная блокировка (LATs):** Агент *обязан* прочитать файл и получить токен (`LAT:...`) перед правкой. Если файл изменился извне — токен сгорает, а внешнее изменение автоматически записывается в историю файла. Никаких состояний гонки (Race Conditions).
*   **Умная инвалидация токенов:** Токены отслеживают **CRC диапазона**, а не всего файла. Правки вне вашего диапазона не инвалидируют токен — только изменения конкретных строк, с которыми вы работаете, требуют перечитывания. Это радикально сокращает ненужные обновления токенов в больших файлах.
*   **Path Aliasing:** Токены сохраняют валидность после операций `move`/`rename`. Система отслеживает идентичность файла через алиасы путей с транзитивным разрешением — даже цепочки `A → B → C` сохраняют валидность токенов.
*   **Строгая песочница:** Все пути нормализуются и привязываются к корню проекта. Выход через `../../` невозможен.
*   **Защита инфраструктуры:** Блокировка изменений `.git`, `.env` и конфигов сборки (можно настроить).
*   **Защита от OOM:** Блокировка чтения гигантских файлов (>10MB), способных обрушить контекстное окно модели.
*   **Структурированные коды ошибок:** Все ошибки содержат машиночитаемые коды (`FILE_NOT_FOUND`, `TOKEN_EXPIRED` и др.) с понятными решениями. Никаких загадочных исключений — каждая ошибка объясняет, что пошло не так и как это исправить.

#### 4. ⏪ Управление состоянием: Чекпоинты и Deep Undo
*   **Журнал сессии:** Логирует каждый логический шаг.
*   **Чекпоинты:** Агент может создать `nts_session checkpoint('pre-refactor')` и безопасно сделать `rollback`, если гипотеза не сработала.
*   **Deep Undo (Умный откат):** Система отслеживает **Родословную файлов (Lineage)**. Если переместить `FileA -> FileB` и нажать Undo, NTS поймет, что контент нужно вернуть в `FileA`.
*   **Git интеграция:** Возможность создавать Git stashes как аварийные точки сохранения (`git_checkpoint`).

#### 4.1. 👁️ Отслеживание внешних изменений
Сервер автоматически определяет, когда файлы были изменены **вне MCP** (пользователем, линтером, IDE или другими инструментами).
*   **Детекция по CRC:** При каждом чтении файла создаётся снапшот. При следующем доступе, если CRC отличается — изменение обнаруживается.
*   **История файла:** Внешние изменения записываются в историю и доступны через `nts_session journal`.
*   **Умные подсказки:** При обнаружении внешнего изменения агент получает TIP с рекомендацией изучить изменения перед продолжением работы, т.к. они могут быть преднамеренной правкой пользователя.
*   **Поддержка отката:** При необходимости внешние изменения можно откатить через стандартный механизм undo.

#### 4.2. 💡 Умные контекстные подсказки (Smart TIPs)
Каждый ответ инструмента содержит интеллектуальные контекстные подсказки, направляющие агента по оптимальному workflow.
*   **Руководство по workflow:** После каждой операции TIPs предлагают логичный следующий шаг (например, «Токен готов для редактирования → nts_edit_file(...)»).
*   **Подсказки производительности:** Чтение большого диапазона предлагает использовать symbol-навигацию или grep для точности.
*   **Предотвращение ошибок:** Анализ паттернов определяет regex-подобные запросы без `isRegex=true` и предупреждает заранее.
*   **Управление токенами:** При изменении количества строк после правки TIPs напоминают использовать НОВЫЙ токен.
*   **Осведомлённость о рефакторинге:** Изменения сигнатуры предлагают проверить места вызова через `nts_code_navigate(action='references')`.
*   **Обновление импортов:** После move/rename Java/Kotlin файлов TIPs предлагают поискать import-ы для обновления.

**Примеры TIPs в действии:**
```
[WORKFLOW: Token ready for editing -> nts_edit_file(path, startLine, content, accessToken)]
[TIP: Large range read (150 lines). Consider using 'symbol' parameter for precise symbol boundaries.]
[TIP: Pattern contains regex-like characters (.*). If you intended regex search, add isRegex=true parameter.]
[TIP: Line count changed (+5). Use NEW TOKEN above for subsequent edits to this file.]
```

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
*   **Change Signature:** Добавление, удаление, переименование, изменение типа и порядка параметров с автообновлением вызовов.
*   **Generate:** Генерация getters, setters, конструкторов, builder, toString, equals/hashCode.
*   **Extract Method:** Извлечение кода в метод с правильными параметрами.
*   **Inline:** Встраивание метода/переменной.
*   **Preview Mode:** Просмотр изменений перед применением (`preview: true`).
*   **Параллельный поиск ссылок:** И `nts_code_navigate`, и `nts_code_refactor` используют параллельное сканирование файлов с предварительной фильтрацией, ищут на глубину до 15 уровней для максимального покрытия.
*   **Интеграция с Batch:** Возвращает массив `affectedFiles` с токенами для каждого изменённого файла — позволяет строить цепочки `refactor → edit` в `nts_batch_tools`.

```json
{
  "action": "rename",
  "path": "src/User.java",
  "symbol": "getName",
  "newName": "getFullName",
  "preview": true
}
```
**Ответ содержит токены для цепочек в batch:**
```json
{
  "affectedFiles": [
    { "path": "src/User.java", "accessToken": "LAT:...", "crc32c": "A1B2C3D4", "lineCount": 50 },
    { "path": "src/UserService.java", "accessToken": "LAT:...", "crc32c": "E5F6G7H8", "lineCount": 120 }
  ]
}
```

---

### 🛠️ Инструментарий: Система дисциплины, а не просто утилиты

Каждый инструмент NTS спроектирован как часть **взаимосвязанной системы дисциплины**. Они не просто выполняют операции — они обеспечивают рабочий процесс, в котором агент остаётся сфокусированным, верифицированным и восстанавливаемым.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ЦИКЛ ДИСЦИПЛИНЫ NTS                                  │
│                                                                             │
│   ┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐          │
│   │  INIT    │────▶│  READ    │────▶│  EDIT    │────▶│ ПРОВЕРКА │          │
│   │ Сессия   │     │ + Токен  │     │ + Токен  │     │  (Diff)  │          │
│   └──────────┘     └──────────┘     └──────────┘     └────┬─────┘          │
│        │                                                   │                │
│        │              ┌──────────┐                         │                │
│        └─────────────▶│  UNDO    │◀────────────────────────┘                │
│        (при панике)   │Восстановл│    (при ошибке)                          │
│                       └──────────┘                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### 🔐 `nts_init` — Граница ответственности

**Зачем:** Создаёт изолированную сессию с собственной историей undo, чекпоинтами и реестром токенов.

**Роль в дисциплине:** Всё, что делает агент, отслеживается. Нет «анонимного» редактирования. Если что-то сломается — журнал сессии знает, что именно произошло и когда.

**Реактивация сессии:** Если сервер перезапустился или соединение прервалось, сессию можно реактивировать:
```json
{ "sessionId": "ваш-предыдущий-uuid" }
```
Это восстанавливает директорию сессии с todos и историей файлов. Состояние в памяти (токены, стек undo) начинается с чистого листа, но данные на диске сохраняются.

**Связь:** Все остальные инструменты требуют `sessionId`. Это не бюрократия — это **прослеживаемость**.

---

#### 📖 `nts_file_read` — Шлюз внимания

**Зачем:** Читает содержимое файла и выдаёт **Line Access Token (LAT)**.

**Роль в дисциплине:** Агент обязан **явно решить**, какие строки ему нужны. Нет лёгкого пути «просто прочитать всё».

```
❌ read({ path: "file.java" })                    // ЗАПРЕЩЕНО
✅ read({ path: "file.java", startLine: 10, endLine: 30 })  // Принудительная точность
```

**Связь:** Токен, возвращённый здесь, **обязателен** для `nts_edit_file`. Read → Token → Edit. Без сокращений.

**Умные TIPs:** Ответы содержат подсказки workflow (например, «Токен готов для редактирования») и предлагают symbol-чтение для больших диапазонов.

**Массовое чтение (Bulk Read):** Чтение нескольких связанных файлов одним запросом:
```json
{
  "bulk": [
    { "path": "UserService.java", "symbol": "createUser" },
    { "path": "UserRepository.java", "symbol": "save" },
    { "path": "User.java", "startLine": 1, "endLine": 30 }
  ]
}
```
Каждый файл отделён в выводе и имеет свой TOKEN. Ошибка в одном файле не влияет на остальные.

---

#### ✏️ `nts_edit_file` — Верифицированная мутация

**Зачем:** Применяет построчные правки с обязательной валидацией токена.

**Роль в дисциплине:**
1. **Токен обязателен** — доказывает, что агент прочитал текущее состояние
2. **Diff в ответе** — агент сразу видит, что изменилось
3. **Проверка CRC** — если файл изменён извне, правка безопасно отклоняется
4. **Умные TIPs** — контекстные подсказки для типичных ситуаций:
   - Многострочный контент заменяет одну строку без `endLine` → предлагает `insert_after` или указать диапазон
   - Изменилось количество строк → напоминает использовать НОВЫЙ токен
   - Обнаружено изменение сигнатуры → предлагает проверить места вызова через `nts_code_navigate`
   - Значительные изменения → напоминает запустить тесты

**Связь:** Потребляет токен от `nts_file_read`, выдаёт новый токен для последующих правок. Цепочка владения не прерывается.

---

#### 📁 `nts_file_manage` — Структура с памятью

**Зачем:** Создание, удаление, перемещение, переименование файлов и директорий.

**Роль в дисциплине:**
- `create` возвращает токен — новые файлы сразу готовы к редактированию
- `rename`/`move` **переносят токены через path aliasing** — токены остаются валидными даже после перемещения файла (транзитивные цепочки `A → B → C` работают)
- `delete` **инвалидирует токены** — нельзя редактировать «призраков»

**Связь:** Работает с `nts_batch_tools` для атомарной реструктуризации. Алиасы путей сохраняются на протяжении сессии.

---

#### 🔍 `nts_file_search` — Поиск с намерением

**Зачем:** Поиск файлов (`glob`), поиск в содержимом (`grep`), просмотр структуры.

**Роль в дисциплине:** `grep` возвращает **токены для найденных диапазонов**. Агент может искать и сразу редактировать без отдельного шага чтения.

```
grep("TODO") → находит строку 47 → возвращает TOKEN для строк 45-50
           → агент может редактировать строки 45-50 напрямую
```

**Умные TIPs:** После grep подсказки workflow напоминают, что токены готовы для прямого редактирования. Если паттерн похож на regex, но `isRegex=false`, предлагает включить его.

**Связь:** Мост между обнаружением и действием. Сокращает обращения, сохраняя токенную дисциплину.

---

#### ⏪ `nts_session` — Кнопка паники

**Зачем:** Undo, redo, чекпоинты, откат и журнал сессии.

**Роль в дисциплине:** Когда агент ошибается, у него есть **структурированное восстановление** вместо неконтролируемой спирали исправлений.

```
checkpoint("before-risky-refactor")
  → пробуем опасные изменения
  → если неправильно: rollback("before-risky-refactor")
  → проект восстановлен одной командой
```

**Связь:** Это страховочная сеть, которая делает возможным агрессивный рефакторинг. Агенты могут быть смелыми, потому что восстановление гарантировано.

---

#### 🔗 `nts_batch_tools` — Атомарный скриптинг

**Зачем:** Выполняет несколько инструментов как единую атомарную транзакцию.

**Роль в дисциплине:** Сложные операции либо **полностью успешны, либо полностью откатываются**. Никаких наполовину сломанных состояний.

```json
{
  "actions": [
    { "id": "svc", "tool": "nts_file_manage", "params": { "action": "create", "path": "Service.java" }},
    { "tool": "nts_edit_file", "params": { "path": "{{svc.path}}", "accessToken": "{{svc.token}}", ... }}
  ]
}
// Если edit падает → create откатывается → проект нетронут
```

**Связь:** Использует интерполяцию `{{step.token}}`. Токены перетекают между шагами автоматически. Это кульминация системы дисциплины.

---

#### 🔄 `nts_project_replace` — Контролируемая массовая мутация

**Зачем:** Глобальный поиск и замена по всему проекту.

**Роль в дисциплине:**
- `dryRun: true` показывает **все изменения до применения**
- Атомарность: все файлы изменены или ни один
- Автоматический чекпоинт перед выполнением

**Связь:** Высокорисковая операция с максимальными гарантиями.

---

#### 🧭 `nts_code_navigate` — Семантическое понимание

**Зачем:** Go to definition, find references, hover info, список символов.

**Роль в дисциплине:** Агент может понять структуру кода **до редактирования**. Меньше догадок, больше точности.

**Связь:** Возвращает токены для найденных мест. Навигация → понимание → уверенная правка.

---

#### 🔧 `nts_code_refactor` — Интеллектуальная трансформация

**Зачем:** Переименование символов, изменение сигнатур, генерация кода, извлечение методов — с автоматическим обновлением ссылок.

**Роль в дисциплине:**
- `preview: true` показывает **все затронутые файлы** до применения
- Семантическое переименование обновляет ВСЕ ссылки, а не просто текстовые совпадения
- Атомарность: весь рефакторинг успешен или отменён целиком
- **Возвращает токены** для всех изменённых файлов — позволяет строить цепочки `refactor → edit` в батчах

**Связь:** Использует tree-sitter для точности. Интегрируется с `nts_batch_tools` через интерполяцию `{{step.affectedFiles[0].accessToken}}`. Безопаснее ручного редактирования нескольких файлов.

---

#### 📋 `nts_todo` — Якорь фокуса

**Зачем:** Ведёт Markdown-список задач, интегрированный с HUD.

**Роль в дисциплине:** Держит агента сфокусированным на **одной задаче за раз**. HUD постоянно напоминает, что дальше.

```
[HUD] Plan: Auth Refactor [✓2 ○3] → #3: Update Login Controller
```

**Связь:** Предотвращает расползание скоупа. Агент всегда знает текущую цель даже после суммаризации контекста.

---

#### 🔀 `nts_git` — Интеграция с контролем версий

**Зачем:** Git status, diff, add, commit — не покидая NTS.

**Роль в дисциплине:**
- `git_checkpoint` создаёт stash как аварийный бэкап
- `commit_session` автогенерирует сообщение коммита из прогресса TODO
- Только безопасные операции (без push/force)

**Связь:** Интегрируется с журналом сессии. Коммиты могут ссылаться на завершённые задачи.

---

#### 📊 `nts_compare_files` — Визуальная верификация

**Зачем:** Показывает unified diff между любыми двумя файлами.

**Роль в дисциплине:** Агент может явно верифицировать изменения, сравнивая состояния до/после.

**Связь:** Полезен для ревью результатов batch-операций или рефакторинга.

---

#### ⚙️ `nts_gradle_task` — Цикл обратной связи от сборки

**Зачем:** Запуск Gradle-задач (build, test, check) с парсингом вывода.

**Роль в дисциплине:** Агент немедленно получает фидбэк, сломали ли изменения сборку. Ошибки распарсены и готовы к действию.

**Связь:** Замыкает цикл: Edit → Build → Fix → Repeat.

---

#### 🖥️ `nts_task` — Осведомлённость о фоне

**Зачем:** Мониторинг и управление долгими фоновыми задачами.

**Роль в дисциплине:** Агент может проверять прогресс медленных операций без блокировки.

**Связь:** Работает с `nts_gradle_task` для долгих сборок.

---

### Система как целое

Эти инструменты — не независимые утилиты. Они образуют **замкнутый цикл дисциплины**:

1. **Session** устанавливает ответственность
2. **Read** принуждает к вниманию и выдаёт токены
3. **Edit** требует токены и показывает результаты
4. **Session** обеспечивает восстановление при необходимости
5. **Batch** позволяет сложные операции атомарно
6. **HUD + TODO** поддерживают фокус на протяжении длинных сессий

**Каждый инструмент усиливает остальные.** Нет лазейки, чтобы «просто редактировать вслепую». Дисциплина — архитектурная.

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

#### 3. Docker (Без установки Java)

Docker избавляет от необходимости устанавливать Java 25+ локально. Сервер работает в контейнере, а директории проектов монтируются как volumes.

> **Важно: Docker-режим и Roots**
>
> В Docker необходимо явно монтировать директории и указывать их через `NTS_DOCKER_ROOTS`. Эти roots **переопределяют** любые roots от MCP-клиента, поскольку клиент передаёт пути хост-системы, которые не существуют внутри контейнера.

**Вариант А: Готовый образ (рекомендуется)**

```bash
docker pull ghcr.io/nefrols/nts-mcp-fs:latest
```

**Один проект:**
```json
{
  "mcpServers": {
    "NTS-FileSystem": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/home/user/myproject:/mnt/project",
        "-e", "NTS_DOCKER_ROOTS=/mnt/project",
        "ghcr.io/nefrols/nts-mcp-fs:latest"
      ]
    }
  }
}
```

**Несколько проектов:**
```json
{
  "mcpServers": {
    "NTS-FileSystem": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "-v", "/home/user/project1:/mnt/p1",
        "-v", "/home/user/project2:/mnt/p2",
        "-e", "NTS_DOCKER_ROOTS=/mnt/p1:/mnt/p2",
        "ghcr.io/nefrols/nts-mcp-fs:latest"
      ]
    }
  }
}
```

**Вариант Б: Локальная сборка**
```bash
docker build -t nts-mcp-fs .
docker run -i --rm \
  -v /путь/к/проекту:/mnt/project \
  -e NTS_DOCKER_ROOTS=/mnt/project \
  nts-mcp-fs
```

**Переменные окружения:**
| Переменная | Описание |
|------------|----------|
| `NTS_DOCKER_ROOTS` | **Обязательна.** Список путей внутри контейнера через двоеточие. Должны соответствовать точкам монтирования `-v`. Переопределяет roots от клиента. |
| `JAVA_OPTS` | Опции JVM (по умолчанию: `-XX:+UseZGC -Xmx512m`) |
| `MCP_DEBUG` | Установите `true` для отладочного логирования |

**Доступные теги образа:**
| Тег | Описание |
|-----|----------|
| `latest` | Последний стабильный релиз |
| `1.2.3` | Конкретная версия |
| `1.2` | Последний патч минорной версии |
| `edge` | Последняя dev-сборка (ветка main) |

---

<p align="center">
  <sub>Built with ❤️ by <a href="https://github.com/Nefrols">Nefrols</a></sub>
</p>