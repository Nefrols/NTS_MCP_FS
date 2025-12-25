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
            - Tokens auto-merge: reading [1-50] then [40-60] -> single token [1-60]

            TIPS:
            - Start with action='info' to see line count and preview
            - Use 'ranges' array to read multiple sections efficiently
            - Pass previous 'token' to check if re-read needed
            - Use 'contextStartPattern' for dynamic anchor-based reading
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

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.path("action").asText("read").toLowerCase();
        String pathStr = params.get("path").asText();

        if ("exists".equals(action)) {
            return executeExists(pathStr);
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
            throw new IllegalArgumentException("File not found: " + path);
        }
        PathSanitizer.checkFileSize(path);

        // Определяем режим чтения
        boolean hasStartLine = params.has("startLine");
        boolean hasLine = params.has("line");
        boolean hasRanges = params.has("ranges");
        boolean hasPattern = params.has("contextStartPattern");

        if (!hasStartLine && !hasLine && !hasRanges && !hasPattern) {
            throw new IllegalArgumentException("Reading entire file is not allowed. Specify one of: " + "startLine/endLine, line, ranges, or contextStartPattern. " + "Use action='info' to get file metadata first.");
        }

        // Загружаем файл
        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        String content = fileData.content();
        String[] lines = content.split("\n", -1);
        int lineCount = lines.length;
        long crc32 = calculateCRC32(path);

        // Многодиапазонное чтение
        if (hasRanges) {
            return executeReadRanges(path, params.get("ranges"), lines, crc32, lineCount, fileData.charset());
        }

        // Определяем диапазон
        int startLine, endLine;
        if (hasLine) {
            startLine = endLine = params.get("line").asInt();
        } else if (hasPattern) {
            String patternStr = params.get("contextStartPattern").asText();
            int range = params.path("contextRange").asInt(0);
            int anchorIdx = findPatternLine(lines, patternStr);
            if (anchorIdx == -1) {
                throw new IllegalArgumentException("Pattern not found: " + patternStr);
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
            throw new IllegalArgumentException("startLine " + startLine + " exceeds file length (" + lineCount + " lines)");
        }

        // Проверяем существующий токен (если передан и force=false)
        boolean force = params.path("force").asBoolean(false);
        if (params.has("token") && !force) {
            String tokenStr = params.get("token").asText();
            try {
                LineAccessToken token = LineAccessToken.decode(tokenStr, path);
                var validation = LineAccessTracker.validateToken(token, crc32, lineCount);

                if (validation.valid() && token.covers(startLine, endLine)) {
                    // Файл не изменился и токен покрывает запрошенный диапазон
                    return createUnchangedResponse(token.encode(), crc32, lineCount, startLine, endLine);
                }
            } catch (Exception e) {
                // Некорректный токен - игнорируем, продолжаем чтение
            }
        }

        // Регистрируем доступ и получаем токен
        LineAccessToken newToken = LineAccessTracker.registerAccess(path, startLine, endLine, crc32, lineCount);

        // Session Tokens: отмечаем файл как разблокированный в транзакции
        TransactionManager.markFileAccessedInTransaction(path);

        // Извлекаем контент
        String resultText = extractLines(lines, startLine, endLine);

        return createReadResponse(path, lineCount, fileData.charset().name(), crc32, startLine, endLine, resultText, newToken.encode());
    }

    private JsonNode executeReadRanges(Path path, JsonNode rangesNode, String[] lines, long crc32, int lineCount, Charset charset) {
        StringBuilder sb = new StringBuilder();
        List<String> tokens = new ArrayList<>();

        sb.append(String.format("[FILE: %s | LINES: %d | ENCODING: %s | CRC32C: %X]\n", path.getFileName(), lineCount, charset.name(), crc32));

        for (JsonNode range : rangesNode) {
            int start = range.get("startLine").asInt();
            int end = range.path("endLine").asInt(start);

            // Валидация границ
            start = Math.max(1, start);
            end = Math.min(lineCount, end);

            // Регистрируем доступ
            LineAccessToken token = LineAccessTracker.registerAccess(path, start, end, crc32, lineCount);
            tokens.add(token.encode());

            sb.append(String.format("\n--- Lines %d-%d ---\n", start, end));
            sb.append(String.format("[TOKEN: %s]\n", token.encode()));
            sb.append(extractLines(lines, start, end));
            sb.append("\n");
        }

        sb.append("\n[TOKENS ISSUED: ").append(tokens.size()).append("]");
        return createResponse(sb.toString().trim());
    }

    private JsonNode executeInfo(Path path) throws IOException {
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
            sb.append("\n[TIP: Large file. Use grep to find specific code, or read targeted ranges.]");
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
            sb.append(String.format("%4d| %s\n", i + 1, lines[i].replace("\r", "")));
        }
        return sb.toString().stripTrailing();
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

    private JsonNode createReadResponse(Path path, int totalLines, String encoding, long crc32, int startLine, int endLine, String content, String token) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[FILE: %s | LINES: %d-%d of %d | ENCODING: %s | CRC32C: %X]\n", path.getFileName(), startLine, endLine, totalLines, encoding, crc32));
        sb.append(String.format("[TOKEN: %s]\n\n", token));
        sb.append(content);

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
