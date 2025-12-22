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
 * Инструмент для переименования файлов и директорий.
 */
public class RenameFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "rename_file";
    }

    @Override
    public String getDescription() {
        return "Переименовывает файл или директорию внутри текущего расположения.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Текущий путь к файлу");
        props.putObject("newName").put("type", "string").put("description", "Новое имя (только имя, не путь)");
        
        schema.putArray("required").add("path").add("newName");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String newName = params.get("newName").asText();
        
        Path source = PathSanitizer.sanitize(pathStr, false);
        
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Файл не найден: " + pathStr);
        }

        if (newName.contains("/") || newName.contains("\\")) {
            throw new IllegalArgumentException("Новое имя не должно содержать путь. Используйте move_file для перемещения.");
        }

        Path target = source.resolveSibling(newName);
        
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Файл с именем " + newName + " уже существует.");
        }

        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        AccessTracker.moveRecord(source, target);

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", "Успешно переименовано в " + newName);
        return result;
    }
}
