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
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractor;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Инструмент для чтения файлов с системой токенов доступа к строкам.
 * <p>
 * Особенности:
 * - Чтение только по диапазону строк (полное чтение файла запрещено)
 * - Возвращает токен доступа к прочитанным строкам
 * - Повторное чтение с токеном: если файл не изменился -> [UNCHANGED]
 * - Параметр force=true для принудительного чтения контента
 */
public class FileReadTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String EXTERNAL_CHANGE_TIP =
        "[EXTERNAL CHANGE DETECTED - recorded in file history]\n" +
        "TIP: External changes may be intentional user edits (e.g., an emergency fix or refactoring by IDE/linter). " +
        "Review the changes carefully before proceeding. Your current plan may require adjustment.\n";

    // TIP: Подсказка для работы с большими диапазонами
    private static final String LARGE_RANGE_TIP =
        "[TIP: Large range read (%d lines). Consider using 'symbol' parameter for precise symbol boundaries, " +
        "or nts_file_search(action='grep') to find specific code patterns first.]";

    // TIP: Подсказка о следующем шаге после чтения
    private static final String WORKFLOW_TIP_EDIT =
        "[WORKFLOW: Token ready for editing -> nts_edit_file(path, startLine, content, accessToken)]";

    // TIP: Подсказка для файлов с поддержкой tree-sitter
    private static final String SYMBOL_AVAILABLE_TIP =
        "[TIP: This file supports symbol navigation. Use 'symbol' parameter to read exact method/class boundaries.]";

    @Override
    public String getName() {
        return "nts_file_read";
    }

    @Override
    public String getDescription() {
        return """
            Secure file reader with line-level access control.

            WORKFLOW: info -> read(range) -> get TOKEN -> use TOKEN for edit

            KEY RULES:
            - Full file read is BLOCKED - always specify line range
            - Returns TOKEN required for editing (nts_edit_file)
            - Re-read with same TOKEN: returns UNCHANGED if file intact
            - Existing token reuse: if you have [1-100], requesting [50-60] returns the broader token

            TIPS:
            - Start with action='info' to see line count and preview
            - Use 'ranges' array to read multiple sections efficiently
            - Pass previous 'token' to check if re-read needed
            - Use 'contextStartPattern' for dynamic anchor-based reading

            BULK READ: Read 2+ files in single request
            - Errors in one file don't affect others
            - Each file supports: line, startLine/endLine, symbol, ranges, contextStartPattern

            Example:
              bulk: [
                { path: "UserService.java", symbol: "createUser" },
                { path: "UserRepository.java", symbol: "save" },
                { path: "User.java", startLine: 1, endLine: 30 }
              ]
            """;
    }

    @Override
    public String getCategory() {
        return "fs";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation mode: 'read' (get content + token), 'info' (metadata + line count, START HERE), " +
                "'exists' (check path), 'history' (session changes). Default: 'read'");

        props.putObject("path").put("type", "string").put("description",
                "File path (relative to project root or absolute).");

        props.putObject("encoding").put("type", "string").put("description",
                "Optional: Force specific encoding (e.g. 'UTF-8', 'windows-1251'). If omitted, auto-detection is used.");
        props.putObject("startLine").put("type", "integer").put("description",
                "First line to read (1-based). REQUIRED for 'read' unless using 'line', 'ranges', or 'contextStartPattern'.");

        props.putObject("endLine").put("type", "integer").put("description",
                "Last line to read (1-based, inclusive). Defaults to startLine if omitted.");

        props.putObject("line").put("type", "integer").put("description",
                "Shortcut: read single line N. Equivalent to startLine=N, endLine=N.");

        props.putObject("contextStartPattern").put("type", "string").put("description",
                "Regex to find anchor line dynamically. Example: 'public void myMethod' -> finds method start. " +
                "Use with contextRange to get surrounding lines.");

        props.putObject("contextRange").put("type", "integer").put("description",
                "Lines before AND after the anchor match. Default: 0 (only matching line).");

        props.putObject("symbol").put("type", "string").put("description",
                "Read specific symbol (class, method, function) by name. Uses tree-sitter for precise boundaries. " +
                "Combines nts_code_navigate + read in one call. Returns symbol content with TOKEN.");

        props.putObject("symbolKind").put("type", "string").put("description",
                "Filter by symbol kind: 'class', 'method', 'function', 'field'. Used with 'symbol' param.");

        var ranges = props.putObject("ranges");
        ranges.put("type", "array").put("description",
                "Read multiple non-contiguous sections. Each range gets its own token. " +
                "Example: [{startLine:1,endLine:10}, {startLine:50,endLine:60}]");
        var item = ranges.putObject("items").put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("startLine").put("type", "integer").put("description", "Range start (1-based)");
        itemProps.putObject("endLine").put("type", "integer").put("description", "Range end (1-based, inclusive)");

        props.putObject("token").put("type", "string").put("description",
                "Pass previous token to check if file changed. If valid -> returns UNCHANGED (saves tokens). " +
                "Get token from previous read or from nts_file_search list/grep output.");

        props.putObject("force").put("type", "boolean").put("description",
                "Bypass UNCHANGED optimization and always return content. Use when you need fresh content despite valid token.");

        // Bulk read: read multiple files in single request
        var bulk = props.putObject("bulk");
        bulk.put("type", "array").put("description",
                "Read multiple files in single request. Each item specifies a file and how to read it. " +
                "Returns combined results with individual tokens. Errors in one file don't affect others.");
        var bulkItem = bulk.putObject("items").put("type", "object");
        var bulkProps = bulkItem.putObject("properties");
        bulkProps.putObject("path").put("type", "string").put("description", "File path (required)");
        bulkProps.putObject("startLine").put("type", "integer").put("description", "First line to read (1-based)");
        bulkProps.putObject("endLine").put("type", "integer").put("description", "Last line to read (1-based, inclusive)");
        bulkProps.putObject("line").put("type", "integer").put("description", "Shortcut: read single line N");
        bulkProps.putObject("symbol").put("type", "string").put("description", "Read specific symbol by name");
        bulkProps.putObject("symbolKind").put("type", "string").put("description", "Filter by symbol kind");
        bulkProps.putObject("contextStartPattern").put("type", "string").put("description", "Regex to find anchor line");
        bulkProps.putObject("contextRange").put("type", "integer").put("description", "Lines before AND after the anchor match");
        bulkProps.putObject("token").put("type", "string").put("description", "Pass previous token to check if unchanged");
        bulkProps.putObject("encoding").put("type", "string").put("description", "Force specific encoding");
        var bulkRanges = bulkProps.putObject("ranges");
        bulkRanges.put("type", "array").put("description", "Read multiple non-contiguous sections");
        var bulkRangeItem = bulkRanges.putObject("items").put("type", "object");
        var bulkRangeProps = bulkRangeItem.putObject("properties");
        bulkRangeProps.putObject("startLine").put("type", "integer");
        bulkRangeProps.putObject("endLine").put("type", "integer");
        bulkItem.putArray("required").add("path");

        // Note: path is not strictly required when using bulk
        // The validation will be done in execute() method
        schema.putArray("required"); // Empty - path or bulk must be provided
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Check for bulk read first (before path is required)
        if (params.has("bulk")) {
            return executeBulkRead(params.get("bulk"));
        }

        String action = params.path("action").asText("read").toLowerCase();
        String pathStr = params.path("path").asText(null);

        if ("exists".equals(action)) {
            if (pathStr == null || pathStr.isBlank()) {
                throw new IllegalArgumentException("Parameter 'path' is required for action 'exists'.");
            }
            return executeExists(pathStr);
        }

        // Все остальные действия требуют path
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("Parameter 'path' is required for action '" + action + "'.");
        }

        Path path = PathSanitizer.sanitize(pathStr, true);

        return switch (action) {
            case "read" -> executeRead(path, params);
            case "info" -> executeInfo(path);
            case "history" -> executeHistory(path, pathStr);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeExists(String pathStr) {
        Path path = PathSanitizer.sanitize(pathStr, false);
        boolean exists = Files.exists(path);
        String type = exists ? (Files.isDirectory(path) ? "Directory" : "File") : "None";
        return createResponse(String.format("Path '%s' exists: %b (Type: %s)", pathStr, exists, type));
    }

    private JsonNode executeRead(Path path, JsonNode params) throws Exception {
        if (!Files.exists(path)) {
            throw NtsFileException.notFound(path);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                    "Path is a directory, not a file: " + path +
                    ". Use nts_file_search(action='list') to browse directory contents.");
        }
        PathSanitizer.checkFileSize(path);

        // Определяем режим чтения
        boolean hasStartLine = params.has("startLine");
        boolean hasLine = params.has("line");
        boolean hasRanges = params.has("ranges");
        boolean hasPattern = params.has("contextStartPattern");
        boolean hasSymbol = params.has("symbol");

        if (!hasStartLine && !hasLine && !hasRanges && !hasPattern && !hasSymbol) {
            throw new IllegalArgumentException("Reading entire file is not allowed. Specify one of: " +
                    "startLine/endLine, line, ranges, contextStartPattern, or symbol. " +
                    "Use action='info' to get file metadata first.");
        }

        // Загружаем файл с учетом принудительной кодировки если указана
        EncodingUtils.TextFileContent fileData;
        if (params.has("encoding")) {
            Charset forcedCharset = Charset.forName(params.get("encoding").asText());
            fileData = EncodingUtils.readTextFile(path, forcedCharset);
        } else {
            fileData = EncodingUtils.readTextFile(path);
        }
        
        String content = fileData.content();
        String[] lines = content.split("\n", -1);
        int lineCount = lines.length;
        long crc32 = calculateCRC32(path);

        // Проверяем внешние изменения
        ExternalChangeTracker externalTracker = SessionContext.currentOrDefault().externalChanges();
        ExternalChangeTracker.ExternalChangeResult externalChange = externalTracker.checkForExternalChange(
            path, crc32, content, fileData.charset(), lineCount
        );

        boolean hasExternalChange = externalChange.hasExternalChange();
        if (hasExternalChange) {
            // Записываем внешнее изменение в журнал
            ExternalChangeTracker.FileSnapshot previous = externalChange.previousSnapshot();
            TransactionManager.recordExternalChange(
                path,
                previous.content(),
                previous.crc32c(),
                crc32,
                String.format("External change: %s", path.getFileName())
            );
            // Обновляем снапшот текущим содержимым
            externalTracker.updateSnapshot(path, content, crc32, fileData.charset(), lineCount);
        }

        // Многодиапазонное чтение
        if (hasRanges) {
            // Регистрируем снапшот если это первое чтение
            if (!hasExternalChange) {
                externalTracker.registerSnapshot(path, content, crc32, fileData.charset(), lineCount);
            }
            return executeReadRanges(path, params.get("ranges"), lines, crc32, lineCount, fileData.charset(), hasExternalChange);
        }

        // Определяем диапазон
        int startLine, endLine;
        SymbolInfo foundSymbol = null;

        if (hasSymbol) {
            // Чтение по имени символа через tree-sitter
            String symbolName = params.get("symbol").asText();
            String kindFilter = params.path("symbolKind").asText(null);
            foundSymbol = findSymbolInFile(path, content, symbolName, kindFilter);
            if (foundSymbol == null) {
                throw NtsParamException.symbolNotFound(symbolName, path.getFileName().toString());
            }
            startLine = foundSymbol.location().startLine();
            endLine = foundSymbol.location().endLine();
        } else if (hasLine) {
            startLine = endLine = params.get("line").asInt();
        } else if (hasPattern) {
            String patternStr = params.get("contextStartPattern").asText();
            int range = params.path("contextRange").asInt(0);
            int anchorIdx = findPatternLine(lines, patternStr);
            if (anchorIdx == -1) {
                throw NtsParamException.patternNotFound(patternStr, path.getFileName().toString());
            }
            startLine = Math.max(1, anchorIdx + 1 - range);
            endLine = Math.min(lineCount, anchorIdx + 1 + range);
        } else {
            startLine = params.get("startLine").asInt();
            endLine = params.path("endLine").asInt(startLine);
        }

        // Валидация границ
        startLine = Math.max(1, startLine);
        endLine = Math.min(lineCount, endLine);

        if (startLine > lineCount) {
            throw NtsParamException.lineExceeds(startLine, lineCount, path.getFileName().toString());
        }

        // Извлекаем содержимое диапазона для отображения (с номерами строк)
        String rangeContent = extractLines(lines, startLine, endLine);
        // Чистое содержимое для CRC токена (без номеров строк) - консистентно с рефакторингом
        String rawContent = extractRawContent(lines, startLine, endLine);

        // Проверяем существующий токен (если передан и force=false)
        boolean force = params.path("force").asBoolean(false);
        if (params.has("token") && !force) {
            String tokenStr = params.get("token").asText();
            try {
                LineAccessToken token = LineAccessToken.decode(tokenStr, path);
                // Извлекаем чистое содержимое диапазона токена для валидации CRC
                String tokenRawContent = extractRawContent(lines, token.startLine(), Math.min(token.endLine(), lineCount));
                var validation = LineAccessTracker.validateToken(token, tokenRawContent, lineCount);

                if (validation.valid() && token.covers(startLine, endLine)) {
                    // Содержимое диапазона не изменилось и токен покрывает запрошенный диапазон
                    return createUnchangedResponse(token.encode(), crc32, lineCount, startLine, endLine);
                }
            } catch (Exception e) {
                // Некорректный токен - игнорируем, продолжаем чтение
            }
        }

        // Регистрируем доступ и получаем токен (с rangeCrc от чистого содержимого)
        LineAccessToken newToken = LineAccessTracker.registerAccess(path, startLine, endLine, rawContent, lineCount);

        // Проверяем, был ли возвращён покрывающий токен (шире чем запрошено)
        String coveringTokenTip = SessionLineAccessTracker.getCoveringTokenTip(newToken, startLine, endLine);

        // Session Tokens: отмечаем файл как разблокированный в транзакции
        TransactionManager.markFileAccessedInTransaction(path);

        // Регистрируем снапшот для отслеживания будущих внешних изменений
        // (если это первое чтение или снапшот уже обновлён при обнаружении внешнего изменения)
        if (!hasExternalChange) {
            externalTracker.registerSnapshot(path, content, crc32, fileData.charset(), lineCount);
        }

        // Используем уже извлечённый контент (rangeContent)
        // Если читали по символу, добавляем информацию о символе
        if (foundSymbol != null) {
            return createSymbolReadResponse(path, lineCount, fileData.charset().name(), crc32,
                    startLine, endLine, rangeContent, newToken, foundSymbol, hasExternalChange, coveringTokenTip);
        }

        return createReadResponse(path, lineCount, fileData.charset().name(), crc32, startLine, endLine, rangeContent, newToken, hasExternalChange, coveringTokenTip);
    }

    private JsonNode executeReadRanges(Path path, JsonNode rangesNode, String[] lines, long crc32, int lineCount, Charset charset, boolean hasExternalChange) {
        StringBuilder sb = new StringBuilder();
        List<String> tokens = new ArrayList<>();

        if (hasExternalChange) {
            sb.append(EXTERNAL_CHANGE_TIP);
        }
        sb.append(String.format("[FILE: %s | LINES: %d | ENCODING: %s | CRC32C: %X]\n", path.getFileName(), lineCount, charset.name(), crc32));

        for (JsonNode range : rangesNode) {
            int start = range.get("startLine").asInt();
            int end = range.path("endLine").asInt(start);

            // Валидация границ
            start = Math.max(1, start);
            end = Math.min(lineCount, end);

            // Извлекаем содержимое диапазона для отображения (с номерами строк)
            String rangeContent = extractLines(lines, start, end);
            // Чистое содержимое для CRC токена (без номеров строк)
            String rawContent = extractRawContent(lines, start, end);

            // Регистрируем доступ с rangeCrc от чистого содержимого
            LineAccessToken token = LineAccessTracker.registerAccess(path, start, end, rawContent, lineCount);
            tokens.add(token.encode());

            // Проверяем был ли возвращён покрывающий токен
            String coveringTip = SessionLineAccessTracker.getCoveringTokenTip(token, start, end);

            sb.append(String.format("\n--- Lines %d-%d ---\n", start, end));
            sb.append(String.format("[ACCESS: lines %d-%d | TOKEN: %s]\n",
                    token.startLine(), token.endLine(), token.encode()));
            if (!coveringTip.isEmpty()) {
                sb.append(coveringTip).append("\n");
            }
            sb.append(rangeContent);
            sb.append("\n");
        }

        sb.append("\n[TOKENS ISSUED: ").append(tokens.size()).append("]");
        return createResponse(sb.toString().trim());
    }

    /**
     * Bulk read: reads multiple files in a single request.
     * Each file can have its own read mode (line, range, symbol, etc.).
     * Errors in one file don't affect others.
     *
     * @param bulkNode Array of file specifications
     * @return Combined results with individual tokens
     */
    private JsonNode executeBulkRead(JsonNode bulkNode) {
        int totalFiles = bulkNode.size();
        int succeeded = 0;
        int failed = 0;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < bulkNode.size(); i++) {
            JsonNode fileSpec = bulkNode.get(i);
            String pathStr = fileSpec.get("path").asText();

            sb.append(String.format("\n=== FILE %d: %s ===\n", i + 1, pathStr));

            try {
                // Create params for single file read
                ObjectNode singleParams = mapper.createObjectNode();
                singleParams.put("path", pathStr);
                singleParams.put("action", "read");

                // Copy all relevant fields
                if (fileSpec.has("startLine")) singleParams.put("startLine", fileSpec.get("startLine").asInt());
                if (fileSpec.has("endLine")) singleParams.put("endLine", fileSpec.get("endLine").asInt());
                if (fileSpec.has("line")) singleParams.put("line", fileSpec.get("line").asInt());
                if (fileSpec.has("symbol")) singleParams.put("symbol", fileSpec.get("symbol").asText());
                if (fileSpec.has("symbolKind")) singleParams.put("symbolKind", fileSpec.get("symbolKind").asText());
                if (fileSpec.has("contextStartPattern")) singleParams.put("contextStartPattern", fileSpec.get("contextStartPattern").asText());
                if (fileSpec.has("contextRange")) singleParams.put("contextRange", fileSpec.get("contextRange").asInt());
                if (fileSpec.has("token")) singleParams.put("token", fileSpec.get("token").asText());
                if (fileSpec.has("encoding")) singleParams.put("encoding", fileSpec.get("encoding").asText());
                if (fileSpec.has("force")) singleParams.put("force", fileSpec.get("force").asBoolean());
                if (fileSpec.has("ranges")) singleParams.set("ranges", fileSpec.get("ranges"));

                // Execute single file read
                JsonNode result = execute(singleParams);
                String content = result.get("content").get(0).get("text").asText();

                sb.append("[SUCCESS]\n");
                sb.append(content);
                sb.append("\n");
                succeeded++;

            } catch (Exception e) {
                sb.append("[ERROR]\n");
                sb.append(String.format("[ERROR: %s - %s]\n", e.getClass().getSimpleName(), e.getMessage()));
                failed++;
            }
        }

        // Build header
        String header = String.format("[BULK READ: %d files | %d succeeded | %d failed]\n",
                totalFiles, succeeded, failed);

        return createResponse(header + sb.toString().trim());
    }

    private JsonNode executeInfo(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw NtsFileException.notFound(path);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException(
                    "Path is a directory, not a file: " + path +
                    ". Use nts_file_search(action='list') to browse directory contents.");
        }
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        Charset charset = EncodingUtils.detectEncoding(path);
        long crc32 = calculateCRC32(path);
        List<String> head = new ArrayList<>();
        long lineCount = 0;

        // Для больших файлов показываем больше строк (обычно там imports)
        final int headLimit = 10;

        try (var linesStream = Files.lines(path, charset)) {
            var it = linesStream.peek(l -> {
                if (head.size() < headLimit) {
                    head.add(l);
                }
            }).iterator();
            while (it.hasNext()) {
                it.next();
                lineCount++;
            }
        } catch (Exception e) {
            lineCount = -1;
        }

        // Показываем прочитанные диапазоны
        String accessedRanges = LineAccessTracker.formatAccessedRanges(path);
        boolean isLargeFile = lineCount > 500;

        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(path.toAbsolutePath()).append("\n");
        sb.append("Size: ").append(attrs.size()).append(" bytes\n");
        sb.append("Encoding: ").append(charset.name()).append("\n");
        sb.append("Lines: ").append(lineCount);
        if (isLargeFile) {
            sb.append(" [LARGE FILE]");
        }
        sb.append("\n");
        sb.append("CRC32C: ").append(String.format("%X", crc32)).append("\n");
        sb.append("Modified: ").append(attrs.lastModifiedTime()).append("\n");

        if (!accessedRanges.isEmpty()) {
            sb.append("Accessed: ").append(accessedRanges).append("\n");
        }

        // Заголовок файла (package/imports для кода)
        sb.append("\n### Header (first ").append(head.size()).append(" lines - usually package/imports):\n");
        for (int i = 0; i < head.size(); i++) {
            sb.append(String.format("%4d| %s\n", i + 1, head.get(i)));
        }

        // Подсказка для больших файлов
        if (isLargeFile) {
            sb.append("\n[TIP: Large file. Use nts_file_search(action='grep') to find specific code patterns.]");
        }

        // TIP о доступных возможностях навигации
        boolean hasTreeSitter = LanguageDetector.detect(path).isPresent();
        if (hasTreeSitter) {
            sb.append("\n").append(SYMBOL_AVAILABLE_TIP);
            sb.append("\n[TIP: Use nts_code_navigate(action='symbols') to list all classes, methods, and functions.]");
        }

        return createResponse(sb.toString().trim());
    }

    private JsonNode executeHistory(Path path, String pathStr) {
        List<String> history = TransactionManager.getFileHistory(path);
        if (history.isEmpty()) {
            return createResponse("No session history for: " + pathStr);
        }

        StringBuilder sb = new StringBuilder("Session history for " + pathStr + ":\n");
        history.forEach(entry -> sb.append("- ").append(entry).append("\n"));
        return createResponse(sb.toString().trim());
    }

    // ============ Вспомогательные методы ============

    /**
     * Ищет символ в файле по имени с использованием tree-sitter.
     */
    private SymbolInfo findSymbolInFile(Path path, String content, String symbolName, String kindFilter) {
        String langId = LanguageDetector.detect(path).orElse(null);
        if (langId == null) {
            return null;
        }

        try {
            TreeSitterManager treeManager = TreeSitterManager.getInstance();
            SymbolExtractor extractor = SymbolExtractor.getInstance();
            var tree = treeManager.parse(content, langId);

            List<SymbolInfo> symbols = extractor.extractDefinitions(tree, path, content, langId);

            // Фильтруем по имени и виду
            List<SymbolInfo> matches = symbols.stream()
                    .filter(s -> s.name().equals(symbolName))
                    .filter(s -> kindFilter == null || matchesKind(s, kindFilter))
                    .toList();

            if (matches.isEmpty()) {
                return null;
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }

            // Если несколько совпадений, предпочитаем более конкретные типы (метод > класс)
            return matches.stream()
                    .min((a, b) -> {
                        // Порядок предпочтения: METHOD > FUNCTION > FIELD > CLASS
                        int priorityA = getKindPriority(a.kind());
                        int priorityB = getKindPriority(b.kind());
                        return Integer.compare(priorityA, priorityB);
                    })
                    .orElse(matches.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean matchesKind(SymbolInfo symbol, String kind) {
        return switch (kind.toLowerCase()) {
            case "class" -> symbol.kind() == SymbolInfo.SymbolKind.CLASS;
            case "interface" -> symbol.kind() == SymbolInfo.SymbolKind.INTERFACE;
            case "method" -> symbol.kind() == SymbolInfo.SymbolKind.METHOD;
            case "function" -> symbol.kind() == SymbolInfo.SymbolKind.FUNCTION;
            case "field" -> symbol.kind() == SymbolInfo.SymbolKind.FIELD;
            case "property" -> symbol.kind() == SymbolInfo.SymbolKind.PROPERTY;
            case "variable" -> symbol.kind() == SymbolInfo.SymbolKind.VARIABLE;
            case "enum" -> symbol.kind() == SymbolInfo.SymbolKind.ENUM;
            case "struct" -> symbol.kind() == SymbolInfo.SymbolKind.STRUCT;
            default -> true;
        };
    }

    private int getKindPriority(SymbolInfo.SymbolKind kind) {
        return switch (kind) {
            case METHOD, FUNCTION -> 1;
            case FIELD, PROPERTY -> 2;
            case VARIABLE -> 3;
            case CLASS, INTERFACE, STRUCT, ENUM -> 4;
            default -> 5;
        };
    }

    private int findPatternLine(String[] lines, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }

    private String extractLines(String[] lines, int startLine, int endLine) {
        int startIdx = Math.max(0, startLine - 1);
        int endIdx = Math.min(lines.length, endLine);

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            if (i > startIdx) {
                sb.append("\n");
            }
            sb.append(String.format("%4d\t%s", i + 1, lines[i].replace("\r", "")));
        }
        return sb.toString();
    }

    /**
     * Извлекает чистое содержимое диапазона строк (без номеров строк).
     * Используется для вычисления CRC токена - должно совпадать с форматом
     * в ProjectReplaceTool и операциях рефакторинга.
     */
    private String extractRawContent(String[] lines, int startLine, int endLine) {
        int startIdx = Math.max(0, startLine - 1);
        int endIdx = Math.min(lines.length, endLine);

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            if (i > startIdx) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private long calculateCRC32(Path path) throws IOException {
        CRC32C crc = new CRC32C();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    private JsonNode createReadResponse(Path path, int totalLines, String encoding, long crc32,
                                         int startLine, int endLine, String content,
                                         LineAccessToken token, boolean hasExternalChange, String coveringTokenTip) {
        StringBuilder sb = new StringBuilder();
        if (hasExternalChange) {
            sb.append(EXTERNAL_CHANGE_TIP);
        }
        sb.append(String.format("[FILE: %s | LINES: %d-%d of %d | ENCODING: %s | CRC32C: %X]\n",
                path.getFileName(), startLine, endLine, totalLines, encoding, crc32));

        // Показываем диапазон токена явно для LLM
        sb.append(String.format("[ACCESS: lines %d-%d | TOKEN: %s]\n",
                token.startLine(), token.endLine(), token.encode()));

        // TIP если вернулся покрывающий токен
        if (coveringTokenTip != null && !coveringTokenTip.isEmpty()) {
            sb.append(coveringTokenTip).append("\n");
        }

        sb.append("\n").append(content);

        // TIPs для улучшения workflow
        int rangeSize = endLine - startLine + 1;
        sb.append("\n\n");

        // TIP для больших диапазонов
        if (rangeSize > 100) {
            boolean hasTreeSitter = LanguageDetector.detect(path).isPresent();
            if (hasTreeSitter) {
                sb.append(String.format(LARGE_RANGE_TIP, rangeSize)).append("\n");
            }
        }

        // TIP о следующем шаге
        sb.append(WORKFLOW_TIP_EDIT);

        return createResponse(sb.toString());
    }

    private JsonNode createSymbolReadResponse(Path path, int totalLines, String encoding, long crc32,
                                               int startLine, int endLine, String content,
                                               LineAccessToken token, SymbolInfo symbol,
                                               boolean hasExternalChange, String coveringTokenTip) {
        StringBuilder sb = new StringBuilder();
        if (hasExternalChange) {
            sb.append(EXTERNAL_CHANGE_TIP);
        }
        sb.append(String.format("[SYMBOL: %s | KIND: %s", symbol.name(), symbol.kind().getDisplayName()));
        if (symbol.parentName() != null) {
            sb.append(" | PARENT: ").append(symbol.parentName());
        }
        sb.append("]\n");
        sb.append(String.format("[FILE: %s | LINES: %d-%d of %d | ENCODING: %s | CRC32C: %X]\n",
                path.getFileName(), startLine, endLine, totalLines, encoding, crc32));

        // Показываем диапазон токена явно для LLM
        sb.append(String.format("[ACCESS: lines %d-%d | TOKEN: %s]\n",
                token.startLine(), token.endLine(), token.encode()));

        // TIP если вернулся покрывающий токен
        if (coveringTokenTip != null && !coveringTokenTip.isEmpty()) {
            sb.append(coveringTokenTip).append("\n");
        }

        if (symbol.signature() != null) {
            sb.append("[SIGNATURE: ").append(symbol.signature()).append("]\n");
        }
        if (symbol.documentation() != null) {
            sb.append("[DOC: ").append(symbol.documentation().replace("\n", " ").trim()).append("]\n");
        }

        sb.append("\n").append(content);

        return createResponse(sb.toString());
    }

    private JsonNode createUnchangedResponse(String token, long crc32, int lineCount, int startLine, int endLine) {
        String msg = String.format("[STATUS: UNCHANGED | LINES: %d-%d of %d | CRC32C: %X]\n" + "[TOKEN: %s]\n\n" + "File content has not changed since last read. Use force=true to re-read content.", startLine, endLine, lineCount, crc32, token);
        return createResponse(msg);
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
