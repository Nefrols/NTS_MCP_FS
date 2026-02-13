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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.*;
import ru.nts.tools.mcp.tools.editing.EditFileTool;
import ru.nts.tools.mcp.tools.fs.FileManageTool;
import ru.nts.tools.mcp.tools.fs.FileReadTool;
import ru.nts.tools.mcp.tools.task.TaskTool;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты для системы отслеживания внешних изменений.
 * Тестирует взаимодействие FileReadTool, EditFileTool и FileManageTool
 * с ExternalChangeTracker.
 */
class ExternalChangeIntegrationTest {

    private FileReadTool readTool;
    private EditFileTool editTool;
    private FileManageTool manageTool;
    private TaskTool TaskTool;
    private ObjectMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        LineAccessTracker.reset();
        TaskContext.resetAll();

        readTool = new FileReadTool();
        editTool = new EditFileTool();
        manageTool = new FileManageTool();
        TaskTool = new TaskTool();
        mapper = new ObjectMapper();
    }

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

    private String extractToken(JsonNode result) {
        String text = result.get("content").get(0).get("text").asText();
        // Новый формат: [ACCESS: lines X-Y | TOKEN: LAT:...]
        int tokenStart = text.indexOf("TOKEN: ") + 7;
        int tokenEnd = text.indexOf("]", tokenStart);
        return text.substring(tokenStart, tokenEnd);
    }

    // ==================== FileReadTool: регистрация снапшота ====================

    @Test
    void testFileRead_RegistersSnapshot() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.txt");
        params.put("startLine", 1);
        params.put("endLine", 3);
        readTool.execute(params);

        // Проверяем, что снапшот зарегистрирован
        ExternalChangeTracker tracker = TaskContext.currentOrDefault().externalChanges();
        assertTrue(tracker.hasSnapshot(file));

        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(file);
        assertEquals("line1\nline2\nline3", snapshot.content());
        assertEquals(3, snapshot.lineCount());
    }

    @Test
    void testFileRead_DetectsExternalChange() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "original content");

        // Первое чтение - регистрирует снапшот
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.txt");
        params.put("startLine", 1);
        params.put("endLine", 1);
        readTool.execute(params);

        // Внешнее изменение файла
        Files.writeString(file, "externally modified content");

        // Второе чтение - должно обнаружить изменение
        JsonNode result = readTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("EXTERNAL CHANGE DETECTED"));
        assertTrue(text.contains("recorded in file history"));
    }

    @Test
    void testFileRead_NoFalsePositiveForUnchangedFile() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "stable content");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.txt");
        params.put("startLine", 1);
        params.put("endLine", 1);

        // Первое чтение
        readTool.execute(params);

        // Второе чтение (файл не изменился)
        JsonNode result = readTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertFalse(text.contains("EXTERNAL CHANGE DETECTED"));
    }

    @Test
    void testFileRead_ExternalChangeRecordedInJournal() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "original");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.txt");
        params.put("startLine", 1);
        readTool.execute(params);

        // Внешнее изменение
        Files.writeString(file, "modified");
        readTool.execute(params);

        // Проверяем журнал
        ObjectNode journalParams = mapper.createObjectNode();
        journalParams.put("action", "journal");
        JsonNode journal = TaskTool.execute(journalParams);
        String journalText = journal.get("content").get(0).get("text").asText();

        assertTrue(journalText.contains("[EXTERNAL]"));
    }

    @Test
    void testFileRead_ExternalChangeCanBeUndone() throws Exception {
        Path file = tempDir.resolve("test.txt");
        String originalContent = "original content here";
        Files.writeString(file, originalContent);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "test.txt");
        params.put("startLine", 1);
        readTool.execute(params);

        // Внешнее изменение
        Files.writeString(file, "externally modified");
        readTool.execute(params);

        // Undo внешнего изменения
        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        TaskTool.execute(undoParams);

        assertEquals(originalContent, Files.readString(file));
    }

    // ==================== FileReadTool: multi-range чтение ====================

    @Test
    void testFileRead_MultiRangeRegistersSnapshot() throws Exception {
        Path file = tempDir.resolve("multirange.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "multirange.txt");
        var ranges = params.putArray("ranges");
        ranges.addObject().put("startLine", 1).put("endLine", 2);
        ranges.addObject().put("startLine", 4).put("endLine", 5);
        readTool.execute(params);

        ExternalChangeTracker tracker = TaskContext.currentOrDefault().externalChanges();
        assertTrue(tracker.hasSnapshot(file));
    }

    // ==================== EditFileTool: обнаружение внешних изменений ====================

    @Test
    void testEditFile_DetectsExternalChange() throws Exception {
        Path file = tempDir.resolve("edit_test.txt");
        Files.writeString(file, "original");

        // Первое чтение
        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "edit_test.txt");
        readParams.put("startLine", 1);
        JsonNode readResult = readTool.execute(readParams);
        String token = extractToken(readResult);

        // Внешнее изменение
        Files.writeString(file, "externally modified");

        // Попытка редактирования с устаревшим токеном
        ObjectNode editParams = mapper.createObjectNode();
        editParams.put("path", "edit_test.txt");
        editParams.put("startLine", 1);
        editParams.put("content", "my edit");
        editParams.put("accessToken", token);

        Exception exception = assertThrows(SecurityException.class, () -> editTool.execute(editParams));
        String msg = exception.getMessage();

        assertTrue(msg.contains("EXTERNAL CHANGE DETECTED"));
        assertTrue(msg.contains("recorded in file history"));
        assertTrue(msg.contains("re-read the file"));
    }

    @Test
    void testEditFile_ExternalChangeRecordedBeforeError() throws Exception {
        Path file = tempDir.resolve("edit_test.txt");
        Files.writeString(file, "original");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "edit_test.txt");
        readParams.put("startLine", 1);
        JsonNode readResult = readTool.execute(readParams);
        String token = extractToken(readResult);

        // Внешнее изменение
        Files.writeString(file, "modified");

        // Попытка редактирования (должна упасть, но внешнее изменение записано)
        ObjectNode editParams = mapper.createObjectNode();
        editParams.put("path", "edit_test.txt");
        editParams.put("startLine", 1);
        editParams.put("content", "my edit");
        editParams.put("accessToken", token);

        try {
            editTool.execute(editParams);
        } catch (SecurityException ignored) {}

        // Проверяем, что внешнее изменение записано в журнал
        ObjectNode journalParams = mapper.createObjectNode();
        journalParams.put("action", "journal");
        JsonNode journal = TaskTool.execute(journalParams);
        String journalText = journal.get("content").get(0).get("text").asText();

        assertTrue(journalText.contains("[EXTERNAL]"));
    }

    @Test
    void testEditFile_UpdatesSnapshotAfterSuccessfulEdit() throws Exception {
        Path file = tempDir.resolve("success_edit.txt");
        Files.writeString(file, "original content");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "success_edit.txt");
        readParams.put("startLine", 1);
        JsonNode readResult = readTool.execute(readParams);
        String token = extractToken(readResult);

        // Успешное редактирование
        ObjectNode editParams = mapper.createObjectNode();
        editParams.put("path", "success_edit.txt");
        editParams.put("startLine", 1);
        editParams.put("content", "new content");
        editParams.put("accessToken", token);
        editTool.execute(editParams);

        // Проверяем, что снапшот обновлён
        ExternalChangeTracker tracker = TaskContext.currentOrDefault().externalChanges();
        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(file);
        assertEquals("new content", snapshot.content());
    }

    // ==================== FileManageTool: перемещение и удаление снапшотов ====================

    @Test
    void testFileMove_MovesSnapshot() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "content to move");

        // Читаем файл (создаём снапшот)
        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "source.txt");
        readParams.put("startLine", 1);
        readTool.execute(readParams);

        ExternalChangeTracker tracker = TaskContext.currentOrDefault().externalChanges();
        assertTrue(tracker.hasSnapshot(source));

        // Перемещаем файл
        ObjectNode moveParams = mapper.createObjectNode();
        moveParams.put("action", "move");
        moveParams.put("path", "source.txt");
        moveParams.put("targetPath", "target.txt");
        manageTool.execute(moveParams);

        // Проверяем, что снапшот перенесён
        assertFalse(tracker.hasSnapshot(source));
        assertTrue(tracker.hasSnapshot(target));
        assertEquals("content to move", tracker.getSnapshot(target).content());
    }

    @Test
    void testFileRename_MovesSnapshot() throws Exception {
        Path file = tempDir.resolve("oldname.txt");
        Path renamed = tempDir.resolve("newname.txt");
        Files.writeString(file, "content");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "oldname.txt");
        readParams.put("startLine", 1);
        readTool.execute(readParams);

        ExternalChangeTracker tracker = TaskContext.currentOrDefault().externalChanges();
        assertTrue(tracker.hasSnapshot(file));

        // Переименовываем
        ObjectNode renameParams = mapper.createObjectNode();
        renameParams.put("action", "rename");
        renameParams.put("path", "oldname.txt");
        renameParams.put("newName", "newname.txt");
        manageTool.execute(renameParams);

        assertFalse(tracker.hasSnapshot(file));
        assertTrue(tracker.hasSnapshot(renamed));
    }

    @Test
    void testFileDelete_RemovesSnapshot() throws Exception {
        Path file = tempDir.resolve("to_delete.txt");
        Files.writeString(file, "content");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "to_delete.txt");
        readParams.put("startLine", 1);
        readTool.execute(readParams);

        ExternalChangeTracker tracker = TaskContext.currentOrDefault().externalChanges();
        assertTrue(tracker.hasSnapshot(file));

        // Удаляем
        ObjectNode deleteParams = mapper.createObjectNode();
        deleteParams.put("action", "delete");
        deleteParams.put("path", "to_delete.txt");
        manageTool.execute(deleteParams);

        assertFalse(tracker.hasSnapshot(file));
    }

    // ==================== Сценарии с множественными операциями ====================

    @Test
    void testComplexScenario_MultipleEditsAndExternalChanges() throws Exception {
        Path file = tempDir.resolve("complex.txt");
        Files.writeString(file, "v0");

        // Чтение + редактирование
        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "complex.txt");
        readParams.put("startLine", 1);
        JsonNode readResult = readTool.execute(readParams);
        String token1 = extractToken(readResult);

        ObjectNode editParams = mapper.createObjectNode();
        editParams.put("path", "complex.txt");
        editParams.put("startLine", 1);
        editParams.put("content", "v1");
        editParams.put("accessToken", token1);
        editTool.execute(editParams);

        // Внешнее изменение (симулируем линтер)
        Files.writeString(file, "v1 (formatted)");

        // Чтение обнаруживает внешнее изменение
        JsonNode result = readTool.execute(readParams);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("EXTERNAL CHANGE DETECTED"));

        // Можем откатить внешнее изменение
        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        TaskTool.execute(undoParams);
        assertEquals("v1", Files.readString(file));

        // Можем откатить наше редактирование
        TaskTool.execute(undoParams);
        assertEquals("v0", Files.readString(file));
    }

    @Test
    void testExternalChange_AfterFileCreatedByTool() throws Exception {
        // Создаём файл через инструмент
        ObjectNode createParams = mapper.createObjectNode();
        createParams.put("action", "create");
        createParams.put("path", "created.txt");
        createParams.put("content", "created by tool");
        manageTool.execute(createParams);

        Path file = tempDir.resolve("created.txt");

        // Читаем файл
        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "created.txt");
        readParams.put("startLine", 1);
        readTool.execute(readParams);

        // Внешнее изменение
        Files.writeString(file, "externally modified");

        // Чтение обнаруживает изменение
        JsonNode result = readTool.execute(readParams);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("EXTERNAL CHANGE DETECTED"));
    }

    // ==================== Граничные случаи ====================

    @Test
    void testExternalChange_ToEmptyFile() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "original content");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "test.txt");
        readParams.put("startLine", 1);
        readTool.execute(readParams);

        // Внешнее изменение - файл становится пустым
        Files.writeString(file, "");

        JsonNode result = readTool.execute(readParams);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("EXTERNAL CHANGE DETECTED"));
    }

    @Test
    void testExternalChange_FromEmptyFile() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "empty.txt");
        readParams.put("startLine", 1);
        readParams.put("endLine", 1);
        readTool.execute(readParams);

        // Внешнее изменение - файл получает контент
        Files.writeString(file, "now has content");

        JsonNode result = readTool.execute(readParams);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("EXTERNAL CHANGE DETECTED"));
    }

    @Test
    void testMultipleFilesExternalChanges() throws Exception {
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "content1");
        Files.writeString(file2, "content2");

        // Читаем оба файла
        ObjectNode readParams1 = mapper.createObjectNode();
        readParams1.put("path", "file1.txt");
        readParams1.put("startLine", 1);
        readTool.execute(readParams1);

        ObjectNode readParams2 = mapper.createObjectNode();
        readParams2.put("path", "file2.txt");
        readParams2.put("startLine", 1);
        readTool.execute(readParams2);

        // Внешнее изменение обоих файлов
        Files.writeString(file1, "modified1");
        Files.writeString(file2, "modified2");

        // Чтение обнаруживает оба изменения
        JsonNode result1 = readTool.execute(readParams1);
        JsonNode result2 = readTool.execute(readParams2);

        assertTrue(result1.get("content").get(0).get("text").asText().contains("EXTERNAL CHANGE"));
        assertTrue(result2.get("content").get(0).get("text").asText().contains("EXTERNAL CHANGE"));

        // В журнале должно быть два внешних изменения
        ObjectNode journalParams = mapper.createObjectNode();
        journalParams.put("action", "journal");
        JsonNode journal = TaskTool.execute(journalParams);
        String journalText = journal.get("content").get(0).get("text").asText();

        // Считаем количество вхождений [EXTERNAL]
        int count = 0;
        int idx = 0;
        while ((idx = journalText.indexOf("[EXTERNAL]", idx)) != -1) {
            count++;
            idx++;
        }
        assertEquals(2, count);
    }

    @Test
    void testForceReadIgnoresExternalChangeForResponseButStillRecords() throws Exception {
        Path file = tempDir.resolve("force.txt");
        Files.writeString(file, "original");

        ObjectNode readParams = mapper.createObjectNode();
        readParams.put("path", "force.txt");
        readParams.put("startLine", 1);
        readTool.execute(readParams);

        // Внешнее изменение
        Files.writeString(file, "modified");

        // Чтение с force=true все равно обнаружит и запишет внешнее изменение
        readParams.put("force", true);
        JsonNode result = readTool.execute(readParams);
        String text = result.get("content").get(0).get("text").asText();

        // Изменение всё равно должно быть обнаружено и записано
        assertTrue(text.contains("EXTERNAL CHANGE DETECTED"));
    }
}
