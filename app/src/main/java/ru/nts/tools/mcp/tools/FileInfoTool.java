// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Инструмент для получения детальной технической информации о файле.
 * Позволяет LLM оценить размер, структуру и актуальность файла без полной вычитки его содержимого.
 * Информация включает: размер на диске, количество строк (для текстовых файлов),
 * кодировку и дату последнего изменения.
 */
public class FileInfoTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_file_info";
    }

    @Override
    public String getDescription() {
        return "Get file metadata: size, lines, encoding.";
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
        // Разрешаем получение информации даже о защищенных файлах (например, .git/config) для анализа окружения
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found or is not a regular file: '" + pathStr + "'. Information can only be retrieved for regular files.");
        }

        // Чтение атрибутов файловой системы
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        long size = attrs.size();

        // Быстрая проверка кодировки
        Charset charset = EncodingUtils.detectEncoding(path);

        // Попытка подсчета количества строк (эффективно только для текстовых файлов)
        long lineCount = 0;
        try {
            lineCount = Files.lines(path, charset).count();
        } catch (Exception e) {
            // Если файл бинарный или кодировка несовместима — помечаем как неопределенное количество
            lineCount = -1;
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");

        // Формирование итогового текстового отчета для LLM
        StringBuilder sb = new StringBuilder();
        sb.append("File: ").append(path.toAbsolutePath()).append("\n");
        sb.append("Size: ").append(size).append(" bytes\n");
        sb.append("Encoding: ").append(charset.name()).append("\n");
        if (lineCount >= 0) {
            sb.append("Lines: ").append(lineCount).append("\n");
        }
        sb.append("Last modified: ").append(attrs.lastModifiedTime());

        text.put("text", sb.toString());
        return result;
    }
}