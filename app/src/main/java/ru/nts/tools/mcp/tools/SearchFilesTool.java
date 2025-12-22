// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Инструмент для рекурсивного поиска текста, оптимизированный с помощью виртуальных потоков.
 */
public class SearchFilesTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public String getDescription() {
        return "Параллельный рекурсивный поиск строки в файлах с использованием виртуальных потоков.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к базовой директории");
        props.putObject("query").put("type", "string").put("description", "Строка для поиска");
        
        schema.putArray("required").add("path").add("query");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        Path rootPath = Path.of(params.get("path").asText());
        String query = params.get("query").asText();
        
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Директория не найдена: " + rootPath);
        }

        var matches = new ConcurrentLinkedQueue<String>();
        
        // Используем виртуальные потоки для параллельного сканирования файлов
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    executor.submit(() -> {
                        try {
                            Charset charset = EncodingUtils.detectEncoding(path);
                            try (Stream<String> lines = Files.lines(path, charset)) {
                                if (lines.anyMatch(line -> line.contains(query))) {
                                    matches.add(path.toAbsolutePath().toString());
                                }
                            }
                        } catch (Exception ignored) {
                            // Ошибки доступа или кодировки игнорируем при массовом поиске
                        }
                    });
                });
            }
            // Executor автоматически дождется завершения всех задач при закрытии (try-with-resources)
        }

        var sortedMatches = new ArrayList<>(matches);
        Collections.sort(sortedMatches);

        var result = mapper.createObjectNode();
        var content = result.putArray("content");
        var textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", sortedMatches.isEmpty() ? "Совпадений не найдено." : 
                "Найдены совпадения в файлах (" + sortedMatches.size() + "):\n" + String.join("\n", sortedMatches));
        
        return result;
    }
}