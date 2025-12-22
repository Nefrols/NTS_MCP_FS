// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Инструмент для удаления файлов и директорий.
 */
public class DeleteFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "delete_file";
    }

    @Override
    public String getDescription() {
        return "Удаляет файл или директорию. Для директорий поддерживается рекурсивное удаление.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к удаляемому объекту");
        props.putObject("recursive").put("type", "boolean").put("description", "Удалить рекурсивно (для директорий)");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        boolean recursive = params.path("recursive").asBoolean(false);
        
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Объект не найден: " + pathStr);
        }

        if (Files.isDirectory(path) && recursive) {
            try (var walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            // Еще одна проверка внутри рекурсии на всякий случай
                            PathSanitizer.sanitize(p.toString(), false);
                            Files.delete(p);
                        } catch (Exception e) {
                            throw new RuntimeException("Ошибка при удалении " + p + ": " + e.getMessage());
                        }
                    });
            }
        } else {
            Files.delete(path);
        }

        var result = mapper.createObjectNode();
        var contentArray = result.putArray("content");
        contentArray.addObject().put("type", "text").put("text", "Успешно удалено: " + pathStr);
        return result;
    }
}
