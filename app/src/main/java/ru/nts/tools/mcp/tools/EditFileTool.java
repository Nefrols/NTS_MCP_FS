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
 * Поддерживает:
 * - Одиночные замены текста.
 * - Пакетное редактирование (batching) с автоматическим пересчетом смещений строк.
 * - Типизированные операции: replace, delete, insert_before, insert_after.
 * - Контекстную адресацию через регулярные выражения (якоря).
 * - Автоматическое наследование отступов (auto-indentation).
 * - Проактивную диагностику при несовпадении expectedContent.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Редактирует файл. Позволяет заменять текст, удалять или вставлять строки. " +
               "Поддерживает пакетные операции и автоматический пересчет индексов строк.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к файлу");
        
        // Поля для одиночной операции (для обратной совместимости)
        props.putObject("oldText").put("type", "string").put("description", "Текст для замены");
        props.putObject("newText").put("type", "string").put("description", "Новый текст");
        props.putObject("startLine").put("type", "integer").put("description", "Начальная строка (от 1)");
        props.putObject("endLine").put("type", "integer").put("description", "Конечная строка (от 1)");
        props.putObject("expectedContent").put("type", "string").put("description", "Ожидаемое содержимое диапазона");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Паттерн для поиска строки-якоря");

        // Поле для пакетных операций
        var ops = props.putObject("operations");
        ops.put("type", "array");
        var item = ops.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("operation").put("type", "string")
                .put("enum", mapper.createArrayNode().add("replace").add("delete").add("insert_before").add("insert_after"));
        itemProps.putObject("startLine").put("type", "integer");
        itemProps.putObject("endLine").put("type", "integer");
        itemProps.putObject("line").put("type", "integer").put("description", "Целевая строка для вставки");
        itemProps.putObject("content").put("type", "string").put("description", "Новый контент");
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

        // Проверка предохранителя: файл должен быть прочитан перед редактированием
        if (!AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Доступ запрещен: нельзя редактировать файл, который не был прочитан в текущей сессии. Используйте read_file.");
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        String content = Files.readString(path, charset);
        
        // Разделяем контент на строки, сохраняя пустые строки в конце
        List<String> currentLines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        int lineOffset = 0;
        int operationsApplied = 0;

        try {
            if (params.has("operations")) {
                // Обработка пакета операций
                JsonNode ops = params.get("operations");
                for (JsonNode opNode : ops) {
                    // Каждая операция возвращает изменение количества строк для коррекции последующих индексов
                    lineOffset += applyTypedOperation(currentLines, opNode, lineOffset);
                    operationsApplied++;
                }
            } else if (params.has("oldText") && params.has("newText")) {
                // Классическая замена текста по содержимому (fuzzy match)
                String oldText = params.get("oldText").asText();
                String newText = params.get("newText").asText();
                String newContent = performSmartReplace(content, oldText, newText);
                Files.writeString(path, newContent, charset);
                return createResponse("Успешно заменено вхождение текста.");
            } else if (params.has("startLine") && params.has("newText")) {
                // Одиночная замена диапазона (через общий механизм операций)
                applyTypedOperation(currentLines, params, 0);
                operationsApplied = 1;
            } else {
                throw new IllegalArgumentException("Недостаточно параметров для редактирования.");
            }

            // Физическая запись на диск происходит только если ВСЕ операции прошли успешно (атомарность)
            Files.writeString(path, String.join("\n", currentLines), charset);
            return createResponse("Успешно применено операций: " + operationsApplied);
        } catch (IllegalStateException e) {
            // Ошибки валидации (например, expectedContent) пробрасываются наружу с диагностикой
            throw e;
        }
    }

    /**
     * Применяет типизированную операцию к списку строк.
     * Реализует логику смещений, контекстной адресации и автоматических отступов.
     *
     * @param lines Текущий список строк файла.
     * @param op Узел JSON с описанием операции.
     * @param cumulativeOffset Накопленное смещение строк от предыдущих операций в батче.
     * @return Изменение общего количества строк в файле после этой операции.
     */
    private int applyTypedOperation(List<String> lines, JsonNode op, int cumulativeOffset) {
        String type = op.path("operation").asText("replace");
        int requestedStart = op.path("startLine").asInt(op.path("line").asInt(0));
        int requestedEnd = op.path("endLine").asInt(requestedStart);
        String newText = op.path("content").asText(op.path("newText").asText(""));
        String expected = op.path("expectedContent").asText(null);
        String contextPattern = op.path("contextStartPattern").asText(null);

        // 1. Поиск якоря (контекстная адресация)
        int anchorLine = 0;
        if (contextPattern != null) {
            anchorLine = findAnchorLine(lines, contextPattern);
            if (anchorLine == -1) {
                throw new IllegalArgumentException("Контекстный паттерн не найден: " + contextPattern);
            }
        }

        // 2. Вычисление абсолютных индексов с учетом смещения
        int start = anchorLine + requestedStart + cumulativeOffset;
        int end = anchorLine + requestedEnd + cumulativeOffset;

        // 3. Корректировка индексов под тип операции
        if ("insert_before".equals(type)) {
            end = start - 1; // Диапазон становится пустым перед целевой строкой
        } else if ("insert_after".equals(type)) {
            start++;
            end = start - 1; // Диапазон становится пустым после целевой строки
        } else if ("delete".equals(type)) {
            newText = null; // Пометка на удаление без вставки
        }

        // 4. Валидация диапазона
        if (start < 1 || (start > lines.size() + 1) || (end < start - 1) || (end > lines.size())) {
            throw new IllegalArgumentException("Ошибка адресации: " + start + "-" + end + " (размер файла: " + lines.size() + ")");
        }

        // 5. Проверка ожидаемого содержимого (expectedContent) с проактивной диагностикой
        if (expected != null && end >= start) {
            String actual = lines.subList(start - 1, end).stream()
                    .map(l -> l.replace("\r", ""))
                    .collect(Collectors.joining("\n"));
            
            String normActual = actual.replace("\r", "");
            String normExpected = expected.replace("\r", "");
            
            if (!normActual.equals(normExpected)) {
                throw new IllegalStateException("Контроль содержимого не пройден!\n" +
                        "ОЖИДАЛОСЬ:\n[" + normExpected + "]\n" +
                        "АКТУАЛЬНОЕ СОДЕРЖИМОЕ В ДИАПАЗОНЕ " + start + "-" + end + ":\n[" + normActual + "]\n" +
                        "Пожалуйста, используйте актуальное содержимое для формирования корректного запроса.");
            }
        }

        // 6. Определение отступа (auto-indentation)
        int oldLineCount = (end >= start) ? (end - start + 1) : 0;
        String indentation = (start > 1) ? getIndentation(lines.get(start - 2)) : "";
        String indentedText = (newText != null) ? applyIndentation(newText, indentation) : null;
        
        // 7. Применение изменений к списку строк
        // Удаляем старые строки
        for (int i = 0; i < oldLineCount; i++) {
            lines.remove(start - 1);
        }
        
        // Вставляем новые строки
        int addedLinesCount = 0;
        if (indentedText != null) {
            String[] newLines = indentedText.split("\n", -1);
            for (int i = 0; i < newLines.length; i++) {
                lines.add(start - 1 + i, newLines[i]);
            }
            addedLinesCount = newLines.length;
        }

        // Возвращаем дельту количества строк
        return addedLinesCount - oldLineCount;
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }

    /**
     * Извлекает отступ (пробелы и табы) из строки.
     */
    private String getIndentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /**
     * Применяет указанный отступ к каждой непустой строке текста.
     */
    private String applyIndentation(String text, String indentation) {
        if (indentation.isEmpty()) return text;
        return Arrays.stream(text.split("\n", -1))
                .map(line -> line.isEmpty() ? line : indentation + line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Находит индекс строки, соответствующей паттерну.
     */
    private int findAnchorLine(List<String> lines, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) return i;
        }
        return -1;
    }

    /**
     * Выполняет умную замену текста, устойчивую к различиям в форматировании.
     */
    private String performSmartReplace(String content, String oldText, String newText) {
        // Точное совпадение
        if (content.contains(oldText)) return content.replace(oldText, newText);

        // Совпадение с нормализацией переносов
        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");
        if (normContent.contains(normOld)) return normContent.replace(normOld, newText);

        // Fuzzy match: игнорируем количество пробелов
        String escapedOld = escapeRegexExceptWhitespace(normOld);
        String regex = escapedOld.replaceAll("\\s+", "\\\\s+");
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
        throw new IllegalArgumentException("Текст не найден. Проверьте правильность oldText или используйте редактирование по строкам.");
    }

    /**
     * Экранирует спецсимволы регулярных выражений, сохраняя пробелы нетронутыми.
     */
    private String escapeRegexExceptWhitespace(String input) {
        StringBuilder sb = new StringBuilder();
        String specials = "<([{\\^-=$!|]})?*+.>";
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                sb.append(c);
            } else if (specials.indexOf(c) != -1) {
                sb.append("\\").append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}