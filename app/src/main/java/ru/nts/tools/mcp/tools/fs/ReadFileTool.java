// Aristo 22.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

/**
 * Инструмент для чтения содержимого файлов проекта.
 * Особенности:
 * 1. Умное чтение: Поддержка выборки по диапазонам строк и поиска контекста вокруг паттернов.
 * 2. Метаданные: Каждый ответ содержит технический заголовок (размер, строки, кодировка, CRC32C).
 * 3. Безопасность: Автоматическая детекция и блокировка бинарных файлов, а также лимиты на размер (OOM Protection).
 * 4. Контроль доступа: Регистрация факта чтения в трекере доступа (AccessTracker) для последующего разрешения правок.
 */
public class ReadFileTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_read_file";
    }

    @Override
    public String getDescription() {
        return "Reads file content + metadata. MANDATORY for editing. Pro tip: use 'contextStartPattern' to find code blocks.";
    }

    @Override
    public String getCategory() {
        return "fs";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "File path.");

        props.putObject("startLine").put("type", "integer").put("description", "Start line (from 1).");

        props.putObject("endLine").put("type", "integer").put("description", "End line (inclusive).");

        props.putObject("line").put("type", "integer").put("description", "Single line to read.");

        props.putObject("contextStartPattern").put("type", "string").put("description", "Regex anchor to find context. Example: 'public void recordChange' or '^class .*Manager'.");

        props.putObject("contextRange").put("type", "integer").put("description", "Lines around anchor (default 0). Example: 5 to see 5 lines above and 5 below.");

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        // Санитарная нормализация пути и проверка прав
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: '" + pathStr + "'. Ensure the path is correct and the file exists.");
        }

        // Предотвращение загрузки гигантских файлов (OOM Protection)
        PathSanitizer.checkFileSize(path);

        // Чтение файла, автоматическое определение кодировки и проверка на бинарность за один проход
        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        Charset charset = fileData.charset();
        String content = fileData.content();

        // Регистрация в трекере доступа: LLM "увидела" файл, теперь она может его править
        AccessTracker.registerRead(path);

        // Подготовка строк для выборки диапазонов
        String[] lines = content.split("\n", -1);
        String resultText;

        // Логика выборки контента
        if (params.has("contextStartPattern")) {
            // Режим "Умное чтение вокруг контекста"
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
                // Улучшенная диагностика: поиск похожих строк для подсказки
                List<String> suggestions = new ArrayList<>();
                for (String line : lines) {
                    if (line.toLowerCase().contains(patternStr.toLowerCase())) {
                        suggestions.add(line.trim());
                        if (suggestions.size() >= 3) break;
                    }
                }

                String msg = "Context pattern not found: '" + patternStr + "'.";
                if (!suggestions.isEmpty()) {
                    msg += " Suggestions: Did you mean one of these?\n- " + String.join("\n- ", suggestions);
                } else {
                    msg += " Please check the pattern or read the whole file to find correct context.";
                }
                throw new IllegalArgumentException(msg);
            }

            // Вычисление границ окна чтения
            int start = Math.max(0, anchorIdx - range);
            int end = Math.min(lines.length - 1, anchorIdx + range);

            resultText = Stream.of(Arrays.copyOfRange(lines, start, end + 1)).map(l -> l.replace("\r", "")).collect(Collectors.joining("\n"));

        } else if (params.has("line")) {
            // Режим чтения одной конкретной строки
            int lineNum = params.get("line").asInt();
            resultText = (lineNum >= 1 && lineNum <= lines.length) ? lines[lineNum - 1].replace("\r", "") : "";
        } else if (params.has("startLine") || params.has("endLine")) {
            // Режим чтения диапазона строк
            int start = params.path("startLine").asInt(1);
            int end = params.path("endLine").asInt(lines.length);

            int startIdx = Math.max(0, start - 1);
            int endIdx = Math.min(lines.length, end);

            if (startIdx >= endIdx) {
                resultText = "";
            } else {
                resultText = Stream.of(Arrays.copyOfRange(lines, startIdx, endIdx)).map(l -> l.replace("\r", "")).collect(Collectors.joining("\n"));
            }
        } else {
            // Чтение всего файла целиком
            resultText = content;
        }

        // Подготовка информативного заголовка для LLM
        long size = Files.size(path);
        long crc32 = calculateCRC32(path);
        int charCount = content.length();
        int lineCount = content.isEmpty() ? 0 : lines.length;

        String header = String.format("[FILE: %s | SIZE: %d bytes | CHARS: %d | LINES: %d | ENCODING: %s | CRC32C: %X]\n", path.getFileName(), size, charCount, lineCount, charset.name(), crc32);

        ObjectNode result = mapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", header + resultText);
        return result;
    }

    /**
     * Вычисляет контрольную сумму файла CRC32C.
     * Используется моделью для верификации локального кэша и отслеживания изменений.
     *
     * @param path Путь к файлу.
     *
     * @return Значение CRC32C.
     *
     * @throws IOException При ошибках чтения.
     */
    private long calculateCRC32(Path path) throws IOException {
        CRC32C crc = new CRC32C();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }
}