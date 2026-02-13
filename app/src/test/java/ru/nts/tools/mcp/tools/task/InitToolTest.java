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
package ru.nts.tools.mcp.tools.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.McpServer;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InitToolTest {

    @TempDir
    Path tempDir;

    private InitTool initTool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TaskContext.resetAll();
        initTool = new InitTool();
        mapper = new ObjectMapper();
    }

    @Test
    void testRequiresTaskReturnsFalse() {
        assertFalse(initTool.requiresTask(), "InitTool should not require task");
    }

    @Test
    void testExecuteCreatesTask() throws Exception {
        JsonNode params = mapper.createObjectNode();
        JsonNode result = initTool.execute(params);

        // Проверяем структуру ответа
        assertTrue(result.has("content"), "Result should have content");
        JsonNode content = result.get("content");
        assertTrue(content.isArray(), "Content should be array");
        assertTrue(content.size() >= 2, "Content should have at least 2 elements");

        // Проверяем текстовое сообщение
        String text = content.get(0).get("text").asText();
        assertTrue(text.contains("Task initialized successfully"), "Should contain success message");
        assertTrue(text.contains("TASK ID:"), "Should contain task ID");
        assertTrue(text.contains("taskId"), "Should explain how to use taskId");
    }

    @Test
    void testExecuteCreatesTaskDirectories() throws Exception {
        JsonNode params = mapper.createObjectNode();
        initTool.execute(params);

        TaskContext ctx = TaskContext.current();
        assertNotNull(ctx, "Task context should be set");

        Path todosDir = ctx.getTodosDir();

        assertTrue(Files.exists(todosDir), "Todos directory should be created");
    }

    @Test
    void testExecuteRegistersValidTask() throws Exception {
        JsonNode params = mapper.createObjectNode();
        initTool.execute(params);

        TaskContext ctx = TaskContext.current();
        assertNotNull(ctx, "Task context should be set");

        String taskId = ctx.getTaskId();
        assertTrue(McpServer.isValidTask(taskId), "Task should be registered as valid");
    }

    @Test
    void testMultipleInitCreatesMultipleTasks() throws Exception {
        JsonNode params = mapper.createObjectNode();

        // Первый вызов
        initTool.execute(params);
        TaskContext ctx1 = TaskContext.current();
        String taskId1 = ctx1.getTaskId();

        // Сбрасываем текущий контекст для имитации нового запроса
        TaskContext.clearCurrent();

        // Второй вызов
        initTool.execute(params);
        TaskContext ctx2 = TaskContext.current();
        String taskId2 = ctx2.getTaskId();

        // Проверяем что это разные сессии
        assertNotEquals(taskId1, taskId2, "Each init should create unique task");
        assertTrue(McpServer.isValidTask(taskId1), "First Task should be valid");
        assertTrue(McpServer.isValidTask(taskId2), "Second Task should be valid");
    }
}
