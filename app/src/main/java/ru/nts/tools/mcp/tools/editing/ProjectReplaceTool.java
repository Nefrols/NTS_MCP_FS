// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.editing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инструмент для глобального поиска и замены текста во всем проекте.
 * Позволяет проводить массовый рефакторинг в рамках одной транзакции.
 */
public class ProjectReplaceTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_project_replace";
    }

    @Override
    public String getDescription() {
        return "Global search and replace across multiple files. Transactional (all changes can be undone by one nts_undo).";
    }

    @Override
    public String getCategory() {
        return "editing";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("pattern").put("type", "string").put("description", "Search string or regex.");
        props.putObject("replacement").put("type", "string").put("description", "Replacement text.");
        props.putObject("isRegex").put("type", "boolean").put("description", "Treat pattern as regex.");
        props.putObject("include").put("type", "string").put("description", "Optional: Glob pattern for files to include (e.g. 'src/**/*.java').");
        props.putObject("exclude").put("type", "string").put("description", "Optional: Glob pattern for files to exclude.");
        props.putObject("instruction").put("type", "string").put("description", "Semantic label for the transaction.");

        schema.putArray("required").add("pattern").add("replacement");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String query = params.get("pattern").asText();
        String replacement = params.get("replacement").asText();
        boolean isRegex = params.path("isRegex").asBoolean(false);
        String includeGlob = params.path("include").asText(null);
        String excludeGlob = params.path("exclude").asText(null);
        String instruction = params.path("instruction").asText(null);

        Path root = PathSanitizer.getRoot();
        PathMatcher includeMatcher = includeGlob != null ? FileSystems.getDefault().getPathMatcher("glob:" + includeGlob) : null;
        PathMatcher excludeMatcher = excludeGlob != null ? FileSystems.getDefault().getPathMatcher("glob:" + excludeGlob) : null;

        Pattern pattern = isRegex ? Pattern.compile(query, Pattern.MULTILINE | Pattern.DOTALL) : null;

        List<ReplaceTask> tasks = new ArrayList<>();
        int totalOccurrences = 0;

        // 1. Предварительное сканирование
        try (Stream<Path> walk = Files.walk(root)) {
            Iterable<Path> iterable = walk.filter(p -> Files.isRegularFile(p) && !PathSanitizer.isProtected(p))::iterator;
            for (Path p : iterable) {
                try {
                    Path relPath = root.relativize(p);
                    // Нормализация пути для матчера (замена \ на /)
                    Path normalizedRelPath = Path.of(relPath.toString().replace('\\', '/'));
                    
                    if (includeMatcher != null && !includeMatcher.matches(normalizedRelPath)) continue;
                    if (excludeMatcher != null && excludeMatcher.matches(normalizedRelPath)) continue;

                    // Защита от огромных файлов
                    PathSanitizer.checkFileSize(p);

                    // Эффективное чтение с детекцией кодировки и проверкой на бинарность
                    EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(p);
                    String content = fileData.content();
                    int count = 0;

                    if (isRegex) {
                        Matcher m = pattern.matcher(content);
                        while (m.find()) count++;
                    } else {
                        int idx = content.indexOf(query);
                        while (idx >= 0) {
                            count++;
                            idx = content.indexOf(query, idx + query.length());
                        }
                    }

                    if (count > 0) {
                        tasks.add(new ReplaceTask(p, content, fileData.charset(), count));
                        totalOccurrences += count;
                    }
                } catch (Exception ignored) {
                    // Игнорируем бинарные файлы, ошибки доступа или слишком большие файлы в процессе массового сканирования
                }
            }
        }

        if (tasks.isEmpty()) {
            return createResponse("No matches found. No files modified.");
        }

        // 2. Выполнение замены в транзакции
        TransactionManager.startTransaction("Project Replace: '" + query + "' -> '" + replacement + "'", instruction);
        try {
            for (ReplaceTask task : tasks) {
                TransactionManager.backup(task.path);
                
                String newContent;
                if (isRegex) {
                    newContent = pattern.matcher(task.originalContent).replaceAll(replacement);
                } else {
                    newContent = task.originalContent.replace(query, replacement);
                }

                FileUtils.safeWrite(task.path, newContent, task.charset);
                // Массовая замена считается легитимной операцией, обновляем AccessTracker
                AccessTracker.registerRead(task.path);
            }
            TransactionManager.commit();
        } catch (Exception e) {
            TransactionManager.rollback();
            throw e;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Global replacement successful:\n");
        sb.append("- Pattern: ").append(query).append("\n");
        sb.append("- Replacement: ").append(replacement).append("\n");
        sb.append("- Files affected: ").append(tasks.size()).append("\n");
        sb.append("- Total occurrences: ").append(totalOccurrences).append("\n");
        sb.append("\nModified files:\n");
        for (ReplaceTask task : tasks) {
            sb.append("  - ").append(root.relativize(task.path)).append(" (").append(task.count).append(")\n");
        }

        return createResponse(sb.toString().trim());
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }

    private record ReplaceTask(Path path, String originalContent, Charset charset, int count) {}
}