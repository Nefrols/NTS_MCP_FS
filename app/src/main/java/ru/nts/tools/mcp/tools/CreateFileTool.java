// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Инструмент для создания новых файлов.
 * Возможности:
 * - Автоматическое создание промежуточных директорий.
 * - Защита от перезаписи существующих непрочитанных файлов.
 * - Транзакционная атомарность через TransactionManager.
 * - Возвращает листинг директории после создания для мгновенной обратной связи.
 */
public class CreateFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "create_file";
    }

    @Override
    public String getDescription() {
        return "Creates a new file. Supports auto-creation of directories and returns the updated directory listing.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Path to the new file.");
        props.putObject("content").put("type", "string").put("description", "Text content of the file.");
        
        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String content = params.get("content").asText();
        
        // Санитарная проверка пути
        Path path = PathSanitizer.sanitize(pathStr, false);
        
        // Предохранитель: если файл существует, его нужно прочитать перед перезаписью
        if (Files.exists(path) && !AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Access denied: file already exists and has not been read. Use read_file before overwriting.");
        }

        // Запуск транзакции
        TransactionManager.startTransaction("Create file: " + pathStr);
        try {
            // Создаем резервную копию (если файл новый, TransactionManager пометит его на удаление при откате)
            TransactionManager.backup(path);

            // Создаем родительские директории если их нет
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            Files.writeString(path, content, StandardCharsets.UTF_8);
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");
            
            StringBuilder sb = new StringBuilder();
            sb.append("File created successfully: ").append(pathStr).append("\n\n");
            sb.append("Directory content ").append(path.getParent()).append(":\n");
            sb.append(getDirectoryListing(path.getParent()));
            
            contentArray.addObject().put("type", "text").put("text", sb.toString());
            return result;
        } catch (Exception e) {
            // Откат при сбое
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Формирует простой список файлов в директории для обратной связи LLM.
     */
    private String getDirectoryListing(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return "(empty)";
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String type = Files.isDirectory(entry) ? "[DIR]" : "[FILE]";
                entries.add(type + " " + entry.getFileName().toString());
            }
        }
        Collections.sort(entries);
        return entries.isEmpty() ? "(empty)" : String.join("\n", entries);
    }
}
