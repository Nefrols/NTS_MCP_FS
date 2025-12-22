// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.ProcessExecutor;

/**
 * Инструмент для получения текущего лога выполняющейся фоновой задачи.
 * Позволяет LLM отслеживать прогресс длительных операций (например, сборки проекта),
 * которые не успели завершиться в рамках основного таймаута.
 */
public class TaskLogTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "task_log";
    }

    @Override
    public String getDescription() {
        return "Requests the current output log of a background task by its taskId (hash).";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("taskId").put("type", "string").put("description", "The unique hash of the task (returned in previous command output).");

        schema.putArray("required").add("taskId");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String taskId = params.get("taskId").asText();

        // Получение актуального среза лога из реестра ProcessExecutor
        String log = ProcessExecutor.getTaskLog(taskId);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");

        // Возвращаем лог с обрамлением для удобства чтения
        content.put("text", "Current log for task [" + taskId + "]:\n\n" + log);
        return response;
    }
}