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
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.McpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Инструмент инициализации сессии.
 *
 * ВАЖНО: Это ЕДИНСТВЕННЫЙ инструмент, который может работать без sessionId.
 * Все остальные инструменты требуют валидный sessionId, полученный от этого инструмента.
 *
 * При вызове создает новую сессию с уникальным UUID и возвращает его.
 * LLM должна сохранить этот UUID и передавать его во всех последующих запросах.
 */
public class InitTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_init";
    }

    @Override
    public String getDescription() {
        return """
            Initialize a new MCP session.

            CRITICAL: Call this FIRST before using any other nts_* tools!

            This tool creates a new isolated session with:
            • Unique session UUID (required for all other tools)
            • Session-scoped undo/redo history
            • Session-scoped file access tokens
            • Session-scoped TODO plans

            WORKFLOW:
            1. Call nts_init() → receive session UUID
            2. Store the UUID from response
            3. Pass sessionId in arguments for ALL subsequent tool calls

            PASSING SESSION ID:
            All other tools have 'sessionId' as a required parameter.
            Simply include it in the tool arguments:
            { "sessionId": "<uuid>", "path": "/some/file", ... }

            RETURNS:
            • sessionId: UUID to pass in all subsequent tool calls
            • projectRoot: Working directory path
            • message: Welcome message with session info

            NOTE: Session UUID is also shown in HUD output after every tool call.
            If you lose the UUID, check the HUD or call nts_init again.
            """;
    }

    @Override
    public String getCategory() {
        return "session";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        // No required parameters - init can be called without any args
        return schema;
    }

    /**
     * Помечает этот инструмент как не требующий сессии.
     * McpServer проверяет это перед валидацией sessionId.
     */
    @Override
    public boolean requiresSession() {
        return false;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Генерируем новый UUID для сессии
        String sessionId = UUID.randomUUID().toString();

        // Регистрируем сессию как валидную
        McpServer.registerValidSession(sessionId);

        // Создаем контекст сессии
        SessionContext ctx = SessionContext.getOrCreate(sessionId);
        SessionContext.setCurrent(ctx);

        // Создаем директории сессии
        Path sessionDir = ctx.getSessionDir();
        Files.createDirectories(ctx.getTodosDir());
        Files.createDirectories(ctx.getSnapshotsDir());

        Path projectRoot = PathSanitizer.getRoot();

        // Формируем ответ
        ObjectNode result = mapper.createObjectNode();
        var content = result.putArray("content");

        // Основное сообщение
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", String.format("""
            Session initialized successfully.

            SESSION ID: %s
            Project root: %s
            Session directory: %s

            IMPORTANT: Pass this sessionId in ALL subsequent tool calls.
            Example: { "sessionId": "%s", "path": "/file.txt", ... }

            The session UUID is also shown in the HUD output after every tool call.
            """, sessionId, projectRoot, sessionDir, sessionId));

        // Структурированные данные для машинного чтения
        ObjectNode dataNode = content.addObject();
        dataNode.put("type", "text");
        dataNode.put("text", "---\nsessionId: " + sessionId + "\nprojectRoot: " + projectRoot);

        return result;
    }
}
