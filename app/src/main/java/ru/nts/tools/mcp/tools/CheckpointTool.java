// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

/**
 * Инструмент для создания и управления контрольными точками восстановления.
 */
public class CheckpointTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_checkpoint"; }

    @Override
    public String getDescription() { return "Manage restore points. Create named snapshots or rollback to them."; }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("name").put("type", "string").put("description", "Checkpoint name.");
        props.putObject("rollback").put("type", "boolean").put("description", "If true, rollback to the named checkpoint. Default false.");
        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String name = params.get("name").asText();
        boolean rollback = params.path("rollback").asBoolean(false);

        ObjectNode res = mapper.createObjectNode();
        String msg;

        if (rollback) {
            msg = TransactionManager.rollbackToCheckpoint(name);
        } else {
            TransactionManager.createCheckpoint(name);
            msg = "Checkpoint created: " + name;
        }

        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}