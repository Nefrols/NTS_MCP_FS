// Aristo 22.12.2025
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
import java.util.ArrayList;
import java.util.List;

/**
 * Инструмент для редактирования файлов.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Редактирует файл с автоматическим определением кодировки. Поддерживает замену подстроки или замену диапазона строк.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        props.putObject("oldText").put("type", "string").put("description", "Текст для замены");
        props.putObject("newText").put("type", "string").put("description", "Новый текст");
        props.putObject("startLine").put("type", "integer").put("description", "Начальная строка диапазона (от 0)");
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка диапазона (от 0)");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false); // Запрещаем редактирование защищенных файлов

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Файл не найден: " + pathStr);
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        String resultMsg;
        if (params.has("oldText") && params.has("newText")) {
            String content = Files.readString(path, charset);
            String oldText = params.get("oldText").asText();
            String newText = params.get("newText").asText();
            if (!content.contains(oldText)) {
                throw new IllegalArgumentException("Текст для замены не найден в файле.");
            }
            Files.writeString(path, content.replace(oldText, newText), charset);
            resultMsg = "Успешно заменено вхождение текста.";
        } else if (params.has("startLine") && params.has("endLine") && params.has("newText")) {
            int start = params.get("startLine").asInt();
            int end = params.get("endLine").asInt();
            String newText = params.get("newText").asText();
            
            List<String> lines = Files.readAllLines(path, charset);
            List<String> newLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (i == start) {
                    newLines.add(newText);
                }
                if (i < start || i > end) {
                    newLines.add(lines.get(i));
                }
            }
            Files.write(path, newLines, charset);
            resultMsg = "Успешно заменен диапазон строк " + start + "-" + end;
        } else {
            throw new IllegalArgumentException("Недостаточно параметров для редактирования.");
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", resultMsg);
        return result;
    }
}