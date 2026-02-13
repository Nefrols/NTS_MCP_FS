/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;

/**
 * Объединенный инструмент для поиска и навигации по проекту.
 * Поддерживает листинг директорий (list), поиск файлов по паттерну (find),
 * поиск по содержимому (grep) и генерацию структуры проекта (structure).
 */
public class FileSearchTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    // TIP: Подсказка о workflow после grep
    private static final String GREP_WORKFLOW_TIP =
        "[WORKFLOW: Tokens from grep output are ready for editing -> " +
        "nts_edit_file(path, startLine, content, accessToken=TOKEN_FROM_ABOVE)]";

    // TIP: Подсказка при большом количестве совпадений
    private static final String MANY_MATCHES_TIP =
        "[TIP: Many matches found. Consider narrowing search with more specific pattern, " +
        "or use isRegex=true for regex patterns like 'method.*Name']";

    // TIP: Паттерн похож на regex
    private static final String REGEX_HINT_TIP =
        "[TIP: Pattern contains regex-like characters (%s). " +
        "If you intended regex search, add isRegex=true parameter.]";

    @Override
    public String getName() {
        return "nts_file_search";
    }

    @Override
    public String getDescription() {
        return """
            Project navigation and code search with automatic token generation.

            ACTIONS:
            - list  - Directory contents. Shows files with their access tokens (if read before).
                      Format: [FILE] name.java
                                [Lines 1-50 | TOKEN: LAT:...]
            - find  - Locate files by glob pattern (e.g., '**/*.java', 'src/**/Test*.ts')
            - grep  - Search file contents. RETURNS TOKENS for matched line ranges!
                      Use these tokens directly with nts_edit_file.
                      Supports context: before=N, after=N (like grep -B/-A)
            - structure - Project tree visualization (respects .gitignore)

            GREP OUTPUT FORMAT:
            - Match lines marked with ':' (e.g., "  42: public void foo()")
            - Context lines marked with '-' (e.g., "  41- @Override")

            TOKEN WORKFLOW:
            1. grep for code pattern -> get tokens for matches (+ context if needed)
            2. Use tokens from grep output directly in nts_edit_file
            3. Or use list to see previously accessed file ranges with tokens

            TIPS:
            - grep with before=2, after=2 gives surrounding context for understanding
            - grep returns grouped line ranges with tokens - no need to nts_file_read first!
            - Use autoIgnore=true (default) to skip build artifacts
            """;
    }

    @Override
    public String getCategory() {
        return "fs";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'list' (directory + access tokens), 'find' (glob filename search), " +
                "'grep' (content search -> RETURNS TOKENS), 'structure' (tree view). Required.");

        props.putObject("path").put("type", "string").put("description",
                "Starting directory or file path. For 'grep': can be a single file. Use '.' for project root. Default: '.'");

        props.putObject("pattern").put("type", "string").put("description",
                "Search pattern. For 'find': glob like '**/*.java' or 'Test*.ts'. " +
                "For 'grep': literal text or regex (if isRegex=true). Required for find/grep.");

        props.putObject("isRegex").put("type", "boolean").put("description",
                "Treat pattern as regex for 'grep'. Enables: .* quantifiers, \\d classes, etc. " +
                "Default: false (literal substring match).");

        props.putObject("depth").put("type", "integer").put("description",
                "Max directory depth. For 'list': 1=current only, 2=include subdirs. " +
                "For 'structure': tree depth. Default varies by action.");

        props.putObject("autoIgnore").put("type", "boolean").put("description",
                "Skip .git, build/, node_modules/, and .gitignore patterns. Default: true. " +
                "Set false to search everything including ignored paths.");

        props.putObject("maxResults").put("type", "integer").put("description",
                "Maximum number of matching files for 'grep'. Default: 100. " +
                "Set 0 for unlimited (may be slow on large codebases).");

        props.putObject("before").put("type", "integer").put("description",
                "For 'grep': lines of context BEFORE each match (like grep -B). Default: 0.");

        props.putObject("after").put("type", "integer").put("description",
                "For 'grep': lines of context AFTER each match (like grep -A). Default: 0.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();
        String pathStr = params.path("path").asText(".");

        return switch (action) {
            case "list" -> executeList(pathStr, params);
            case "find" -> executeFind(pathStr, params.get("pattern").asText());
            case "grep" ->
                    executeGrep(pathStr, params.get("pattern").asText(),
                            params.path("isRegex").asBoolean(false),
                            params.path("maxResults").asInt(100),
                            params.path("before").asInt(0),
                            params.path("after").asInt(0));
            case "structure" ->
                    executeStructure(pathStr, params.path("depth").asInt(3), params.path("autoIgnore").asBoolean(true));
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeList(String pathStr, JsonNode params) throws IOException {
        Path path = PathSanitizer.sanitize(pathStr, true);
        int depth = params.path("depth").asInt(1);
        boolean autoIgnore = params.path("autoIgnore").asBoolean(true);

        Set<String> ignored = autoIgnore ? getStandardIgnored() : new HashSet<>();
        List<String> entries = new ArrayList<>();
        listRecursive(path, entries, 0, depth, "", ignored);

        return createResponse(entries.isEmpty() ? "(directory is empty)" : String.join("\n", entries));
    }

    private void listRecursive(Path current, List<String> result, int level, int max, String indent, Set<String> ignored) throws IOException {
        if (level >= max) {
            return;
        }
        List<Path> sub = new ArrayList<>();
        try (var s = Files.newDirectoryStream(current)) {
            s.forEach(sub::add);
        }
        sub.sort((a, b) -> {
            boolean ad = Files.isDirectory(a), bd = Files.isDirectory(b);
            return (ad != bd) ? (ad ? -1 : 1) : a.getFileName().compareTo(b.getFileName());
        });

        for (Path p : sub) {
            if (PathSanitizer.isProtected(p) || ignored.contains(p.getFileName().toString())) {
                continue;
            }
            boolean isDir = Files.isDirectory(p);
            int matches = (!isDir) ? SearchTracker.getMatchCount(p) : 0;
            String mStatus = matches > 0 ? " [MATCHES: " + matches + "]" : "";

            // Основная строка файла/директории
            result.add(indent + (isDir ? "[DIR]" : "[FILE]") + " " + p.getFileName() + mStatus);

            // Для файлов добавляем токены построчно
            if (!isDir) {
                List<String> tokenLines = LineAccessTracker.getFormattedTokenLines(p, indent);
                result.addAll(tokenLines);
            }

            if (isDir) {
                listRecursive(p, result, level + 1, max, indent + "  ", ignored);
            }
        }
    }

    private JsonNode executeFind(String pathStr, String pattern) throws IOException {
        Path basePath = PathSanitizer.sanitize(pathStr, true);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        Path root = PathSanitizer.getRoot();

        // Для паттернов типа **/*.java создаём дополнительный matcher для корневых файлов
        // Извлекаем паттерн после **/ для проверки файлов в корне
        PathMatcher fileNameMatcher = null;
        if (pattern.startsWith("**/")) {
            String filePattern = pattern.substring(3); // Убираем **/
            fileNameMatcher = FileSystems.getDefault().getPathMatcher("glob:" + filePattern);
        }
        final PathMatcher finalFileNameMatcher = fileNameMatcher;

        List<String> found = new ArrayList<>();
        try (var s = Files.walk(basePath)) {
            s.filter(p -> {
                Path relativePath = root.relativize(p);
                Path fileName = p.getFileName();

                // Проверяем полный относительный путь
                if (matcher.matches(relativePath)) {
                    return true;
                }
                // Проверяем только имя файла (для простых паттернов типа *.java)
                if (matcher.matches(fileName)) {
                    return true;
                }
                // Для **/*.ext проверяем файлы в корне через extracted pattern
                if (finalFileNameMatcher != null && finalFileNameMatcher.matches(fileName)) {
                    return true;
                }
                return false;
            }).forEach(p -> found.add(root.relativize(p).toString()));
        }
        return createResponse("Found " + found.size() + " matches:\n" + String.join("\n", found));
    }

    private JsonNode executeGrep(String pathStr, String query, boolean isRegex, int maxResults,
                                  int contextBefore, int contextAfter) throws Exception {
        Path rootPath = PathSanitizer.sanitize(pathStr, true);
        if (!Files.exists(rootPath)) {
            throw new IllegalArgumentException("Search path not found: " + pathStr);
        }

        // Поддержка поиска в одиночном файле
        boolean isSingleFile = Files.isRegularFile(rootPath);
        if (isSingleFile && PathSanitizer.isProtected(rootPath)) {
            throw new IllegalArgumentException("Cannot search in protected file: " + pathStr);
        }

        SearchTracker.clear();
        var results = new java.util.concurrent.ConcurrentLinkedQueue<FileSearchResult>();
        var filesProcessed = new java.util.concurrent.atomic.AtomicInteger(0);
        var filesWithMatches = new java.util.concurrent.atomic.AtomicInteger(0);
        final int maxFiles = maxResults > 0 ? maxResults : Integer.MAX_VALUE;

        // Захватываем контекст сессии для проброса в worker threads
        final TaskContext parentContext = TaskContext.current();

        // Создаём поток файлов: один файл или обход директории
        java.util.stream.Stream<Path> fileStream;
        if (isSingleFile) {
            fileStream = java.util.stream.Stream.of(rootPath);
        } else {
            fileStream = Files.walk(rootPath)
                    .filter(path -> Files.isRegularFile(path) && !PathSanitizer.isProtected(path));
        }

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try (java.util.stream.Stream<Path> walk = fileStream) {
                walk.forEach(path -> {
                    // Ранняя остановка: не запускаем новые задачи если достигли лимита
                    if (filesWithMatches.get() >= maxFiles) {
                        return;
                    }

                    executor.submit(() -> {
                        // Пробрасываем контекст сессии в worker thread
                        if (parentContext != null) {
                            TaskContext.setCurrent(parentContext);
                        }
                        try {
                            // Двойная проверка внутри потока
                            if (filesWithMatches.get() >= maxFiles) {
                                return;
                            }

                            PathSanitizer.checkFileSize(path);
                            filesProcessed.incrementAndGet();

                            // Используем оптимизированный FastSearch с контекстом
                            FastSearch.SearchResult searchResult = FastSearch.search(
                                    path, query, isRegex,
                                    0,  // maxResults per file - без лимита
                                    contextBefore, contextAfter
                            );

                            if (searchResult != null && !searchResult.matches().isEmpty()) {
                                int matchesInFile = filesWithMatches.incrementAndGet();
                                if (matchesInFile > maxFiles) {
                                    return; // Превысили лимит
                                }

                                long matchCount = searchResult.matches().stream()
                                        .filter(FastSearch.MatchedLine::isMatch).count();
                                SearchTracker.registerMatches(path, (int) matchCount);

                                // Преобразуем результаты и группируем в диапазоны
                                List<MatchedLine> matchedLines = searchResult.matches().stream()
                                        .map(m -> new MatchedLine(m.lineNumber(), m.text(), m.isMatch()))
                                        .toList();

                                List<LineRange> ranges = groupLinesIntoRanges(
                                        path, matchedLines,
                                        searchResult.crc32c(),
                                        searchResult.lineCount()
                                );

                                results.add(new FileSearchResult(
                                        path.toAbsolutePath().toString(),
                                        matchedLines,
                                        ranges
                                ));
                            }
                        } catch (Exception ignored) {
                            // Игнорируем ошибки обработки отдельных файлов
                        } finally {
                            // Очищаем контекст потока
                            TaskContext.clearCurrent();
                        }
                    });
                });
            }
        }

        var sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparing(FileSearchResult::path));

        if (sortedResults.isEmpty()) {
            return createResponse("No matches found. (Scanned " + filesProcessed.get() + " files)");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Matches found in ").append(sortedResults.size()).append(" files");
        if (sortedResults.size() >= maxFiles && maxResults > 0) {
            sb.append(" (limit reached, use maxResults=0 for all)");
        }
        sb.append(":\n\n");

        for (var res : sortedResults) {
            sb.append(res.path()).append(":\n");
            for (var range : res.ranges()) {
                sb.append(String.format("  [Lines %d-%d | TOKEN: %s]\n", range.start(), range.end(), range.token()));
            }
            for (var line : res.lines()) {
                // ':' для совпадений, '-' для контекста (как в grep)
                char marker = line.isMatch() ? ':' : '-';
                sb.append(String.format("  %4d%c %s\n", line.number(), marker, line.text()));
            }
            sb.append("\n");
        }

        // TIPs для улучшения workflow
        sb.append("\n");

        // TIP о workflow
        sb.append(GREP_WORKFLOW_TIP).append("\n");

        // TIP если много совпадений
        int totalMatches = sortedResults.stream().mapToInt(r -> (int) r.lines().stream().filter(MatchedLine::isMatch).count()).sum();
        if (totalMatches > 20) {
            sb.append(MANY_MATCHES_TIP).append("\n");
        }

        // TIP если паттерн похож на regex но isRegex=false
        if (!isRegex && looksLikeRegex(query)) {
            String regexChars = detectRegexChars(query);
            sb.append(String.format(REGEX_HINT_TIP, regexChars)).append("\n");
        }

        return createResponse(sb.toString().trim());
    }

    /**
     * Определяет, похож ли паттерн на регулярное выражение.
     */
    private boolean looksLikeRegex(String pattern) {
        // Проверяем наличие типичных regex-символов
        return pattern.contains(".*") || pattern.contains(".+") ||
               pattern.contains("\\d") || pattern.contains("\\w") || pattern.contains("\\s") ||
               pattern.contains("[") || pattern.contains("(") ||
               pattern.contains("^") || pattern.contains("$") ||
               pattern.contains("?") || pattern.contains("+");
    }

    /**
     * Извлекает обнаруженные regex-символы для подсказки.
     */
    private String detectRegexChars(String pattern) {
        List<String> found = new ArrayList<>();
        if (pattern.contains(".*")) found.add(".*");
        else if (pattern.contains(".+")) found.add(".+");
        if (pattern.contains("\\d")) found.add("\\d");
        if (pattern.contains("\\w")) found.add("\\w");
        if (pattern.contains("[")) found.add("[...]");
        if (pattern.contains("(")) found.add("(...)");
        if (pattern.contains("?")) found.add("?");
        if (pattern.contains("+") && !pattern.contains(".+")) found.add("+");
        return String.join(", ", found.isEmpty() ? List.of("regex chars") : found);
    }

    /**
     * Группирует найденные строки в смежные диапазоны и регистрирует токены доступа.
     * Использует текст строк для вычисления rangeCrc.
     */
    private List<LineRange> groupLinesIntoRanges(Path path, List<MatchedLine> lines, long crc, int lineCount) {
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        // Создаём карту номер строки -> текст
        Map<Integer, String> lineTexts = new HashMap<>();
        for (MatchedLine ml : lines) {
            lineTexts.put(ml.number(), ml.text());
        }

        // Сортируем по номеру строки
        List<Integer> lineNums = lines.stream().map(MatchedLine::number).sorted().distinct().toList();

        List<LineRange> ranges = new ArrayList<>();
        int rangeStart = lineNums.get(0);
        int rangeEnd = rangeStart;

        for (int i = 1; i < lineNums.size(); i++) {
            int current = lineNums.get(i);
            if (current == rangeEnd + 1) {
                // Смежная строка - расширяем диапазон
                rangeEnd = current;
            } else {
                // Разрыв - регистрируем текущий диапазон
                String rangeContent = buildRangeContent(lineTexts, rangeStart, rangeEnd);
                LineAccessToken token = LineAccessTracker.registerAccess(path, rangeStart, rangeEnd, rangeContent, lineCount, crc);
                ranges.add(new LineRange(rangeStart, rangeEnd, token.encode()));
                rangeStart = current;
                rangeEnd = current;
            }
        }

        // Регистрируем последний диапазон
        String rangeContent = buildRangeContent(lineTexts, rangeStart, rangeEnd);
        LineAccessToken token = LineAccessTracker.registerAccess(path, rangeStart, rangeEnd, rangeContent, lineCount, crc);
        ranges.add(new LineRange(rangeStart, rangeEnd, token.encode()));

        return ranges;
    }

    /**
     * Строит чистое содержимое диапазона из карты текстов строк (без номеров строк).
     * Формат должен совпадать с extractRawContent в FileReadTool и EditFileTool
     * для корректного вычисления CRC токена.
     */
    private String buildRangeContent(Map<Integer, String> lineTexts, int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) {
                sb.append("\n");
            }
            sb.append(lineTexts.getOrDefault(i, ""));
        }
        return sb.toString();
    }


    private record MatchedLine(int number, String text, boolean isMatch) {
    }

    private record LineRange(int start, int end, String token) {
    }

    private record FileSearchResult(String path, List<MatchedLine> lines, List<LineRange> ranges) {
    }

    private JsonNode executeStructure(String pathStr, int depth, boolean autoIgnore) throws IOException {
        Path path = PathSanitizer.sanitize(pathStr, true);
        Set<String> ignored = autoIgnore ? getStandardIgnored() : new HashSet<>();
        StringBuilder sb = new StringBuilder();
        generateTree(path, sb, 0, depth, "", ignored);
        return createResponse(sb.toString());
    }

    private void generateTree(Path current, StringBuilder sb, int level, int max, String indent, Set<String> ignored) throws IOException {
        if (level >= max) {
            return;
        }
        List<Path> sub = new ArrayList<>();
        try (var s = Files.newDirectoryStream(current)) {
            s.forEach(sub::add);
        }
        sub.sort((a, b) -> {
            boolean ad = Files.isDirectory(a), bd = Files.isDirectory(b);
            return (ad != bd) ? (ad ? -1 : 1) : a.getFileName().compareTo(b.getFileName());
        });

        for (int i = 0; i < sub.size(); i++) {
            Path p = sub.get(i);
            if (PathSanitizer.isProtected(p) || ignored.contains(p.getFileName().toString())) {
                continue;
            }
            boolean isLast = (i == sub.size() - 1);
            sb.append(indent).append(isLast ? "└── " : "├── ").append(p.getFileName()).append("\n");
            if (Files.isDirectory(p)) {
                generateTree(p, sb, level + 1, max, indent + (isLast ? "    " : "│   "), ignored);
            }
        }
    }

    private Set<String> getStandardIgnored() {
        Set<String> s = new HashSet<>(Arrays.asList(".git", ".gradle", ".idea", "build", ".nts"));
        try {
            s.addAll(GitUtils.getIgnoredPaths());
        } catch (Exception ignored) {
        }
        return s;
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}