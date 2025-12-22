// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Инструмент для перемещения файлов и директорий.
 */
public class MoveFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "move_file";
    }

    @Override
    public String getDescription() {
        return "Перемещает файл или директорию в новое местоположение. Создает промежуточные директории если нужно.";
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

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", "Успешно перемещено из " + sourceStr + " в " + targetStr);
        return result;
    }
}
