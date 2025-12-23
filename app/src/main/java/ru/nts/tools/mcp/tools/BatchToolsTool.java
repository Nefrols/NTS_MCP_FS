// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

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
        return "Atomic orchestrator. Executes multiple DIFFERENT tools in a single transaction (e.g. rename + edit).";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        // Определение массива действий для выполнения
        var actions = props.putObject("actions");
        actions.put("type", "array");
        var item = actions.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");

        itemProps.putObject("tool").put("type", "string").put("description", "Tool name.");

        itemProps.putObject("params").put("type", "object").put("description", "Tool parameters.");

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
        TransactionManager.startTransaction("Batch Tools (" + actions.size() + " actions)");
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
                    results.add(result);
                } catch (Exception e) {
                    throw new IllegalStateException(String.format("Batch failed at action #%d ('%s'): %s", index, toolName, e.getMessage()), e);
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