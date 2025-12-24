// Aristo 24.12.2025
package ru.nts.tools.mcp.tools.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для управления сессией и транзакциями.
 * Поддерживает создание контрольных точек, откат к ним, отмену (undo) и повтор (redo) операций, 
 * а также просмотр журнала транзакций.
 */
public class SessionTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_session"; }

    @Override
    public String getDescription() {
        return "Recovery and Session hub. Manage restore points, undo changes, and view the activity journal. Best practice: Create a 'checkpoint' before starting a complex task. Note: Only tools from this MCP are tracked.";
    }

    @Override
    public String getCategory() {
        return "session";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description", "Strategy: 'checkpoint' (save state), 'rollback' (revert to point), 'undo' (step back), 'redo' (step forward), 'journal' (activity log).");
        props.putObject("name").put("type", "string").put("description", "Label for 'checkpoint' or target for 'rollback'.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();

        return switch (action) {
            case "checkpoint" -> executeCheckpoint(params);
            case "rollback" -> executeRollback(params);
            case "undo" -> executeUndo();
            case "redo" -> executeRedo();
            case "journal" -> executeJournal();
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeCheckpoint(JsonNode params) {
        String name = params.get("name").asText();
        TransactionManager.createCheckpoint(name);
        return createResponse("Checkpoint created: " + name);
    }

    private JsonNode executeRollback(JsonNode params) throws Exception {
        String name = params.get("name").asText();
        String msg = TransactionManager.rollbackToCheckpoint(name);
        return createResponse(msg);
    }

    private JsonNode executeUndo() throws Exception {
        String status = TransactionManager.undo();
        String journal = TransactionManager.getJournal();
        return createResponse(status + "\n\n" + journal);
    }

    private JsonNode executeRedo() throws Exception {
        String status = TransactionManager.redo();
        String journal = TransactionManager.getJournal();
        return createResponse(status + "\n\n" + journal);
    }

    private JsonNode executeJournal() {
        return createResponse(TransactionManager.getJournal());
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
