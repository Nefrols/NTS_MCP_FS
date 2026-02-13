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
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.task.TaskTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для записи внешних изменений и их undo/redo.
 * Тестирует TaskTransactionManager.recordExternalChange() и связанную функциональность.
 */
class ExternalChangeTransactionTest {

    private TaskTool taskTool;
    private ObjectMapper mapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        LineAccessTracker.reset();
        TaskContext.resetAll();

        taskTool = new TaskTool();
        mapper = new ObjectMapper();
    }

    // ==================== Тесты записи внешних изменений ====================

    @Test
    void testRecordExternalChange_AddsToUndoStack() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "new content");

        TransactionManager.recordExternalChange(
            file,
            "old content",
            100L,
            200L,
            "External change: test.txt"
        );

        // Проверяем, что изменение отображается в журнале
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "journal");
        JsonNode result = taskTool.execute(params);
        String journal = result.get("content").get(0).get("text").asText();

        assertTrue(journal.contains("[EXTERNAL]"));
        assertTrue(journal.contains("External change: test.txt"));
    }

    @Test
    void testRecordExternalChange_ShowsCrcInJournal() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "new content");

        TransactionManager.recordExternalChange(
            file,
            "old content",
            0x12345678L,
            0xABCDEF00L,
            "External change: test.txt"
        );

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "journal");
        JsonNode result = taskTool.execute(params);
        String journal = result.get("content").get(0).get("text").asText();

        assertTrue(journal.contains("12345678"));
        assertTrue(journal.contains("ABCDEF00") || journal.contains("abcdef00"));
        assertTrue(journal.contains("external modification"));
    }

    // ==================== Тесты undo внешних изменений ====================

    @Test
    void testUndoExternalChange_RestoresOriginalContent() throws Exception {
        Path file = tempDir.resolve("test.txt");
        String originalContent = "original content here";
        String modifiedContent = "externally modified content";

        Files.writeString(file, modifiedContent);

        // Записываем внешнее изменение
        TransactionManager.recordExternalChange(
            file,
            originalContent,
            100L,
            200L,
            "External change: test.txt"
        );

        // Выполняем undo
        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        JsonNode undoResult = taskTool.execute(undoParams);
        String undoMsg = undoResult.get("content").get(0).get("text").asText();

        // Проверяем сообщение
        assertTrue(undoMsg.contains("Undone external change") || undoMsg.contains("External change"));

        // Проверяем, что файл восстановлен
        String restoredContent = Files.readString(file);
        assertEquals(originalContent, restoredContent);
    }

    @Test
    void testUndoExternalChange_MultipleExternalChanges() throws Exception {
        Path file = tempDir.resolve("test.txt");

        // Симулируем три последовательных внешних изменения
        Files.writeString(file, "v3");
        TransactionManager.recordExternalChange(file, "v0", 100L, 101L, "Change 1");

        Files.writeString(file, "v3");
        TransactionManager.recordExternalChange(file, "v1", 101L, 102L, "Change 2");

        Files.writeString(file, "v3");
        TransactionManager.recordExternalChange(file, "v2", 102L, 103L, "Change 3");

        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");

        // Откатываем все три изменения
        taskTool.execute(undoParams);
        assertEquals("v2", Files.readString(file));

        taskTool.execute(undoParams);
        assertEquals("v1", Files.readString(file));

        taskTool.execute(undoParams);
        assertEquals("v0", Files.readString(file));
    }

    // ==================== Тесты redo внешних изменений ====================

    @Test
    void testRedoExternalChange_AfterUndo() throws Exception {
        Path file = tempDir.resolve("test.txt");
        String originalContent = "original";
        String modifiedContent = "modified by external tool";

        Files.writeString(file, modifiedContent);
        TransactionManager.recordExternalChange(file, originalContent, 100L, 200L, "External change");

        // Undo
        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);
        assertEquals(originalContent, Files.readString(file));

        // Redo
        ObjectNode redoParams = mapper.createObjectNode();
        redoParams.put("action", "redo");
        taskTool.execute(redoParams);

        // После redo файл должен содержать то, что было после внешнего изменения
        // (т.е. то, что было на диске на момент undo)
        String content = Files.readString(file);
        assertNotEquals(originalContent, content);
    }

    // ==================== Тесты истории файла ====================

    @Test
    void testFileHistory_IncludesExternalChanges() throws Exception {
        Path file = tempDir.resolve("history_test.txt");
        Files.writeString(file, "content");

        TransactionManager.recordExternalChange(
            file, "old", 100L, 200L, "Linter formatted the file"
        );

        var history = TransactionManager.getFileHistory(file);

        assertFalse(history.isEmpty());
        assertTrue(history.stream().anyMatch(h -> h.contains("[EXTERNAL]")));
        assertTrue(history.stream().anyMatch(h -> h.contains("Linter formatted the file")));
    }

    // ==================== Тесты смешанных транзакций ====================

    @Test
    void testMixedTransactions_ExternalAndNormal() throws Exception {
        Path file = tempDir.resolve("mixed.txt");
        Files.writeString(file, "init");

        // Обычная транзакция
        TransactionManager.startTransaction("Normal edit");
        TransactionManager.backup(file);
        Files.writeString(file, "after edit");
        TransactionManager.commit();

        // Внешнее изменение
        Files.writeString(file, "external modification");
        TransactionManager.recordExternalChange(file, "after edit", 100L, 200L, "External");

        // Проверяем журнал - оба должны быть
        ObjectNode params = mapper.createObjectNode();
        params.put("action", "journal");
        JsonNode result = taskTool.execute(params);
        String journal = result.get("content").get(0).get("text").asText();

        assertTrue(journal.contains("Normal edit"));
        assertTrue(journal.contains("[EXTERNAL]"));

        // Undo внешнего изменения
        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);
        assertEquals("after edit", Files.readString(file));

        // Undo обычной транзакции
        taskTool.execute(undoParams);
        assertEquals("init", Files.readString(file));
    }

    // ==================== Граничные случаи ====================

    @Test
    void testRecordExternalChange_EmptyContent() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "not empty");

        TransactionManager.recordExternalChange(file, "", 0L, 100L, "File was emptied");

        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);

        assertEquals("", Files.readString(file));
    }

    @Test
    void testRecordExternalChange_LargeContent() throws Exception {
        Path file = tempDir.resolve("large.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Line ").append(i).append("\n");
        }
        String largeContent = sb.toString();

        Files.writeString(file, "small");
        TransactionManager.recordExternalChange(file, largeContent, 100L, 200L, "Large content");

        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);

        assertEquals(largeContent, Files.readString(file));
    }

    @Test
    void testRecordExternalChange_UnicodeContent() throws Exception {
        Path file = tempDir.resolve("unicode.txt");
        String unicodeContent = "Привет! 你好! مرحبا! 🎉🎊";

        Files.writeString(file, "ascii");
        TransactionManager.recordExternalChange(file, unicodeContent, 100L, 200L, "Unicode");

        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);

        assertEquals(unicodeContent, Files.readString(file));
    }

    @Test
    void testRecordExternalChange_FileInSubdirectory() throws Exception {
        Path subdir = tempDir.resolve("sub/dir/deep");
        Files.createDirectories(subdir);
        Path file = subdir.resolve("test.txt");
        Files.writeString(file, "new");

        TransactionManager.recordExternalChange(file, "old", 100L, 200L, "Deep file");

        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);

        assertEquals("old", Files.readString(file));
    }

    // ==================== Тесты redo stack invalidation ====================

    @Test
    void testNewExternalChange_ClearsRedoStack() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "v1");

        // Первое внешнее изменение
        TransactionManager.recordExternalChange(file, "v0", 100L, 101L, "Change 1");

        // Undo
        ObjectNode undoParams = mapper.createObjectNode();
        undoParams.put("action", "undo");
        taskTool.execute(undoParams);
        assertEquals("v0", Files.readString(file));

        // Второе внешнее изменение (должно очистить redo stack)
        Files.writeString(file, "v2");
        TransactionManager.recordExternalChange(file, "v0", 100L, 102L, "Change 2");

        // Попытка redo должна сообщить "нечего повторять"
        ObjectNode redoParams = mapper.createObjectNode();
        redoParams.put("action", "redo");
        JsonNode result = taskTool.execute(redoParams);
        String msg = result.get("content").get(0).get("text").asText();

        assertTrue(msg.contains("No operations to redo"));
    }
}
