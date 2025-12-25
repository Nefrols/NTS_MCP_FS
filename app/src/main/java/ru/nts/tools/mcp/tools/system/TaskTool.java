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
package ru.nts.tools.mcp.tools.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.ProcessExecutor;

/**
 * Инструмент для управления фоновыми задачами.
 * Поддерживает получение логов и завершение процессов по их taskId.
 */
public class TaskTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_task"; }

    @Override
    public String getDescription() {
        return """
            Background task manager - monitor and control async processes.

            ACTIONS:
            - log  - Get current output from running task
            - kill - Terminate a running task

            WHEN TO USE:
            - Gradle build taking too long? Check progress with 'log'
            - Task stuck or wrong command? Stop it with 'kill'
            - Git command running async? Monitor with 'log'

            WORKFLOW:
            1. Start long task (e.g., nts_gradle_task with large timeout)
            2. Task returns taskId while running
            3. Poll with log(taskId) to check progress
            4. Kill if needed, or wait for completion

            NOTE: taskId is returned by tools that run async (Gradle, Git commands).
            """;
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'log' (get output), 'kill' (terminate). Required.");

        props.putObject("taskId").put("type", "string").put("description",
                "Task identifier from async tool response. Format: 'task-XXXX'. " +
                "Example: 'task-1234' returned by nts_gradle_task or nts_git.");

        schema.putArray("required").add("action").add("taskId");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();
        String taskId = params.get("taskId").asText();

        return switch (action) {
            case "kill" -> executeKill(taskId);
            case "log" -> executeLog(taskId);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeKill(String taskId) {
        boolean killed = ProcessExecutor.killTask(taskId);
        return createResponse(killed ? "Task [" + taskId + "] killed." : "Task [" + taskId + "] not found or already finished.");
    }

    private JsonNode executeLog(String taskId) {
        String log = ProcessExecutor.getTaskLog(taskId);
        return createResponse("Log for task [" + taskId + "]:\n\n" + log);
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}