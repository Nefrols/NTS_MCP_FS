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
package ru.nts.tools.mcp.tools.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.McpServer;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;

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
        SessionContext.resetAll();
        initTool = new InitTool();
        mapper = new ObjectMapper();
    }

    @Test
    void testRequiresSessionReturnsFalse() {
        assertFalse(initTool.requiresSession(), "InitTool should not require session");
    }

    @Test
    void testExecuteCreatesSession() throws Exception {
        JsonNode params = mapper.createObjectNode();
        JsonNode result = initTool.execute(params);

        // Проверяем структуру ответа
        assertTrue(result.has("content"), "Result should have content");
        JsonNode content = result.get("content");
        assertTrue(content.isArray(), "Content should be array");
        assertTrue(content.size() >= 2, "Content should have at least 2 elements");

        // Проверяем текстовое сообщение
        String text = content.get(0).get("text").asText();
        assertTrue(text.contains("Session initialized successfully"), "Should contain success message");
        assertTrue(text.contains("SESSION ID:"), "Should contain session ID");
        assertTrue(text.contains("sessionId"), "Should explain how to use sessionId");
    }

    @Test
    void testExecuteCreatesSessionDirectories() throws Exception {
        JsonNode params = mapper.createObjectNode();
        initTool.execute(params);

        SessionContext ctx = SessionContext.current();
        assertNotNull(ctx, "Session context should be set");

        Path todosDir = ctx.getTodosDir();
        Path snapshotsDir = ctx.getSnapshotsDir();

        assertTrue(Files.exists(todosDir), "Todos directory should be created");
        assertTrue(Files.exists(snapshotsDir), "Snapshots directory should be created");
    }

    @Test
    void testExecuteRegistersValidSession() throws Exception {
        JsonNode params = mapper.createObjectNode();
        initTool.execute(params);

        SessionContext ctx = SessionContext.current();
        assertNotNull(ctx, "Session context should be set");

        String sessionId = ctx.getSessionId();
        assertTrue(McpServer.isValidSession(sessionId), "Session should be registered as valid");
    }

    @Test
    void testMultipleInitCreatesMultipleSessions() throws Exception {
        JsonNode params = mapper.createObjectNode();

        // Первый вызов
        initTool.execute(params);
        SessionContext ctx1 = SessionContext.current();
        String sessionId1 = ctx1.getSessionId();

        // Сбрасываем текущий контекст для имитации нового запроса
        SessionContext.clearCurrent();

        // Второй вызов
        initTool.execute(params);
        SessionContext ctx2 = SessionContext.current();
        String sessionId2 = ctx2.getSessionId();

        // Проверяем что это разные сессии
        assertNotEquals(sessionId1, sessionId2, "Each init should create unique session");
        assertTrue(McpServer.isValidSession(sessionId1), "First session should be valid");
        assertTrue(McpServer.isValidSession(sessionId2), "Second session should be valid");
    }
}
