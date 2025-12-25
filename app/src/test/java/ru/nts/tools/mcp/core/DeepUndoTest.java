// Aristo 25.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для системы Deep Undo:
 * - FileLineageTracker: отслеживание файлов по ID через цепочки перемещений
 * - SmartUndoEngine: умный откат с поддержкой Path Lineage
 * - UndoResult: детальные результаты отката
 */
class DeepUndoTest {

    @TempDir
    Path tempDir;

    private FileLineageTracker lineageTracker;
    private SmartUndoEngine undoEngine;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        SessionContext.resetAll();
        lineageTracker = new FileLineageTracker();
        undoEngine = new SmartUndoEngine(lineageTracker, tempDir);
    }

    // ==================== FileLineageTracker Tests ====================

    @Test
    void testRegisterFile() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        String fileId = lineageTracker.registerFile(file);

        assertNotNull(fileId);
        assertEquals(file.toAbsolutePath().normalize(), lineageTracker.getCurrentPath(fileId));
    }

    @Test
    void testRegisterFileTwiceReturnsSameId() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        String fileId1 = lineageTracker.registerFile(file);
        String fileId2 = lineageTracker.registerFile(file);

        assertEquals(fileId1, fileId2);
    }

    @Test
    void testRecordMove() throws IOException {
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Files.writeString(fileA, "content");

        String fileId = lineageTracker.registerFile(fileA);
        lineageTracker.recordMove(fileA, fileB);

        // ID должен указывать на новый путь
        assertEquals(fileB.toAbsolutePath().normalize(), lineageTracker.getCurrentPath(fileId));

        // История должна содержать запись о перемещении
        var history = lineageTracker.getPathHistory(fileId);
        assertEquals(1, history.size());
        assertEquals(fileA.toAbsolutePath().normalize(), history.get(0).oldPath());
        assertEquals(fileB.toAbsolutePath().normalize(), history.get(0).newPath());
    }

    @Test
    void testChainedMoves() throws IOException {
        // Тест цепочки A → B → C
        Path fileA = tempDir.resolve("a.txt");
        Path fileB = tempDir.resolve("b.txt");
        Path fileC = tempDir.resolve("c.txt");
        Files.writeString(fileA, "content");

        String fileId = lineageTracker.registerFile(fileA);

        lineageTracker.recordMove(fileA, fileB);
        lineageTracker.recordMove(fileB, fileC);

        // ID должен указывать на финальный путь C
        assertEquals(fileC.toAbsolutePath().normalize(), lineageTracker.getCurrentPath(fileId));

        // История должна содержать обе записи
        var history = lineageTracker.getPathHistory(fileId);
        assertEquals(2, history.size());
    }

    @Test
    void testFindByCrc() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "unique content for crc test");

        lineageTracker.registerFile(file);

        // Получаем CRC файла
        FileLineageTracker.FileInfo info = lineageTracker.getFileInfo(
                lineageTracker.getFileId(file));

        // Поиск по CRC должен найти файл
        var found = lineageTracker.findByCrc(info.lastKnownCrc());
        assertEquals(1, found.size());
        assertEquals(file.toAbsolutePath().normalize(), found.get(0));
    }

    @Test
    void testUpdateCrc() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "original content");

        lineageTracker.registerFile(file);
        String fileId = lineageTracker.getFileId(file);
        long originalCrc = lineageTracker.getFileInfo(fileId).lastKnownCrc();

        // Изменяем файл
        Files.writeString(file, "modified content");
        lineageTracker.updateCrc(file);

        long newCrc = lineageTracker.getFileInfo(fileId).lastKnownCrc();
        assertNotEquals(originalCrc, newCrc);
    }

    // ==================== SmartUndoEngine Tests ====================

    @Test
    void testSmartUndoSuccess() throws IOException {
        // Создаём файл и бекап
        Path file = tempDir.resolve("test.txt");
        Path backup = tempDir.resolve("backup.bak");
        Files.writeString(file, "modified");
        Files.writeString(backup, "original");

        Map<Path, Path> snapshots = new HashMap<>();
        snapshots.put(file.toAbsolutePath().normalize(), backup);

        UndoResult result = undoEngine.smartUndo(snapshots, "Test undo");

        assertTrue(result.isSuccess());
        assertEquals(UndoResult.Status.SUCCESS, result.getStatus());
        assertEquals("original", Files.readString(file));
    }

    @Test
    void testSmartUndoRestoreDeleted() throws IOException {
        // Тест восстановления удалённого файла
        Path file = tempDir.resolve("deleted.txt");
        Path backup = tempDir.resolve("backup.bak");
        Files.writeString(backup, "original content");
        // file не существует

        Map<Path, Path> snapshots = new HashMap<>();
        snapshots.put(file.toAbsolutePath().normalize(), backup);

        UndoResult result = undoEngine.smartUndo(snapshots, "Restore deleted");

        assertTrue(result.isSuccess());
        assertTrue(Files.exists(file));
        assertEquals("original content", Files.readString(file));
    }

    @Test
    void testSmartUndoDeleteCreated() throws IOException {
        // Тест удаления созданного файла
        Path file = tempDir.resolve("created.txt");
        Files.writeString(file, "should be deleted");

        Map<Path, Path> snapshots = new HashMap<>();
        snapshots.put(file.toAbsolutePath().normalize(), null); // null = файл не существовал

        UndoResult result = undoEngine.smartUndo(snapshots, "Delete created");

        assertTrue(result.isSuccess());
        assertFalse(Files.exists(file));
    }

    @Test
    void testSmartUndoWithRelocatedFile() throws IOException {
        // Тест отката для перемещённого файла
        Path originalPath = tempDir.resolve("original.txt");
        Path movedPath = tempDir.resolve("subdir/moved.txt");
        Path backup = tempDir.resolve("backup.bak");

        Files.createDirectories(movedPath.getParent());
        Files.writeString(movedPath, "modified after move");
        Files.writeString(backup, "original content");

        // Регистрируем файл и записываем перемещение
        lineageTracker.registerFile(originalPath);
        String fileId = lineageTracker.getFileId(originalPath);
        lineageTracker.recordMove(originalPath, movedPath);

        Map<Path, Path> snapshots = new HashMap<>();
        snapshots.put(originalPath.toAbsolutePath().normalize(), backup);

        UndoResult result = undoEngine.smartUndo(snapshots, "Undo with relocated file");

        // Должен найти файл по новому пути и восстановить
        assertTrue(result.isSuccess() || result.getStatus() == UndoResult.Status.RESOLVED_MOVE);
    }

    @Test
    void testSmartUndoDirtyDirectory() throws IOException {
        // Тест частичного отката при dirty directory
        Path dir = tempDir.resolve("dirty-dir");
        Files.createDirectories(dir);

        // Создаём "грязный" файл в директории
        Path dirtyFile = dir.resolve("external.txt");
        Files.writeString(dirtyFile, "added externally");

        Map<Path, Path> snapshots = new HashMap<>();
        snapshots.put(dir.toAbsolutePath().normalize(), null); // Директория была создана

        UndoResult result = undoEngine.smartUndo(snapshots, "Undo with dirty dir");

        // Должен пропустить удаление непустой директории
        assertTrue(result.isPartial() || result.isSuccess());
        assertTrue(Files.exists(dirtyFile));
    }

    @Test
    void testNothingToUndo() {
        UndoResult result = undoEngine.smartUndo(new HashMap<>(), "Empty");

        assertEquals(UndoResult.Status.NOTHING_TO_UNDO, result.getStatus());
    }

    // ==================== UndoResult Tests ====================

    @Test
    void testUndoResultFormat() {
        UndoResult result = UndoResult.builder()
                .status(UndoResult.Status.SUCCESS)
                .message("Test undo completed")
                .transactionDescription("Edit file.txt")
                .addFileDetail(
                        Path.of("/test/file.txt"),
                        Path.of("/test/file.txt"),
                        "abc-123",
                        UndoResult.FileStatus.RESTORED,
                        "Content restored"
                )
                .build();

        String formatted = result.format();

        assertTrue(formatted.contains("SUCCESS"));
        assertTrue(formatted.contains("Test undo completed"));
        assertTrue(formatted.contains("Edit file.txt"));
        assertTrue(formatted.contains("[OK]"));
    }

    @Test
    void testUndoResultWithGitSuggestion() {
        UndoResult result = UndoResult.builder()
                .status(UndoResult.Status.STUCK)
                .message("Cannot undo")
                .gitSuggestion("git checkout HEAD -- file.txt")
                .build();

        String formatted = result.format();

        assertTrue(formatted.contains("STUCK"));
        assertTrue(formatted.contains("[GIT RECOVERY OPTION]"));
        assertTrue(formatted.contains("git checkout"));
    }

    @Test
    void testUndoResultStatuses() {
        assertTrue(UndoResult.success("test").isSuccess());
        assertTrue(UndoResult.nothingToUndo().getStatus() == UndoResult.Status.NOTHING_TO_UNDO);

        UndoResult partial = UndoResult.builder()
                .status(UndoResult.Status.PARTIAL)
                .message("Partial")
                .build();
        assertTrue(partial.isPartial());

        UndoResult stuck = UndoResult.builder()
                .status(UndoResult.Status.STUCK)
                .message("Stuck")
                .build();
        assertTrue(stuck.isFailed());
    }

    // ==================== Integration Tests ====================

    @Test
    void testFullUndoWorkflow() throws IOException {
        // Полный workflow: create -> edit -> move -> undo
        Path file = tempDir.resolve("workflow.txt");
        Path backup1 = tempDir.resolve("backup1.bak");
        Path backup2 = tempDir.resolve("backup2.bak");

        // Шаг 1: Создание файла
        Files.writeString(file, "initial");
        lineageTracker.registerFile(file);

        // Шаг 2: Редактирование
        Files.writeString(backup1, "initial");
        Files.writeString(file, "edited");

        // Шаг 3: Перемещение
        Path movedFile = tempDir.resolve("moved/workflow.txt");
        Files.createDirectories(movedFile.getParent());
        Files.writeString(backup2, "edited");
        Files.move(file, movedFile);
        lineageTracker.recordMove(file, movedFile);

        // Шаг 4: Undo перемещения
        Map<Path, Path> moveSnapshots = new HashMap<>();
        moveSnapshots.put(file.toAbsolutePath().normalize(), backup2);

        UndoResult result = undoEngine.smartUndo(moveSnapshots, "Undo move");

        // Файл должен быть найден по relocated path
        assertTrue(result.isSuccess() || result.getStatus() == UndoResult.Status.RESOLVED_MOVE);
    }
}
