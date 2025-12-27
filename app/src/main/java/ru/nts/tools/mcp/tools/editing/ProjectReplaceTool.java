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
import java.util.zip.CRC32C;

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
        return """
            Global search and replace across entire project.

            USE CASES:
            - Rename class/method across all files
            - Update import paths
            - Change API endpoints
            - Fix typos in comments/strings

            FEATURES:
            - Atomic transaction: all files succeed or all rollback
            - Regex support for complex patterns
            - Include/exclude globs to limit scope
            - Binary files automatically skipped
            - Report shows affected files with occurrence counts
            - DRY RUN MODE: Preview unified diff without modifying files
            - AUTO-CHECKPOINT: Session checkpoint created before changes

            SAFETY:
            - Use dryRun=true to preview changes first
            - All changes undoable via nts_session undo

            TOKEN OUTPUT:
            Returns affectedFiles with path, crc32c, lineCount, and accessToken for each modified file.
            Use tokens in batch: {{stepN.affectedFiles}} or parse from output for subsequent edits.

            EXAMPLES:
            - Preview: pattern='OldClass', replacement='NewClass', dryRun=true
            - Rename: pattern='OldClass', replacement='NewClass', include='**/*.java'
            - Regex: pattern='log\\.(info|debug)', replacement='logger.$1', isRegex=true

            WARNING: Powerful tool - use dryRun=true first to verify pattern matches!
            """;
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

        props.putObject("pattern").put("type", "string").put("description",
                "Text to find. Literal string unless isRegex=true. " +
                "For regex: use Java Pattern syntax. Required.");

        props.putObject("replacement").put("type", "string").put("description",
                "Replacement text. For regex: $1, $2 for capture groups. Required. " +
                "Example: 'New$1Name' replaces 'OldFooName' with 'NewFooName' if pattern='Old(.*)Name'.");

        props.putObject("isRegex").put("type", "boolean").put("description",
                "Interpret pattern as regex. Default: false (literal match). " +
                "Use for: wildcards (.*), groups ((foo|bar)), special chars (\\d+).");

        props.putObject("dryRun").put("type", "boolean").put("description",
                "Preview mode: shows unified diff without modifying files. Default: false. " +
                "RECOMMENDED: Always use dryRun=true first to verify changes before applying.");

        props.putObject("maxPreviewMatches").put("type", "integer").put("description",
                "Maximum matches to show in dryRun mode per file. Default: 10. " +
                "Set higher to see more context, lower for large projects.");

        props.putObject("include").put("type", "string").put("description",
                "Glob to limit scope. Examples: 'src/**/*.java', '**/*.ts', 'app/models/*.py'. " +
                "Omit to search entire project.");

        props.putObject("exclude").put("type", "string").put("description",
                "Glob to skip files/dirs. Examples: '**/test/**', '**/*.min.js'. " +
                "Build dirs and .git always excluded.");

        props.putObject("instruction").put("type", "string").put("description",
                "Description for session journal. Example: 'Rename UserService to AccountService'. " +
                "Shown in nts_session journal output.");
                        props.putObject("encoding").put("type", "string").put("description",
                                "Optional: Output encoding (e.g. 'UTF-8', 'windows-1251'). If specified, all modified files will be converted to this encoding.");

        schema.putArray("required").add("pattern").add("replacement");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String query = params.get("pattern").asText();
        String replacement = params.get("replacement").asText();
        boolean isRegex = params.path("isRegex").asBoolean(false);
        boolean dryRun = params.path("dryRun").asBoolean(false);
        int maxPreviewMatches = params.path("maxPreviewMatches").asInt(10);
        String includeGlob = params.path("include").asText(null);
        String excludeGlob = params.path("exclude").asText(null);
        String instruction = params.path("instruction").asText(null);

        Path root = PathSanitizer.getRoot();
        PathMatcher includeMatcher = includeGlob != null ? FileSystems.getDefault().getPathMatcher("glob:" + includeGlob) : null;
        PathMatcher excludeMatcher = excludeGlob != null ? FileSystems.getDefault().getPathMatcher("glob:" + excludeGlob) : null;

        Pattern pattern = isRegex ? Pattern.compile(query, Pattern.MULTILINE | Pattern.DOTALL) : null;

        List<ReplaceTask> tasks = new ArrayList<>();
        int totalOccurrences = 0;

        Charset forcedCharset = null;
        if (params.has("encoding")) {
            try {
                forcedCharset = Charset.forName(params.get("encoding").asText());
            } catch (Exception ignored) {}
        }

        // 1. Предварительное сканирование
        try (Stream<Path> walk = Files.walk(root)) {
            Iterable<Path> iterable = walk.filter(p -> Files.isRegularFile(p) && !PathSanitizer.isProtected(p))::iterator;
            for (Path p : iterable) {
                try {
                    Path relPath = root.relativize(p);
                    // Нормализация пути для матчера (замена \ на /)
                    Path normalizedRelPath = Path.of(relPath.toString().replace('\\', '/'));

                    if (includeMatcher != null && !includeMatcher.matches(normalizedRelPath)) {
                        continue;
                    }
                    if (excludeMatcher != null && excludeMatcher.matches(normalizedRelPath)) {
                        continue;
                    }

                    // Защита от огромных файлов
                    PathSanitizer.checkFileSize(p);

                    // Эффективное чтение с детекцией кодировки и проверкой на бинарность
                    EncodingUtils.TextFileContent fileData = (forcedCharset != null) 
                            ? EncodingUtils.readTextFile(p, forcedCharset) 
                            : EncodingUtils.readTextFile(p);
                    
                    String content = fileData.content();
                    int count = 0;

                    if (isRegex) {
                        Matcher m = pattern.matcher(content);
                        while (m.find()) {
                            count++;
                        }
                    } else {
                        int idx = content.indexOf(query);
                        while (idx >= 0) {
                            count++;
                            idx = content.indexOf(query, idx + query.length());
                        }
                    }

                    if (count > 0) {
                        Charset outputCharset = (forcedCharset != null) ? forcedCharset : fileData.charset();
                        tasks.add(new ReplaceTask(p, content, outputCharset, count));
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

        // 2. Режим dryRun - генерация unified diff без изменений
        if (dryRun) {
            return generateDryRunDiff(tasks, query, replacement, isRegex, pattern, root, totalOccurrences, maxPreviewMatches);
        }

        // 3. Автоматический checkpoint перед массовой заменой
        try {
            TransactionManager.createCheckpoint("auto_project_replace_" + System.currentTimeMillis());
        } catch (Exception e) {
            // Checkpoint не критичен, продолжаем
        }

        // 4. Выполнение замены в транзакции
        List<AffectedFile> affectedFiles = new ArrayList<>();

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

                // Вычисляем CRC32C от байтов, которые будем записывать
                // (до записи, для консистентности)
                byte[] contentBytes = newContent.getBytes(task.charset);
                long crc = calculateCRC32FromBytes(contentBytes);
                int lineCount = newContent.split("\n", -1).length;

                FileUtils.safeWrite(task.path, newContent, task.charset);

                // Обновляем снапшот для отслеживания внешних изменений
                SessionContext.currentOrDefault().externalChanges()
                    .updateSnapshot(task.path, newContent, crc, task.charset, lineCount);

                // Регистрируем токен доступа с корректной CRC
                LineAccessToken token = LineAccessTracker.registerAccess(task.path, 1, lineCount, crc, lineCount);

                // Session Tokens: отмечаем файл как разблокированный
                TransactionManager.markFileAccessedInTransaction(task.path);

                affectedFiles.add(new AffectedFile(task.path, crc, lineCount, token.encode(), task.count));
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
        sb.append("- Files affected: ").append(affectedFiles.size()).append("\n");
        sb.append("- Total occurrences: ").append(totalOccurrences).append("\n");
        sb.append("\nAffected files with tokens:\n");
        for (AffectedFile af : affectedFiles) {
            sb.append(String.format("  [%s] %d occurrences | CRC32C: %X | Lines: %d\n",
                    root.relativize(af.path), af.occurrences, af.crc32c, af.lineCount));
            sb.append(String.format("    [TOKEN: %s]\n", af.token));
        }

        return createResponse(sb.toString().trim());
    }

    /**
     * Генерирует unified diff для режима dryRun.
     */
    private JsonNode generateDryRunDiff(List<ReplaceTask> tasks, String query, String replacement,
                                          boolean isRegex, Pattern pattern, Path root,
                                          int totalOccurrences, int maxMatchesPerFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DRY RUN MODE - No files modified ===\n\n");
        sb.append("Pattern: ").append(query).append("\n");
        sb.append("Replacement: ").append(replacement).append("\n");
        sb.append("Files to modify: ").append(tasks.size()).append("\n");
        sb.append("Total occurrences: ").append(totalOccurrences).append("\n\n");

        int shownFiles = 0;
        int maxFilesToShow = 20; // Ограничиваем количество файлов в preview

        for (ReplaceTask task : tasks) {
            if (shownFiles >= maxFilesToShow) {
                sb.append(String.format("\n... and %d more file(s) not shown\n",
                        tasks.size() - maxFilesToShow));
                break;
            }

            Path relPath = root.relativize(task.path);
            String[] originalLines = task.originalContent.split("\n", -1);

            // Применяем замену для генерации diff
            String newContent;
            if (isRegex) {
                newContent = pattern.matcher(task.originalContent).replaceAll(replacement);
            } else {
                newContent = task.originalContent.replace(query, replacement);
            }
            String[] newLines = newContent.split("\n", -1);

            sb.append("--- a/").append(relPath).append("\n");
            sb.append("+++ b/").append(relPath).append("\n");

            // Генерация унифицированного diff с контекстом
            int matchesShown = 0;
            int contextLines = 2; // Строки контекста до/после изменения

            for (int i = 0; i < originalLines.length && matchesShown < maxMatchesPerFile; i++) {
                String origLine = originalLines[i];
                String newLine = (i < newLines.length) ? newLines[i] : "";

                // Проверяем, содержит ли строка паттерн
                boolean hasMatch;
                if (isRegex) {
                    hasMatch = pattern.matcher(origLine).find();
                } else {
                    hasMatch = origLine.contains(query);
                }

                if (hasMatch && !origLine.equals(newLine)) {
                    matchesShown++;

                    // Заголовок хунка
                    int startLine = Math.max(1, i + 1 - contextLines);
                    sb.append(String.format("@@ -%d,%d +%d,%d @@\n",
                            startLine, Math.min(contextLines * 2 + 1, originalLines.length - startLine + 1),
                            startLine, Math.min(contextLines * 2 + 1, newLines.length - startLine + 1)));

                    // Контекст до
                    for (int j = Math.max(0, i - contextLines); j < i; j++) {
                        sb.append(" ").append(originalLines[j]).append("\n");
                    }

                    // Измененные строки
                    sb.append("-").append(origLine).append("\n");
                    sb.append("+").append(newLine).append("\n");

                    // Контекст после
                    for (int j = i + 1; j <= Math.min(i + contextLines, originalLines.length - 1); j++) {
                        sb.append(" ").append(originalLines[j]).append("\n");
                    }
                }
            }

            if (matchesShown >= maxMatchesPerFile && task.count > maxMatchesPerFile) {
                sb.append(String.format("... %d more match(es) in this file\n",
                        task.count - maxMatchesPerFile));
            }

            sb.append("\n");
            shownFiles++;
        }

        sb.append("\n=== To apply changes, re-run without dryRun=true ===\n");

        return createResponse(sb.toString().trim());
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }

    private record ReplaceTask(Path path, String originalContent, Charset charset, int count) {
    }

    private record AffectedFile(Path path, long crc32c, int lineCount, String token, int occurrences) {
    }

    /**
     * Вычисляет CRC32C из массива байтов.
     * Более консистентный метод - вычисляет CRC от тех же байтов,
     * которые будут записаны в файл.
     */
    private long calculateCRC32FromBytes(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }
}