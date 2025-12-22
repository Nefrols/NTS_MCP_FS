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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Инструмент для редактирования файлов.
 * Поддерживает контекстную адресацию, проверку содержимого и автоматический отступ.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Редактирует файл. Поддерживает контекстную адресацию, проверку expectedContent и автоматический отступ.";
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
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка (от 1). Если меньше startLine, производится вставка.");
        props.putObject("expectedContent").put("type", "string").put("description", "Ожидаемое содержимое диапазона");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Регулярное выражение для поиска строки-якоря");
        
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
            throw new SecurityException("Доступ запрещен: файл не был прочитан.");
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        String content = Files.readString(path, charset);
        String[] lines = content.split("\n", -1);
        String resultMsg;

        if (params.has("oldText") && params.has("newText")) {
            String oldText = params.get("oldText").asText();
            String newText = params.get("newText").asText();
            content = performSmartReplace(content, oldText, newText);
            Files.writeString(path, content, charset);
            resultMsg = "Успешно заменено вхождение текста.";
        } else if (params.has("startLine") && params.has("endLine") && params.has("newText")) {
            int requestedStart = params.get("startLine").asInt();
            int requestedEnd = params.get("endLine").asInt();
            String newText = params.get("newText").asText();
            String expectedContent = params.path("expectedContent").asText(null);
            String contextPattern = params.path("contextStartPattern").asText(null);
            
            int anchorLine = 0; 
            if (contextPattern != null) {
                anchorLine = findAnchorLine(lines, contextPattern);
                if (anchorLine == -1) {
                    throw new IllegalArgumentException("Контекстный паттерн не найден: " + contextPattern);
                }
            }

            int start = anchorLine + requestedStart; 
            int end = anchorLine + requestedEnd;

            // Разрешаем end = start - 1 для вставки
            if (start < 1 || start > lines.length + 1 || (end < start - 1) || (end > lines.length)) {
                throw new IllegalArgumentException("Ошибка адресации. Абсолютный диапазон: " + start + "-" + end + " (Файл: " + lines.length + " строк).");
            }

            // Валидация содержимого (только если диапазон не пустой)
            if (expectedContent != null && end >= start) {
                StringBuilder actualPart = new StringBuilder();
                for (int i = start - 1; i < end; i++) {
                    actualPart.append(lines[i].replace("\r", ""));
                    if (i < end - 1) actualPart.append("\n");
                }
                String normActual = actualPart.toString().replace("\r", "");
                String normExpected = expectedContent.replace("\r", "");
                if (!normActual.equals(normExpected)) {
                    throw new IllegalStateException("Контроль содержимого не пройден!");
                }
            }

            // Автоматический отступ (берем от предыдущей строки)
            String indentation = "";
            if (start > 1) {
                indentation = getIndentation(lines[start - 2]);
            } else if (lines.length > 0) {
                // Если вставляем в начало, можем попробовать взять отступ первой строки, 
                // но обычно в начале файла отступа нет. Оставим пустым.
            }
            
            String indentedNewText = applyIndentation(newText, indentation);

            List<String> resultLines = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                int currentLineNum = i + 1;
                if (currentLineNum == start) {
                    resultLines.add(indentedNewText);
                }
                if (currentLineNum < start || currentLineNum > end) {
                    resultLines.add(lines[i].replace("\r", ""));
                }
            }
            // Случай вставки в самый конец файла
            if (start == lines.length + 1) {
                resultLines.add(indentedNewText);
            }

            Files.writeString(path, String.join("\n", resultLines), charset);
            resultMsg = "Успешно изменен диапазон строк " + start + "-" + end;
        } else {
            throw new IllegalArgumentException("Недостаточно параметров.");
        }

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", resultMsg);
        return result;
    }

    private String getIndentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private String applyIndentation(String text, String indentation) {
        if (indentation.isEmpty()) return text;
        String[] textLines = text.split("\n", -1);
        return Arrays.stream(textLines)
                .map(line -> line.isEmpty() ? line : indentation + line)
                .collect(Collectors.joining("\n"));
    }

    private int findAnchorLine(String[] lines, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).find()) {
                return i;
            }
        }
        return -1;
    }

    private String performSmartReplace(String content, String oldText, String newText) {
        if (content.contains(oldText)) return content.replace(oldText, newText);
        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");
        if (normContent.contains(normOld)) return normContent.replace(normOld, newText);

        String regex = escapeRegexExceptWhitespace(normOld).replaceAll("\\s+", "\\\\s+");
        Pattern p = Pattern.compile(regex, Pattern.MULTILINE | Pattern.DOTALL);
        Matcher m = p.matcher(normContent);
        if (m.find()) {
            int s = m.start();
            int e = m.end();
            if (m.find()) throw new IllegalArgumentException("Неоднозначное совпадение.");
            return normContent.substring(0, s) + newText + normContent.substring(e);
        }
        throw new IllegalArgumentException("Текст не найден.");
    }

    private String escapeRegexExceptWhitespace(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if (Character.isWhitespace(c)) sb.append(c);
            else if ("<([{\\^-=$!|]})?*+.>".indexOf(c) != -1) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.toString();
    }
}