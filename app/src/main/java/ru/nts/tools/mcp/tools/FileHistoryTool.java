// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Path;
import java.util.List;

/**
 * Инструмент для просмотра истории изменений файла в рамках текущей сессии.
 * Извлекает данные из менеджера транзакций.
 */
public class FileHistoryTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_file_history";
    }

    @Override
    public String getDescription() {
        return "Show session history of changes for a specific file.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Path to the file.");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, true);

        List<String> history = TransactionManager.getFileHistory(path);

        ObjectNode res = mapper.createObjectNode();
        var content = res.putArray("content").addObject();
        content.put("type", "text");
        
        if (history.isEmpty()) {
            content.put("text", "No session history found for: " + pathStr);
        } else {
            StringBuilder sb = new StringBuilder("Session history for " + pathStr + ":\n");
            for (String entry : history) {
                sb.append("- ").append(entry).append("\n");
            }
            content.put("text", sb.toString().trim());
        }

        return res;
    }
}
