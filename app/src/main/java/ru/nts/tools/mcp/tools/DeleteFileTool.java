// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nts.tools.mcp.core.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Инструмент для удаления файлов и директорий из файловой системы.
 * Возможности:
 * 1. Безопасность: Предотвращает удаление системных файлов через PathSanitizer.
 * 2. Рекурсия: Позволяет удалять непустые папки (при установке флага recursive).
 * 3. Транзакционность: Интегрирован с TransactionManager. Удаленные объекты могут быть
 * восстановлены командой 'undo'.
 * 4. Git-интеграция: Сообщает о статусе родительской директории после удаления.
 */
public class DeleteFileTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_delete_file";
    }

    @Override
    public String getDescription() {
        return "Deletes file or directory. Recursive deletion for folders.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "Path to delete.");
        props.putObject("recursive").put("type", "boolean").put("description", "Delete non-empty folders.");
        props.putObject("instruction").put("type", "string").put("description", "Semantic label for the transaction (e.g. 'Cleanup: removed old logs').");
        props.putObject("dryRun").put("type", "boolean").put("description", "If true, only checks if deletion is possible without performing it.");
        props.putObject("compact").put("type", "boolean").put("description", "If true, returns a compact one-line response.");

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        boolean recursive = params.path("recursive").asBoolean(false);
        boolean dryRun = params.path("dryRun").asBoolean(false);
        boolean compact = params.path("compact").asBoolean(false);

        // Санитарная проверка пути (блокирует удаление .git, .nts и т.д.)
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Object not found at path: '" + pathStr + "'. Ensure the path is correct (use nts_list_directory to verify).");
        }

        if (dryRun) {
            String type = Files.isDirectory(path) ? "directory" : "file";
            String msg = "[DRY RUN] Would delete " + type + ": " + pathStr;
            if (Files.isDirectory(path) && !recursive) {
                try (var s = Files.list(path)) {
                    if (s.findAny().isPresent()) {
                        throw new IllegalArgumentException("Cannot delete non-empty directory without 'recursive: true'.");
                    }
                }
            }
            var res = mapper.createObjectNode();
            res.putArray("content").addObject().put("type", "text").put("text", msg + "\n[DRY RUN] No changes were applied.");
            return res;
        }

        // Запуск транзакции удаления
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        TransactionManager.startTransaction("Delete: " + pathStr, instruction);
        try {
            if (Files.isDirectory(path) && recursive) {
                // Рекурсивный обход для подготовки бэкапов всех вложенных файлов
                try (var walk = Files.walk(path)) {
                    // Собираем все пути для обеспечения корректного порядка удаления
                    List<Path> allPaths = walk.collect(Collectors.toList());
                    for (Path p : allPaths) {
                        if (Files.isRegularFile(p)) {
                            // Бэкапим каждый файл перед физическим удалением
                            TransactionManager.backup(p);
                        }
                    }

                    // Удаляем объекты в обратном порядке (от листьев к корню), чтобы не оставить пустых папок
                    for (int i = allPaths.size() - 1; i >= 0; i--) {
                        FileUtils.safeDelete(allPaths.get(i));
                    }
                }
            } else {
                // Одиночное удаление файла или пустой директории
                if (Files.isRegularFile(path)) {
                    TransactionManager.backup(path);
                }
                FileUtils.safeDelete(path);
            }
            // Подтверждение успеха транзакции
            TransactionManager.commit();
        } catch (Exception e) {
            // В случае сбоя доступа или системной ошибки — восстанавливаем всё
            TransactionManager.rollback();
            throw e;
        }

        var result = mapper.createObjectNode();
        var contentArray = result.putArray("content");

        // Информируем LLM о том, как удаление повлияло на статус Git родительской папки
        String gitStatus = GitUtils.getFileStatus(path.getParent());
        
        if (compact) {
            String msg = String.format("Deleted: %s | Parent Git: %s", pathStr, gitStatus.isEmpty() ? "Unchanged" : gitStatus);
            contentArray.addObject().put("type", "text").put("text", msg);
            return result;
        }

        String msg = "Deleted successfully: " + pathStr;
        if (!gitStatus.isEmpty()) {
            msg += " [Parent Git: " + gitStatus + "]";
        }

        contentArray.addObject().put("type", "text").put("text", msg);
        return result;
    }
}