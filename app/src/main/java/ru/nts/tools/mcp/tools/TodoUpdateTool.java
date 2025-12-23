// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Инструмент для обновления текущего плана работ.
 */
public class TodoUpdateTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_todo_update"; }

    @Override
    public String getDescription() { return "Update the current planning document."; }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("fileName").put("type", "string").put("description", "Optional: Specific TODO file name. Defaults to latest.");
        props.putObject("content").put("type", "string").put("description", "New content for the plan.");
        schema.putArray("required").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String content = params.get("content").asText();
        String fileName = params.path("fileName").asText(null);

        Path todoDir = PathSanitizer.getRoot().resolve(".mcp/todos");
        if (!Files.exists(todoDir)) throw new IllegalStateException("No TODOs found. Use nts_todo_create first.");

        Path targetFile;
        if (fileName != null) {
            targetFile = todoDir.resolve(fileName);
        } else {
            // Ищем последний созданный файл
            try (Stream<Path> s = Files.list(todoDir)) {
                targetFile = s.filter(p -> p.getFileName().toString().startsWith("TODO_"))
                        .max(Comparator.comparing(Path::getFileName))
                        .orElseThrow(() -> new IllegalStateException("No active TODO found."));
            }
        }

        Files.writeString(targetFile, content);
        TransactionManager.setActiveTodo(targetFile.getFileName().toString());

        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text")
           .put("text", "Plan updated: " + targetFile.getFileName());
        return res;
    }
}