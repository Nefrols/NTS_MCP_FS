// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.ProcessExecutor;

/**
 * Инструмент для принудительного завершения фоновой задачи.
 * Используется для остановки зависших или ошибочно запущенных длительных процессов
 * (например, бесконечных тестов или цикличной сборки).
 */
public class TaskKillTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "task_kill";
    }

    @Override
    public String getDescription() {
        return "Kills a running background task by its taskId (hash).";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("taskId").put("type", "string").put("description", "The unique hash of the task to terminate.");

        schema.putArray("required").add("taskId");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String taskId = params.get("taskId").asText();

        // Попытка принудительного завершения процесса по его идентификатору
        boolean killed = ProcessExecutor.killTask(taskId);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");

        // Информируем модель об успехе или о том, что задача уже не активна
        content.put("text", killed ? "Task [" + taskId + "] was successfully killed." : "Task [" + taskId + "] not found or already finished.");
        return response;
    }
}