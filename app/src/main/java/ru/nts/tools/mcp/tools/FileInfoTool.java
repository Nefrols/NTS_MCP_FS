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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.CRC32;

/**
 * Инструмент для получения детальной информации о файле.
 */
public class FileInfoTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "file_info";
    }

    @Override
    public String getDescription() {
        return "Returns detailed information about a file: size, line count, encoding, modification date, and CRC32.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Path to the file.");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, true); // Разрешаем чтение защищенных файлов
        
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found or is not a regular file: " + pathStr);
        }

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attrs.size();
        
        Charset charset = EncodingUtils.detectEncoding(path);
        
        long lineCount = 0;
        try {
            lineCount = Files.lines(path, charset).count();
        } catch (Exception e) {
            lineCount = -1;
        }

        long crc32 = calculateCRC32(path);

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
        sb.append("Last modified: ").append(attrs.lastModifiedTime()).append("\n");
        sb.append("CRC32: ").append(Long.toHexString(crc32).toUpperCase());
        
        text.put("text", sb.toString());
        return result;
    }

    private long calculateCRC32(Path path) throws Exception {
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