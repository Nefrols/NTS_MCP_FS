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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инструмент для рекурсивного поиска текста.
 * Возвращает пути к файлам, номера строк и фрагменты текста для мгновенного анализа.
 */
public class SearchFilesTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public String getDescription() {
        return "Параллельный рекурсивный поиск в файлах. Возвращает пути, номера строк и содержимое найденных строк.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Путь к базовой директории");
        props.putObject("query").put("type", "string").put("description", "Строка или регулярное выражение для поиска");
        props.putObject("isRegex").put("type", "boolean").put("description", "Если true, то query трактуется как регулярное выражение");
        
        schema.putArray("required").add("path").add("query");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        Path rootPath = Path.of(params.get("path").asText());
        String query = params.get("query").asText();
        boolean isRegex = params.path("isRegex").asBoolean(false);
        
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Директория не найдена: " + rootPath);
        }

        final Pattern pattern = isRegex ? Pattern.compile(query) : null;
        var results = new ConcurrentLinkedQueue<FileSearchResult>();
        
        // Используем виртуальные потоки для параллельного сканирования файлов
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(Files::isRegularFile).forEach(path -> {
                    executor.submit(() -> {
                        try {
                            Charset charset = EncodingUtils.detectEncoding(path);
                            List<MatchedLine> matchedLines = new ArrayList<>();
                            AtomicInteger currentLineNum = new AtomicInteger(1);
                            
                            try (Stream<String> lines = Files.lines(path, charset)) {
                                lines.forEach(line -> {
                                    boolean matched = isRegex 
                                        ? pattern.matcher(line).find()
                                        : line.contains(query);
                                    
                                    if (matched) {
                                        // Сохраняем номер строки и обрезанный текст (без лишних пробелов по краям)
                                        matchedLines.add(new MatchedLine(currentLineNum.get(), line.trim()));
                                    }
                                    currentLineNum.incrementAndGet();
                                });
                            }
                            
                            if (!matchedLines.isEmpty()) {
                                results.add(new FileSearchResult(path.toAbsolutePath().toString(), matchedLines));
                            }
                        } catch (Exception ignored) {
                        }
                    });
                });
            }
        }

        var sortedResults = new ArrayList<>(results);
        Collections.sort(sortedResults, (a, b) -> a.path().compareTo(b.path()));

        var resultNode = mapper.createObjectNode();
        var contentArray = resultNode.putArray("content");
        var textNode = contentArray.addObject();
        textNode.put("type", "text");
        
        if (sortedResults.isEmpty()) {
            textNode.put("text", "Совпадений не найдено.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Найдены совпадения в файлах (").append(sortedResults.size()).append("):\n\n");
            for (var res : sortedResults) {
                sb.append(res.path()).append(":\n");
                for (var line : res.lines()) {
                    sb.append("  ").append(line.number()).append(": ").append(line.text()).append("\n");
                }
                sb.append("\n");
            }
            textNode.put("text", sb.toString());
        }
        
        return resultNode;
    }

    private record MatchedLine(int number, String text) {}
    private record FileSearchResult(String path, List<MatchedLine> lines) {}
}
