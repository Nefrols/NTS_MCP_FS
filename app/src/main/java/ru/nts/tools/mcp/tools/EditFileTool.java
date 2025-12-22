// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.GitUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

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
 * Инновационные возможности:
 * 1. Пакетное редактирование (Batching): Применение нескольких правок за один вызов.
 * 2. Multi-file Batching: Возможность редактировать несколько файлов одновременно в одной транзакции.
 * 3. Автоматический пересчет смещений: Инструмент корректирует индексы строк последующих операций в батче.
 * 4. Контроль содержимого (expectedContent): Предотвращает ошибки, если файл изменился параллельно.
 * 5. Авто-отступ (Auto-indentation): Наследует форматирование окружающего кода.
 * 6. Нечеткий поиск (Fuzzy Match): Устойчивость к различиям в пробелах и типах переносов строк (\n vs \r\n).
 * 7. Проактивная диагностика: При ошибке контроля возвращает актуальное содержимое строк для самокоррекции.
 * 8. Транзакционность: Все изменения в батче атомарны.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Smart editing of one or multiple files. Supports batch operations, automatic indentation, and global atomicity.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        
        // Target path (для одиночного файла)
        props.putObject("path").put("type", "string").put("description", "Path to the file (if editing a single file).");
        
        // Список правок для нескольких файлов (Multi-file batching)
        var edits = props.putObject("edits");
        edits.put("type", "array");
        var editItem = edits.putObject("items");
        editItem.put("type", "object");
        var editProps = editItem.putObject("properties");
        editProps.putObject("path").put("type", "string").put("description", "Path to the file.");
        editProps.set("operations", mapper.createObjectNode().put("type", "array").put("description", "Array of operations for this file."));
        
        // Поля для одиночной операции (для обратной совместимости)
        editProps.putObject("oldText").put("type", "string").put("description", "Text to replace literally.");
        editProps.putObject("newText").put("type", "string").put("description", "New text.");
        editProps.putObject("startLine").put("type", "integer").put("description", "Start line (from 1).");
        editProps.putObject("endLine").put("type", "integer").put("description", "End line (inclusive).");
        editProps.putObject("expectedContent").put("type", "string").put("description", "Expected text for validation.");

        // Поля для обратной совместимости корневого уровня
        props.putObject("oldText").put("type", "string").put("description", "Text to replace literally.");
        props.putObject("newText").put("type", "string").put("description", "New text.");
        props.putObject("startLine").put("type", "integer").put("description", "Start line (from 1).");
        props.putObject("endLine").put("type", "integer").put("description", "End line (inclusive).");
        props.putObject("expectedContent").put("type", "string").put("description", "Expected text for validation.");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Regex anchor pattern.");

        // Поддержка массива операций для одиночного файла
        var ops = props.putObject("operations");
        ops.put("type", "array").put("description", "Array of operations for the file.");
        
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Определяем режим работы: несколько файлов или один
        if (params.has("edits")) {
            return executeMultiFileEdit(params.get("edits"));
        } else if (params.has("path")) {
            return executeSingleFileEdit(params);
        } else {
            throw new IllegalArgumentException("Must specify 'path' or 'edits'.");
        }
    }

    /**
     * Выполняет редактирование нескольких файлов в рамках одной транзакции.
     * 
     * @param editsArray Массив объектов с описанием правок для каждого файла.
     * @return Ответ с количеством успешно обработанных файлов.
     */
    private JsonNode executeMultiFileEdit(JsonNode editsArray) throws Exception {
        // Открываем одну глобальную транзакцию на весь батч файлов
        TransactionManager.startTransaction("Multi-file batch edit (" + editsArray.size() + " files)");
        try {
            StringBuilder statusMsg = new StringBuilder();
            statusMsg.append("Successfully updated ").append(editsArray.size()).append(" files:\n");
            
            for (JsonNode editNode : editsArray) {
                Path path = applyFileEdits(editNode);
                String gitStatus = GitUtils.getFileStatus(path);
                statusMsg.append("- ").append(path.getFileName());
                if (!gitStatus.isEmpty()) statusMsg.append(" [Git: ").append(gitStatus).append("]");
                statusMsg.append("\n");
            }
            TransactionManager.commit();
            return createResponse(statusMsg.toString().trim());
        } catch (Exception e) {
            // При ошибке в ЛЮБОМ файле откатываем изменения во ВСЕХ файлах транзакции
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Выполняет редактирование одиночного файла.
     * 
     * @param params Параметры запроса для одного файла.
     * @return Ответ сервера.
     */
    private JsonNode executeSingleFileEdit(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        // Транзакция для одного файла
        TransactionManager.startTransaction("Edit file: " + pathStr);
        try {
            Path path = applyFileEdits(params);
            TransactionManager.commit();
            
            String gitStatus = GitUtils.getFileStatus(path);
            String msg = "Edits successfully applied to file: " + pathStr;
            if (!gitStatus.isEmpty()) msg += " [Git: " + gitStatus + "]";
            
            return createResponse(msg);
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Применяет набор правок к конкретному файлу.
     * Выполняет чтение, бэкап и последовательное применение операций.
     * 
     * @param fileParams Параметры правок для конкретного файла (path + operations/text).
     * @return Путь к измененному файлу.
     */
    private Path applyFileEdits(JsonNode fileParams) throws Exception {
        String pathStr = fileParams.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + pathStr);
        }

        // Проверка предохранителя: файл должен быть прочитан перед модификацией
        if (!AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Access denied: file " + pathStr + " has not been read in current session.");
        }

        Charset charset = EncodingUtils.detectEncoding(path);
        String content = Files.readString(path, charset);
        
        // Разделяем контент на строки для корректной работы батчинга по индексам
        List<String> currentLines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        
        // Создаем резервную копию файла внутри текущей транзакции
        TransactionManager.backup(path);

        if (fileParams.has("operations")) {
            // Выполнение пакета правок для файла.
            // Для корректной работы индексов сортируем операции по убыванию номера строки (обратный порядок).
            List<JsonNode> sortedOps = new ArrayList<>();
            fileParams.get("operations").forEach(sortedOps::add);
            sortedOps.sort((a, b) -> {
                int lineA = a.path("startLine").asInt(a.path("line").asInt(0));
                int lineB = b.path("startLine").asInt(b.path("line").asInt(0));
                return Integer.compare(lineB, lineA);
            });

            for (JsonNode opNode : sortedOps) {
                // При обработке снизу вверх смещение вышестоящих строк не происходит
                applyTypedOperation(currentLines, opNode, 0);
            }
        } else if (fileParams.has("oldText") && fileParams.has("newText")) {
            // Режим нечеткой замены текста (fuzzy match)
            String newContent = performSmartReplace(content, fileParams.get("oldText").asText(), fileParams.get("newText").asText());
            Files.writeString(path, newContent, charset);
            return path;
        } else if (fileParams.has("startLine") && (fileParams.has("newText") || fileParams.has("content"))) {
            // Одиночная правка диапазона (для совместимости)
            applyTypedOperation(currentLines, fileParams, 0);
        } else {
            throw new IllegalArgumentException("Insufficient parameters for file: " + pathStr);
        }

        // Физическая запись промежуточного результата (транзакция защищает файл)
        Files.writeString(path, String.join("\n", currentLines), charset);
        return path;
    }

    /**
     * Выполняет конкретную типизированную операцию над списком строк.
     * Реализует:
     * - Контекстную адресацию (якоря).
     * - Автоматический расчет абсолютных индексов с учетом накопленного смещения.
     * - Проверку содержимого (expectedContent) с диагностикой.
     * - Наследование отступов (auto-indentation).
     * 
     * @return Дельта изменения количества строк.
     */
    private int applyTypedOperation(List<String> lines, JsonNode op, int cumulativeOffset) {
        String type = op.path("operation").asText("replace");
        int requestedStart = op.path("startLine").asInt(op.path("line").asInt(0));
        int requestedEnd = op.path("endLine").asInt(requestedStart);
        String newText = op.path("content").asText(op.path("newText").asText(""));
        String expected = op.path("expectedContent").asText(null);
        String contextPattern = op.path("contextStartPattern").asText(null);

        // 1. Контекстная адресация (поиск якоря)
        int anchorLine = 0;
        if (contextPattern != null) {
            anchorLine = findAnchorLine(lines, contextPattern);
            if (anchorLine == -1) {
                throw new IllegalArgumentException("Context pattern not found: " + contextPattern);
            }
        }

        // 2. Расчет итоговых индексов с учетом батчинга и смещений
        int start = anchorLine + requestedStart + cumulativeOffset;
        int end = anchorLine + requestedEnd + cumulativeOffset;

        // 3. Корректировка индексов под специфику операций
        if ("insert_before".equals(type)) {
            end = start - 1;
        } else if ("insert_after".equals(type)) {
            start++;
            end = start - 1;
        } else if ("delete".equals(type)) {
            newText = null; // Пометка на удаление
        }

        // 4. Валидация границ в текущем состоянии списка строк
        if (start < 1 || (start > lines.size() + 1) || (end < start - 1) || (end > lines.size())) {
            throw new IllegalArgumentException("Addressing error: " + start + "-" + end + " (file size: " + lines.size() + ")");
        }

        // 5. Контроль содержимого с проактивной диагностикой
        if (expected != null && end >= start) {
            String actual = lines.subList(start - 1, end).stream()
                    .map(l -> l.replace("\r", ""))
                    .collect(Collectors.joining("\n"));
            
            String normActual = actual.replace("\r", "");
            String normExpected = expected.replace("\r", "");
            
            if (!normActual.equals(normExpected)) {
                throw new IllegalStateException("Content validation failed!\n" +
                        "EXPECTED:\n[" + normExpected + "]\n" +
                        "ACTUAL CONTENT IN RANGE " + start + "-" + end + ":\n[" + normActual + "]\n" +
                        "Please adjust your request using the actual text.");
            }
        }

        // 6. Реализация auto-indentation: берем отступ строки перед началом диапазона
        int oldLineCount = (end >= start) ? (end - start + 1) : 0;
        int indentLineIdx = (start > 1) ? (start - 2) : (start - 1);
        String indentation = (indentLineIdx >= 0 && indentLineIdx < lines.size()) ? getIndentation(lines.get(indentLineIdx)) : "";
        
        // 7. Манипуляция строками в памяти
        // Удаляем заменяемый блок
        for (int i = 0; i < oldLineCount; i++) {
            lines.remove(start - 1);
        }
        
        // Вставляем новый блок с сохранением отступа
        int addedLinesCount = 0;
        if (newText != null) {
            String indentedText = applyIndentation(newText, indentation);
            String[] newLines = indentedText.split("\n", -1);
            for (int i = 0; i < newLines.length; i++) {
                lines.add(start - 1 + i, newLines[i]);
            }
            addedLinesCount = newLines.length;
        }

        // Возвращаем дельту количества строк для коррекции последующих операций
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
        // 1. Точное совпадение
        if (content.contains(oldText)) return content.replace(oldText, newText);

        // 2. Совпадение с нормализацией переносов строк
        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");
        if (normContent.contains(normOld)) return normContent.replace(normOld, newText);

        // 3. Fuzzy match: игнорируем количество пробелов и табуляций
        String escapedOld = escapeRegexExceptWhitespace(normOld);
        String regex = escapedOld.replaceAll("\\s+", "\\\\s+");
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(normContent);
        if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (matcher.find()) {
                throw new IllegalArgumentException("Multiple ambiguous matches found during fuzzy search. Provide more context.");
            }
            return normContent.substring(0, start) + newText + normContent.substring(end);
        }
        throw new IllegalArgumentException("Text not found. Use line-based editing with expectedContent.");
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
