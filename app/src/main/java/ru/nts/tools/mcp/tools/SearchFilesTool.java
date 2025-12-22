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
 * - Маркировка файлов, которые уже были прочитаны LLM ( [READ] ).
 * - Игнорирование системных и защищенных папок.
 */
public class SearchFilesTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "search_files";
    }

    @Override
    public String getDescription() {
        return "Рекурсивный поиск текста или регулярных выражений в файлах проекта. Возвращает фрагменты кода.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("path").put("type", "string").put("description", "Базовая директория для поиска.");
        props.putObject("query").put("type", "string").put("description", "Строка или регулярное выражение.");
        props.putObject("isRegex").put("type", "boolean").put("description", "Трактовать запрос как регулярное выражение.");
        
        schema.putArray("required").add("path").add("query");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        String query = params.get("query").asText();
        boolean isRegex = params.path("isRegex").asBoolean(false);
        
        Path rootPath = PathSanitizer.sanitize(pathStr, true);
        
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Директория не найдена: " + pathStr);
        }

        // Подготовка паттерна (MULTILINE позволяет искать по строкам, DOTALL - захватывать переводы строк если нужно)
        final Pattern pattern = isRegex ? Pattern.compile(query, Pattern.MULTILINE | Pattern.DOTALL) : null;
        var results = new ConcurrentLinkedQueue<FileSearchResult>();
        
        // Используем виртуальные потоки для высокой производительности при массовом чтении с диска
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (Stream<Path> walk = Files.walk(rootPath)) {
                // Фильтруем только обычные файлы и пропускаем системные/защищенные объекты
                walk.filter(path -> Files.isRegularFile(path) && !PathSanitizer.isProtected(path)).forEach(path -> {
                    executor.submit(() -> {
                        try {
                            // Автоматическое определение кодировки для каждого файла
                            Charset charset = EncodingUtils.detectEncoding(path);
                            String content = Files.readString(path, charset);
                            List<MatchedLine> matchedLines = new ArrayList<>();

                            if (isRegex) {
                                Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    addMatchWithLines(content, m.start(), matchedLines);
                                }
                            } else {
                                int index = content.indexOf(query);
                                while (index >= 0) {
                                    addMatchWithLines(content, index, matchedLines);
                                    index = content.indexOf(query, index + 1);
                                }
                            }
                            
                            if (!matchedLines.isEmpty()) {
                                // Помечаем, видел ли я уже этот файл целиком
                                boolean wasRead = AccessTracker.hasBeenRead(path);
                                results.add(new FileSearchResult(path.toAbsolutePath().toString(), matchedLines, wasRead));
                            }
                        } catch (Exception ignored) {
                            // Ошибки доступа или бинарные данные игнорируем
                        }
                    });
                });
            }
        }

        var sortedResults = new ArrayList<>(results);
        Collections.sort(sortedResults, (a, b) -> a.path().compareTo(b.path()));

        var resultNode = mapper.createObjectNode();
        var textNode = resultNode.putArray("content").addObject();
        textNode.put("type", "text");
        
        if (sortedResults.isEmpty()) {
            textNode.put("text", "Совпадений не найдено.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Найдены совпадения в файлах (").append(sortedResults.size()).append("):\n\n");
            for (var res : sortedResults) {
                String readMarker = res.wasRead() ? " [READ]" : "";
                sb.append(res.path()).append(readMarker).append(":\n");
                for (var line : res.lines()) {
                    sb.append("  ").append(line.number()).append("| ").append(line.text()).append("\n");
                }
                sb.append("\n");
            }
            textNode.put("text", sb.toString());
        }
        
        return resultNode;
    }

    /**
     * Извлекает полную строку, в которой произошло совпадение, и определяет её номер.
     */
    private void addMatchWithLines(String content, int start, List<MatchedLine> matchedLines) {
        int lineNum = 1;
        int lastNewLine = -1;
        for (int i = 0; i < start; i++) {
            if (content.charAt(i) == '\n') {
                lineNum++;
                lastNewLine = i;
            }
        }
        
        int nextNewLine = content.indexOf('\n', start);
        if (nextNewLine == -1) nextNewLine = content.length();
        
        // Очищаем строку от CR для консистентного вывода
        String lineText = content.substring(lastNewLine + 1, nextNewLine).replace("\r", "");
        
        int finalLineNum = lineNum;
        if (matchedLines.stream().noneMatch(l -> l.number() == finalLineNum)) {
            matchedLines.add(new MatchedLine(lineNum, lineText));
        }
    }

    private record MatchedLine(int number, String text) {}
    private record FileSearchResult(String path, List<MatchedLine> lines, boolean wasRead) {}
}
