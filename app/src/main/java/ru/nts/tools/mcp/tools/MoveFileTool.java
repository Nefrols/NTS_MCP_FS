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
 * Инструмент для перемещения файлов и директорий в рамках файловой системы проекта.
 * Особенности:
 * 1. Интеллектуальное перемещение: Автоматически создает недостающие промежуточные папки в пути назначения.
 * 2. Сохранение контекста: Статус прочтения [READ] в трекере доступа автоматически переносится на новый путь.
 * 3. Транзакционность: Перемещение выполняется атомарно. В случае ошибки ФС возвращается в исходное состояние.
 * 4. Обратная связь: Возвращает список файлов в целевой директории после завершения операции.
 */
public class MoveFileTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "move_file";
    }

    @Override
    public String getDescription() {
        return "Move file/directory. Creates subfolders. Returns listing. Atomic move.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("sourcePath").put("type", "string").put("description", "Current path to the object.");

        props.putObject("targetPath").put("type", "string").put("description", "New path for the object.");

        schema.putArray("required").add("sourcePath").add("targetPath");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String sourceStr = params.get("sourcePath").asText();
        String targetStr = params.get("targetPath").asText();

        // Санитарная проверка обоих путей
        Path source = PathSanitizer.sanitize(sourceStr, false);
        Path target = PathSanitizer.sanitize(targetStr, false);

        if (!Files.exists(source)) {
            throw new IllegalArgumentException("Source object not found: " + sourceStr);
        }

        if (Files.exists(target)) {
            throw new IllegalArgumentException("Target object already exists: " + targetStr);
        }

        // Открытие транзакции перемещения
        TransactionManager.startTransaction("Move: " + sourceStr + " -> " + targetStr);
        try {
            // Подготовка резервных копий исходных данных перед перемещением
            if (Files.isRegularFile(source)) {
                // Бэкап одиночного файла
                TransactionManager.backup(source);
            } else if (Files.isDirectory(source)) {
                // Рекурсивный бэкап всех файлов внутри папки
                try (var walk = Files.walk(source)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (Files.isRegularFile(p)) {
                            TransactionManager.backup(p);
                        }
                    }
                }
            }
            // Регистрация целевого пути в транзакции (для корректного удаления при откате)
            TransactionManager.backup(target);

            // Создание структуры директорий для целевого пути
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            // Физическое перемещение на уровне ОС (атомарное, если поддерживается системой)
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);

            // Синхронизация трекера доступа
            AccessTracker.moveRecord(source, target);

            // Фиксация изменений
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");

            // Сбор информации о результате для LLM
            String gitStatus = GitUtils.getFileStatus(target);
            StringBuilder sb = new StringBuilder();
            sb.append("Successfully moved from ").append(sourceStr).append(" to ").append(targetStr);
            if (!gitStatus.isEmpty()) {
                sb.append(" [Git: ").append(gitStatus).append("]");
            }
            sb.append("\n\n");
            sb.append("Directory content ").append(target.getParent()).append(":\n");
            sb.append(getDirectoryListing(target.getParent()));

            contentArray.addObject().put("type", "text").put("text", sb.toString());
            return result;
        } catch (Exception e) {
            // Мгновенный откат всех действий в случае сбоя
            TransactionManager.rollback();
            throw e;
        }
    }

    /**
     * Формирует простой список файлов в директории.
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