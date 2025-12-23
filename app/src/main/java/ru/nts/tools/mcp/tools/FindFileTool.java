// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Инструмент для быстрого поиска файлов по имени или glob-паттерну.
 * Позволяет находить файлы в проекте без необходимости рекурсивного обхода всех директорий вручную.
 */
public class FindFileTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_find_file";
    }

    @Override
    public String getDescription() {
        return "Recursive search for files by name or glob pattern (e.g. '**/*.java'). Returns relative paths.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("pattern").put("type", "string").put("description", "File name or glob pattern (e.g. 'FileUtils.java' or '**/test/*.xml').");
        props.putObject("path").put("type", "string").put("description", "Base search directory (default: '.').");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String patternStr = params.get("pattern").asText();
        String basePathStr = params.path("path").asText(".");

        Path root = PathSanitizer.getRoot();
        Path startPath = PathSanitizer.sanitize(basePathStr, true);

        if (!Files.exists(startPath) || !Files.isDirectory(startPath)) {
            throw new IllegalArgumentException("Start directory not found: " + basePathStr);
        }

        // Если паттерн не содержит глоб-символов, превращаем его в поиск по имени
        String glob = (patternStr.contains("*") || patternStr.contains("?")) ? patternStr : "**/" + patternStr;
        if (!glob.startsWith("glob:")) {
            glob = "glob:" + glob;
        }

        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher(glob.replace("\\", "/"));
        List<String> foundFiles = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(startPath)) {
            walk.filter(p -> Files.isRegularFile(p) && !PathSanitizer.isProtected(p))
                .forEach(p -> {
                    Path relPath = root.relativize(p);
                    // Нормализация пути для матчера (замена \ на /)
                    Path normalizedRelPath = Path.of(relPath.toString().replace('\\', '/'));
                    if (matcher.matches(normalizedRelPath)) {
                        foundFiles.add(relPath.toString().replace('\\', '/'));
                    }
                });
        }

        Collections.sort(foundFiles);

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");

        if (foundFiles.isEmpty()) {
            textNode.put("text", "No files found matching pattern: " + patternStr);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(foundFiles.size()).append(" files:\n\n");
            for (String f : foundFiles) {
                sb.append("- ").append(f).append("\n");
            }
            textNode.put("text", sb.toString().trim());
        }

        return result;
    }
}