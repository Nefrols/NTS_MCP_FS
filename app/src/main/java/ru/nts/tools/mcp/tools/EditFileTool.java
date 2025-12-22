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
 * Расширенный инструмент для редактирования файлов.
 * Поддерживает типизированные операции, пакетную обработку и автоматический пересчет смещений.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Редактирует файл. Поддерживает одиночные замены или массив операций (replace, delete, insert_before, insert_after). " +
               "Автоматически корректирует номера строк при пакетном редактировании.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        
        // Одиночная операция (совместимость)
        props.putObject("oldText").put("type", "string").put("description", "Текст для замены");
        props.putObject("newText").put("type", "string").put("description", "Новый текст");
        props.putObject("startLine").put("type", "integer").put("description", "Начальная строка");
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка");
        props.putObject("expectedContent").put("type", "string").put("description", "Ожидаемый контент");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Паттерн якоря");

        // Пакетные операции
        var ops = props.putObject("operations");
        ops.put("type", "array");
        var item = ops.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("operation").put("type", "string").put("enum", mapper.createArrayNode().add("replace").add("delete").add("insert_before").add("insert_after"));
        itemProps.putObject("startLine").put("type", "integer");
        itemProps.putObject("endLine").put("type", "integer");
        itemProps.putObject("line").put("type", "integer").put("description", "Используется для insert_before/after вместо диапазона");
        itemProps.putObject("content").put("type", "string").put("description", "Новый текст для вставки/замены");
        itemProps.putObject("expectedContent").put("type", "string");
        itemProps.putObject("contextStartPattern").put("type", "string");

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
            throw new SecurityException("Доступ запрещен: файл не был прочитан. Сначала вызовите read_file.");
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        String content = Files.readString(path, charset);
        
        List<String> currentLines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        int lineOffset = 0;
        int operationsApplied = 0;

        if (params.has("operations")) {
            // Пакетная обработка
            JsonNode ops = params.get("operations");
            for (JsonNode opNode : ops) {
                lineOffset += applyTypedOperation(currentLines, opNode, lineOffset);
                operationsApplied++;
            }
        } else if (params.has("oldText") && params.has("newText")) {
            // Умная замена текста
            String oldText = params.get("oldText").asText();
            String newText = params.get("newText").asText();
            String newContent = performSmartReplace(content, oldText, newText);
            Files.writeString(path, newContent, charset);
            return createResponse("Успешно заменено вхождение текста.");
        } else if (params.has("startLine") && params.has("newText")) {
            // Одиночная замена диапазона (совместимость)
            applyTypedOperation(currentLines, params, 0);
            operationsApplied = 1;
        } else {
            throw new IllegalArgumentException("Недостаточно параметров для редактирования.");
        }

        Files.writeString(path, String.join("\n", currentLines), charset);
        return createResponse("Успешно применено операций: " + operationsApplied);
    }

    private int applyTypedOperation(List<String> lines, JsonNode op, int cumulativeOffset) {
        String type = op.path("operation").asText("replace");
        int requestedStart = op.path("startLine").asInt(op.path("line").asInt(0));
        int requestedEnd = op.path("endLine").asInt(requestedStart);
        String newText = op.path("content").asText(op.path("newText").asText(""));
        String expected = op.path("expectedContent").asText(null);
        String contextPattern = op.path("contextStartPattern").asText(null);

        // Поиск якоря
        int anchorLine = 0;
        if (contextPattern != null) {
            anchorLine = findAnchorLine(lines, contextPattern);
            if (anchorLine == -1) throw new IllegalArgumentException("Контекст не найден: " + contextPattern);
        }

        // Вычисление абсолютных индексов с учетом смещения от предыдущих операций
        int start = anchorLine + requestedStart + cumulativeOffset;
        int end = anchorLine + requestedEnd + cumulativeOffset;

        // Корректировка для специфичных операций
        if ("insert_before".equals(type)) {
            end = start - 1;
        } else if ("insert_after".equals(type)) {
            start++;
            end = start - 1;
        } else if ("delete".equals(type)) {
            newText = null; // Пометка на удаление
        }

        // Валидация
        if (start < 1 || (start > lines.size() + 1) || (end < start - 1) || (end > lines.size())) {
            throw new IllegalArgumentException("Ошибка адресации: " + start + "-" + end + " (размер " + lines.size() + ")");
        }

        // Проверка expectedContent
        if (expected != null && end >= start) {
            String actual = lines.subList(start - 1, end).stream()
                    .map(l -> l.replace("\r", ""))
                    .collect(Collectors.joining("\n"));
            if (!actual.replace("\r", "").equals(expected.replace("\r", ""))) {
                throw new IllegalStateException("Контроль содержимого не пройден!");
            }
        }

        // Применение изменений
        int oldLineCount = (end >= start) ? (end - start + 1) : 0;
        String indentation = (start > 1) ? getIndentation(lines.get(start - 2)) : "";
        String indentedText = (newText != null) ? applyIndentation(newText, indentation) : null;
        
        // Удаляем старые строки
        for (int i = 0; i < oldLineCount; i++) {
            lines.remove(start - 1);
        }
        
        // Вставляем новые если есть
        int addedLinesCount = 0;
        if (indentedText != null) {
            String[] newLines = indentedText.split("\n", -1);
            for (int i = 0; i < newLines.length; i++) {
                lines.add(start - 1 + i, newLines[i]);
            }
            addedLinesCount = newLines.length;
        }

        return addedLinesCount - oldLineCount;
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }

    private String getIndentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private String applyIndentation(String text, String indentation) {
        if (indentation.isEmpty()) return text;
        return Arrays.stream(text.split("\n", -1))
                .map(line -> line.isEmpty() ? line : indentation + line)
                .collect(Collectors.joining("\n"));
    }

    private int findAnchorLine(List<String> lines, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) return i;
        }
        return -1;
    }

    private String performSmartReplace(String content, String oldText, String newText) {
        if (content.contains(oldText)) return content.replace(oldText, newText);
        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");
        if (normContent.contains(normOld)) return normContent.replace(normOld, newText);
        String escapedOld = escapeRegexExceptWhitespace(normOld);
        String regex = escapedOld.replaceAll("\\s+", "\\\\s+");
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(normContent);
        if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (matcher.find()) throw new IllegalArgumentException("Неоднозначное совпадение.");
            return normContent.substring(0, start) + newText + normContent.substring(end);
        }
        throw new IllegalArgumentException("Текст не найден.");
    }

    private String escapeRegexExceptWhitespace(String input) {
        StringBuilder sb = new StringBuilder();
        String specials = "<([{\\^-=$!|]})?*+.>";
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
            } else if (specials.indexOf(c) != -1) {
                sb.append("\\").append(c); // Используем строковой литерал "\"
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}