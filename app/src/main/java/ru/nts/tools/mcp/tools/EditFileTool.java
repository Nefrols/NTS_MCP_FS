// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
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
import java.util.zip.CRC32;

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
 * 9. Детализированный отчет: Возвращает количество операций и итоговый CRC32.
 */
public class EditFileTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Edit file(s) atomically. Supports fuzzy replace, line ranges, auto-indent, and multi-file batches. REQUIRED: read_file first.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        
        props.putObject("path").put("type", "string").put("description", "Target file path.");
        
        var edits = props.putObject("edits");
        edits.put("type", "array");
        var editItem = edits.putObject("items");
        editItem.put("type", "object");
        var editProps = editItem.putObject("properties");
        editProps.putObject("path").put("type", "string").put("description", "File to edit in batch.");
        editProps.set("operations", mapper.createObjectNode().put("type", "array").put("description", "Atomic operations list."));
        
        editProps.putObject("oldText").put("type", "string").put("description", "Literal text to replace.");
        editProps.putObject("newText").put("type", "string").put("description", "Replacement text.");
        editProps.putObject("startLine").put("type", "integer").put("description", "1-based start line.");
        editProps.putObject("endLine").put("type", "integer").put("description", "1-based end line.");
        editProps.putObject("expectedContent").put("type", "string").put("description", "Validation string (exact match required).");

        props.putObject("oldText").put("type", "string").put("description", "Fuzzy text replace (escapes special chars).");
        props.putObject("newText").put("type", "string");
        props.putObject("startLine").put("type", "integer").put("description", "Range start.");
        props.putObject("endLine").put("type", "integer").put("description", "Range end.");
        props.putObject("expectedContent").put("type", "string").put("description", "REQUIRED for safety in line edits.");
        props.putObject("contextStartPattern").put("type", "string").put("description", "Regex anchor for relative line indexing.");

        var ops = props.putObject("operations");
        ops.put("type", "array").put("description", "Atomic steps for a single file.");
        
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
     * @return Ответ с детализированной статистикой по каждому файлу.
     */
    private JsonNode executeMultiFileEdit(JsonNode editsArray) throws Exception {
        // Открываем одну глобальную транзакцию на весь батч файлов
        TransactionManager.startTransaction("Multi-file batch edit (" + editsArray.size() + " files)");
        try {
            StringBuilder statusMsg = new StringBuilder();
            statusMsg.append("Multi-file batch edit successful (" + editsArray.size() + " files):\n\n");
            
            for (JsonNode editNode : editsArray) {
                FileEditStats stats = applyFileEdits(editNode);
                String gitStatus = GitUtils.getFileStatus(stats.path);
                
                statusMsg.append(String.format("- %s [Git: %s]\n", stats.path.getFileName(), gitStatus.isEmpty() ? "Unchanged" : gitStatus));
                statusMsg.append(String.format("  Operations: %d (Ins: %d, Del: %d, Repl: %d)\n", 
                        stats.total(), stats.inserts, stats.deletes, stats.replaces));
                statusMsg.append(String.format("  New CRC32: %X\n\n", stats.crc32));
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
     * @return Ответ сервера с краткой статистикой.
     */
    private JsonNode executeSingleFileEdit(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        // Транзакция для одного файла
        TransactionManager.startTransaction("Edit file: " + pathStr);
        try {
            FileEditStats stats = applyFileEdits(params);
            TransactionManager.commit();
            
            String gitStatus = GitUtils.getFileStatus(stats.path);
            StringBuilder sb = new StringBuilder();
            sb.append("Edits successfully applied to file: ").append(pathStr);
            if (!gitStatus.isEmpty()) sb.append(" [Git: ").append(gitStatus).append("]");
            sb.append("\nOperations: ").append(stats.total());
            sb.append("\nNew CRC32: ").append(Long.toHexString(stats.crc32).toUpperCase());
            
            return createResponse(sb.toString());
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
     * @return Статистика правок по файлу.
     */
    private FileEditStats applyFileEdits(JsonNode fileParams) throws Exception {
        String pathStr = fileParams.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + pathStr);
        }

        // Проверка предохранителя: файл должен быть прочитан перед модификацией
        if (!AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Access denied: file " + pathStr + " has not been read in current session.");
        }

        // Защита от OOM
        PathSanitizer.checkFileSize(path);

        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        Charset charset = fileData.charset();
        String content = fileData.content();
        
        // Разделяем контент на строки для корректной работы батчинга по индексам
        List<String> currentLines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        
        // Создаем резервную копию файла внутри текущей транзакции
        TransactionManager.backup(path);

        FileEditStats stats = new FileEditStats(path);

        if (fileParams.has("operations")) {
            // Выполнение пакета правок для файла.
            // Сортируем операции по убыванию номера строки, чтобы индексы вышестоящих строк оставались стабильными.
            List<JsonNode> sortedOps = new ArrayList<>();
            fileParams.get("operations").forEach(sortedOps::add);
            sortedOps.sort((a, b) -> {
                int lineA = a.path("startLine").asInt(a.path("line").asInt(0));
                int lineB = b.path("startLine").asInt(b.path("line").asInt(0));
                return Integer.compare(lineB, lineA);
            });

            for (JsonNode opNode : sortedOps) {
                applyTypedOperation(currentLines, opNode, 0, stats);
            }
        } else if (fileParams.has("oldText") && fileParams.has("newText")) {
            // Режим нечеткой замены текста (fuzzy match)
            String newContent = performSmartReplace(content, fileParams.get("oldText").asText(), fileParams.get("newText").asText());
            Files.writeString(path, newContent, charset);
            stats.replaces++;
        } else if (fileParams.has("startLine") && (fileParams.has("newText") || fileParams.has("content"))) {
            // Одиночная правка диапазона (для совместимости)
            applyTypedOperation(currentLines, fileParams, 0, stats);
        } else {
            throw new IllegalArgumentException("Insufficient parameters for file: " + pathStr);
        }

        // Сохраняем итоговый результат на диск
        if (!fileParams.has("oldText")) {
            Files.writeString(path, String.join("\n", currentLines), charset);
        }
        
        // Вычисляем итоговый CRC32
        stats.crc32 = calculateCRC32(path);
        return stats;
    }

    /**
     * Выполняет конкретную типизированную операцию над списком строк.
     * 
     * @param stats Объект для сбора статистики.
     */
    private void applyTypedOperation(List<String> lines, JsonNode op, int cumulativeOffset, FileEditStats stats) {
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

        // 2. Расчет итоговых индексов
        int start = anchorLine + requestedStart + cumulativeOffset;
        int end = anchorLine + requestedEnd + cumulativeOffset;

        // 3. Корректировка индексов под специфику операций и обновление статистики
        if ("insert_before".equals(type)) {
            end = start - 1;
            stats.inserts++;
        } else if ("insert_after".equals(type)) {
            start++;
            end = start - 1;
            stats.inserts++;
        } else if ("delete".equals(type)) {
            newText = null;
            stats.deletes++;
        } else {
            stats.replaces++;
        }

        // 4. Валидация границ
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

        // 6. Реализация auto-indentation
        int oldLineCount = (end >= start) ? (end - start + 1) : 0;
        int indentLineIdx = (start > 1) ? (start - 2) : (start - 1);
        String indentation = (indentLineIdx >= 0 && indentLineIdx < lines.size()) ? getIndentation(lines.get(indentLineIdx)) : "";
        
        // 7. Манипуляция строками
        for (int i = 0; i < oldLineCount; i++) {
            lines.remove(start - 1);
        }
        
        if (newText != null) {
            String indentedText = applyIndentation(newText, indentation);
            String[] newLines = indentedText.split("\n", -1);
            for (int i = 0; i < newLines.length; i++) {
                lines.add(start - 1 + i, newLines[i]);
            }
        }
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
            if (matcher.find()) {
                throw new IllegalArgumentException("Multiple ambiguous matches found during fuzzy search.");
            }
            return normContent.substring(0, start) + newText + normContent.substring(end);
        }
        throw new IllegalArgumentException("Text not found.");
    }

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

    private long calculateCRC32(Path path) throws IOException {
        CRC32 crc = new CRC32();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    /**
     * Вспомогательный класс для сбора статистики правок.
     */
    private static class FileEditStats {
        final Path path;
        int replaces = 0;
        int inserts = 0;
        int deletes = 0;
        long crc32 = 0;

        FileEditStats(Path path) { this.path = path; }
        int total() { return replaces + inserts + deletes; }
    }
}