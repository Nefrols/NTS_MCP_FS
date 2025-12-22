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
import java.util.List;
import java.util.stream.Stream;

/**
 * Инструмент для рекурсивного поиска текста в файлах директории.
 * Оптимизирован для работы с файлами любого размера и кодировки.
 */
public class SearchFilesTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public String getDescription() {
        return "Рекурсивно ищет строку в содержимом файлов в указанной директории с автоматическим определением кодировки.";
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
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

        List<String> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(rootPath)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Charset charset = EncodingUtils.detectEncoding(path);
                    try (Stream<String> lines = Files.lines(path, charset)) {
                        if (lines.anyMatch(line -> line.contains(query))) {
                            matches.add(path.toAbsolutePath().toString());
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }

        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", matches.isEmpty() ? "Совпадений не найдено." : "Найдены совпадения в файлах:\n" + String.join("\n", matches));
        
        return result;
    }
}
