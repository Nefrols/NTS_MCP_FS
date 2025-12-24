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
        return """
            Session recovery and undo/redo system.

            ACTIONS:
            • checkpoint - Save named restore point BEFORE risky changes
            • rollback   - Revert ALL changes back to checkpoint
            • undo       - Step back one operation (like Ctrl+Z)
            • redo       - Step forward one operation (like Ctrl+Y)
            • journal    - View full session activity log

            BEST PRACTICES:
            1. Create checkpoint before complex refactoring
            2. Use undo for quick fixes (wrong edit)
            3. Use rollback to abandon failed approach
            4. Check journal to understand what changed

            LIMITATIONS:
            • Only tracks changes made through THIS MCP
            • External tools (IDE, other MCPs) not tracked
            • Tokens invalidated after undo/rollback - re-read files!

            EXAMPLE: checkpoint('before-refactor') → make changes → rollback('before-refactor')
            """;
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

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'checkpoint', 'rollback', 'undo', 'redo', 'journal'. Required.");

        props.putObject("name").put("type", "string").put("description",
                "For 'checkpoint': descriptive name to identify restore point (e.g., 'before-api-change'). " +
                "For 'rollback': exact name of checkpoint to restore. " +
                "Not needed for undo/redo/journal.");

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
