// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инструмент для рекурсивного поиска текста в файлах проекта.
 * Особенности:
 * - Параллельное сканирование файлов с использованием виртуальных потоков Java 21+.
 * - Поддержка регулярных выражений и многострочного поиска.
 * - Вывод найденных строк с номерами для мгновенного анализа контекста.
 * - Поддержка контекстных строк (before/after) для лучшего понимания кода.
 * - Маркировка файлов, которые уже были прочитаны LLM ( [READ] ).
 * - Игнорирование системных, защищенных и слишком больших бинарных файлов.
 */
public class SearchFilesTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public String getDescription() {
        return "Recursive text/regex search. Shows matching lines with [READ] markers and context.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Base search directory.");
        props.putObject("query").put("type", "string").put("description", "Search string or regex.");
        props.putObject("isRegex").put("type", "boolean").put("description", "Treat query as regex.");
        props.putObject("beforeContext").put("type", "integer").put("description", "Context lines before match.");
        props.putObject("afterContext").put("type", "integer").put("description", "Context lines after match.");
        
        schema.putArray("required").add("path").add("query");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String query = params.get("query").asText();
        boolean isRegex = params.path("isRegex").asBoolean(false);
        int before = params.path("beforeContext").asInt(0);
        int after = params.path("afterContext").asInt(0);
        
        Path rootPath = PathSanitizer.sanitize(pathStr, true);
        
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Directory not found: " + pathStr);
        }

        // Подготовка паттерна
        final Pattern pattern = isRegex ? Pattern.compile(query, Pattern.MULTILINE | Pattern.DOTALL) : null;
        var results = new ConcurrentLinkedQueue<FileSearchResult>();
        
        // Используем виртуальные потоки для высокой производительности
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(path -> Files.isRegularFile(path) && !PathSanitizer.isProtected(path)).forEach(path -> {
                    executor.submit(() -> {
                        try {
                            // Проверка размера перед чтением (защита от OOM)
                            PathSanitizer.checkFileSize(path);
                            
                            // Автоматическое определение кодировки и проверка на бинарность
                            Charset charset = EncodingUtils.detectEncoding(path);
                            
                            String content = Files.readString(path, charset);
                            String[] allLines = content.split("\n", -1);
                            List<MatchedLine> matchedLines = new ArrayList<>();

                            if (isRegex) {
                                Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    addMatchWithContext(content, allLines, m.start(), before, after, matchedLines);
                                }
                            } else {
                                int index = content.indexOf(query);
                                while (index >= 0) {
                                    addMatchWithContext(content, allLines, index, before, after, matchedLines);
                                    index = content.indexOf(query, index + 1);
                                }
                            }
                            
                            if (!matchedLines.isEmpty()) {
                                boolean wasRead = AccessTracker.hasBeenRead(path);
                                results.add(new FileSearchResult(path.toAbsolutePath().toString(), matchedLines, wasRead));
                            }
                        } catch (Exception ignored) {
                            // Пропускаем слишком большие или бинарные файлы молча при массовом поиске
                        }
                    });
                });
            }
        }

        // Сортировка результатов
        var sortedResults = new ArrayList<>(results);
        Collections.sort(sortedResults, (a, b) -> a.path().compareTo(b.path()));

        var resultNode = mapper.createObjectNode();
        var textNode = resultNode.putArray("content").addObject();
        textNode.put("type", "text");
        
        if (sortedResults.isEmpty()) {
            textNode.put("text", "No matches found.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Matches found in files (").append(sortedResults.size()).append("):\n\n");
            for (var res : sortedResults) {
                String readMarker = res.wasRead() ? " [READ]" : "";
                sb.append(res.path()).append(readMarker).append(":\n");
                for (var line : res.lines()) {
                    String prefix = line.isMatch ? "  " + line.number + "| " : "  " + line.number + ": ";
                    sb.append(prefix).append(line.text).append("\n");
                }
                sb.append("\n");
            }
            textNode.put("text", sb.toString());
        }
        
        return resultNode;
    }

    /**
     * Извлекает строку с совпадением и окружающий контекст.
     */
    private void addMatchWithContext(String content, String[] allLines, int startPos, int before, int after, List<MatchedLine> matchedLines) {
        int lineNum = 1;
        for (int i = 0; i < startPos; i++) {
            if (content.charAt(i) == '\n') lineNum++;
        }
        
        int matchIdx = lineNum - 1;
        int startIdx = Math.max(0, matchIdx - before);
        int endIdx = Math.min(allLines.length - 1, matchIdx + after);

        for (int i = startIdx; i <= endIdx; i++) {
            int currentNum = i + 1;
            boolean isMatch = (currentNum == lineNum);
            String text = allLines[i].replace("\r", "");
            
            int finalI = i;
            if (matchedLines.stream().noneMatch(l -> l.number == (finalI + 1))) {
                matchedLines.add(new MatchedLine(currentNum, text, isMatch));
            } else if (isMatch) {
                for (int j = 0; j < matchedLines.size(); j++) {
                    if (matchedLines.get(j).number == currentNum) {
                        matchedLines.set(j, new MatchedLine(currentNum, text, true));
                        break;
                    }
                }
            }
        }
    }

    private record MatchedLine(int number, String text, boolean isMatch) {}
    private record FileSearchResult(String path, List<MatchedLine> lines, boolean wasRead) {}
}