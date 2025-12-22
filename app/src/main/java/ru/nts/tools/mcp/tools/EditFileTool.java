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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Инструмент для редактирования файлов.
 * Устойчив к ошибкам LLM и поддерживает проверку ожидаемого содержимого.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Редактирует файл. Поддерживает замену текста и замену диапазона строк с проверкой expectedContent.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        props.putObject("oldText").put("type", "string").put("description", "Текст для замены");
        props.putObject("newText").put("type", "string").put("description", "Новый текст");
        props.putObject("startLine").put("type", "integer").put("description", "Начальная строка (от 1)");
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка (от 1)");
        props.putObject("expectedContent").put("type", "string").put("description", "Ожидаемое содержимое диапазона строк для проверки");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Файл не найден: " + pathStr);
        }

        if (!AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Доступ запрещен: нельзя редактировать файл, который не был прочитан. Используйте read_file.");
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        String content = Files.readString(path, charset);
        String resultMsg;

        if (params.has("oldText") && params.has("newText")) {
            String oldText = params.get("oldText").asText();
            String newText = params.get("newText").asText();
            
            content = performSmartReplace(content, oldText, newText);
            Files.writeString(path, content, charset);
            resultMsg = "Успешно заменено вхождение текста.";
        } else if (params.has("startLine") && params.has("endLine") && params.has("newText")) {
            int start = params.get("startLine").asInt();
            int end = params.get("endLine").asInt();
            String newText = params.get("newText").asText();
            String expectedContent = params.path("expectedContent").asText(null);
            
            String[] lines = content.split("\n", -1);
            
            // Валидация диапазона
            if (start < 1 || end > lines.length || start > end) {
                throw new IllegalArgumentException("Неверный диапазон строк: " + start + "-" + end + ". В файле " + lines.length + " строк.");
            }

            // Проверка expectedContent
            if (expectedContent != null) {
                StringBuilder actualPart = new StringBuilder();
                for (int i = start - 1; i < end; i++) {
                    actualPart.append(lines[i].replace("\r", ""));
                    if (i < end - 1) actualPart.append("\n");
                }
                
                String normActual = actualPart.toString().replace("\r", "");
                String normExpected = expectedContent.replace("\r", "");
                
                if (!normActual.equals(normExpected)) {
                    throw new IllegalStateException("Контроль содержимого не пройден! Ожидалось:\n[" + normExpected + "]\nНо на строках " + start + "-" + end + " сейчас:\n[" + normActual + "]");
                }
            }

            List<String> resultLines = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                int currentLineNum = i + 1;
                if (currentLineNum == start) {
                    resultLines.add(newText);
                }
                if (currentLineNum < start || currentLineNum > end) {
                    resultLines.add(lines[i].replace("\r", ""));
                }
            }
            
            Files.writeString(path, String.join("\n", resultLines), charset);
            resultMsg = "Успешно заменен диапазон строк " + start + "-" + end;
        } else {
            throw new IllegalArgumentException("Недостаточно параметров для редактирования.");
        }

        ObjectNode result = mapper.createObjectNode();
        var contentArray = result.putArray("content");
        contentArray.addObject().put("type", "text").put("text", resultMsg);
        return result;
    }

    private String performSmartReplace(String content, String oldText, String newText) {
        if (content.contains(oldText)) {
            return content.replace(oldText, newText);
        }

        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");
        if (normContent.contains(normOld)) {
            return normContent.replace(normOld, newText);
        }

        String escapedOld = escapeRegexExceptWhitespace(normOld);
        String regex = escapedOld.replaceAll("\s+", "\\\\s+");
        
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(normContent);
        
        if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (matcher.find()) {
                throw new IllegalArgumentException("Найдено более одного совпадения при нечетком поиске. Укажите более точный контекст.");
            }
            return normContent.substring(0, start) + newText + normContent.substring(end);
        }

        throw new IllegalArgumentException("Текст не найден. Проверьте правильность oldText или используйте номера строк с expectedContent.");
    }

    private String escapeRegexExceptWhitespace(String input) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
            } else if ("<([{\\^-=$!|]})?*+.>".indexOf(c) != -1) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}