// Aristo 22.12.2025
package ru.nts.tools.mcp.tools.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент-оркестратор для пакетного выполнения различных инструментов MCP в рамках одной транзакции.
 * Позволяет объединять логически связанные действия (например, переименование класса и последующая правка его контента)
 * в единый атомарный блок. Если любой инструмент в цепочке вернет ошибку, все предыдущие действия будут откатаны.
 */
public class BatchToolsTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ссылка на роутер сервера для выполнения вложенных вызовов инструментов.
     */
    private final McpRouter router;

    /**
     * Создает новый инструмент пакетного выполнения.
     *
     * @param router Роутер, содержащий реестр всех доступных инструментов.
     */
    public BatchToolsTool(McpRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "nts_batch_tools";
    }

    @Override
    public String getDescription() {
        return "Atomic orchestrator. Executes a sequence of various actions as a single indivisible transaction. Note: Use of standard tools or other MCPs is not tracked by the session manager and cannot be restored via undo/redo/checkpoint.";
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

        var actions = props.putObject("actions");
        actions.put("type", "array");
        actions.put("description", "Ordered list of actions. Each action requires a 'tool' name and its 'params' object. Ideal for complex workflows like rename + edit.");
        var item = actions.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("tool").put("type", "string").put("description", "Target MCP tool name.");
        itemProps.putObject("params").put("type", "object").put("description", "Arguments for the tool.");

        props.putObject("instruction").put("type", "string").put("description", "Semantic label for the entire batch to record in the transaction journal.");

        schema.putArray("required").add("actions");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        JsonNode actions = params.get("actions");
        if (actions == null || !actions.isArray()) {
            throw new IllegalArgumentException("Parameter 'actions' must be an array.");
        }

        // Запуск глобальной транзакции для всей цепочки действий
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        TransactionManager.startTransaction("Batch Tools (" + actions.size() + " actions)", instruction);
        try {
            ArrayNode results = mapper.createArrayNode();
            int index = 0;
            for (JsonNode action : actions) {
                index++;
                String toolName = action.path("tool").asText();
                JsonNode toolParams = action.path("params");

                // Вызываем целевой инструмент через роутер.
                // Благодаря поддержке вложенности в TransactionManager, вызовы commit() 
                // внутри этих инструментов не приведут к фиксации на диск до завершения батча.
                try {
                    JsonNode result = router.callTool(toolName, toolParams);
                    // Проверяем, не вернул ли инструмент ошибку через executeWithFeedback
                    if (result.has("isError") && result.get("isError").asBoolean()) {
                        String errorMsg = result.path("content").get(0).path("text").asText("Unknown error");
                        throw new IllegalStateException(errorMsg);
                    }
                    results.add(result);
                } catch (Exception e) {
                    throw new IllegalStateException(String.format("Batch execution failed at action #%d ('%s'). Error: %s. All previous actions in this batch have been rolled back.", index, toolName, e.getMessage()), e);
                }
            }
            // Успешное завершение всей цепочки — фиксируем изменения
            TransactionManager.commit();

            ObjectNode response = mapper.createObjectNode();
            var content = response.putArray("content").addObject();
            content.put("type", "text");
            content.put("text", "Batch execution successful. All " + actions.size() + " actions applied atomically.");

            return response;
        } catch (Exception e) {
            // Любая ошибка (включая ошибки валидации или безопасности в любом инструменте)
            // приводит к полному откату всех изменений, сделанных в рамках этого батча.
            TransactionManager.rollback();
            throw e;
        }
    }
}
