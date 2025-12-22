// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.GitUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Инструмент для удаления файлов и директорий.
 * Особенности:
 * - Поддерживает рекурсивное удаление папок.
 * - Интегрирован с TransactionManager для возможности отмены (UNDO).
 * - Безопасность гарантируется через PathSanitizer.
 */
public class DeleteFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "delete_file";
    }

    @Override
    public String getDescription() {
        return "Deletes a file or directory. Supports recursive deletion for folders.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Path to the object to delete.");
        props.putObject("recursive").put("type", "boolean").put("description", "Flag for recursive folder deletion.");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        boolean recursive = params.path("recursive").asBoolean(false);
        
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Object not found: " + pathStr);
        }

        TransactionManager.startTransaction("Delete: " + pathStr);
        try {
            if (Files.isDirectory(path) && recursive) {
                try (var walk = Files.walk(path)) {
                    // Собираем все файлы для бэкапа перед удалением
                    List<Path> allPaths = walk.collect(Collectors.toList());
                    for (Path p : allPaths) {
                        if (Files.isRegularFile(p)) {
                            TransactionManager.backup(p);
                        }
                    }

                    // Удаляем в обратном порядке (файлы, потом пустые папки)
                    for (int i = allPaths.size() - 1; i >= 0; i--) {
                        Files.delete(allPaths.get(i));
                    }
                }
            } else {
                // Одиночное удаление
                if (Files.isRegularFile(path)) {
                    TransactionManager.backup(path);
                }
                Files.delete(path);
            }
            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }

        var result = mapper.createObjectNode();
        var contentArray = result.putArray("content");
        
        String gitStatus = GitUtils.getFileStatus(path.getParent());
        String msg = "Deleted successfully: " + pathStr;
        if (!gitStatus.isEmpty()) msg += " [Parent Git: " + gitStatus + "]";
        
        contentArray.addObject().put("type", "text").put("text", msg);
        return result;
    }
}
