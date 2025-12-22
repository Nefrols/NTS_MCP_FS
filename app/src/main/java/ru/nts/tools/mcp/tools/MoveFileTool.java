// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Инструмент для перемещения файлов и директорий.
 * После перемещения возвращает листинг целевой директории.
 */
public class MoveFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "move_file";
    }

    @Override
    public String getDescription() {
        return "Перемещает файл или директорию в новое местоположение. Возвращает листинг новой директории.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("sourcePath").put("type", "string").put("description", "Исходный путь");
        props.putObject("targetPath").put("type", "string").put("description", "Путь назначения");
        
        schema.putArray("required").add("sourcePath").add("targetPath");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String sourceStr = params.get("sourcePath").asText();
        String targetStr = params.get("targetPath").asText();
        
        Path source = PathSanitizer.sanitize(sourceStr, false);
        Path target = PathSanitizer.sanitize(targetStr, false);

        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Исходный файл не найден: " + sourceStr);
        }
        
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Файл назначения уже существует: " + targetStr);
        }

        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }

        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        AccessTracker.moveRecord(source, target);

        var result = mapper.createObjectNode();
        var contentArray = result.putArray("content");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Успешно перемещено из ").append(sourceStr).append(" в ").append(targetStr).append("\n\n");
        sb.append("Содержимое директории ").append(target.getParent()).append(":\n");
        sb.append(getDirectoryListing(target.getParent()));
        
        contentArray.addObject().put("type", "text").put("text", sb.toString());
        return result;
    }

    private String getDirectoryListing(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return "(пусто)";
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String type = Files.isDirectory(entry) ? "[DIR]" : "[FILE]";
                entries.add(type + " " + entry.getFileName().toString());
            }
        }
        Collections.sort(entries);
        return entries.isEmpty() ? "(пусто)" : String.join("\n", entries);
    }
}