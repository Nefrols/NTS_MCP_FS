package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.EditFileTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExtendedHudTest {

    @Test
    void testHudWithStats(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset();

        // Создаем TODO файл
        Path todoDir = tempDir.resolve(".nts/todos");
        Files.createDirectories(todoDir);
        Files.writeString(todoDir.resolve("TODO_1.md"), "# TODO: Test Plan\n- [x] Task 1\n- [ ] Task 2");

        // Эмулируем активность
        Path file = tempDir.resolve("work.txt");
        Files.writeString(file, "old");
        AccessTracker.registerRead(file);

        EditFileTool editTool = new EditFileTool();
        var params = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        params.put("path", "work.txt");
        params.put("oldText", "old");
        params.put("newText", "new");
        editTool.execute(params);

        TodoManager.HudInfo hud = TodoManager.getHudInfo();
        String hudStr = hud.toString();

        assertTrue(hudStr.contains("Plan: Test Plan"));
        assertTrue(hudStr.contains("Progress: 1/2"));
        assertTrue(hudStr.contains("Session: 1 edits"));
        assertTrue(hudStr.contains("Unlocked: 1 files"));
    }
}
