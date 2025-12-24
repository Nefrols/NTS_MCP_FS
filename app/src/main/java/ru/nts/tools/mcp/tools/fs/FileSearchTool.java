// Aristo 24.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Объединенный инструмент для поиска и навигации по проекту.
 * Поддерживает листинг директорий (list), поиск файлов по паттерну (find),
 * поиск по содержимому (grep) и генерацию структуры проекта (structure).
 */
public class FileSearchTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_file_search";
    }

    @Override
    public String getDescription() {
        return """
            Project navigation and code search with automatic token generation.

            ACTIONS:
            • list  - Directory contents. Shows files with their access tokens (if read before).
                      Format: [FILE] name.java
                                [Lines 1-50 | TOKEN: LAT:...]
            • find  - Locate files by glob pattern (e.g., '**/*.java', 'src/**/Test*.ts')
            • grep  - Search file contents. RETURNS TOKENS for matched line ranges!
                      Use these tokens directly with nts_edit_file.
            • structure - Project tree visualization (respects .gitignore)

            TOKEN WORKFLOW:
            1. grep for code pattern → get tokens for matches
            2. Use tokens from grep output directly in nts_edit_file
            3. Or use list to see previously accessed file ranges with tokens

            TIPS:
            • grep returns grouped line ranges with tokens - no need to nts_file_read first!
            • list shows [ACCESS: ...] for files you've already read
            • Use autoIgnore=true (default) to skip build artifacts
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
                "'grep' (content search → RETURNS TOKENS), 'structure' (tree view). Required.");

        props.putObject("path").put("type", "string").put("description",
                "Starting directory. Use '.' for project root. Default: '.'");

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
                    executeGrep(pathStr, params.get("pattern").asText(), params.path("isRegex").asBoolean(false));
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
        Path path = PathSanitizer.sanitize(pathStr, true);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> found = new ArrayList<>();
        try (var s = Files.walk(path)) {
            s.filter(p -> matcher.matches(p.getFileName())).forEach(p -> found.add(PathSanitizer.getRoot().relativize(p).toString()));
        }
        return createResponse("Found " + found.size() + " matches:\n" + String.join("\n", found));
    }

    private JsonNode executeGrep(String pathStr, String query, boolean isRegex) throws Exception {
        Path rootPath = PathSanitizer.sanitize(pathStr, true);
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Search directory not found: " + pathStr);
        }

        SearchTracker.clear();
        final Pattern pattern = isRegex ? Pattern.compile(query, Pattern.MULTILINE | Pattern.DOTALL) : null;
        var results = new java.util.concurrent.ConcurrentLinkedQueue<FileSearchResult>();

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try (java.util.stream.Stream<Path> walk = Files.walk(rootPath)) {
                walk.filter(path -> Files.isRegularFile(path) && !PathSanitizer.isProtected(path)).forEach(path -> {
                    executor.submit(() -> {
                        try {
                            PathSanitizer.checkFileSize(path);
                            EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
                            String content = fileData.content();
                            String[] allLines = content.split("\n", -1);
                            int lineCount = allLines.length;
                            List<MatchedLine> matchedLines = new ArrayList<>();

                            if (isRegex) {
                                java.util.regex.Matcher m = pattern.matcher(content);
                                while (m.find()) {
                                    addMatchWithContext(content, allLines, m.start(), 0, 0, matchedLines);
                                }
                            } else {
                                int index = content.indexOf(query);
                                while (index >= 0) {
                                    addMatchWithContext(content, allLines, index, 0, 0, matchedLines);
                                    index = content.indexOf(query, index + 1);
                                }
                            }

                            if (!matchedLines.isEmpty()) {
                                long matchCount = matchedLines.stream().filter(l -> l.isMatch).count();
                                SearchTracker.registerMatches(path, (int) matchCount);

                                // Вычисляем CRC и группируем строки в диапазоны
                                long crc = calculateCRC32(path);
                                List<LineRange> ranges = groupLinesIntoRanges(path, matchedLines, crc, lineCount);

                                results.add(new FileSearchResult(path.toAbsolutePath().toString(), matchedLines, ranges));
                            }
                        } catch (Exception ignored) {
                        }
                    });
                });
            }
        }

        var sortedResults = new ArrayList<>(results);
        sortedResults.sort(Comparator.comparing(FileSearchResult::path));

        if (sortedResults.isEmpty()) {
            return createResponse("No matches found.");
        }

        StringBuilder sb = new StringBuilder("Matches found in " + sortedResults.size() + " files:\n\n");
        for (var res : sortedResults) {
            sb.append(res.path()).append(":\n");
            // Показываем выданные токены для диапазонов
            for (var range : res.ranges()) {
                sb.append(String.format("  [Lines %d-%d | TOKEN: %s]\n", range.start(), range.end(), range.token()));
            }
            for (var line : res.lines()) {
                sb.append(String.format("  %4d| %s\n", line.number(), line.text()));
            }
            sb.append("\n");
        }
        return createResponse(sb.toString().trim());
    }

    /**
     * Группирует найденные строки в смежные диапазоны и регистрирует токены доступа.
     */
    private List<LineRange> groupLinesIntoRanges(Path path, List<MatchedLine> lines, long crc, int lineCount) {
        if (lines.isEmpty()) {
            return Collections.emptyList();
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
                LineAccessToken token = LineAccessTracker.registerAccess(path, rangeStart, rangeEnd, crc, lineCount);
                ranges.add(new LineRange(rangeStart, rangeEnd, token.encode()));
                rangeStart = current;
                rangeEnd = current;
            }
        }

        // Регистрируем последний диапазон
        LineAccessToken token = LineAccessTracker.registerAccess(path, rangeStart, rangeEnd, crc, lineCount);
        ranges.add(new LineRange(rangeStart, rangeEnd, token.encode()));

        return ranges;
    }

    /**
     * Вычисляет CRC32C для файла.
     */
    private long calculateCRC32(Path path) throws Exception {
        CRC32C crc = new CRC32C();
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    private void addMatchWithContext(String content, String[] allLines, int startPos, int before, int after, List<MatchedLine> matchedLines) {
        int lineNum = 1;
        for (int i = 0; i < startPos; i++) {
            if (content.charAt(i) == '\n') {
                lineNum++;
            }
        }
        int matchIdx = lineNum - 1;
        int startIdx = Math.max(0, matchIdx - before);
        int endIdx = Math.min(allLines.length - 1, matchIdx + after);

        for (int i = startIdx; i <= endIdx; i++) {
            int currentNum = i + 1;
            boolean isMatch = (currentNum == lineNum);
            String text = allLines[i].replace("\r", "");
            if (matchedLines.stream().noneMatch(l -> l.number() == currentNum)) {
                matchedLines.add(new MatchedLine(currentNum, text, isMatch));
            } else if (isMatch) {
                for (int j = 0; j < matchedLines.size(); j++) {
                    if (matchedLines.get(j).number() == currentNum) {
                        matchedLines.set(j, new MatchedLine(currentNum, text, true));
                        break;
                    }
                }
            }
        }
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