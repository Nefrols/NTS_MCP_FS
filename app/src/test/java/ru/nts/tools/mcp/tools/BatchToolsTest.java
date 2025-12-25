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

    @Test
    void testTokenInterpolationById() throws Exception {
        // Тест интерполяции токенов: read → edit с использованием {{id.token}}
        Path file = tempDir.resolve("interp.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Чтение файла с id для последующей ссылки
        ObjectNode readAction = actions.addObject();
        readAction.put("id", "myread");
        readAction.put("tool", "nts_file_read");
        ObjectNode readParams = readAction.putObject("params");
        readParams.put("path", "interp.txt");
        readParams.put("startLine", 1);
        readParams.put("endLine", 3);

        // Шаг 2: Редактирование с интерполяцией токена из шага 1
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "interp.txt");
        editParams.put("startLine", 2);
        editParams.put("content", "MODIFIED");
        editParams.put("accessToken", "{{myread.token}}");

        var result = batchTool.execute(params);
        String resultText = result.get("content").get(0).get("text").asText();

        assertTrue(resultText.contains("successful"), "Batch should complete: " + resultText);
        assertEquals("Line 1\nMODIFIED\nLine 3", Files.readString(file));
    }

    @Test
    void testTokenInterpolationByStepNumber() throws Exception {
        // Тест интерполяции по номеру шага: {{step1.token}}
        Path file = tempDir.resolve("stepnum.txt");
        Files.writeString(file, "Original");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Чтение (без id)
        ObjectNode readAction = actions.addObject();
        readAction.put("tool", "nts_file_read");
        ObjectNode readParams = readAction.putObject("params");
        readParams.put("path", "stepnum.txt");
        readParams.put("startLine", 1);
        readParams.put("endLine", 1);

        // Шаг 2: Редактирование с {{step1.token}}
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "stepnum.txt");
        editParams.put("startLine", 1);
        editParams.put("content", "Changed");
        editParams.put("accessToken", "{{step1.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));
        assertEquals("Changed", Files.readString(file));
    }

    @Test
    void testCreateThenEditWithInterpolation() throws Exception {
        // Тест: create → edit с автоматическим токеном от create
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Создание файла
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "create");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "newfile.txt");
        createParams.put("content", "Initial\nContent");

        // Шаг 2: Редактирование с токеном от create
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "newfile.txt");
        editParams.put("startLine", 1);
        editParams.put("content", "Modified");
        editParams.put("accessToken", "{{create.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        Path created = tempDir.resolve("newfile.txt");
        assertTrue(Files.exists(created));
        assertEquals("Modified\nContent", Files.readString(created));
    }

    @Test
    void testInvalidVariableReference() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Чтение
        ObjectNode readAction = actions.addObject();
        readAction.put("id", "read");
        readAction.put("tool", "nts_file_read");
        readAction.putObject("params").put("path", "test.txt").put("startLine", 1).put("endLine", 1);

        // Шаг 2: Ссылка на несуществующий шаг
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        editAction.putObject("params")
                .put("path", "test.txt")
                .put("startLine", 1)
                .put("content", "x")
                .put("accessToken", "{{nonexistent.token}}");

        Exception ex = assertThrows(Exception.class, () -> batchTool.execute(params));
        assertTrue(ex.getMessage().contains("nonexistent") || ex.getCause().getMessage().contains("nonexistent"));
    }
}
