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
 * Инструмент для объединения вызовов различных инструментов в одну атомарную транзакцию.
 * Позволяет выполнять сложные цепочки действий (например, Rename + Edit).
 */
public class BatchToolsTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpRouter router;

    public BatchToolsTool(McpRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "batch_tools";
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

        TransactionManager.startTransaction("Batch Tools (" + actions.size() + " actions)");
        try {
            ArrayNode results = mapper.createArrayNode();
            for (JsonNode action : actions) {
                String toolName = action.path("tool").asText();
                JsonNode toolParams = action.path("params");
                
                // Вызываем инструмент через роутер. Благодаря вложенности в TransactionManager,
                // внутренние commit() инструментов ничего не сделают до завершения этого метода.
                JsonNode result = router.callTool(toolName, toolParams);
                results.add(result);
            }
            TransactionManager.commit();

            ObjectNode response = mapper.createObjectNode();
            var content = response.putArray("content").addObject();
            content.put("type", "text");
            content.put("text", "Batch execution successful. All " + actions.size() + " actions applied atomically.");
            
            return response;
        } catch (Exception e) {
            // Любая ошибка в цепочке приводит к полному откату всех инструментов в батче
            TransactionManager.rollback();
            throw e;
        }
    }
}
