// Aristo 24.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * Объединенный инструмент для чтения файлов и получения информации о них.
 * Поддерживает чтение (стандартное и многодиапазонное), метаданные (info), 
 * проверку существования (exists) и историю изменений в сессии (history).
 */
public class FileReadTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_file_read"; }

    @Override
    public String getDescription() {
        return "Essential tool for exploring the codebase and obtaining context. Supports reading content, metadata, existence checks, and change history. MANDATORY: You must use this tool to read a file before you can edit it. Note: Changes made by external tools or other MCPs are not tracked and cannot be restored.";
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

        props.putObject("action").put("type", "string").put("description", "Selection: 'read' (fetch content), 'info' (metadata & preview), 'exists' (verify path), 'history' (session changes). Use 'info' first for unknown large files.");
        props.putObject("path").put("type", "string").put("description", "Relative path to the target file or directory.");
        props.putObject("startLine").put("type", "integer").put("description", "1-based line to start reading. Use for focused context gathering.");
        props.putObject("endLine").put("type", "integer").put("description", "1-based line to end reading (inclusive).");
        props.putObject("line").put("type", "integer").put("description", "Read a specific single line. Overrides startLine/endLine.");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Regex anchor. Best for finding specific methods or classes in large files. Returns lines around the match.");
        props.putObject("contextRange").put("type", "integer").put("description", "Number of lines to include above and below the anchor. Default is 0 (match line only).");
        
        var ranges = props.putObject("ranges");
        ranges.put("type", "array");
        ranges.put("description", "Fetch multiple non-contiguous segments (e.g., imports and a method) in one call to save tokens.");
        var item = ranges.putObject("items").put("type", "object");
        item.putObject("startLine").put("type", "integer").put("description", "Start of the segment.");
        item.putObject("endLine").put("type", "integer").put("description", "End of the segment.");

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
        if (!Files.exists(path)) throw new IllegalArgumentException("File not found: " + path);
        PathSanitizer.checkFileSize(path);

        if (params.has("ranges")) {
            return executeReadRanges(path, params.get("ranges"));
        }

        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        AccessTracker.registerRead(path);
        
        String content = fileData.content();
        String[] lines = content.split("\n", -1);
        String resultText;

        if (params.has("contextStartPattern")) {
            String patternStr = params.get("contextStartPattern").asText();
            int range = params.path("contextRange").asInt(0);
            Pattern pattern = Pattern.compile(patternStr);
            int anchorIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) { anchorIdx = i; break; }
            }
            if (anchorIdx == -1) throw new IllegalArgumentException("Context pattern not found: " + patternStr);
            int start = Math.max(0, anchorIdx - range);
            int end = Math.min(lines.length - 1, anchorIdx + range);
            resultText = Stream.of(Arrays.copyOfRange(lines, start, end + 1)).map(l -> l.replace("\r", "")).collect(Collectors.joining("\n"));
        } else if (params.has("line")) {
            int lineNum = params.get("line").asInt();
            resultText = (lineNum >= 1 && lineNum <= lines.length) ? lines[lineNum - 1].replace("\r", "") : "";
        } else {
            int start = params.path("startLine").asInt(1);
            int end = params.path("endLine").asInt(lines.length);
            int startIdx = Math.max(0, start - 1);
            int endIdx = Math.min(lines.length, end);
            resultText = Stream.of(Arrays.copyOfRange(lines, startIdx, endIdx)).map(l -> l.replace("\r", "")).collect(Collectors.joining("\n"));
        }

        long size = Files.size(path);
        long crc32 = calculateCRC32(path);
        String header = String.format("[FILE: %s | SIZE: %d | LINES: %d | ENCODING: %s | CRC32C: %X]\n", 
            path.getFileName(), size, lines.length, fileData.charset().name(), crc32);
        
        return createResponse(header + resultText);
    }

    private JsonNode executeReadRanges(Path path, JsonNode rangesNode) throws IOException {
        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        AccessTracker.registerRead(path);
        String[] allLines = fileData.content().split("\n", -1);
        StringBuilder sb = new StringBuilder();
        
        for (JsonNode range : rangesNode) {
            int start = range.get("startLine").asInt();
            int end = range.get("endLine").asInt();
            sb.append("\n--- Lines ").append(start).append("-").append(end).append(" ---\n");
            int startIdx = Math.max(0, start - 1);
            int endIdx = Math.min(allLines.length, end);
            for (int i = startIdx; i < endIdx; i++) {
                sb.append(allLines[i].replace("\r", "")).append("\n");
            }
        }
        return createResponse(sb.toString().trim());
    }

    private JsonNode executeInfo(Path path) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        Charset charset = EncodingUtils.detectEncoding(path);
        List<String> head = new ArrayList<>();
        long lineCount = 0;
        try (var lines = Files.lines(path, charset)) {
            var it = lines.peek(l -> { if (head.size() < 5) head.add(l); }).iterator();
            while (it.hasNext()) { it.next(); lineCount++; }
        } catch (Exception e) { lineCount = -1; }

        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(path.toAbsolutePath()).append("\n");
        sb.append("Size: ").append(attrs.size()).append(" bytes\n");
        sb.append("Encoding: ").append(charset.name()).append("\n");
        sb.append("Lines: ").append(lineCount).append("\n");
        sb.append("Modified: ").append(attrs.lastModifiedTime()).append("\n\n### Head:\n");
        head.forEach(l -> sb.append(l).append("\n"));
        
        return createResponse(sb.toString().trim());
    }

    private JsonNode executeHistory(Path path, String pathStr) {
        List<String> history = TransactionManager.getFileHistory(path);
        if (history.isEmpty()) return createResponse("No session history for: " + pathStr);
        
        StringBuilder sb = new StringBuilder("Session history for " + pathStr + ":\n");
        history.forEach(entry -> sb.append("- ").append(entry).append("\n"));
        return createResponse(sb.toString().trim());
    }

    private long calculateCRC32(Path path) throws IOException {
        CRC32C crc = new CRC32C();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) crc.update(buffer, 0, len);
        }
        return crc.getValue();
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
