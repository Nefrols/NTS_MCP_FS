// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Инструмент для создания новых файлов.
 */
public class CreateFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "create_file";
    }

    @Override
    public String getDescription() {
        return "Создает новый файл с указанным содержимым. Автоматически создает промежуточные директории.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к новому файлу");
        props.putObject("content").put("type", "string").put("description", "Содержимое файла");
        
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String content = params.get("content").asText();
        
        // Санитарная проверка пути
        Path path = PathSanitizer.sanitize(pathStr, false);
        
        if (Files.exists(path)) {
            throw new IllegalArgumentException("Файл уже существует: " + pathStr);
        }

        // Создаем родительские директории если их нет
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        Files.writeString(path, content, StandardCharsets.UTF_8);

        var result = mapper.createObjectNode();
        var contentArray = result.putArray("content");
        contentArray.addObject().put("type", "text").put("text", "Файл успешно создан: " + pathStr);
        return result;
    }
}
