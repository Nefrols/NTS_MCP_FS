// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * пока файл не будет предварительно прочитан (валидация контекста).
 * 3. Транзакционность: В случае ошибки при записи, ФС откатывается в исходное состояние.
 * 4. Обратная связь: Возвращает список файлов в папке назначения после создания.
 */
public class CreateFileTool implements McpTool {

    /**
     * Манипулятор JSON данных.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "create_file";
    }

    @Override
    public String getDescription() {
        return "Create file + directories. Returns listing. REQUIRED: read_file first if overwriting.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "New file path.");

        props.putObject("content").put("type", "string").put("description", "Full file content.");

        schema.putArray("required").add("path").add("content");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String content = params.get("content").asText();

        // Нормализация пути и проверка нахождения внутри корня проекта
        Path path = PathSanitizer.sanitize(pathStr, false);

        // Проверка политики перезаписи: нельзя менять то, что не видел
        if (Files.exists(path) && !AccessTracker.hasBeenRead(path)) {
            throw new SecurityException("Access denied: file already exists and has not been read. Use read_file before overwriting.");
        }

        // Открытие транзакции для обеспечения атомарности операции
        TransactionManager.startTransaction("Create file: " + pathStr);
        try {
            // Регистрация в менеджере транзакций (создает бэкап или пометку на удаление)
            TransactionManager.backup(path);

            // Рекурсивное создание родительских директорий
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // Физическая запись контента (в UTF-8)
            Files.writeString(path, content, StandardCharsets.UTF_8);

            // Фиксация транзакции
            TransactionManager.commit();

            var result = mapper.createObjectNode();
            var contentArray = result.putArray("content");

            // Получение статуса в Git для информирования модели
            String gitStatus = GitUtils.getFileStatus(path);
            StringBuilder sb = new StringBuilder();
            sb.append("File created successfully: ").append(pathStr);
            if (!gitStatus.isEmpty()) {
                sb.append(" [Git: ").append(gitStatus).append("]");
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
     * Помогает LLM подтвердить успешность структурных изменений.
     *
     * @param dir Путь к папке для листинга.
     *
     * @return Многострочный текст со списком файлов или "(empty)".
     *
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