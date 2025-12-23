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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Инструмент для создания нового плана работ (TODO).
 */
public class TodoCreateTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_todo_create"; }

    @Override
    public String getDescription() { return "Create a new planning document (TODO)."; }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("title").put("type", "string").put("description", "Plan title.");
        props.putObject("content").put("type", "string").put("description", "Markdown content of the plan.");
        schema.putArray("required").add("title").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String title = params.get("title").asText();
        String content = params.get("content").asText();

        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) Files.createDirectories(todoDir);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "TODO_" + timestamp + ".md";
        Path todoFile = todoDir.resolve(fileName);

        String fullContent = "# TODO: " + title + "\n\n" + content;
        Files.writeString(todoFile, fullContent);

        TransactionManager.setActiveTodo(fileName);

        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text")
           .put("text", "Plan created: " + fileName + "\nThis plan is now set as ACTIVE.");
        return res;
    }
}