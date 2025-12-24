// Aristo 24.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Инструмент для управления файловой структурой.
 * Поддерживает создание, удаление, перемещение и переименование файлов и директорий.
 */
public class FileManageTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_file_manage"; }

    @Override
    public String getDescription() {
        return "Structural modification tool. Use this to create, delete, move, or rename files and directories. All operations are transactional and support UNDO. Note: Changes made by external tools or other MCPs are not tracked and cannot be restored.";
    }

    @Override
    public String getCategory() {
        return "fs";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description", "Action: 'create' (new file), 'delete' (remove), 'move' (relocate), 'rename' (change name).");
        props.putObject("path").put("type", "string").put("description", "Primary target path relative to project root.");
        props.putObject("content").put("type", "string").put("description", "Initial file data for 'create'. Can be empty.");
        props.putObject("targetPath").put("type", "string").put("description", "Destination for 'move'. Missing parent directories will be created automatically.");
        props.putObject("newName").put("type", "string").put("description", "New filename for 'rename' (keeps the same parent directory).");
        props.putObject("recursive").put("type", "boolean").put("description", "Required for 'delete' if the target is a non-empty directory.");

        schema.putArray("required").add("action").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false);

        return switch (action) {
            case "create" -> executeCreate(path, pathStr, params.path("content").asText(""));
            case "delete" -> executeDelete(path, pathStr, params.path("recursive").asBoolean(false));
            case "move" -> executeMove(path, pathStr, params.get("targetPath").asText());
            case "rename" -> executeRename(path, pathStr, params.get("newName").asText());
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeCreate(Path path, String pathStr, String content) throws IOException {
        if (Files.exists(path)) throw new IOException("File already exists: " + pathStr);
        
        TransactionManager.startTransaction("Create file: " + pathStr);
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            TransactionManager.backup(path); // This will record that file didn't exist
            Files.writeString(path, content);
            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
        return createResponse("File created: " + pathStr);
    }

    private JsonNode executeDelete(Path path, String pathStr, boolean recursive) throws IOException {
        if (!Files.exists(path)) throw new IOException("Path not found: " + pathStr);

        TransactionManager.startTransaction("Delete: " + pathStr);
        try {
            if (Files.isDirectory(path)) {
                // For directory delete, we backup all files inside
                try (var s = Files.walk(path)) {
                    s.filter(Files::isRegularFile).forEach(p -> {
                        try { TransactionManager.backup(p); } catch (IOException ignored) {}
                    });
                }
                if (recursive) deleteRecursive(path);
                else Files.delete(path);
            } else {
                TransactionManager.backup(path);
                Files.delete(path);
            }
            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
        return createResponse("Deleted: " + pathStr);
    }
    
    private void deleteRecursive(Path path) throws IOException {
        try (var s = Files.walk(path)) {
            s.sorted((p1, p2) -> p2.compareTo(p1)).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }
    }

    private JsonNode executeMove(Path src, String srcStr, String destStr) throws IOException {
        Path dest = PathSanitizer.sanitize(destStr, false);
        TransactionManager.startTransaction("Move from " + srcStr + " to " + destStr);
        try {
            if (dest.getParent() != null) Files.createDirectories(dest.getParent());
            TransactionManager.backup(src);
            TransactionManager.backup(dest);
            Files.move(src, dest);
            AccessTracker.moveRecord(src, dest);
            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
        return createResponse("Moved from " + srcStr + " to " + destStr);
    }

    private JsonNode executeRename(Path path, String pathStr, String newName) throws IOException {
        Path newPath = path.resolveSibling(newName);
        TransactionManager.startTransaction("Rename " + pathStr + " to " + newName);
        try {
            TransactionManager.backup(path);
            TransactionManager.backup(newPath);
            Files.move(path, newPath);
            AccessTracker.moveRecord(path, newPath);
            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
        return createResponse("Renamed " + pathStr + " to " + newName);
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}