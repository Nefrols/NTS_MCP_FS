// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.io.IOException;
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
 * Расширенный инструмент для интеллектуального редактирования файлов.
 * Поддерживает:
 * 1. Пакетное редактирование (Batching): Применение множества правок за один вызов.
 * 2. Глобальная атомарность (Multi-file): Редактирование нескольких файлов в одной транзакции.
 * 3. Авто-коррекция индексов: Сортировка операций снизу вверх гарантирует стабильность строк.
 * 4. Контроль целостности (expectedContent): Валидация текущего состояния перед внесением изменений.
 * 5. Умное форматирование: Автоматическое наследование отступов (Auto-indentation).
 * 6. Отказоустойчивость: Интеграция с TransactionManager (UNDO/REDO).
 */
public class EditFileTool implements McpTool {

    /**
     * Объект для работы с JSON структурами.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_edit_file";
    }

    @Override
    public String getDescription() {
        return "Edit file(s) atomically. PREFERRED: Use 'operations' for precise, multi-hunk editing. Supports fuzzy text replacement (less reliable), line ranges, and auto-indent. REQUIRED: read_file first (OR provide valid expectedChecksum to bypass for sequential editing).";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        // Основной путь (для одиночных правок)
        props.putObject("path").put("type", "string").put("description", "Target file path.");

        // Структура для пакетного редактирования нескольких файлов
        var edits = props.putObject("edits");
        edits.put("type", "array");
        var editItem = edits.putObject("items");
        editItem.put("type", "object");
        var editProps = editItem.putObject("properties");
        editProps.putObject("path").put("type", "string").put("description", "File to edit in batch.");
        editProps.set("operations", mapper.createObjectNode().put("type", "array").put("description", "Atomic operations list."));

        // Вспомогательные поля для каждого элемента в батче
        editProps.putObject("oldText").put("type", "string").put("description", "Literal text to replace.");
        editProps.putObject("newText").put("type", "string").put("description", "Replacement text.");
        editProps.putObject("startLine").put("type", "integer").put("description", "1-based start line.");
        editProps.putObject("endLine").put("type", "integer").put("description", "1-based end line.");
        editProps.putObject("expectedContent").put("type", "string").put("description", "Validation string (exact match required).");

        // Поля корневого уровня для одиночных правок (обратная совместимость)
        props.putObject("oldText").put("type", "string").put("description", "Exact text to replace. If not unique, throws error. If not found, attempts fuzzy match. PREFERRED: Use 'operations'.");
        props.putObject("newText").put("type", "string").put("description", "Replacement text.");
        props.putObject("startLine").put("type", "integer").put("description", "Range start.");
        props.putObject("endLine").put("type", "integer").put("description", "Range end.");
        props.putObject("expectedContent").put("type", "string").put("description", "REQUIRED for safety in line edits.");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Regex anchor for relative line indexing.");
        props.putObject("expectedChecksum").put("type", "string").put("description", "CRC32C hex of file before edit. Bypasses read_file requirement if correct. Use the checksum from the previous successful edit to chain operations safely.");
        
        props.putObject("instruction").put("type", "string").put("description", "Semantic label for the transaction (e.g. 'Fix: updated null-check logic').");

        // Массив атомарных операций для одного файла
        var ops = props.putObject("operations");
        ops.put("type", "array").put("description", "Atomic steps for a single file. PREFERRED method for code editing.");

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        // Выбор режима: мульти-файловое или одиночное редактирование
        if (params.has("edits")) {
            JsonNode editsNode = params.get("edits");
            // Поддержка наследования пути: если path указан в корне, но отсутствует в элементе edits, копируем его.
            if (params.has("path") && editsNode.isArray()) {
                String rootPath = params.get("path").asText();
                for (JsonNode edit : editsNode) {
                    if (!edit.has("path") && edit instanceof ObjectNode) {
                        ((ObjectNode) edit).put("path", rootPath);
                    }
                }
            }
            return executeMultiFileEdit(params, editsNode);
        } else if (params.has("path")) {
            return executeSingleFileEdit(params);
        } else {
            throw new IllegalArgumentException("Must specify 'path' or 'edits'.");
        }
    }

    /**
     * Выполняет пакетное редактирование нескольких файлов в рамках единой транзакции.
     * Если хотя бы одна правка в любом файле не пройдет валидацию, откатываются ВСЕ файлы.
     *
     * @param params Корневые параметры запроса.
     * @param editsArray JSON-массив правок для файлов.
     *
     * @return Текстовый отчет об успешном завершении батча.
     */
    private JsonNode executeMultiFileEdit(JsonNode params, JsonNode editsArray) throws Exception {
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        TransactionManager.startTransaction("Multi-file batch edit (" + editsArray.size() + " files)", instruction);
        try {
            StringBuilder statusMsg = new StringBuilder();
            statusMsg.append("Multi-file batch edit successful (").append(editsArray.size()).append(" files):\n\n");

            for (JsonNode editNode : editsArray) {
                // Применяем правки к каждому файлу и собираем статистику
                FileEditStats stats = applyFileEdits(editNode);
                String gitStatus = GitUtils.getFileStatus(stats.path);

                statusMsg.append(String.format("- %s [Git: %s]\n", stats.path.getFileName(), gitStatus.isEmpty() ? "Unchanged" : gitStatus));
                statusMsg.append(String.format("  Operations: %d (Ins: %d, Del: %d, Repl: %d)\n", stats.total(), stats.inserts, stats.deletes, stats.replaces));
                statusMsg.append(String.format("  New CRC32C: %X\n", stats.crc32));

                if (stats.diff != null && !stats.diff.isEmpty()) {
                    statusMsg.append("\n```diff\n").append(stats.diff).append("\n```\n");
                }
                statusMsg.append("\n");
            }
            TransactionManager.commit();
            statusMsg.append("(Tip: Use this checksum in your next nts_edit_file call to continue editing without re-reading the file.)");
            return createResponse(statusMsg.toString().trim());
        } catch (Exception e) {
            // Глобальный откат при любом сбое
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Выполняет редактирование одиночного файла.
     *
     * @param params Параметры запроса.
     *
     * @return Текстовый отчет с Git-статусом и CRC32.
     */
    private JsonNode executeSingleFileEdit(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        TransactionManager.startTransaction("Edit file: " + pathStr, instruction);
        try {
            FileEditStats stats = applyFileEdits(params);
            TransactionManager.commit();

            String gitStatus = GitUtils.getFileStatus(stats.path);
            StringBuilder sb = new StringBuilder();
            sb.append("Edits successfully applied to file: ").append(pathStr);
            if (!gitStatus.isEmpty()) {
                sb.append(" [Git: ").append(gitStatus).append("]");
            }
            sb.append("\nOperations: ").append(stats.total());
            sb.append("\nNew CRC32C: ").append(Long.toHexString(stats.crc32).toUpperCase());
            sb.append("\n(Tip: Use this checksum in your next nts_edit_file call to continue editing without re-reading the file.)");

            if (stats.diff != null && !stats.diff.isEmpty()) {
                sb.append("\n\n```diff\n").append(stats.diff).append("\n```");
            }

            return createResponse(sb.toString());
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Логика применения правок к конкретному файлу.
     * Выполняет чтение, проверку прав, бэкап и последовательную обработку операций.
     *
     * @param fileParams Параметры (путь и действия).
     *
     * @return Объект со статистикой изменений.
     */
    private FileEditStats applyFileEdits(JsonNode fileParams) throws Exception {
        String pathStr = fileParams.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: '" + pathStr + "'. Verify the path using nts_list_directory.");
        }

        // Предотвращение загрузки гигантских файлов (OOM Protection)
        PathSanitizer.checkFileSize(path);

        // Чтение файла и определение кодировки за один проход
        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        Charset charset = fileData.charset();
        String content = fileData.content();

        // Валидация checksum для bypass проверки AccessTracker
        if (fileParams.has("expectedChecksum")) {
            String expectedHex = fileParams.get("expectedChecksum").asText();
            long currentCrc = FileUtils.calculateCRC32(path);
            long expectedCrc;
            try {
                expectedCrc = Long.parseUnsignedLong(expectedHex, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid expectedChecksum format: '" + expectedHex + "'. Expected a hexadecimal value.");
            }

            if (currentCrc != expectedCrc) {
                throw new IllegalStateException("Checksum mismatch (Optimistic Locking)! Expected: " + expectedHex + ", Actual: " + Long.toHexString(currentCrc).toUpperCase() + ". The file was modified externally. Please perform nts_read_file again or use the new checksum for synchronization.");
            }
            // Хеш совпал — значит LLM знает актуальное состояние файла.
            AccessTracker.registerRead(path);
        }

        // Проверка "предохранителя": запрет редактирования файлов без предварительного чтения
        if (!AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Access denied: file '" + pathStr + "' has not been read in the current session. You must read the file (nts_read_file) or provide valid expectedChecksum before making edits to ensure context consistency.");
        }

        // Представление контента в виде списка строк для корректной манипуляции
        List<String> currentLines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

        // Регистрация состояния файла "ДО" в текущей транзакции
        TransactionManager.backup(path);

        FileEditStats stats = new FileEditStats(path);
        String newContent;

        if (fileParams.has("operations")) {
            // Обработка списка типизированных операций
            List<JsonNode> sortedOps = new ArrayList<>();
            fileParams.get("operations").forEach(sortedOps::add);

            // Сортировка по убыванию индекса (снизу вверх). 
            // Это критически важно: изменения в конце файла не влияют на номера строк в его начале.
            sortedOps.sort((a, b) -> {
                int lineA = a.path("startLine").asInt(a.path("line").asInt(0));
                int lineB = b.path("startLine").asInt(b.path("line").asInt(0));
                return Integer.compare(lineB, lineA);
            });

            for (JsonNode opNode : sortedOps) {
                applyTypedOperation(currentLines, opNode, 0, stats);
            }
            
            newContent = String.join("\n", currentLines);
            // Сохранение итогового состояния на диск
            FileUtils.safeWrite(path, newContent, charset);
        } else if (fileParams.has("oldText") && fileParams.has("newText")) {
            // Режим нечеткой (fuzzy) замены текста по содержимому
            newContent = performSmartReplace(content, fileParams.get("oldText").asText(), fileParams.get("newText").asText());
            FileUtils.safeWrite(path, newContent, charset);
            stats.replaces++;
        } else if (fileParams.has("startLine") && (fileParams.has("newText") || fileParams.has("content"))) {
            // Одиночная правка по индексам
            applyTypedOperation(currentLines, fileParams, 0, stats);
            newContent = String.join("\n", currentLines);
            // Сохранение итогового состояния на диск
            FileUtils.safeWrite(path, newContent, charset);
        } else {
            throw new IllegalArgumentException("Insufficient parameters for file: " + pathStr);
        }

        // Генерация diff
        stats.diff = DiffUtils.getUnifiedDiff(path.getFileName().toString(), content, newContent);

        // Вычисление итоговой контрольной суммы для подтверждения целостности
        stats.crc32 = FileUtils.calculateCRC32(path);
        return stats;
    }

    /**
     * Применяет одну типизированную операцию (replace, insert, delete) к списку строк.
     * Реализует поиск якоря, валидацию содержимого и автоматический отступ.
     */
    private void applyTypedOperation(List<String> lines, JsonNode op, int cumulativeOffset, FileEditStats stats) {
        String type = op.path("operation").asText("replace");
        int requestedStart = op.path("startLine").asInt(op.path("line").asInt(0));
        int requestedEnd = op.path("endLine").asInt(requestedStart);
        String newText = op.path("content").asText(op.path("newText").asText(""));
        String expected = op.path("expectedContent").asText(null);
        String contextPattern = op.path("contextStartPattern").asText(null);

        // 1. Поиск якоря для относительной адресации (если указан паттерн)
        int anchorLine = 0;
        if (contextPattern != null) {
            anchorLine = findAnchorLine(lines, contextPattern);
            if (anchorLine == -1) {
                throw new IllegalArgumentException("Context pattern not found: " + contextPattern);
            }
        }

        // 2. Расчет абсолютных индексов (1-based)
        int start = anchorLine + requestedStart + cumulativeOffset;
        int end = anchorLine + requestedEnd + cumulativeOffset;

        // 3. Адаптация индексов под специфику типа операции
        if ("insert_before".equals(type)) {
            end = start - 1; // Пустой диапазон перед строкой
            stats.inserts++;
        } else if ("insert_after".equals(type)) {
            start++;
            end = start - 1; // Пустой диапазон после строки
            stats.inserts++;
        } else if ("delete".equals(type)) {
            newText = null; // Маркер удаления
            stats.deletes++;
        } else {
            stats.replaces++;
        }

        // 4. Валидация границ в текущем состоянии файла
        if (start < 1 || (start > lines.size() + 1) || (end < start - 1) || (end > lines.size())) {
            throw new IllegalArgumentException("Addressing error: range " + start + "-" + end + " is out of bounds for file with " + lines.size() + " lines.");
        }

        // 5. Контроль ожидаемого содержимого (expectedContent) с диагностикой
        if (expected != null && end >= start) {
            String actual = lines.subList(start - 1, end).stream().map(l -> l.replace("\r", "")).collect(Collectors.joining("\n"));

            String normActual = actual.replace("\r", "");
            String normExpected = expected.replace("\r", "");

            if (!normActual.equals(normExpected)) {
                // Если не совпало — выбрасываем ошибку с подробным дампом текущих строк
                throw new IllegalStateException("Content validation failed for range " + start + "-" + end + "!\n" +
                        "EXPECTED:\n[" + normExpected + "]\n" +
                        "ACTUAL CONTENT IN FILE:\n[" + normActual + "]\n" +
                        "Please adjust your request using the actual text from the file.");
            }
        }

        // 6. Определение отступа для вставляемого кода (Auto-indentation)
        int oldLineCount = (end >= start) ? (end - start + 1) : 0;
        // Берем отступ либо от строки выше, либо от текущей первой строки диапазона
        int indentLineIdx = (start > 1) ? (start - 2) : (start - 1);
        String indentation = (indentLineIdx >= 0 && indentLineIdx < lines.size()) ? getIndentation(lines.get(indentLineIdx)) : "";

        // 7. Манипуляция списком строк
        for (int i = 0; i < oldLineCount; i++) {
            lines.remove(start - 1);
        }

        if (newText != null) {
            // Применяем отступ к каждой строке нового текста
            String indentedText = applyIndentation(newText, indentation);
            String[] newLines = indentedText.split("\n", -1);
            for (int i = 0; i < newLines.length; i++) {
                lines.add(start - 1 + i, newLines[i]);
            }
        }
    }

    /**
     * Создает стандартный MCP ответ с текстовым содержимым.
     */
    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }

    /**
     * Извлекает последовательность пробельных символов в начале строки.
     */
    private String getIndentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * Добавляет указанный отступ ко всем строкам текста.
     */
    private String applyIndentation(String text, String indentation) {
        if (indentation.isEmpty()) {
            return text;
        }
        return Arrays.stream(text.split("\n", -1)).map(line -> line.isEmpty() ? line : indentation + line).collect(Collectors.joining("\n"));
    }

    /**
     * Находит индекс строки (0-based), содержащей указанный паттерн.
     */
    private int findAnchorLine(List<String> lines, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Выполняет замену текста, игнорируя различия в количестве пробелов и типах переносов строк.
     */
    private String performSmartReplace(String content, String oldText, String newText) {
        // Точное совпадение
        if (content.contains(oldText)) {
            int count = countOccurrences(content, oldText);
            if (count > 1) {
                throw new IllegalArgumentException("Multiple matches (" + count + ") found for 'oldText'. Use 'operations' with line numbers for precise editing, or provide more context in 'oldText' to make it unique.");
            }
            return content.replace(oldText, newText);
        }

        // Совпадение с нормализованными переносами (\r\n -> \n)
        String normContent = content.replace("\r\n", "\n");
        String normOld = oldText.replace("\r\n", "\n");
        if (normContent.contains(normOld)) {
            int count = countOccurrences(normContent, normOld);
            if (count > 1) {
                throw new IllegalArgumentException("Multiple matches (" + count + ") found for 'oldText' (after newline normalization). Use 'operations' with line numbers for precise editing.");
            }
            return normContent.replace(normOld, newText);
        }

        // Нечеткий поиск: превращаем пробелы в \s+
        String escapedOld = escapeRegexExceptWhitespace(normOld);
        String regex = escapedOld.replaceAll("\\s+", "\\\\s+");
        Pattern pattern = Pattern.compile(regex, Pattern.MULTILINE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(normContent);
        if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (matcher.find()) {
                throw new IllegalArgumentException("Multiple ambiguous matches found during fuzzy search.");
            }
            return normContent.substring(0, start) + newText + normContent.substring(end);
        }
        throw new IllegalArgumentException("Text not found. Use line-based editing with expectedContent.");
    }

    /**
     * Экранирует метасимволы регулярных выражений, не затрагивая пробелы.
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

    /**
     * Подсчитывает количество непересекающихся вхождений подстроки.
     */
    private int countOccurrences(String text, String str) {
        if (str.isEmpty()) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(str, idx)) != -1) {
            count++;
            idx += str.length();
        }
        return count;
    }

    /**
     * Вспомогательный объект для агрегации статистики изменений по файлу.
     */
    private static class FileEditStats {
        final Path path;
        int replaces = 0;
        int inserts = 0;
        int deletes = 0;
        long crc32 = 0;
        String diff = "";

        FileEditStats(Path path) {
            this.path = path;
        }

        int total() {
            return replaces + inserts + deletes;
        }
    }
}