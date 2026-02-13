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
package ru.nts.tools.mcp.tools.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;
import ru.nts.tools.mcp.core.treesitter.SymbolIndex;
import ru.nts.tools.mcp.McpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Инструмент инициализации задачи.
 *
 * ВАЖНО: Это ЕДИНСТВЕННЫЙ инструмент, который может работать без taskId.
 * Все остальные инструменты требуют валидный taskId, полученный от этого инструмента.
 *
 * При вызове создает новую задачу с уникальным UUID и возвращает его.
 * LLM должна сохранить этот UUID и передавать его во всех последующих запросах.
 */
public class InitTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_init";
    }

    @Override
    public String getDescription() {
        return """
            Initialize or reactivate an MCP task.

            CRITICAL: Call this FIRST before using any other nts_* tools!

            This tool creates a new isolated task with:
            - Unique task UUID (required for all other tools)
            - Task-scoped undo/redo history
            - Task-scoped file access tokens
            - Task-scoped TODO plans

            MODES:
            1. NEW TASK: Call without parameters -> creates new task with UUID
            2. REACTIVATE: Call with taskId parameter -> restores existing task

            TASK REACTIVATION:
            If your task was interrupted (server restart, connection drop), you can
            reactivate it by passing the old taskId. This preserves:
            - Task directory with todos and snapshots
            - File history and journal on disk
            Note: In-memory state (tokens, undo stack) starts fresh after reactivation.

            WORKFLOW:
            1. Call nts_init() -> receive task UUID
            2. Store the UUID from response
            3. Pass taskId in arguments for ALL subsequent tool calls
            4. If task becomes invalid -> call nts_init(taskId="<old-uuid>") to reactivate

            PASSING TASK ID:
            All other tools have 'taskId' as a required parameter.
            Simply include it in the tool arguments:
            { "taskId": "<uuid>", "path": "/some/file", ... }

            RETURNS:
            - taskId: UUID to pass in all subsequent tool calls
            - projectRoot: Working directory path
            - message: Welcome message with task info
            - reactivated: true if this was a task reactivation

            NOTE: Task UUID is also shown in HUD output after every tool call.
            """;
    }

    @Override
    public String getCategory() {
        return "task";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("taskId").put("type", "string").put("description",
                "Optional. Pass an existing task UUID to REACTIVATE a previous task. " +
                "Use this when resuming work after server restart or connection drop. " +
                "If omitted, a new task is created.");

        props.putObject("workingDirectory").put("type", "string").put("description",
                "Optional. Working directory path for this task (e.g., 'D:/projects/my-app'). " +
                "Stored in journal.json for task identification and recovery. " +
                "If omitted, uses the current project root (PathSanitizer.getRoot()).");

        schema.putArray("required"); // Empty array - no required params
        return schema;
    }

    /**
     * Помечает этот инструмент как не требующий задачи.
     * McpServer проверяет это перед валидацией taskId.
     */
    @Override
    public boolean requiresTask() {
        return false;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Проверяем, запрошена ли реактивация существующей задачи
        String requestedTaskId = params.path("taskId").asText(null);
        boolean isReactivation = false;
        String taskId;
        TaskContext ctx;

        if (requestedTaskId != null && !requestedTaskId.isBlank()) {
            // Режим реактивации: пытаемся восстановить существующую задачу
            if (TaskContext.isActiveInMemory(requestedTaskId)) {
                // Задача уже активна в памяти - просто используем её
                taskId = requestedTaskId;
                ctx = TaskContext.getOrCreate(taskId);
                isReactivation = true;
            } else if (TaskContext.existsOnDisk(requestedTaskId)) {
                // Задача существует на диске - реактивируем
                ctx = TaskContext.reactivateTask(requestedTaskId);
                taskId = requestedTaskId;
                isReactivation = true;
            } else {
                // Task not found — create new task with the specified ID
                ctx = TaskContext.getOrCreate(requestedTaskId);
                taskId = requestedTaskId;
            }
        } else {
            // Режим создания новой задачи
            taskId = UUID.randomUUID().toString();
            ctx = TaskContext.getOrCreate(taskId);
        }

        // Устанавливаем рабочую директорию задачи
        String workingDir = params.path("workingDirectory").asText(null);
        if (workingDir != null && !workingDir.isBlank()) {
            ctx.setWorkingDirectory(Path.of(workingDir));
        } else if (!isReactivation) {
            // Для новых задач — используем текущий project root
            ctx.setWorkingDirectory(PathSanitizer.getRoot());
        }
        // Для реактивации без workingDirectory — оставляем восстановленное из journal.json

        // Регистрируем задачу как валидную
        McpServer.registerValidTask(taskId);
        TaskContext.setCurrent(ctx);

        // Создаем директории задачи
        Path taskDir = ctx.getTaskDir();
        Files.createDirectories(ctx.getTodosDir());

        // Сохраняем журнал задачи (journal.json)
        ctx.saveJournal();

        Path projectRoot = PathSanitizer.getRoot();
        List<Path> allRoots = PathSanitizer.getRoots();

        // Запускаем асинхронную индексацию символов для быстрой навигации
        SymbolIndex symbolIndex = SymbolIndex.getInstance();
        if (!symbolIndex.isIndexed() && !symbolIndex.isIndexing()) {
            symbolIndex.indexProjectAsync(projectRoot);
        }

        // Формируем информацию о roots
        String rootsInfo;
        if (allRoots.size() == 1) {
            rootsInfo = "Project root: " + projectRoot;
        } else {
            rootsInfo = "Project roots (" + allRoots.size() + "):\n" +
                allRoots.stream()
                    .map(r -> "  - " + r.toString())
                    .collect(Collectors.joining("\n"));
        }

        // Формируем ответ
        ObjectNode result = mapper.createObjectNode();
        var content = result.putArray("content");

        // Основное сообщение
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");

        String statusMessage = isReactivation
            ? "Task REACTIVATED successfully.\n\n" +
              "[NOTE: Undo/redo history RESTORED from journal.\n" +
              " File access tokens start fresh (re-read files before editing).\n" +
              " TODOs and snapshots from task directory are preserved.]\n\n" +
              "[TIP: Review restored task state before continuing:]\n" +
              "  → nts_task(action=\"journal\") - view transaction history & undo stack\n" +
              "  → nts_todo(action=\"read\")    - view current TODO plan progress\n"
            : "Task initialized successfully.\n";

        textNode.put("text", String.format("""
            %s
            TASK ID: %s
            %s
            Task directory: %s

            IMPORTANT: Pass this taskId in ALL subsequent tool calls.
            Example: { "taskId": "%s", "path": "/file.txt", ... }

            The task UUID is also shown in the HUD output after every tool call.
            """, statusMessage, taskId, rootsInfo, taskDir, taskId));

        // Структурированные данные для машинного чтения
        ObjectNode dataNode = content.addObject();
        dataNode.put("type", "text");

        StringBuilder yamlData = new StringBuilder();
        yamlData.append("---\ntaskId: ").append(taskId).append("\n");
        yamlData.append("reactivated: ").append(isReactivation).append("\n");
        yamlData.append("primaryRoot: ").append(projectRoot).append("\n");
        yamlData.append("workingDirectory: ").append(ctx.getWorkingDirectory()).append("\n");
        yamlData.append("taskDirectory: ").append(taskDir).append("\n");
        if (allRoots.size() > 1) {
            yamlData.append("roots:\n");
            for (Path root : allRoots) {
                yamlData.append("  - ").append(root).append("\n");
            }
        }
        dataNode.put("text", yamlData.toString());

        return result;
    }
}
