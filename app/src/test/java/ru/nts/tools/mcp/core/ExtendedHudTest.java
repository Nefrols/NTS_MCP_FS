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

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedHudTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        // Полностью сбрасываем все сессии для чистого состояния
        SessionContext.resetAll();
        // Устанавливаем стабильную сессию для всех тестов
        SessionContext ctx = SessionContext.getOrCreate("test-session");
        SessionContext.setCurrent(ctx);
    }

    @Test
    void testHudWithStats() throws Exception {
        SessionContext ctx = SessionContext.current();

        // Создаем TODO файл в директории сессии
        Path todoDir = ctx.getTodosDir();
        Files.createDirectories(todoDir);
        Files.writeString(todoDir.resolve("TODO_1.md"), "# TODO: Test Plan\n- [x] Task 1\n- [ ] Task 2");
        TodoManager.setSessionTodo("TODO_1.md");

        // Эмулируем активность - регистрируем доступ к файлу
        Path file = tempDir.resolve("work.txt");
        Files.writeString(file, "old");

        // Регистрируем чтение и получаем токен
        String rangeContent = buildRangeContent(Files.readString(file), 1, 1);
        LineAccessToken token = LineAccessTracker.registerAccess(file, 1, 1, rangeContent, 1);

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

        // Новый формат HUD: [HUD sid:test-session] Test Plan [✓1 ○1] → #2: Task 2 | Session: 1 edits, 0 undos | Unlocked: 1 files
        assertTrue(hudStr.contains("sid:test-session"), "Should contain session ID: " + hudStr);
        assertTrue(hudStr.contains("Test Plan"), "Should contain plan title: " + hudStr);
        assertTrue(hudStr.contains("✓1"), "Should show 1 completed task");
        assertTrue(hudStr.contains("○1"), "Should show 1 pending task");
        assertTrue(hudStr.contains("Session: 1 edits"), "Should show edit count");
        assertTrue(hudStr.contains("Unlocked: 1 files"), "Should show unlocked files");
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
}
