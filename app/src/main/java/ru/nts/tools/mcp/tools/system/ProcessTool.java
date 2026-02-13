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
 * Инструмент для управления фоновыми процессами.
 * Поддерживает получение логов и завершение процессов по их processId.
 */
public class ProcessTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_process"; }

    @Override
    public String getDescription() {
        return """
            Background process manager - monitor and control async processes.

            ACTIONS:
            - log  - Get current output from running process
            - kill - Terminate a running process

            WHEN TO USE:
            - Gradle build taking too long? Check progress with 'log'
            - Process stuck or wrong command? Stop it with 'kill'
            - Git command running async? Monitor with 'log'

            WORKFLOW:
            1. Start long process (e.g., nts_gradle_task with large timeout)
            2. Tool returns processId while running
            3. Poll with log(processId) to check progress
            4. Kill if needed, or wait for completion

            NOTE: processId is returned by tools that run async (Gradle, Git commands).
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

        props.putObject("processId").put("type", "string").put("description",
                "Process identifier from async tool response. Format: 'task-XXXX'. " +
                "Example: 'task-1234' returned by nts_gradle_task or nts_git.");

        schema.putArray("required").add("action").add("processId");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();
        String processId = params.get("processId").asText();

        return switch (action) {
            case "kill" -> executeKill(processId);
            case "log" -> executeLog(processId);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeKill(String processId) {
        boolean killed = ProcessExecutor.killTask(processId);
        return createResponse(killed ? "Process [" + processId + "] killed." : "Process [" + processId + "] not found or already finished.");
    }

    private JsonNode executeLog(String processId) {
        String log = ProcessExecutor.getTaskLog(processId);
        return createResponse("Log for process [" + processId + "]:\n\n" + log);
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
