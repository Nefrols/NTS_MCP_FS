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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Инструмент для чтения содержимого файла.
 * Поддерживает чтение по диапазонам и умное чтение вокруг контекста.
 */
public class ReadFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Читает содержимое файла. Поддерживает диапазоны строк и поиск контекста (contextStartPattern + contextRange).";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        props.putObject("startLine").put("type", "integer").put("description", "Начальная строка (от 1)");
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка (от 1)");
        props.putObject("line").put("type", "integer").put("description", "Конкретная строка (от 1)");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Паттерн для поиска якоря");
        props.putObject("contextRange").put("type", "integer").put("description", "Количество строк вокруг паттерна (по умолчанию 0)");
        
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
                throw new IllegalArgumentException("Паттерн не найден: " + patternStr);
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

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", resultText);
        return result;
    }
}