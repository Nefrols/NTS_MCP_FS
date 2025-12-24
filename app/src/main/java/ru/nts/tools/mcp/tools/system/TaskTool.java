// Aristo 24.12.2025
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
        return "Process manager for background tasks. Use 'log' to monitor long-running tasks or 'kill' to terminate them. Essential for asynchronous command execution via Gradle or Git.";
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

        props.putObject("action").put("type", "string").put("description", "Operation: 'kill' (stop process), 'log' (fetch output history).");
        props.putObject("taskId").put("type", "string").put("description", "Unique identifier of the background task.");

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