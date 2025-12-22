// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Инструмент для чтения содержимого файла.
 * Поддерживает чтение по диапазонам и умное чтение вокруг контекста с выводом расширенных метаданных.
 */
public class ReadFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Reads file content + metadata. MANDATORY for editing. Pro tip: use 'contextStartPattern' to find code blocks.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "File path.");
        props.putObject("startLine").put("type", "integer").put("description", "Start line (from 1).");
        props.putObject("endLine").put("type", "integer").put("description", "End line (inclusive).");
        props.putObject("line").put("type", "integer").put("description", "Single line to read.");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Regex anchor to find context.");
        props.putObject("contextRange").put("type", "integer").put("description", "Lines around anchor (default 0).");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + pathStr);
        }

        // Защита от OOM
        PathSanitizer.checkFileSize(path);

        Charset charset = EncodingUtils.detectEncoding(path);
        // Регистрируем доступ к файлу для возможности последующего редактирования
        AccessTracker.registerRead(path);
        
        String content = Files.readString(path, charset);
        String[] lines = content.split("\n", -1);
        String resultText;

        if (params.has("contextStartPattern")) {
            // Умное чтение вокруг контекста
            String patternStr = params.get("contextStartPattern").asText();
            int range = params.path("contextRange").asInt(0);
            
            int anchorIdx = -1;
            Pattern pattern = Pattern.compile(patternStr);
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    anchorIdx = i;
                    break;
                }
            }

            if (anchorIdx == -1) {
                throw new IllegalArgumentException("Pattern not found: " + patternStr);
            }

            int start = Math.max(0, anchorIdx - range);
            int end = Math.min(lines.length - 1, anchorIdx + range);
            
            resultText = Stream.of(Arrays.copyOfRange(lines, start, end + 1))
                    .map(l -> l.replace("\r", ""))
                    .collect(Collectors.joining("\n"));
            
        } else if (params.has("line")) {
            int lineNum = params.get("line").asInt();
            resultText = (lineNum >= 1 && lineNum <= lines.length) ? lines[lineNum - 1].replace("\r", "") : "";
        } else if (params.has("startLine") || params.has("endLine")) {
            int start = params.path("startLine").asInt(1);
            int end = params.path("endLine").asInt(lines.length);
            
            int startIdx = Math.max(0, start - 1);
            int endIdx = Math.min(lines.length, end);
            
            if (startIdx >= endIdx) {
                resultText = "";
            } else {
                resultText = Stream.of(Arrays.copyOfRange(lines, startIdx, endIdx))
                        .map(l -> l.replace("\r", ""))
                        .collect(Collectors.joining("\n"));
            }
        } else {
            resultText = content;
        }

        // Формируем заголовок с расширенными метаданными для LLM
        long size = Files.size(path);
        long crc32 = calculateCRC32(path);
        int charCount = content.length();
        int lineCount = lines.length;
        if (content.isEmpty()) lineCount = 0;

        String header = String.format("[FILE: %s | SIZE: %d bytes | CHARS: %d | LINES: %d | ENCODING: %s | CRC32: %X]\n", 
                path.getFileName(), size, charCount, lineCount, charset.name(), crc32);

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", header + resultText);
        return result;
    }

    /**
     * Вычисляет CRC32 хеш-сумму файла.
     */
    private long calculateCRC32(Path path) throws IOException {
        CRC32 crc = new CRC32();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }
}