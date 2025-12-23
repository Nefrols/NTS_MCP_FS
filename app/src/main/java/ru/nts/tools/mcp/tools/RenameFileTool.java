// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nts.tools.mcp.core.*;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Инструмент для переименования файлов и директорий внутри их текущего расположения.
 * Особенности:
 * 1. Локальность: Работает только внутри одной папки. Для переноса объектов используйте 'move_file'.
 * 2. Сохранение контекста: Обновляет записи в трекере доступа (AccessTracker) для нового имени.
 * 3. Транзакционность: Операция защищена менеджером транзакций и поддерживает отмену.
 * 4. Обратная связь: Предоставляет актуальный список файлов в директории после переименования.
 */
public class RenameFileTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_rename_file";
    }

    @Override
    public String getDescription() {
        return "Rename in-place. Returns listing. Use move_file for different paths.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "Current path to the object.");

        props.putObject("newName").put("type", "string").put("description", "New name only.");

        schema.putArray("required").add("path").add("newName");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String newName = params.get("newName").asText();

        // Санитарная нормализация исходного пути
        Path source = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Object not found: '" + pathStr + "'. Verify the path.");
        }

        // Запрещаем наличие разделителей пути в новом имени для соблюдения принципа локальности переименования
        if (newName.contains("/") || newName.contains("\\")) {
            throw new IllegalArgumentException("New name must not contain path components (slashes). Use nts_move_file for moving objects to different directories.");
        }

        // Вычисление целевого пути в той же директории
        Path target = source.resolveSibling(newName);

        if (Files.exists(target)) {
            throw new IllegalArgumentException("Object with name '" + newName + "' already exists in the same directory.");
        }

        // Открытие транзакции переименования
        TransactionManager.startTransaction("Rename: " + pathStr + " -> " + newName);
        try {
            // Подготовка резервных копий
            TransactionManager.backup(source);
            TransactionManager.backup(target);

            // Физическое переименование (атомарная операция ФС)
            FileUtils.safeMove(source, target, StandardCopyOption.ATOMIC_MOVE);

            // Обновление реестра прочитанных файлов
            AccessTracker.moveRecord(source, target);

            // Фиксация транзакции
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");

            // Формирование детального отчета с Git-статусом
            String gitStatus = GitUtils.getFileStatus(target);
            StringBuilder sb = new StringBuilder();
            sb.append("Successfully renamed to ").append(newName);
            if (!gitStatus.isEmpty()) {
                sb.append(" [Git: ").append(gitStatus).append("]");
            }
            sb.append("\n\n");
            sb.append("Directory content ").append(target.getParent()).append(":\n");
            sb.append(getDirectoryListing(target.getParent()));

            contentArray.addObject().put("type", "text").put("text", sb.toString());
            return result;
        } catch (Exception e) {
            // Откат в случае любой ошибки
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Возвращает список объектов в указанной директории.
     * Позволяет LLM верифицировать результат переименования.
     *
     * @param dir Путь к папке.
     *
     * @return Текстовое описание содержимого.
     *
     * @throws IOException При ошибках чтения.
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