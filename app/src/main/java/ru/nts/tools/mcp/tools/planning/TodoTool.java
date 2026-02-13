/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Инструмент для управления планами работ (TODO).
 * Поддерживает создание, просмотр статуса и обновление пунктов плана.
 */
public class TodoTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("^\\s*([-*]|\\d+\\.)\\s+\\[([ xX])]\\s+(.*)$");

    /**
     * Возвращает путь к директории todos текущей сессии.
     */
    private Path getTodosDir() {
        return SessionContext.currentOrDefault().getTodosDir();
    }

    @Override
    public String getName() { return "nts_todo"; }

    @Override
    public String getDescription() {
        return """
            Task planning and progress tracking system.

            ACTIONS:
            - create  - Start new plan with title and checklist
            - read    - View current plan and task states (alias: status)
            - update  - Mark task done/failed or edit task text
            - add     - Add new task to current plan
            - close   - Archive completed plan (removes from HUD)
            - list    - Show all plans (active and archived)
            - reopen  - Reactivate an archived plan
            - items   - Get active TODO as structured JSON (for integrations)
            - all_items - Get ALL active TODOs as structured JSON

            TASK FORMAT (Markdown checklist):
            - [ ] Pending task
            - [x] Completed task
            - [X] Failed task (uppercase X)

            WORKFLOW:
            1. create(title='Feature X', content='- [ ] Step 1\\n- [ ] Step 2')
            2. read() -> view current plan with task IDs
            3. update(id=1, status='done') -> mark task #1 complete
            4. add(task='Step 3') -> append new task
            5. close() -> archive plan when ALL tasks done

            IMPORTANT - MARK TASKS COMPLETE:
            After completing each task, IMMEDIATELY call:
              nts_todo(action='update', id=N, status='done')
            This keeps the plan accurate and visible in HUD.

            IMPORTANT - DUAL TODO USAGE:
            If your client has a built-in TODO system (like Claude Code's TodoWrite),
            use BOTH systems together:
            - Native TODO: for client-side task tracking
            - nts_todo: for persistent server-side plans with undo/session support
            Keep both synchronized for best results.

            INTEGRATION:
            - HUD displays active plan progress (title + ✓done ○pending)
            - nts_git commit_session includes completed tasks
            - Plans persist across session reactivation
            - Plans stored in .nts/sessions/{sessionId}/todos/

            TIP: Use 'close' action when plan is complete to clear HUD.
            """;
    }

    @Override
    public String getCategory() {
        return "planning";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'create', 'read' (or 'status'), 'update', 'add', 'close', 'list', 'reopen', 'items', 'all_items'. Required.");

        props.putObject("title").put("type", "string").put("description",
                "For 'create': plan name shown in HUD. Example: 'Refactor auth module'.");

        props.putObject("content").put("type", "string").put("description",
                "For 'create': Markdown checklist. Use '- [ ] Task' format. " +
                "For 'update' without id: replaces entire plan content.");

        props.putObject("task").put("type", "string").put("description",
                "For 'add': text of new task to append. Example: 'Implement validation'.");

        props.putObject("id").put("type", "integer").put("description",
                "For 'update': 1-based task number to modify. " +
                "Get IDs from 'read' output. Example: id=3 for third task.");

        props.putObject("status").put("type", "string").put("description",
                "For 'update' with id: new task state. " +
                "Values: 'todo' (reset), 'done' (complete), 'failed' (blocked/abandoned).");

        props.putObject("text").put("type", "string").put("description",
                "For 'update' with id: new task text (to edit/reword the task). " +
                "Example: text='Updated task description'.");

        props.putObject("comment").put("type", "string").put("description",
                "For 'update': explanation appended to task. " +
                "Example: 'blocked by missing API' or 'completed with workaround'.");

        props.putObject("fileName").put("type", "string").put("description",
                "Target specific plan file instead of active one. " +
                "For 'reopen': archived file to reactivate. " +
                "Format: 'TODO_YYYYMMDD_HHMMSS.md' or 'DONE_TODO_...md'. Omit to use active plan.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();

        return switch (action) {
            case "create" -> executeCreate(params);
            case "read", "status" -> executeRead(params);
            case "update" -> executeUpdate(params);
            case "add" -> executeAdd(params);
            case "close" -> executeClose(params);
            case "list" -> executeList();
            case "items" -> executeItems();
            case "all_items" -> executeAllItems();
            case "reopen" -> executeReopen(params);
            default -> throw new IllegalArgumentException("Unknown action: " + action +
                ". Valid actions: create, read, update, add, close, list, reopen");
        };
    }

    private JsonNode executeCreate(JsonNode params) throws IOException {
        String title = params.path("title").asText("New Plan");
        String content = params.path("content").asText("");

        Path todoDir = getTodosDir();
        if (!Files.exists(todoDir)) Files.createDirectories(todoDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "TODO_" + timestamp + ".md";
        Path todoFile = todoDir.resolve(fileName);

        String fullContent = "# TODO: " + title + "\n\n" + content;
        FileUtils.safeWrite(todoFile, fullContent, StandardCharsets.UTF_8);

        TodoManager.setSessionTodo(fileName);

        return createResponse("Plan created: " + fileName + "\nThis plan is now set as ACTIVE.");
    }

    private JsonNode executeRead(JsonNode params) throws IOException {
        Path todoDir = getTodosDir();

        // Определяем целевой файл: явно указанный ИЛИ текущий сессионный
        String fileName = params.path("fileName").asText(null);
        if (fileName == null) {
            fileName = TodoManager.getSessionTodo();
        }

        if (fileName == null) {
            return createResponse("No active TODO in current session. Use 'create' to start a new plan.");
        }

        Path targetFile = todoDir.resolve(fileName);
        if (!Files.exists(targetFile)) {
            return createResponse("TODO file not found: " + fileName);
        }

        String content = EncodingUtils.readTextFile(targetFile).content();
        return createResponse("### Current Plan: " + fileName + "\n\n" + content);
    }

    private JsonNode executeUpdate(JsonNode params) throws Exception {
        Path todoDir = getTodosDir();

        // Определяем целевой файл: явно указанный ИЛИ текущий сессионный
        String fileName = params.path("fileName").asText(null);
        if (fileName == null) {
            fileName = TodoManager.getSessionTodo();
        }

        if (fileName == null) {
            throw new IllegalStateException("No active TODO in current session. Use 'create' action first.");
        }

        Path targetFile = todoDir.resolve(fileName);
        if (!Files.exists(targetFile)) {
            throw new IllegalStateException("TODO file not found: " + fileName);
        }

        String resultMsg;
        if (params.has("id")) {
            resultMsg = updateItem(targetFile, params);
                    } else if (params.has("content")) {
                        FileUtils.safeWrite(targetFile, params.get("content").asText(), StandardCharsets.UTF_8);
                        resultMsg = "Plan overwritten: " + fileName;
        } else {
            throw new IllegalArgumentException("Must provide 'content' or 'id' + 'status' for update.");
        }

        return createResponse(resultMsg);
    }

    private String updateItem(Path file, JsonNode params) throws IOException {
        int targetId = params.get("id").asInt();
        String status = params.path("status").asText(null);
        String newText = params.path("text").asText(null);
        String comment = params.path("comment").asText(null);

        List<String> lines = new ArrayList<>(java.util.Arrays.asList(EncodingUtils.readTextFile(file).content().split("\n", -1)));
        List<String> newLines = new ArrayList<>();
        int currentId = 0;
        boolean found = false;

        for (String line : lines) {
            Matcher m = TODO_ITEM_PATTERN.matcher(line);
            if (m.find()) {
                currentId++;
                if (currentId == targetId) {
                    found = true;
                    String currentMarker = m.group(2);
                    String taskText = newText != null ? newText : m.group(3);

                    // Определяем статус: явно указанный или сохраняем текущий
                    String statusChar = currentMarker;
                    if (status != null) {
                        statusChar = switch (status.toLowerCase()) {
                            case "done" -> "x";
                            case "failed" -> "X";
                            case "todo" -> " ";
                            default -> currentMarker;
                        };
                    }

                    if (comment != null) taskText += " (" + comment + ")";
                    String leading = line.substring(0, m.start(2) - 1);
                    newLines.add(leading + "[" + statusChar + "] " + taskText);
                    continue;
                }
            }
            newLines.add(line);
        }

        if (!found) throw new IllegalArgumentException("Task with ID " + targetId + " not found in plan.");

        FileUtils.safeWrite(file, String.join("\n", newLines), StandardCharsets.UTF_8);

        // Формируем информативное сообщение
        StringBuilder msg = new StringBuilder("Task #" + targetId);
        if (status != null) msg.append(" marked '").append(status).append("'");
        if (newText != null) msg.append(" text updated");
        if (comment != null) msg.append(" with comment");
        msg.append(" in ").append(file.getFileName());
        return msg.toString();
    }

    private JsonNode executeAdd(JsonNode params) throws IOException {
        String taskText = params.path("task").asText(null);
        if (taskText == null || taskText.isBlank()) {
            throw new IllegalArgumentException("Parameter 'task' is required for 'add' action.");
        }

        Path todoDir = getTodosDir();
        String fileName = params.path("fileName").asText(null);
        if (fileName == null) {
            fileName = TodoManager.getSessionTodo();
        }

        if (fileName == null) {
            throw new IllegalStateException("No active TODO in current session. Use 'create' action first.");
        }

        Path targetFile = todoDir.resolve(fileName);
        if (!Files.exists(targetFile)) {
            throw new IllegalStateException("TODO file not found: " + fileName);
        }

        String content = EncodingUtils.readTextFile(targetFile).content();
        // Добавляем новую задачу в конец
        String newContent = content.endsWith("\n") ? content : content + "\n";
        newContent += "- [ ] " + taskText + "\n";
        FileUtils.safeWrite(targetFile, newContent, StandardCharsets.UTF_8);

        return createResponse("Task added to " + fileName + ": " + taskText);
    }

    private JsonNode executeClose(JsonNode params) throws IOException {
        Path todoDir = getTodosDir();
        String fileName = params.path("fileName").asText(null);
        if (fileName == null) {
            fileName = TodoManager.getSessionTodo();
        }

        if (fileName == null) {
            return createResponse("No active TODO to close.");
        }

        Path sourceFile = todoDir.resolve(fileName);
        if (!Files.exists(sourceFile)) {
            return createResponse("TODO file not found: " + fileName);
        }

        // Проверяем, все ли задачи выполнены
        TodoManager.HudInfo info = TodoManager.getHudInfo();
        int pending = info.total() - info.done() - info.failed();

        // Переименовываем файл с префиксом DONE_
        String newFileName = "DONE_" + fileName;
        Path targetFile = todoDir.resolve(newFileName);
        Files.move(sourceFile, targetFile);

        // Сбрасываем активный TODO
        TodoManager.setSessionTodo(null);

        StringBuilder msg = new StringBuilder();
        msg.append("Plan archived: ").append(fileName).append(" -> ").append(newFileName).append("\n");
        msg.append("Status: ✓").append(info.done()).append(" done");
        if (info.failed() > 0) msg.append(", ✗").append(info.failed()).append(" failed");
        if (pending > 0) msg.append(", ○").append(pending).append(" pending (incomplete!)");
        msg.append("\n\nPlan removed from HUD. Use 'list' to see all plans.");

        if (pending > 0) {
            msg.append("\n\n[WARNING: Plan closed with ").append(pending).append(" pending tasks!]");
        }

        return createResponse(msg.toString());
    }

    private JsonNode executeList() throws IOException {
        Path todoDir = getTodosDir();
        if (!Files.exists(todoDir)) {
            return createResponse("No plans found. Use 'create' to start a new plan.");
        }

        List<Path> files;
        try (var stream = Files.list(todoDir)) {
            files = stream
                .filter(p -> p.toString().endsWith(".md"))
                .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                .toList();
        }

        if (files.isEmpty()) {
            return createResponse("No plans found. Use 'create' to start a new plan.");
        }

        String activeTodo = TodoManager.getSessionTodo();
        StringBuilder sb = new StringBuilder("### All Plans\n\n");

        for (Path file : files) {
            String name = file.getFileName().toString();
            boolean isActive = name.equals(activeTodo);
            boolean isArchived = name.startsWith("DONE_");

            // Читаем заголовок плана
            String title = "Untitled";
            try {
                String content = EncodingUtils.readTextFile(file).content();
                for (String line : content.split("\n")) {
                    if (line.startsWith("# ")) {
                        title = line.substring(2).trim();
                        if (title.startsWith("TODO: ")) title = title.substring(6);
                        break;
                    }
                }
            } catch (IOException ignored) {}

            sb.append(isActive ? "→ " : "  ");
            sb.append(isArchived ? "[DONE] " : "[ACTIVE] ");
            sb.append(name).append(" - ").append(title);
            if (isActive) sb.append(" ← CURRENT");
            sb.append("\n");
        }

        sb.append("\nUse 'read' with fileName to view a specific plan.");
        sb.append("\nUse 'reopen' with fileName to reactivate an archived plan.");

        return createResponse(sb.toString());
    }

    /**
     * Returns active TODO as structured JSON for integrations.
     * Format: { todoId, title, items: [{ id, text, done, failed, completed }], fileName }
     */
    private JsonNode executeItems() throws IOException {
        String fileName = TodoManager.getSessionTodo();
        if (fileName == null) {
            return createResponse("{\"items\":[]}");
        }

        Path todoFile = getTodosDir().resolve(fileName);
        if (!Files.exists(todoFile)) {
            return createResponse("{\"items\":[]}");
        }

        String content = EncodingUtils.readTextFile(todoFile).content();
        Map<String, Object> result = parseTodoFile(fileName, content);
        return createResponse(mapper.writeValueAsString(result));
    }

    /**
     * Returns ALL active (non-archived) TODO files as structured JSON.
     * Format: { todos: [{ todoId, title, items: [...], fileName }] }
     */
    private JsonNode executeAllItems() throws IOException {
        Path todoDir = getTodosDir();
        if (!Files.exists(todoDir)) {
            return createResponse("{\"todos\":[]}");
        }

        List<Path> files;
        try (var stream = Files.list(todoDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("DONE_"))
                    .sorted()
                    .toList();
        }

        List<Map<String, Object>> todos = new ArrayList<>();
        for (Path file : files) {
            String content = EncodingUtils.readTextFile(file).content();
            Map<String, Object> todo = parseTodoFile(file.getFileName().toString(), content);
            if (!((List<?>) todo.get("items")).isEmpty()) {
                todos.add(todo);
            }
        }

        return createResponse(mapper.writeValueAsString(Map.of("todos", todos)));
    }

    /**
     * Parses a TODO markdown file into structured data.
     */
    private Map<String, Object> parseTodoFile(String fileName, String content) {
        String[] lines = content.split("\n", -1);

        String title = "Untitled";
        for (String line : lines) {
            if (line.startsWith("# ")) {
                title = line.substring(2).trim();
                if (title.startsWith("TODO: ")) title = title.substring(6);
                break;
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int id = 0;
        for (String line : lines) {
            Matcher m = TODO_ITEM_PATTERN.matcher(line);
            if (m.find()) {
                id++;
                String marker = m.group(2);
                boolean done = "x".equals(marker);
                boolean failed = "X".equals(marker);
                String text = m.group(3).trim();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                item.put("text", text);
                item.put("done", done);
                item.put("failed", failed);
                item.put("completed", done || failed);
                items.add(item);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todoId", fileName);
        result.put("title", title);
        result.put("items", items);
        result.put("fileName", fileName);
        return result;
    }

    private JsonNode executeReopen(JsonNode params) throws IOException {
        String fileName = params.path("fileName").asText(null);
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Parameter 'fileName' is required for 'reopen' action.");
        }

        Path todoDir = getTodosDir();
        Path sourceFile = todoDir.resolve(fileName);

        if (!Files.exists(sourceFile)) {
            throw new IllegalStateException("TODO file not found: " + fileName);
        }

        String newFileName = fileName;
        // Если файл архивирован (DONE_), убираем префикс
        if (fileName.startsWith("DONE_")) {
            newFileName = fileName.substring(5); // Remove "DONE_"
            Path targetFile = todoDir.resolve(newFileName);
            Files.move(sourceFile, targetFile);
        }

        // Устанавливаем как активный TODO
        TodoManager.setSessionTodo(newFileName);

        return createResponse("Plan reactivated: " + newFileName + "\nThis plan is now ACTIVE and visible in HUD.");
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
