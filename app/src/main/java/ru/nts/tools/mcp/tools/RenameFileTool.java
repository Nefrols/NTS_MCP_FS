// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.GitUtils;
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
 * Инструмент для переименования файлов и директорий.
 * Особенности:
 * - Работает только в рамках одной директории (для перемещения используйте move_file).
 * - Транзакционная защита через TransactionManager.
 * - Возвращает обновленный листинг директории.
 */
public class RenameFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "rename_file";
    }

    @Override
    public String getDescription() {
        return "Renames an object within the current folder. Supports undo and returns the updated directory listing.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Current path to the object.");
        props.putObject("newName").put("type", "string").put("description", "New name (name only, not a path).");
        
        schema.putArray("required").add("path").add("newName");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String newName = params.get("newName").asText();
        
        Path source = PathSanitizer.sanitize(pathStr, false);
        
        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Object not found: " + pathStr);
        }

        // Запрещаем пути в новом имени
        if (newName.contains("/") || newName.contains("\\")) {
            throw new IllegalArgumentException("New name must not contain path components. Use move_file for moving objects.");
        }

        Path target = source.resolveSibling(newName);
        
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Object with name " + newName + " already exists.");
        }

        TransactionManager.startTransaction("Rename: " + pathStr + " -> " + newName);
        try {
            // Бэкапим исходный путь и целевой
            TransactionManager.backup(source);
            TransactionManager.backup(target);

            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            AccessTracker.moveRecord(source, target);
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");
            
            String gitStatus = GitUtils.getFileStatus(target);
            StringBuilder sb = new StringBuilder();
            sb.append("Successfully renamed to ").append(newName);
            if (!gitStatus.isEmpty()) sb.append(" [Git: ").append(gitStatus).append("]");
            sb.append("\n\n");
            sb.append("Directory content ").append(target.getParent()).append(":\n");
            sb.append(getDirectoryListing(target.getParent()));
            
            contentArray.addObject().put("type", "text").put("text", sb.toString());
            return result;
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
    }

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
