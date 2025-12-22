// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для просмотра журнала транзакций.
 */
public class TransactionJournalTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "transaction_journal";
    }

    @Override
    public String getDescription() {
        return "Returns a list of completed and undone transactions in the current session.";
    }

    @Override
    public JsonNode getInputSchema() {
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String journal = TransactionManager.getJournal();
        
        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", journal);
        return result;
    }
}
