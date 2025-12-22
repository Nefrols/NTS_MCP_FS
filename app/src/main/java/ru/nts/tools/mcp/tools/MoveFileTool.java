// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

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
 * Особенности:
 * - Автоматическое создание папок назначения.
 * - Перенос статуса прочтения [READ] в AccessTracker.
 * - Транзакционная защита (возможность UNDO).
 * - Возврат листинга новой директории.
 */
public class MoveFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "move_file";
    }

    @Override
    public String getDescription() {
        return "Перемещает файл или директорию. Поддерживает отмену и возвращает обновленный листинг.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("sourcePath").put("type", "string").put("description", "Текущий путь к объекту.");
        props.putObject("targetPath").put("type", "string").put("description", "Новый путь к объекту.");
        
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
            throw new IllegalArgumentException("Исходный объект не найден: " + sourceStr);
        }
        
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Объект по пути назначения уже существует: " + targetStr);
        }

        TransactionManager.startTransaction("Move: " + sourceStr + " -> " + targetStr);
        try {
            // Бэкапим исходный файл перед его исчезновением
            if (Files.isRegularFile(source)) {
                TransactionManager.backup(source);
            } else if (Files.isDirectory(source)) {
                // Для папок бэкапим все файлы внутри рекурсивно
                try (var walk = Files.walk(source)) {
                    for (Path p : (Iterable<Path>)walk::iterator) {
                        if (Files.isRegularFile(p)) TransactionManager.backup(p);
                    }
                }
            }
            // Целевой путь бэкапим как "null" (для удаления при откате)
            TransactionManager.backup(target);

            // Создаем папки назначения
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            // Переносим статус [READ] на новое место
            AccessTracker.moveRecord(source, target);
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");
            
            StringBuilder sb = new StringBuilder();
            sb.append("Успешно перемещено из ").append(sourceStr).append(" в ").append(targetStr).append("\n\n");
            sb.append("Содержимое директории ").append(target.getParent()).append(":\n");
            sb.append(getDirectoryListing(target.getParent()));
            
            contentArray.addObject().put("type", "text").put("text", sb.toString());
            return result;
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
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
