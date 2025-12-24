// Aristo 25.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.editing.EditFileTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedHudTest {

    @Test
    void testHudWithStats(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        LineAccessTracker.reset();

        // Создаем TODO файл и устанавливаем его как сессионный
        Path todoDir = tempDir.resolve(".nts/todos");
        Files.createDirectories(todoDir);
        Files.writeString(todoDir.resolve("TODO_1.md"), "# TODO: Test Plan\n- [x] Task 1\n- [ ] Task 2");
        TodoManager.setSessionTodo("TODO_1.md");

        // Эмулируем активность - регистрируем доступ к файлу
        Path file = tempDir.resolve("work.txt");
        Files.writeString(file, "old");

        // Регистрируем чтение и получаем токен
        long crc = calculateCRC32(file);
        LineAccessToken token = LineAccessTracker.registerAccess(file, 1, 1, crc, 1);

        // Выполняем редактирование с токеном
        EditFileTool editTool = new EditFileTool();
        var params = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        params.put("path", "work.txt");
        params.put("startLine", 1);
        params.put("content", "new");
        params.put("accessToken", token.encode());
        editTool.execute(params);

        TodoManager.HudInfo hud = TodoManager.getHudInfo();
        String hudStr = hud.toString();

        // Новый формат HUD: [HUD] Test Plan [✓1 ○1] → #2: Task 2 | Session: 1 edits, 0 undos | Unlocked: 1 files
        assertTrue(hudStr.contains("Test Plan"), "Should contain plan title");
        assertTrue(hudStr.contains("✓1"), "Should show 1 completed task");
        assertTrue(hudStr.contains("○1"), "Should show 1 pending task");
        assertTrue(hudStr.contains("Session: 1 edits"), "Should show edit count");
        assertTrue(hudStr.contains("Unlocked: 1 files"), "Should show unlocked files");
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
}
