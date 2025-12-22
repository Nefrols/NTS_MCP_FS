// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для повтора ранее отмененной транзакции.
 * После выполнения возвращает актуальный журнал истории.
 */
public class RedoTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "redo";
    }

    @Override
    public String getDescription() {
        return "Redo last undone transaction. Returns updated journal.";
    }

    @Override
    public JsonNode getInputSchema() {
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String status = TransactionManager.redo();
        String journal = TransactionManager.getJournal();
        
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text")
                .put("text", status + "\n\n" + journal);
        return result;
    }
}