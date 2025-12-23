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
 * Инструмент для чтения текущего плана работ.
 */
public class TodoStatusTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_todo_status"; }

    @Override
    public String getDescription() { return "Read the current planning document and progress."; }

    @Override
    public JsonNode getInputSchema() {
        return mapper.createObjectNode().put("type", "object");
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) return createResponse("No TODOs found.");

        Path targetFile;
        try (Stream<Path> s = Files.list(todoDir)) {
            targetFile = s.filter(p -> p.getFileName().toString().startsWith("TODO_"))
                    .max(Comparator.comparing(Path::getFileName))
                    .orElse(null);
        }

        if (targetFile == null) return createResponse("No active TODO found.");

        String content = Files.readString(targetFile);
        TransactionManager.setActiveTodo(targetFile.getFileName().toString());

        return createResponse("### Current Plan: " + targetFile.getFileName() + "\n\n" + content);
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}