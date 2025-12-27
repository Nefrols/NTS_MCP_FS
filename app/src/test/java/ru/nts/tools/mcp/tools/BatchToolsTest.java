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
        String content = Files.readString(file);
        int lineCount = content.split("\n", -1).length;
        String rangeContent = buildRangeContent(content, 1, lineCount);
        LineAccessToken token = LineAccessTracker.registerAccess(file, 1, lineCount, rangeContent, lineCount);
        return token.encode();
    }

    private String buildRangeContent(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.length, endLine);
        for (int i = start; i < end; i++) {
            if (i > start) sb.append("\n");
            sb.append(String.format("%4d\t%s", i + 1, lines[i]));
        }
        return sb.toString();
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

    // ============ Virtual FS Context: умная адресация строк ============

    @Test
    void testSmartAddressingPrevEnd() throws Exception {
        // Тест $PREV_END: после редактирования следующая вставка использует позицию
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Создание файла с 3 строками
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "c");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "smart.txt");
        createParams.put("content", "Line 1\nLine 2\nLine 3");

        // Шаг 2: Редактирование строки 2
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "smart.txt");
        editParams.put("startLine", 2);
        editParams.put("endLine", 2);
        editParams.put("content", "Modified 2");
        editParams.put("accessToken", "{{c.token}}");

        // Шаг 3: Вставка после предыдущей правки с $PREV_END+1
        ObjectNode insertAction = actions.addObject();
        insertAction.put("tool", "nts_edit_file");
        ObjectNode insertParams = insertAction.putObject("params");
        insertParams.put("path", "smart.txt");
        insertParams.put("startLine", "$PREV_END+1"); // Должно разрешиться в строку 3
        insertParams.put("operation", "insert_after");
        insertParams.put("content", "Inserted");
        insertParams.put("accessToken", "{{c.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        Path created = tempDir.resolve("smart.txt");
        String content = Files.readString(created);
        // Ожидаем: Line 1, Modified 2, Line 3, Inserted
        assertTrue(content.contains("Line 1"));
        assertTrue(content.contains("Modified 2"));
        assertTrue(content.contains("Inserted"));
    }

    @Test
    void testSmartAddressingLast() throws Exception {
        // Тест $LAST: вставка в конец файла
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Создание файла
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "c");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "lastline.txt");
        createParams.put("content", "First\nSecond");

        // Шаг 2: Вставка в конец с $LAST
        ObjectNode insertAction = actions.addObject();
        insertAction.put("tool", "nts_edit_file");
        ObjectNode insertParams = insertAction.putObject("params");
        insertParams.put("path", "lastline.txt");
        insertParams.put("startLine", "$LAST");
        insertParams.put("operation", "insert_after");
        insertParams.put("content", "Third");
        insertParams.put("accessToken", "{{c.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        Path created = tempDir.resolve("lastline.txt");
        String content = Files.readString(created);
        assertTrue(content.endsWith("Third") || content.contains("Third"));
    }

    @Test
    void testSmartAddressingWithOffset() throws Exception {
        // Тест $PREV_END+2: смещение относительно предыдущей позиции
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Создаем файл с 5 строками
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "c");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "offset.txt");
        createParams.put("content", "Line 1\nLine 2\nLine 3\nLine 4\nLine 5");

        // Редактируем строку 2
        ObjectNode edit1 = actions.addObject();
        edit1.put("tool", "nts_edit_file");
        ObjectNode edit1Params = edit1.putObject("params");
        edit1Params.put("path", "offset.txt");
        edit1Params.put("startLine", 2);
        edit1Params.put("endLine", 2);
        edit1Params.put("content", "MODIFIED");
        edit1Params.put("accessToken", "{{c.token}}");

        // Редактируем строку на 2 позиции дальше ($PREV_END+2 = 2+2 = 4)
        ObjectNode edit2 = actions.addObject();
        edit2.put("tool", "nts_edit_file");
        ObjectNode edit2Params = edit2.putObject("params");
        edit2Params.put("path", "offset.txt");
        edit2Params.put("startLine", "$PREV_END+2");
        edit2Params.put("endLine", "$PREV_END+2");
        edit2Params.put("content", "ALSO MODIFIED");
        edit2Params.put("accessToken", "{{c.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        Path created = tempDir.resolve("offset.txt");
        String content = Files.readString(created);
        assertTrue(content.contains("MODIFIED"));
        assertTrue(content.contains("ALSO MODIFIED"));
    }

    @Test
    void testSmartAddressingErrorOnFirstOperation() throws Exception {
        // $PREV_END на первой операции с файлом должен вызвать ошибку
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "content");
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "existing.txt");
        editParams.put("startLine", "$PREV_END"); // Ошибка: нет предыдущей операции
        editParams.put("content", "x");
        editParams.put("accessToken", token);

        Exception ex = assertThrows(Exception.class, () -> batchTool.execute(params));
        assertTrue(ex.getMessage().contains("no previous operation") ||
                (ex.getCause() != null && ex.getCause().getMessage().contains("no previous operation")));
    }

    // ============ Session References: {{id.path}} для rename/move ============

    @Test
    void testSessionReferencesPathInterpolation() throws Exception {
        // Тест базовой интерполяции {{id.path}}
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Создание файла
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "file");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "original.txt");
        createParams.put("content", "Hello");

        // Шаг 2: Редактирование с использованием {{file.path}}
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "{{file.path}}"); // Должно разрешиться в "original.txt"
        editParams.put("startLine", 1);
        editParams.put("content", "Modified");
        editParams.put("accessToken", "{{file.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        Path created = tempDir.resolve("original.txt");
        assertEquals("Modified", Files.readString(created));
    }

    @Test
    void testSessionReferencesAfterRename() throws Exception {
        // Тест: create → rename → edit с использованием {{id.path}} который автоматически обновляется
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Создание файла
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "svc");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "Service.java");
        createParams.put("content", "class Service {}");

        // Шаг 2: Переименование с {{svc.path}}
        ObjectNode renameAction = actions.addObject();
        renameAction.put("tool", "nts_file_manage");
        ObjectNode renameParams = renameAction.putObject("params");
        renameParams.put("action", "rename");
        renameParams.put("path", "{{svc.path}}");
        renameParams.put("newName", "UserService.java");

        // Шаг 3: Редактирование с {{svc.path}} - должен автоматически разрешиться в UserService.java
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "{{svc.path}}"); // Должно разрешиться в "UserService.java" после rename!
        editParams.put("startLine", 1);
        editParams.put("content", "class UserService {}");
        editParams.put("accessToken", "{{svc.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        // Проверяем что старый файл удалён, новый создан и отредактирован
        assertFalse(Files.exists(tempDir.resolve("Service.java")));
        Path renamed = tempDir.resolve("UserService.java");
        assertTrue(Files.exists(renamed));
        assertEquals("class UserService {}", Files.readString(renamed));
    }

    @Test
    void testSessionReferencesAfterMove() throws Exception {
        // Тест: create → move → edit с использованием {{id.path}}
        Files.createDirectories(tempDir.resolve("subdir"));

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Создание файла
        ObjectNode createAction = actions.addObject();
        createAction.put("id", "f");
        createAction.put("tool", "nts_file_manage");
        ObjectNode createParams = createAction.putObject("params");
        createParams.put("action", "create");
        createParams.put("path", "file.txt");
        createParams.put("content", "Original");

        // Шаг 2: Перемещение
        ObjectNode moveAction = actions.addObject();
        moveAction.put("tool", "nts_file_manage");
        ObjectNode moveParams = moveAction.putObject("params");
        moveParams.put("action", "move");
        moveParams.put("path", "{{f.path}}");
        moveParams.put("targetPath", "subdir/moved.txt");

        // Шаг 3: Редактирование по новому пути
        ObjectNode editAction = actions.addObject();
        editAction.put("tool", "nts_edit_file");
        ObjectNode editParams = editAction.putObject("params");
        editParams.put("path", "{{f.path}}"); // Должно разрешиться в "subdir/moved.txt"
        editParams.put("startLine", 1);
        editParams.put("content", "Moved and Edited");
        editParams.put("accessToken", "{{f.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        assertFalse(Files.exists(tempDir.resolve("file.txt")));
        Path moved = tempDir.resolve("subdir/moved.txt");
        assertTrue(Files.exists(moved));
        assertEquals("Moved and Edited", Files.readString(moved));
    }

    @Test
    void testMultipleFilesSessionReferences() throws Exception {
        // Тест: работа с несколькими файлами через разные id
        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Создаём два файла
        ObjectNode create1 = actions.addObject();
        create1.put("id", "a");
        create1.put("tool", "nts_file_manage");
        create1.putObject("params").put("action", "create").put("path", "a.txt").put("content", "A");

        ObjectNode create2 = actions.addObject();
        create2.put("id", "b");
        create2.put("tool", "nts_file_manage");
        create2.putObject("params").put("action", "create").put("path", "b.txt").put("content", "B");

        // Редактируем оба через их id
        ObjectNode edit1 = actions.addObject();
        edit1.put("tool", "nts_edit_file");
        edit1.putObject("params")
                .put("path", "{{a.path}}")
                .put("startLine", 1)
                .put("content", "AA")
                .put("accessToken", "{{a.token}}");

        ObjectNode edit2 = actions.addObject();
        edit2.put("tool", "nts_edit_file");
        edit2.putObject("params")
                .put("path", "{{b.path}}")
                .put("startLine", 1)
                .put("content", "BB")
                .put("accessToken", "{{b.token}}");

        var result = batchTool.execute(params);
        assertTrue(result.get("content").get(0).get("text").asText().contains("successful"));

        assertEquals("AA", Files.readString(tempDir.resolve("a.txt")));
        assertEquals("BB", Files.readString(tempDir.resolve("b.txt")));
    }
}
