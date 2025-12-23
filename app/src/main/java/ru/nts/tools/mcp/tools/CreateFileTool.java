// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Инструмент для создания новых файлов в файловой системе.
 * Реализует:
 * 1. Авто-создание структуры: Если путь содержит несуществующие папки, они будут созданы автоматически.
 * 2. Защита от случайной перезаписи: Если файл уже существует, операция блокируется,
 * пока не будет предоставлен верный expectedChecksum.
 * 3. Транзакционность: В случае ошибки при записи, ФС откатывается в исходное состояние.
 * 4. Обратная связь: Возвращает список файлов в папке назначения и новую контрольную сумму.
 */
public class CreateFileTool implements McpTool {

    /**
     * Манипулятор JSON данных.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_create_file";
    }

    @Override
    public String getDescription() {
        return "Create file + directories. Returns listing. REQUIRED: provide expectedChecksum if the file already exists.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "New file path.");
        props.putObject("content").put("type", "string").put("description", "Full file content.");
        props.putObject("instruction").put("type", "string").put("description", "Semantic label for the transaction (e.g. 'Fix: added null-check').");
        props.putObject("expectedChecksum").put("type", "string").put("description", "REQUIRED if file exists. CRC32C hex of file before overwrite. Ensures you are overwriting the correct version.");

        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String content = params.get("content").asText();

        // Нормализация пути и проверка нахождения внутри корня проекта
        Path path = PathSanitizer.sanitize(pathStr, false);

        // Проверка политики перезаписи
        if (Files.exists(path)) {
            if (!params.has("expectedChecksum")) {
                throw new SecurityException("File '" + pathStr + "' already exists. Overwriting via nts_create_file requires 'expectedChecksum' to prevent accidental data loss. " +
                        "If you intend to replace the whole content, read the file first or provide its current checksum.");
            }

            String expectedHex = params.get("expectedChecksum").asText();
            long currentCrc = FileUtils.calculateCRC32(path);
            long expectedCrc;
            try {
                expectedCrc = Long.parseUnsignedLong(expectedHex, 16);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid expectedChecksum format: '" + expectedHex + "'. Expected a hexadecimal value.");
            }

            if (currentCrc != expectedCrc) {
                throw new IllegalStateException("Checksum mismatch! File '" + pathStr + "' was modified externally. Expected: " + expectedHex + ", Actual: " + Long.toHexString(currentCrc).toUpperCase() + ". Perform nts_read_file to sync state.");
            }
            
            // Если хеш совпал, регистрируем чтение (LLM "видела" файл)
            AccessTracker.registerRead(path);
        }

        // Открытие транзакции для обеспечения атомарности операции
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        TransactionManager.startTransaction("Create/Overwrite file: " + pathStr, instruction);
        try {
            // Регистрация в менеджере транзакций (создает бэкап или пометку на удаление)
            TransactionManager.backup(path);

            // Рекурсивное создание родительских директорий
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // Физическая запись контента (в UTF-8)
            FileUtils.safeWrite(path, content, StandardCharsets.UTF_8);

            // Фиксация транзакции
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");

            // Получение статуса в Git для информирования модели
            String gitStatus = GitUtils.getFileStatus(path);
            long newCrc = FileUtils.calculateCRC32(path);
            
            StringBuilder sb = new StringBuilder();
            sb.append("File created/overwritten successfully: ").append(pathStr);
            if (!gitStatus.isEmpty()) {
                sb.append(" [Git: ").append(gitStatus).append("]");
            }
            
            sb.append("\nNew CRC32C: ").append(Long.toHexString(newCrc).toUpperCase());
            sb.append("\n(Tip: Use this checksum in your next nts_edit_file or nts_create_file call to continue working without re-reading.)");

            // Генерация diff
            String diff = DiffUtils.getUnifiedDiff(path.getFileName().toString(), "", content);
            if (!diff.isEmpty()) {
                sb.append("\n\n```diff\n").append(diff).append("\n```");
            }

            sb.append("\n\n");
            sb.append("Directory content ").append(path.getParent()).append(":\n");
            sb.append(getDirectoryListing(path.getParent()));

            contentArray.addObject().put("type", "text").put("text", sb.toString());

            return result;
        } catch (Exception e) {
            // При любом сбое записи — восстанавливаем ФС в состояние "ДО"
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Формирует краткий текстовый список объектов в указанной директории.
     *
     * @param dir Путь к папке для листинга.
     * @return Многострочный текст со списком файлов или "(empty)".
     * @throws IOException При ошибке чтения директории.
     */
    private String getDirectoryListing(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return "(empty)";
        }
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String type = Files.isDirectory(entry) ? "[DIR]" : "[FILE]";
                entries.add(type + " " + entry.getFileName().toString());
            }
        }
        Collections.sort(entries);
        return entries.isEmpty() ? "(empty)" : String.join("\n", entries);
    }
}