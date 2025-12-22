// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Инструмент для получения списка файлов в директории.
 */
public class ListDirectoryTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "list_directory";
    }

    @Override
    public String getDescription() {
        return "Возвращает список файлов и папок в указанной директории.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к директории");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        Path path = Path.of(params.get("path").asText());
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Директория не найдена: " + path);
        }

        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path entry : stream) {
                String type = Files.isDirectory(entry) ? "[DIR] " : "[FILE] ";
                entries.add(type + entry.getFileName().toString() + " (" + entry.toAbsolutePath() + ")");
            }
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", String.join("\n", entries));
        
        return result;
    }
}
