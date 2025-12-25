/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

/**
 * Инструмент для управления файловой структурой.
 * Поддерживает создание, удаление, перемещение и переименование файлов и директорий.
 */
public class FileManageTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_file_manage";
    }

    @Override
    public String getDescription() {
        return """
            File system structure manager with full UNDO support.

            ACTIONS:
            - create - New file with optional initial content. Parent dirs auto-created.
            - delete - Remove file/directory. Use recursive=true for non-empty dirs.
            - move   - Relocate file/dir to new path. Preserves access tokens!
            - rename - Change filename in same directory. Preserves access tokens!

            TOKEN BEHAVIOR:
            - create: Returns access token for immediate editing (no read needed!)
            - move/rename: Tokens automatically transferred to new path
            - delete: All tokens for the file are invalidated

            SAFETY:
            - All operations are transactional (auto-rollback on error)
            - Use nts_session(action='undo') to reverse any operation
            - External changes (by other tools) cannot be undone
            """;
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

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'create', 'delete', 'move', 'rename'. Required.");

        props.putObject("path").put("type", "string").put("description",
                "Target path (relative to project root). For create: new file path. " +
                "For delete/move/rename: existing file/dir path. Required.");

        props.putObject("content").put("type", "string").put("description",
                "For 'create': initial file content. Omit for empty file. " +
                "Tip: Use nts_edit_file for complex content - it has better formatting.");

        props.putObject("targetPath").put("type", "string").put("description",
                "For 'move': destination path. Parent directories created automatically. " +
                "Example: move 'src/old.java' to 'src/util/new.java'.");

        props.putObject("newName").put("type", "string").put("description",
                "For 'rename': new filename only (not full path). Stays in same directory. " +
                "Example: rename 'OldClass.java' to 'NewClass.java'.");

        props.putObject("recursive").put("type", "boolean").put("description",
                "For 'delete': required if target is non-empty directory. " +
                "CAUTION: Deletes all contents! Default: false.");

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

    private JsonNode executeCreate(Path path, String pathStr, String content) throws Exception {
        if (Files.exists(path)) {
            throw new IOException("File already exists: " + pathStr);
        }

        TransactionManager.startTransaction("Create file: " + pathStr);
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            TransactionManager.backup(path); // This will record that file didn't exist
            Files.writeString(path, content);

            // Path Lineage: регистрируем файл для отслеживания
            TransactionManager.registerFile(path);
            // InfinityRange: отмечаем файл как созданный в транзакции
            // Это отключает проверку границ токена для последующих правок
            TransactionManager.markFileCreatedInTransaction(path);
            TransactionManager.markFileAccessedInTransaction(path);

            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }

        // Регистрируем токен доступа на весь созданный контент
        long crc = calculateCRC32(path);
        int lineCount = content.isEmpty() ? 1 : content.split("\n", -1).length;
        LineAccessToken token = LineAccessTracker.registerAccess(path, 1, lineCount, crc, lineCount);

        return createResponse(String.format("File created: %s\nLines: %d | CRC32C: %X\n[TOKEN: %s]",
                pathStr, lineCount, crc, token.encode()));
    }

    /**
     * Вычисляет CRC32C для файла.
     */
    private long calculateCRC32(Path path) throws Exception {
        CRC32C crc = new CRC32C();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    private JsonNode executeDelete(Path path, String pathStr, boolean recursive) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Path not found: " + pathStr);
        }

        TransactionManager.startTransaction("Delete: " + pathStr);
        try {
            if (Files.isDirectory(path)) {
                // For directory delete, we backup all files inside and invalidate tokens
                try (var s = Files.walk(path)) {
                    s.filter(Files::isRegularFile).forEach(p -> {
                        try {
                            TransactionManager.backup(p);
                            LineAccessTracker.invalidateFile(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
                if (recursive) {
                    deleteRecursive(path);
                } else {
                    Files.delete(path);
                }
            } else {
                TransactionManager.backup(path);
                LineAccessTracker.invalidateFile(path);
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
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private JsonNode executeMove(Path src, String srcStr, String destStr) throws IOException {
        Path dest = PathSanitizer.sanitize(destStr, false);
        TransactionManager.startTransaction("Move from " + srcStr + " to " + destStr);
        try {
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            TransactionManager.backup(src);
            TransactionManager.backup(dest);
            Files.move(src, dest);
            // Переносим токены доступа на новый путь
            LineAccessTracker.moveTokens(src, dest);
            // Path Lineage: записываем перемещение для Deep Undo
            TransactionManager.recordFileMove(src, dest);
            // Session Tokens: отмечаем новый путь как разблокированный
            TransactionManager.markFileAccessedInTransaction(dest);
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
            // Переносим токены доступа на новый путь
            LineAccessTracker.moveTokens(path, newPath);
            // Path Lineage: записываем перемещение для Deep Undo
            TransactionManager.recordFileMove(path, newPath);
            // Session Tokens: отмечаем новый путь как разблокированный
            TransactionManager.markFileAccessedInTransaction(newPath);
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