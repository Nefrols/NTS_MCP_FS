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
 * Инструмент для получения списка файлов в директории.
 * Особенности:
 * - Поддерживает рекурсивный просмотр на заданную глубину.
 * - Скрывает защищенные системные файлы и папки.
 * - Помечает файлы, которые уже были прочитаны LLM, маркером [READ].
 */
public class ListDirectoryTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "list_directory";
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
        
        // Нормализация и проверка безопасности пути
        Path path = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(path) || !Files.isDirectory(path)) {
            throw new IllegalArgumentException("Directory not found or is not a folder: " + pathStr);
        }

        List<String> entries = new ArrayList<>();
        // Запуск рекурсивного формирования списка
        listRecursive(path, entries, 0, depth, "");

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", entries.isEmpty() ? "(directory is empty)" : String.join("\n", entries));
        
        return result;
    }

    /**
     * Рекурсивно обходит дерево директорий до указанной глубины.
     * 
     * @param currentPath Текущий путь.
     * @param result Результирующий список строк.
     * @param currentDepth Текущая глубина рекурсии.
     * @param maxDepth Максимально допустимая глубина.
     * @param indent Строка отступа для визуализации дерева.
     */
    private void listRecursive(Path currentPath, List<String> result, int currentDepth, int maxDepth, String indent) throws IOException {
        if (currentDepth >= maxDepth) return;

        List<Path> subEntries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentPath)) {
            for (Path entry : stream) {
                subEntries.add(entry);
            }
        }
        
        // Сортировка: сначала папки, затем файлы, по алфавиту
        Collections.sort(subEntries, (a, b) -> {
            boolean aDir = Files.isDirectory(a);
            boolean bDir = Files.isDirectory(b);
            if (aDir != bDir) return aDir ? -1 : 1;
            return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
        });

        for (Path entry : subEntries) {
            // Проверка: не является ли файл системным (скрытым для LLM)
            if (PathSanitizer.isProtected(entry)) continue;

            boolean isDir = Files.isDirectory(entry);
            String name = entry.getFileName().toString();
            String type = isDir ? "[DIR]" : "[FILE]";
            
            // Проверка через AccessTracker: читал ли я уже этот файл?
            String readStatus = (!isDir && AccessTracker.hasBeenRead(entry)) ? " [READ]" : "";
            
            result.add(indent + type + " " + name + readStatus);
            
            // Если это папка — идем глубже
            if (isDir) {
                listRecursive(entry, result, currentDepth + 1, maxDepth, indent + "  ");
            }
        }
    }
}
