// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Инструмент для получения детальной технической информации о файле с превью (Peeking).
 * Информация включает: размер, количество строк, кодировку, дату изменения и первые/последние строки.
 */
public class FileInfoTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_file_info";
    }

    @Override
    public String getDescription() {
        return "Get file metadata and intelligent preview (Head/Tail).";
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
        props.putObject("path").put("type", "string").put("description", "Target file path.");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found or is not a regular file: '" + pathStr + "'.");
        }

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attrs.size();
        Charset charset = EncodingUtils.detectEncoding(path);

        // 1. Подсчет строк и Head (первые 5 строк)
        List<String> head = new ArrayList<>();
        long lineCount = 0;
        try (var lines = Files.lines(path, charset)) {
            var allLines = lines.peek(l -> {
                if (head.size() < 5) head.add(l);
            }).iterator();
            while (allLines.hasNext()) {
                allLines.next();
                lineCount++;
            }
        } catch (Exception e) {
            lineCount = -1;
        }

        // 2. Tail (последние 5 строк) через RandomAccessFile
        List<String> tail = new ArrayList<>();
        if (lineCount > 5) {
            tail = readTail(path, 5, charset);
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");

        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(path.toAbsolutePath()).append("\n");
        sb.append("Size: ").append(size).append(" bytes\n");
        sb.append("Encoding: ").append(charset.name()).append("\n");
        if (lineCount >= 0) {
            sb.append("Lines: ").append(lineCount).append("\n");
        }
        sb.append("Last modified: ").append(attrs.lastModifiedTime()).append("\n\n");

        if (!head.isEmpty()) {
            sb.append("### Head (First 5 lines):\n```\n");
            for (String line : head) sb.append(line).append("\n");
            sb.append("```\n");
        }

        if (!tail.isEmpty()) {
            sb.append("\n### Tail (Last 5 lines):\n```\n");
            for (String line : tail) sb.append(line).append("\n");
            sb.append("```\n");
        }

        text.put("text", sb.toString().trim());
        return result;
    }

    /**
     * Эффективно читает последние N строк файла без загрузки всего содержимого в память.
     */
    private List<String> readTail(Path path, int maxLines, Charset charset) {
        List<byte[]> lineBytes = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) return List.of();

            long pos = fileLength - 1;
            int linesFound = 0;
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();

            // Идем с конца файла
            while (pos >= 0 && linesFound < maxLines) {
                raf.seek(pos);
                int b = raf.read();
                if (b == '\n') {
                    if (buffer.size() > 0) {
                        lineBytes.add(0, buffer.toByteArray());
                        buffer.reset();
                        linesFound++;
                    }
                } else if (b != '\r') {
                    buffer.write(b);
                }
                pos--;
            }
            // Добавляем последнюю (самую верхнюю из прочитанных) строку
            if (buffer.size() > 0 && linesFound < maxLines) {
                lineBytes.add(0, buffer.toByteArray());
            }
        } catch (IOException e) {
            return List.of("[Error reading tail: " + e.getMessage() + "]");
        }

        return lineBytes.stream()
                .map(bytes -> {
                    // Реверсируем байты, так как ByteArrayOutputStream писал их по порядку, 
                    // но мы читали файл с конца символ за символом
                    byte[] reversed = new byte[bytes.length];
                    for (int i = 0; i < bytes.length; i++) {
                        reversed[i] = bytes[bytes.length - 1 - i];
                    }
                    return new String(reversed, charset);
                })
                .collect(Collectors.toList());
    }
}