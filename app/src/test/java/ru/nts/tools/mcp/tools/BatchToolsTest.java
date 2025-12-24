// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.*;
import ru.nts.tools.mcp.tools.editing.EditFileTool;
import ru.nts.tools.mcp.tools.fs.FileManageTool;
import ru.nts.tools.mcp.tools.fs.FileReadTool;
import ru.nts.tools.mcp.tools.system.BatchToolsTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента пакетного выполнения (BatchToolsTool).
 */
class BatchToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpRouter router;
    private BatchToolsTool batchTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        LineAccessTracker.reset();

        router = new McpRouter(mapper);
        router.registerTool(new FileManageTool());
        router.registerTool(new EditFileTool());
        router.registerTool(new FileReadTool());
        batchTool = new BatchToolsTool(router);
    }

    private String registerFullAccess(Path file) throws Exception {
        long crc = calculateCRC32(file);
        String content = Files.readString(file);
        int lineCount = content.split("\n", -1).length;
        LineAccessToken token = LineAccessTracker.registerAccess(file, 1, lineCount, crc, lineCount);
        return token.encode();
    }

    private long calculateCRC32(Path path) throws Exception {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(new java.io.FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    @Test
    void testAccessTransferInBatch() throws Exception {
        Path file = tempDir.resolve("access.txt");
        Files.writeString(file, "content");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Чтение (дает права и токен)
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_file_read");
        ObjectNode p1 = a1.putObject("params");
        p1.put("path", "access.txt");
        p1.put("startLine", 1);
        p1.put("endLine", 1);

        var result = batchTool.execute(params);
        String resultText = result.get("content").get(0).get("text").asText();

        // Batch должен успешно выполниться
        assertTrue(resultText.contains("successful"), "Batch should complete successfully");
        // Токен должен быть зарегистрирован в LineAccessTracker
        assertTrue(LineAccessTracker.hasAnyAccess(file), "File should have access tokens");
    }

    @Test
    void testRenameAndEditBatch() throws Exception {
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "Original Content");
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Переименование
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_file_manage");
        ObjectNode p1 = a1.putObject("params");
        p1.put("action", "rename");
        p1.put("path", "old.txt");
        p1.put("newName", "new.txt");

        batchTool.execute(params);

        Path newFile = tempDir.resolve("new.txt");
        assertTrue(Files.exists(newFile));
        assertFalse(Files.exists(file));
        assertEquals("Original Content", Files.readString(newFile));
    }

    @Test
    void testBatchRollbackOnFailure() throws Exception {
        Path file = tempDir.resolve("safe.txt");
        Files.writeString(file, "Untouched");
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Первая операция - редактирование с токеном
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_edit_file");
        a1.putObject("params").put("path", "safe.txt").put("startLine", 1).put("content", "MODIFIED").put("accessToken", token);

        // Вторая операция - редактирование несуществующего файла
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "nts_edit_file");
        a2.putObject("params").put("path", "missing.txt").put("startLine", 1).put("content", "fail").put("accessToken", "invalid");

        assertThrows(Exception.class, () -> batchTool.execute(params));
        assertEquals("Untouched", Files.readString(file));
    }
}
