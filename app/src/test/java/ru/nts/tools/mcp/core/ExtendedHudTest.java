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
import ru.nts.tools.mcp.tools.editing.EditFileTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedHudTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        PathSanitizer.setTaskRoot(tempDir);
        // Полностью сбрасываем все таски для чистого состояния
        TaskContext.resetAll();
        TaskContext.setForceInMemoryDb(true);
        // Устанавливаем стабильный таск для всех тестов
        TaskContext ctx = TaskContext.getOrCreate("test-task");
        TaskContext.setCurrent(ctx);
    }

    @Test
    void testHudWithStats() throws Exception {
        TaskContext ctx = TaskContext.current();

        // Создаем TODO файл в директории таска
        Path todoDir = ctx.getTodosDir();
        Files.createDirectories(todoDir);
        Files.writeString(todoDir.resolve("TODO_1.md"), "# TODO: Test Plan\n- [x] Task 1\n- [ ] Task 2");
        TodoManager.setTaskTodo("TODO_1.md");

        // Эмулируем активность - регистрируем доступ к файлу
        Path file = tempDir.resolve("work.txt");
        Files.writeString(file, "old");

        // Регистрируем чтение и получаем токен
        String rangeContent = buildRangeContent(Files.readString(file), 1, 1);
        long fileCrc = LineAccessToken.computeRangeCrc(Files.readString(file));
        LineAccessToken token = LineAccessTracker.registerAccess(file, 1, 1, rangeContent, 1, fileCrc);

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

        // Новый формат HUD: [HUD tid:test-task] Test Plan [✓1 ○1] → #2: Task 2 | Task: 1 edits, 0 undos | Unlocked: 1 files
        assertTrue(hudStr.contains("tid:test-task"), "Should contain task ID: " + hudStr);
        assertTrue(hudStr.contains("Test Plan"), "Should contain plan title: " + hudStr);
        assertTrue(hudStr.contains("✓1"), "Should show 1 completed task");
        assertTrue(hudStr.contains("○1"), "Should show 1 pending task");
        assertTrue(hudStr.contains("Task: 1 edits"), "Should show edit count");
        assertTrue(hudStr.contains("Unlocked: 1 files"), "Should show unlocked files");
    }

    @Test
    void testHudShowsFocusHint() throws Exception {
        TaskContext ctx = TaskContext.current();
        Path todoDir = ctx.getTodosDir();
        Files.createDirectories(todoDir);
        // 2 pending tasks — should show FOCUS for #1
        Files.writeString(todoDir.resolve("TODO_focus.md"),
                "# TODO: Focus Test\n- [ ] First important task\n- [ ] Second task");
        TodoManager.setTaskTodo("TODO_focus.md");

        TodoManager.HudInfo hud = TodoManager.getHudInfo();
        String hudStr = hud.toString();

        assertTrue(hudStr.contains("[FOCUS:"), "Should contain FOCUS hint: " + hudStr);
        assertTrue(hudStr.contains("#1"), "Should reference task #1: " + hudStr);
        assertTrue(hudStr.contains("First important task"), "Should contain task text: " + hudStr);
    }

    @Test
    void testHudShowsVerifyHint() throws Exception {
        // Set up TipFilter to allow nts_verify
        TipFilter.setCurrentAllowedTools(java.util.Set.of("nts_verify", "nts_edit_file", "nts_task"));

        try {
            // Simulate 4 edits without verification by calling commit multiple times
            for (int i = 0; i < 4; i++) {
                TransactionManager.startTransaction("edit " + i);
                Path file = tempDir.resolve("file" + i + ".txt");
                Files.writeString(file, "content " + i);
                TransactionManager.backup(file);
                TransactionManager.commit();
            }

            TodoManager.HudInfo hud = TodoManager.getHudInfo();
            String hudStr = hud.toString();

            assertTrue(hudStr.contains("edits without verification"),
                    "Should show verify hint after 4 edits. HUD: " + hudStr);
            assertTrue(hudStr.contains("nts_verify"),
                    "Should mention nts_verify. HUD: " + hudStr);
        } finally {
            TipFilter.clear();
        }
    }

    @Test
    void testHudVerifyCounterResets() throws Exception {
        TipFilter.setCurrentAllowedTools(java.util.Set.of("nts_verify", "nts_edit_file"));

        try {
            // 4 edits
            for (int i = 0; i < 4; i++) {
                TransactionManager.startTransaction("edit " + i);
                Path file = tempDir.resolve("r" + i + ".txt");
                Files.writeString(file, "c" + i);
                TransactionManager.backup(file);
                TransactionManager.commit();
            }

            // Now reset verify counter (simulates nts_verify call)
            TransactionManager.resetVerifyCounter();

            TodoManager.HudInfo hud = TodoManager.getHudInfo();
            String hudStr = hud.toString();

            assertFalse(hudStr.contains("edits without verification"),
                    "After verify counter reset, no verify hint should appear. HUD: " + hudStr);
        } finally {
            TipFilter.clear();
        }
    }

    @Test
    void testHudUndoTip_hiddenWhenNotAllowed() throws Exception {
        // Scout role: nts_task NOT allowed
        TipFilter.setCurrentAllowedTools(java.util.Set.of("nts_file_read", "nts_file_search"));

        try {
            // 5 edits to trigger undo reminder
            for (int i = 0; i < 5; i++) {
                TransactionManager.startTransaction("edit " + i);
                Path file = tempDir.resolve("u" + i + ".txt");
                Files.writeString(file, "x" + i);
                TransactionManager.backup(file);
                TransactionManager.commit();
            }

            TodoManager.HudInfo hud = TodoManager.getHudInfo();
            String hudStr = hud.toString();

            assertFalse(hudStr.contains("nts_task"),
                    "Should NOT mention nts_task when not in allowed tools. HUD: " + hudStr);
        } finally {
            TipFilter.clear();
        }
    }

    @Test
    void testHudVerifyHint_hiddenWhenNotAllowed() throws Exception {
        // Role without nts_verify access
        TipFilter.setCurrentAllowedTools(java.util.Set.of("nts_file_read", "nts_task"));

        try {
            for (int i = 0; i < 4; i++) {
                TransactionManager.startTransaction("edit " + i);
                Path file = tempDir.resolve("v" + i + ".txt");
                Files.writeString(file, "z" + i);
                TransactionManager.backup(file);
                TransactionManager.commit();
            }

            TodoManager.HudInfo hud = TodoManager.getHudInfo();
            String hudStr = hud.toString();

            assertFalse(hudStr.contains("nts_verify"),
                    "Should NOT mention nts_verify when not in allowed tools. HUD: " + hudStr);
        } finally {
            TipFilter.clear();
        }
    }

    /**
     * Извлекает чистое содержимое диапазона строк (без номеров строк).
     * Консистентно с extractRawContent в FileReadTool/EditFileTool.
     */
    private String buildRangeContent(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.length, endLine);
        for (int i = start; i < end; i++) {
            if (i > start) sb.append("\n");
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
