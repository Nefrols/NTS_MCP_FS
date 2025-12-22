// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для отмены последней транзакции.
 * После выполнения возвращает актуальный журнал истории.
 */
public class UndoTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "undo";
    }

    @Override
    public String getDescription() {
        return "Undoes the last file change. Restores the previous state and returns the updated transaction journal.";
    }

    @Override
    public JsonNode getInputSchema() {
        return mapper.createObjectNode().put("type", "object"); // Параметры не требуются
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String status = TransactionManager.undo();
        String journal = TransactionManager.getJournal();
        
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text")
                .put("text", status + "\n\n" + journal);
        return result;
    }
}