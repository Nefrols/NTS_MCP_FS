// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;

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
        return "Возвращает информацию о файле: размер, количество строк, кодировку, дату изменения и CRC32.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        Path path = Path.of(params.get("path").asText());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Файл не найден или не является обычным файлом: " + path);
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
        sb.append("Файл: ").append(path.toAbsolutePath()).append("\n");
        sb.append("Размер: ").append(size).append(" байт\n");
        sb.append("Кодировка: ").append(charset.name()).append("\n");
        if (lineCount >= 0) {
            sb.append("Строк: ").append(lineCount).append("\n");
        }
        sb.append("Дата изменения: ").append(attrs.lastModifiedTime()).append("\n");
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
