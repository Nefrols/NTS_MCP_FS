// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.nts.tools.mcp.core.*;

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
 * Высокопроизводительный инструмент для рекурсивного поиска текста и регулярных выражений в проекте.
 * Особенности реализации:
 * 1. Многопоточность: Параллельное сканирование файлов с использованием виртуальных потоков Java 21+
 * минимизирует время ожидания при обработке больших директорий.
 * 2. Контекстная осведомленность: Позволяет запрашивать строки до и после совпадения (before/after context).
 * 3. Интеллектуальный вывод: Группирует результаты по файлам, помечает прочитанные файлы ([READ])
 * и визуально выделяет строки с совпадениями.
 * 4. Отказоустойчивость: Игнорирует системные, защищенные, бинарные и сверхбольшие файлы для предотвращения OOM.
 * 5. Интеграция: Результаты поиска кэшируются в SearchTracker для отображения в nts_list_directory.
 */
public class SearchFilesTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_search_files";
    }

    @Override
    public String getDescription() {
        return "Recursive text/regex search. Shows matching lines with [READ] markers and context. Results are cached for nts_list_directory.";
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
        props.putObject("resetCache").put("type", "boolean").put("description", "If true, only clear search cache without performing new search.");

        schema.putArray("required").add("path").add("query");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        if (params.path("resetCache").asBoolean(false)) {
            SearchTracker.clear();
            var res = mapper.createObjectNode();
            res.putArray("content").addObject().put("type", "text").put("text", "Search cache cleared.");
            return res;
        }

        String pathStr = params.get("path").asText();
        String query = params.get("query").asText();
        boolean isRegex = params.path("isRegex").asBoolean(false);
        int before = params.path("beforeContext").asInt(0);
        int after = params.path("afterContext").asInt(0);

        // Санитарная нормализация пути
        Path rootPath = PathSanitizer.sanitize(pathStr, true);

        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Search directory not found or is not a folder: '" + pathStr + "'. Verify the path.");
        }

        // Очистка старых результатов перед новым поиском
        SearchTracker.clear();

        // Подготовка регулярного выражения для поиска
        final Pattern pattern = isRegex ? Pattern.compile(query, Pattern.MULTILINE | Pattern.DOTALL) : null;

        // Потокобезопасная очередь для сбора результатов из параллельных виртуальных потоков
        var results = new ConcurrentLinkedQueue<FileSearchResult>();

        // Масштабируемая обработка IO операций через Virtual Threads
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (Stream<Path> walk = Files.walk(rootPath)) {
                // Исключаем из поиска папки и защищенные/скрытые системные объекты
                walk.filter(path -> Files.isRegularFile(path) && !PathSanitizer.isProtected(path)).forEach(path -> {
                    executor.submit(() -> {
                        try {
                            // Проверка лимитов размера (защита от загрузки огромных файлов)
                            PathSanitizer.checkFileSize(path);

                            // Эффективное чтение с детекцией кодировки и проверкой на бинарность
                            EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
                            String content = fileData.content();
                            String[] allLines = content.split("\n", -1);
                            List<MatchedLine> matchedLines = new ArrayList<>();

                            // Выбор алгоритма поиска (литеральный или регулярное выражение)
                            if (isRegex) {
                                Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    addMatchWithContext(content, allLines, m.start(), before, after, matchedLines);
                                }
                            } else {
                                int index = content.indexOf(query);
                                while (index >= 0) {
                                    addMatchWithContext(content, allLines, index, before, after, matchedLines);
                                    // Продолжаем поиск со следующего символа
                                    index = content.indexOf(query, index + 1);
                                }
                            }

                            if (!matchedLines.isEmpty()) {
                                // Подсчет уникальных строк-совпадений для трекера
                                long matchCount = matchedLines.stream().filter(l -> l.isMatch).count();
                                SearchTracker.registerMatches(path, (int) matchCount);

                                // Если совпадения найдены — добавляем в результаты и проверяем статус [READ]
                                boolean wasRead = AccessTracker.hasBeenRead(path);
                                results.add(new FileSearchResult(path.toAbsolutePath().toString(), matchedLines, wasRead));
                            }
                        } catch (Exception ignored) {
                            // Игнорируем ошибки доступа и нетекстовые файлы в процессе массового сканирования
                        }
                    });
                });
            }
        }

        // Сортировка результатов по алфавиту путей для стабильного вывода
        var sortedResults = new ArrayList<>(results);
        Collections.sort(sortedResults, (a, b) -> a.path().compareTo(b.path()));

        var resultNode = mapper.createObjectNode();
        var textNode = resultNode.putArray("content").addObject();
        textNode.put("type", "text");

        // Формирование итогового отчета
        if (sortedResults.isEmpty()) {
            textNode.put("text", "No matches found.");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Matches found in files (").append(sortedResults.size()).append("):\n\n");
            for (var res : sortedResults) {
                String readMarker = res.wasRead() ? " [READ]" : "";
                sb.append(res.path()).append(readMarker).append(":\n");
                for (var line : res.lines()) {
                    // Визуальное различие между строкой-совпадением (|) и строкой-контекстом (:) 
                    String prefix = line.isMatch ? "  " + line.number + "| " : "  " + line.number + ": ";
                    sb.append(prefix).append(line.text).append("\n");
                }
                sb.append("\n");
            }
            sb.append("(Tip: These matches are now visible in nts_list_directory output.)");
            textNode.put("text", sb.toString());
        }

        return resultNode;
    }

    /**
     * Вычисляет границы контекста вокруг найденного вхождения и собирает строки.
     *
     * @param content      Полный контент файла.
     * @param allLines     Массив всех строк файла.
     * @param startPos     Позиция символа начала совпадения в контенте.
     * @param before       Количество строк контекста "до".
     * @param after        Количество строк контекста "после".
     * @param matchedLines Список, в который будут добавлены найденные строки.
     */
    private void addMatchWithContext(String content, String[] allLines, int startPos, int before, int after, List<MatchedLine> matchedLines) {
        // Определяем номер строки по позиции символа
        int lineNum = 1;
        for (int i = 0; i < startPos; i++) {
            if (content.charAt(i) == '\n') {
                lineNum++;
            }
        }

        int matchIdx = lineNum - 1;
        // Расчет диапазона строк для вывода
        int startIdx = Math.max(0, matchIdx - before);
        int endIdx = Math.min(allLines.length - 1, matchIdx + after);

        for (int i = startIdx; i <= endIdx; i++) {
            int currentNum = i + 1;
            boolean isMatch = (currentNum == lineNum);
            String text = allLines[i].replace("\r", "");

            // Защита от дублирования строк при перекрывающихся результатах поиска
            int finalI = i;
            if (matchedLines.stream().noneMatch(l -> l.number == (finalI + 1))) {
                matchedLines.add(new MatchedLine(currentNum, text, isMatch));
            } else if (isMatch) {
                // Если строка уже добавлена как контекст, помечаем её как основное совпадение
                for (int j = 0; j < matchedLines.size(); j++) {
                    if (matchedLines.get(j).number == currentNum) {
                        matchedLines.set(j, new MatchedLine(currentNum, text, true));
                        break;
                    }
                }
            }
        }
    }

    /**
     * Представление одной найденной или контекстной строки.
     */
    private record MatchedLine(int number, String text, boolean isMatch) {
    }

    /**
     * Агрегатор результатов поиска по конкретному файлу.
     */
    private record FileSearchResult(String path, List<MatchedLine> lines, boolean wasRead) {
    }
}