// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Инструмент для исследования структуры файловой системы (Listing).
 * Особенности:
 * 1. Многоуровневость: Поддерживает рекурсивный просмотр на заданную глубину (depth).
 * 2. Безопасность: Автоматически скрывает служебные файлы (.git, .gradle, .mcp) через PathSanitizer.
 * 3. Контекст: Помечает файлы, которые уже были изучены моделью (маркер [READ]).
 * 4. Навигация: Папки в списке всегда отображаются выше файлов для удобства ориентации.
 */
public class ListDirectoryTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_list_directory";
    }

    @Override
    public String getDescription() {
        return "List directory contents with recursion and [READ] status indicator.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "Target directory path.");

        props.putObject("depth").put("type", "integer").put("description", "Recursion limit (default 1).");

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        int depth = params.path("depth").asInt(1);

        // Санитарная нормализация пути
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Directory not found or is not a folder: " + pathStr);
        }

        List<String> entries = new ArrayList<>();
        // Запуск рекурсивного формирования текстового представления дерева папок
        listRecursive(path, entries, 0, depth, "");

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");

        // Формирование итогового ответа
        text.put("text", entries.isEmpty() ? "(directory is empty)" : String.join("\n", entries));

        return result;
    }

    /**
     * Рекурсивно строит дерево директорий.
     *
     * @param currentPath  Текущая обрабатываемая директория.
     * @param result       Результирующий список строк с отступами.
     * @param currentDepth Текущий уровень вложенности.
     * @param maxDepth     Лимит рекурсии из параметров.
     * @param indent       Префикс отступа для текущего уровня.
     *
     * @throws IOException При ошибках чтения файловой системы.
     */
    private void listRecursive(Path currentPath, List<String> result, int currentDepth, int maxDepth, String indent) throws IOException {
        // Остановка при достижении заданного лимита глубины
        if (currentDepth >= maxDepth) {
            return;
        }

        List<Path> subEntries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentPath)) {
            for (Path entry : stream) {
                subEntries.add(entry);
            }
        }

        // Сортировка: папки имеют приоритет, внутри категорий — по алфавиту
        Collections.sort(subEntries, (a, b) -> {
            boolean aDir = Files.isDirectory(a);
            boolean bDir = Files.isDirectory(b);
            if (aDir != bDir) {
                return aDir ? -1 : 1;
            }
            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
        });

        for (Path entry : subEntries) {
            // Игнорируем защищенные объекты (скрываем их от глаз LLM)
            if (PathSanitizer.isProtected(entry)) {
                continue;
            }

            boolean isDir = Files.isDirectory(entry);
            String name = entry.getFileName().toString();
            String type = isDir ? "[DIR]" : "[FILE]";

            // Проверка через AccessTracker: читал ли я уже этот файл? 
            // Это помогает LLM понять, на какие файлы у нее уже есть права редактирования.
            String readStatus = (!isDir && AccessTracker.hasBeenRead(entry)) ? " [READ]" : "";

            result.add(indent + type + " " + name + readStatus);

            // Рекурсивный спуск в поддиректории
            if (isDir) {
                listRecursive(entry, result, currentDepth + 1, maxDepth, indent + "  ");
            }
        }
    }
}