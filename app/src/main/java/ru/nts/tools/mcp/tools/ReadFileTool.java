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

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Инструмент для чтения содержимого файла.
 */
public class ReadFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Читает содержимое файла с автоматическим определением кодировки. Можно указать конкретную строку или диапазон строк (от 1).";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        props.putObject("startLine").put("type", "integer").put("description", "Начальная строка (включительно, от 1)");
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка (исключительно, от 1)");
        props.putObject("line").put("type", "integer").put("description", "Конкретная строка для чтения (от 1)");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Файл не найден: " + pathStr);
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        AccessTracker.registerRead(path);
        String contentText;
        if (params.has("line")) {
            int lineNum = params.get("line").asInt();
            try (Stream<String> lines = Files.lines(path, charset)) {
                contentText = lines.skip(Math.max(0, lineNum - 1)).findFirst().orElse("");
            }
        } else if (params.has("startLine") || params.has("endLine")) {
            int start = params.path("startLine").asInt(1);
            int end = params.path("endLine").asInt(Integer.MAX_VALUE);
            try (Stream<String> lines = Files.lines(path, charset)) {
                contentText = lines.skip(Math.max(0, start - 1)).limit(Math.max(0, end - start)).collect(Collectors.joining("\n"));
            }
        } else {
            contentText = Files.readString(path, charset);
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", contentText);
        
        return result;
    }
}
