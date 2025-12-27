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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Модульные тесты для ExternalChangeTracker.
 * Тестирует регистрацию снапшотов, обнаружение внешних изменений,
 * обновление и перемещение снапшотов.
 */
class ExternalChangeTrackerTest {

    private ExternalChangeTracker tracker;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        tracker = new ExternalChangeTracker();
    }

    // ==================== Тесты регистрации снапшотов ====================

    @Test
    void testRegisterSnapshot_BasicRegistration() throws Exception {
        Path file = tempDir.resolve("test.txt");
        String content = "Hello, World!";
        long crc = 12345L;
        int lineCount = 1;

        tracker.registerSnapshot(file, content, crc, StandardCharsets.UTF_8, lineCount);

        assertTrue(tracker.hasSnapshot(file));
        assertEquals(1, tracker.getTrackedFilesCount());

        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(file);
        assertNotNull(snapshot);
        assertEquals(content, snapshot.content());
        assertEquals(crc, snapshot.crc32c());
        assertEquals(lineCount, snapshot.lineCount());
        assertEquals(StandardCharsets.UTF_8, snapshot.charset());
    }

    @Test
    void testRegisterSnapshot_OverwritesPreviousSnapshot() throws Exception {
        Path file = tempDir.resolve("test.txt");

        tracker.registerSnapshot(file, "Old content", 100L, StandardCharsets.UTF_8, 1);
        tracker.registerSnapshot(file, "New content", 200L, StandardCharsets.UTF_8, 2);

        assertEquals(1, tracker.getTrackedFilesCount());
        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(file);
        assertEquals("New content", snapshot.content());
        assertEquals(200L, snapshot.crc32c());
        assertEquals(2, snapshot.lineCount());
    }

    @Test
    void testRegisterSnapshot_NormalizesPath() throws Exception {
        Path file1 = tempDir.resolve("subdir/../test.txt");
        Path file2 = tempDir.resolve("test.txt");

        tracker.registerSnapshot(file1, "content", 100L, StandardCharsets.UTF_8, 1);

        assertTrue(tracker.hasSnapshot(file2));
        assertEquals(1, tracker.getTrackedFilesCount());
    }

    @Test
    void testRegisterSnapshot_MultipleFiles() throws Exception {
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Path file3 = tempDir.resolve("sub/file3.txt");

        tracker.registerSnapshot(file1, "content1", 100L, StandardCharsets.UTF_8, 1);
        tracker.registerSnapshot(file2, "content2", 200L, StandardCharsets.UTF_8, 2);
        tracker.registerSnapshot(file3, "content3", 300L, StandardCharsets.UTF_8, 3);

        assertEquals(3, tracker.getTrackedFilesCount());
        assertTrue(tracker.hasSnapshot(file1));
        assertTrue(tracker.hasSnapshot(file2));
        assertTrue(tracker.hasSnapshot(file3));
    }

    // ==================== Тесты обнаружения внешних изменений ====================

    @Test
    void testCheckForExternalChange_NoSnapshotReturnsNoChange() throws Exception {
        Path file = tempDir.resolve("test.txt");

        var result = tracker.checkForExternalChange(file, 12345L, "content", StandardCharsets.UTF_8, 1);

        assertFalse(result.hasExternalChange());
        assertNull(result.previousSnapshot());
    }

    @Test
    void testCheckForExternalChange_SameCrcReturnsNoChange() throws Exception {
        Path file = tempDir.resolve("test.txt");
        long crc = 12345L;

        tracker.registerSnapshot(file, "content", crc, StandardCharsets.UTF_8, 1);
        var result = tracker.checkForExternalChange(file, crc, "content", StandardCharsets.UTF_8, 1);

        assertFalse(result.hasExternalChange());
    }

    @Test
    void testCheckForExternalChange_DifferentCrcReturnsChange() throws Exception {
        Path file = tempDir.resolve("test.txt");

        tracker.registerSnapshot(file, "old content", 100L, StandardCharsets.UTF_8, 1);
        var result = tracker.checkForExternalChange(file, 200L, "new content", StandardCharsets.UTF_8, 2);

        assertTrue(result.hasExternalChange());
        assertNotNull(result.previousSnapshot());
        assertEquals("old content", result.previousSnapshot().content());
        assertEquals(100L, result.previousSnapshot().crc32c());
        assertEquals("new content", result.currentContent());
        assertEquals(200L, result.currentCrc());
        assertEquals(2, result.currentLineCount());
        assertNotNull(result.changeDescription());
        assertTrue(result.changeDescription().contains("External modification detected"));
    }

    @Test
    void testCheckForExternalChange_LineCountChangeOnly() throws Exception {
        Path file = tempDir.resolve("test.txt");

        // Регистрируем с одним количеством строк
        tracker.registerSnapshot(file, "line1", 100L, StandardCharsets.UTF_8, 1);

        // Проверяем с тем же CRC - не должно быть изменения (CRC главный индикатор)
        var result = tracker.checkForExternalChange(file, 100L, "line1", StandardCharsets.UTF_8, 5);

        assertFalse(result.hasExternalChange());
    }

    // ==================== Тесты обновления снапшотов ====================

    @Test
    void testUpdateSnapshot_UpdatesExistingSnapshot() throws Exception {
        Path file = tempDir.resolve("test.txt");

        tracker.registerSnapshot(file, "old", 100L, StandardCharsets.UTF_8, 1);
        tracker.updateSnapshot(file, "new", 200L, StandardCharsets.UTF_8, 2);

        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(file);
        assertEquals("new", snapshot.content());
        assertEquals(200L, snapshot.crc32c());
        assertEquals(2, snapshot.lineCount());
    }

    @Test
    void testUpdateSnapshot_CreatesNewIfNotExists() throws Exception {
        Path file = tempDir.resolve("test.txt");

        tracker.updateSnapshot(file, "content", 100L, StandardCharsets.UTF_8, 1);

        assertTrue(tracker.hasSnapshot(file));
        assertEquals("content", tracker.getSnapshot(file).content());
    }

    // ==================== Тесты перемещения снапшотов ====================

    @Test
    void testMoveSnapshot_MovesSnapshotToNewPath() throws Exception {
        Path oldPath = tempDir.resolve("old.txt");
        Path newPath = tempDir.resolve("new.txt");

        tracker.registerSnapshot(oldPath, "content", 100L, StandardCharsets.UTF_8, 1);
        tracker.moveSnapshot(oldPath, newPath);

        assertFalse(tracker.hasSnapshot(oldPath));
        assertTrue(tracker.hasSnapshot(newPath));
        assertEquals("content", tracker.getSnapshot(newPath).content());
        assertEquals(1, tracker.getTrackedFilesCount());
    }

    @Test
    void testMoveSnapshot_NoOpIfNoSnapshot() throws Exception {
        Path oldPath = tempDir.resolve("old.txt");
        Path newPath = tempDir.resolve("new.txt");

        tracker.moveSnapshot(oldPath, newPath);

        assertFalse(tracker.hasSnapshot(oldPath));
        assertFalse(tracker.hasSnapshot(newPath));
        assertEquals(0, tracker.getTrackedFilesCount());
    }

    @Test
    void testMoveSnapshot_PreservesAllMetadata() throws Exception {
        Path oldPath = tempDir.resolve("old.txt");
        Path newPath = tempDir.resolve("new.txt");
        String content = "multiline\ncontent\nhere";

        tracker.registerSnapshot(oldPath, content, 12345L, StandardCharsets.ISO_8859_1, 3);
        tracker.moveSnapshot(oldPath, newPath);

        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(newPath);
        assertEquals(content, snapshot.content());
        assertEquals(12345L, snapshot.crc32c());
        assertEquals(3, snapshot.lineCount());
        // Note: charset не сохраняется при move (используется из оригинала)
    }

    // ==================== Тесты удаления снапшотов ====================

    @Test
    void testRemoveSnapshot_RemovesExistingSnapshot() throws Exception {
        Path file = tempDir.resolve("test.txt");

        tracker.registerSnapshot(file, "content", 100L, StandardCharsets.UTF_8, 1);
        tracker.removeSnapshot(file);

        assertFalse(tracker.hasSnapshot(file));
        assertEquals(0, tracker.getTrackedFilesCount());
    }

    @Test
    void testRemoveSnapshot_NoOpIfNotExists() throws Exception {
        Path file = tempDir.resolve("test.txt");

        tracker.removeSnapshot(file);

        assertFalse(tracker.hasSnapshot(file));
        assertEquals(0, tracker.getTrackedFilesCount());
    }

    // ==================== Тесты сброса ====================

    @Test
    void testReset_ClearsAllSnapshots() throws Exception {
        tracker.registerSnapshot(tempDir.resolve("file1.txt"), "c1", 100L, StandardCharsets.UTF_8, 1);
        tracker.registerSnapshot(tempDir.resolve("file2.txt"), "c2", 200L, StandardCharsets.UTF_8, 2);
        tracker.registerSnapshot(tempDir.resolve("file3.txt"), "c3", 300L, StandardCharsets.UTF_8, 3);

        assertEquals(3, tracker.getTrackedFilesCount());

        tracker.reset();

        assertEquals(0, tracker.getTrackedFilesCount());
    }

    // ==================== Тесты FileSnapshot.isChanged ====================

    @Test
    void testFileSnapshot_isChanged_ReturnsTrueForDifferentCrc() throws Exception {
        Path file = tempDir.resolve("test.txt");
        tracker.registerSnapshot(file, "content", 100L, StandardCharsets.UTF_8, 1);

        ExternalChangeTracker.FileSnapshot snapshot = tracker.getSnapshot(file);

        assertTrue(snapshot.isChanged(200L));
        assertFalse(snapshot.isChanged(100L));
    }

    // ==================== Граничные случаи ====================

    @Test
    void testEmptyContent() throws Exception {
        Path file = tempDir.resolve("empty.txt");

        tracker.registerSnapshot(file, "", 0L, StandardCharsets.UTF_8, 0);

        assertTrue(tracker.hasSnapshot(file));
        assertEquals("", tracker.getSnapshot(file).content());
    }

    @Test
    void testLargeContent() throws Exception {
        Path file = tempDir.resolve("large.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("Line ").append(i).append("\n");
        }
        String content = sb.toString();

        tracker.registerSnapshot(file, content, 999L, StandardCharsets.UTF_8, 10000);

        assertEquals(content, tracker.getSnapshot(file).content());
        assertEquals(10000, tracker.getSnapshot(file).lineCount());
    }

    @Test
    void testUnicodeContent() throws Exception {
        Path file = tempDir.resolve("unicode.txt");
        String content = "Привет мир! 你好世界! 🌍🌎🌏";

        tracker.registerSnapshot(file, content, 12345L, StandardCharsets.UTF_8, 1);

        assertEquals(content, tracker.getSnapshot(file).content());
    }

    @Test
    void testConcurrentAccess() throws Exception {
        // Тест на потокобезопасность - ConcurrentHashMap должен справляться
        Path file = tempDir.resolve("concurrent.txt");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                tracker.registerSnapshot(file, "content" + i, i, StandardCharsets.UTF_8, 1);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                tracker.getSnapshot(file);
                tracker.hasSnapshot(file);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Не должно быть исключений, и снапшот должен существовать
        assertTrue(tracker.hasSnapshot(file));
    }
}
