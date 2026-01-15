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
import ru.nts.tools.mcp.core.treesitter.SymbolIndex;
import ru.nts.tools.mcp.McpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
            Initialize or reactivate an MCP session.

            CRITICAL: Call this FIRST before using any other nts_* tools!

            This tool creates a new isolated session with:
            - Unique session UUID (required for all other tools)
            - Session-scoped undo/redo history
            - Session-scoped file access tokens
            - Session-scoped TODO plans

            MODES:
            1. NEW SESSION: Call without parameters -> creates new session with UUID
            2. REACTIVATE: Call with sessionId parameter -> restores existing session

            SESSION REACTIVATION:
            If your session was interrupted (server restart, connection drop), you can
            reactivate it by passing the old sessionId. This preserves:
            - Session directory with todos and snapshots
            - File history and journal on disk
            Note: In-memory state (tokens, undo stack) starts fresh after reactivation.

            WORKFLOW:
            1. Call nts_init() -> receive session UUID
            2. Store the UUID from response
            3. Pass sessionId in arguments for ALL subsequent tool calls
            4. If session becomes invalid -> call nts_init(sessionId="<old-uuid>") to reactivate

            PASSING SESSION ID:
            All other tools have 'sessionId' as a required parameter.
            Simply include it in the tool arguments:
            { "sessionId": "<uuid>", "path": "/some/file", ... }

            RETURNS:
            - sessionId: UUID to pass in all subsequent tool calls
            - projectRoot: Working directory path
            - message: Welcome message with session info
            - reactivated: true if this was a session reactivation

            NOTE: Session UUID is also shown in HUD output after every tool call.
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
        var props = schema.putObject("properties");

        props.putObject("sessionId").put("type", "string").put("description",
                "Optional. Pass an existing session UUID to REACTIVATE a previous session. " +
                "Use this when resuming work after server restart or connection drop. " +
                "If omitted, a new session is created.");

        schema.putArray("required"); // Empty array - no required params
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
        // Проверяем, запрошена ли реактивация существующей сессии
        String requestedSessionId = params.path("sessionId").asText(null);
        boolean isReactivation = false;
        String sessionId;
        SessionContext ctx;

        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            // Режим реактивации: пытаемся восстановить существующую сессию
            if (SessionContext.isActiveInMemory(requestedSessionId)) {
                // Сессия уже активна в памяти - просто используем её
                sessionId = requestedSessionId;
                ctx = SessionContext.getOrCreate(sessionId);
                isReactivation = true;
            } else if (SessionContext.existsOnDisk(requestedSessionId)) {
                // Сессия существует на диске - реактивируем
                ctx = SessionContext.reactivateSession(requestedSessionId);
                sessionId = requestedSessionId;
                isReactivation = true;
            } else {
                // Сессия не найдена ни в памяти, ни на диске
                throw new IllegalArgumentException(
                    "SESSION_NOT_FOUND: Session '" + requestedSessionId + "' does not exist. " +
                    "The session directory may have been deleted or the ID is incorrect. " +
                    "Call nts_init() without parameters to create a new session.");
            }
        } else {
            // Режим создания новой сессии
            sessionId = UUID.randomUUID().toString();
            ctx = SessionContext.getOrCreate(sessionId);
        }

        // Регистрируем сессию как валидную
        McpServer.registerValidSession(sessionId);
        SessionContext.setCurrent(ctx);

        // Создаем директории сессии
        Path sessionDir = ctx.getSessionDir();
        Files.createDirectories(ctx.getTodosDir());
        Files.createDirectories(ctx.getSnapshotsDir());

        // Сохраняем метаданные сессии
        ctx.saveMetadata();

        Path projectRoot = PathSanitizer.getRoot();
        List<Path> allRoots = PathSanitizer.getRoots();

        // Запускаем асинхронную индексацию символов для быстрой навигации
        SymbolIndex symbolIndex = SymbolIndex.getInstance();
        if (!symbolIndex.isIndexed() && !symbolIndex.isIndexing()) {
            symbolIndex.indexProjectAsync(projectRoot);
        }

        // Формируем информацию о roots
        String rootsInfo;
        if (allRoots.size() == 1) {
            rootsInfo = "Project root: " + projectRoot;
        } else {
            rootsInfo = "Project roots (" + allRoots.size() + "):\n" +
                allRoots.stream()
                    .map(r -> "  - " + r.toString())
                    .collect(Collectors.joining("\n"));
        }

        // Формируем ответ
        ObjectNode result = mapper.createObjectNode();
        var content = result.putArray("content");

        // Основное сообщение
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");

        String statusMessage = isReactivation
            ? "Session REACTIVATED successfully.\n\n" +
              "[NOTE: Undo/redo history RESTORED from journal.\n" +
              " File access tokens start fresh (re-read files before editing).\n" +
              " TODOs and snapshots from session directory are preserved.]\n\n" +
              "[TIP: Review restored session state before continuing:]\n" +
              "  → nts_session(action=\"journal\") - view transaction history & undo stack\n" +
              "  → nts_todo(action=\"read\")       - view current TODO plan progress\n"
            : "Session initialized successfully.\n";

        textNode.put("text", String.format("""
            %s
            SESSION ID: %s
            %s
            Session directory: %s

            IMPORTANT: Pass this sessionId in ALL subsequent tool calls.
            Example: { "sessionId": "%s", "path": "/file.txt", ... }

            The session UUID is also shown in the HUD output after every tool call.
            """, statusMessage, sessionId, rootsInfo, sessionDir, sessionId));

        // Структурированные данные для машинного чтения
        ObjectNode dataNode = content.addObject();
        dataNode.put("type", "text");

        StringBuilder yamlData = new StringBuilder();
        yamlData.append("---\nsessionId: ").append(sessionId).append("\n");
        yamlData.append("reactivated: ").append(isReactivation).append("\n");
        yamlData.append("primaryRoot: ").append(projectRoot).append("\n");
        if (allRoots.size() > 1) {
            yamlData.append("roots:\n");
            for (Path root : allRoots) {
                yamlData.append("  - ").append(root).append("\n");
            }
        }
        dataNode.put("text", yamlData.toString());

        return result;
    }
}
