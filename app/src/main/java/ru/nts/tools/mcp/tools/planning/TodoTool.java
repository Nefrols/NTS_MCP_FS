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
import java.util.List;
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
            - create - Start new plan with title and checklist
            - status - View current plan and task states
            - update - Modify plan content or mark task done/failed

            TASK FORMAT (Markdown checklist):
            - [ ] Pending task
            - [x] Completed task
            - [X] Failed task (uppercase X)

            WORKFLOW:
            1. create(title='Implement feature', content='- [ ] Step 1\\n- [ ] Step 2')
            2. status() -> view current plan
            3. update(id=1, status='done') -> mark first task complete
            4. update(id=2, status='failed', comment='blocked by X')

            INTEGRATION:
            - HUD displays active plan progress
            - nts_git commit_session includes completed tasks
            - Plans stored in .nts/sessions/{sessionId}/todos/

            TIP: Use numbered IDs from status output for updates.
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
                "Operation: 'create', 'status', 'update'. Required.");

        props.putObject("title").put("type", "string").put("description",
                "For 'create': plan name shown in HUD. Example: 'Refactor auth module'.");

        props.putObject("content").put("type", "string").put("description",
                "For 'create': Markdown checklist. Use '- [ ] Task' format. " +
                "For 'update' without id: replaces entire plan content.");

        props.putObject("id").put("type", "integer").put("description",
                "For 'update': 1-based task number to modify. " +
                "Get IDs from 'status' output. Example: id=3 for third task.");

        props.putObject("status").put("type", "string").put("description",
                "For 'update' with id: new task state. " +
                "Values: 'todo' (reset), 'done' (complete), 'failed' (blocked/abandoned).");

        props.putObject("comment").put("type", "string").put("description",
                "For 'update': explanation appended to task. " +
                "Example: 'blocked by missing API' or 'completed with workaround'.");

        props.putObject("fileName").put("type", "string").put("description",
                "Target specific plan file instead of active one. " +
                "Format: 'TODO_YYYYMMDD_HHMMSS.md'. Omit to use most recent plan.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();

        return switch (action) {
            case "create" -> executeCreate(params);
            case "status" -> executeStatus(params);
            case "update" -> executeUpdate(params);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
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

    private JsonNode executeStatus(JsonNode params) throws IOException {
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
        String status = params.path("status").asText("todo");
        String comment = params.path("comment").asText(null);

        List<String> lines = new ArrayList<>(java.util.Arrays.asList(EncodingUtils.readTextFile(file).content().split("\n", -1)));
        List<String> newLines = new ArrayList<>();
        int currentId = 0;
        boolean found = false;

        String statusChar = switch (status.toLowerCase()) {
            case "done" -> "x";
            case "failed" -> "X";
            default -> " ";
        };

        for (String line : lines) {
            Matcher m = TODO_ITEM_PATTERN.matcher(line);
            if (m.find()) {
                currentId++;
                if (currentId == targetId) {
                    found = true;
                    String taskText = m.group(3);
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
        return String.format("Task #%d updated to '%s' in %s", targetId, status, file.getFileName());
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
