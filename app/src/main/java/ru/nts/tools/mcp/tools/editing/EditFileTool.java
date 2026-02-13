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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.CRC32C;

/**
 * Инструмент для построчного редактирования файлов.
 * Поддерживает:
 * 1. Замена по диапазону строк (startLine/endLine).
 * 2. Пакетное редактирование (operations): Применение множества правок за один вызов.
 * 3. Глобальная атомарность (Multi-file): Редактирование нескольких файлов в одной транзакции.
 * 4. Авто-коррекция индексов: Сортировка операций снизу вверх гарантирует стабильность строк.
 * 5. Контроль целостности (expectedContent): Валидация текущего состояния перед внесением изменений.
 * 6. Отказоустойчивость: Интеграция с TransactionManager (UNDO/REDO).
 */
public class EditFileTool implements McpTool {

    /**
     * Объект для работы с JSON структурами.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    // TIP: Напоминание об обновлённом токене
    private static final String TOKEN_UPDATED_TIP =
        "Line count changed (%+d). Use NEW TOKEN above for subsequent edits to this file.";

    // TIP: Напоминание о тестировании
    private static final String TEST_REMINDER_TIP =
        "Consider running tests to verify your changes work correctly.";

    // TIP: Предупреждение о связанных файлах при изменении сигнатуры
    private static final String SIGNATURE_CHANGE_TIP =
        "Method signature may have changed. Check call sites with nts_code_navigate(action='references').";

    @Override
    public String getName() {
        return "nts_edit_file";
    }

    @Override
    public String getDescription() {
        return """
            Line-based file editor with mandatory access control.

            PREREQUISITE: Get accessToken from nts_file_read FIRST!
            Token must cover ALL lines you want to edit.

            WORKFLOW:
            1. nts_file_read(path, startLine, endLine) -> get TOKEN
            2. nts_edit_file(path, startLine, content, accessToken=TOKEN) -> get NEW_TOKEN
            3. Use NEW_TOKEN for subsequent edits

            MODES:
            1. Single edit:  path + startLine + content + accessToken
               One edit to one file.
            2. Multi-op (operations[]):  path + operations[] + accessToken
               Multiple edits to SAME file. Auto-sorted bottom-up (safe line numbering).
               Each: {operation, startLine, [endLine], [content]}
            3. Multi-file (edits[]):  edits[] array (NO path/accessToken at top level!)
               Atomic transaction across DIFFERENT files - all succeed or all rollback.
               Each: {path, accessToken, startLine, [endLine], content, [operation]}

            WARNING: operations[] = many edits, ONE file.  edits[] = one edit per file, MANY files.
            Do NOT nest operations[] inside edits[]. Use nts_batch_tools for complex multi-file multi-op scenarios.

            OPERATIONS: replace (default), insert_before, insert_after, delete
            SAFETY: expectedContent validates current state before edit
            PREVIEW: dryRun=true shows diff without writing

            RECOVERY TIP:
            If your edit causes problems or produces unexpected results,
            use nts_task(action='undo') to INSTANTLY REVERT the change
            instead of making multiple incremental fix edits.
            View all changes: nts_task(action='journal')
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

        // === SINGLE FILE EDIT (most common) ===
        props.putObject("path").put("type", "string").put("description",
                "Target file path. Required for single-file edit mode.");

        props.putObject("accessToken").put("type", "string").put("description",
                "MANDATORY: Token from nts_file_read. Must cover lines [startLine..endLine]. " +
                "Without valid token -> SecurityException. After edit -> returns NEW token.");

        props.putObject("startLine").put("type", "integer").put("description",
                "First line to replace (1-based). Content replaces lines [startLine..endLine].");

        props.putObject("endLine").put("type", "integer").put("description",
                "Last line to replace (1-based, inclusive). Omit to replace single line. " +
                "Example: startLine=5, endLine=10 replaces 6 lines with 'content'.");

        props.putObject("encoding").put("type", "string").put("description",
                "Optional: Force output encoding (e.g. 'UTF-8', 'windows-1251'). " +
                "If specified, the file will be saved in this encoding (conversion).");
        props.putObject("content").put("type", "string").put("description",
                "New content. Can be multi-line (use \\n). Replaces entire [startLine..endLine] range. " +
                "For insert: becomes new lines. For delete: leave empty or use operations.");

        props.putObject("expectedContent").put("type", "string").put("description",
                "SAFETY: Expected current content of target lines. Edit fails if mismatch. " +
                "Uses fuzzy matching: ignores trailing whitespace, line ending differences (\\r\\n vs \\n). " +
                "Use ignoreIndentation=true to also ignore leading whitespace differences.");

        props.putObject("ignoreIndentation").put("type", "boolean").put("description",
                "For expectedContent: ignore leading whitespace (spaces/tabs) differences. " +
                "Useful when code formatters change indentation. Default: false.");

        props.putObject("autoIndent").put("type", "boolean").put("description",
                "Auto-apply indentation from surrounding code to inserted content. " +
                "When true, detects indentation from previous line and applies to new content. " +
                "Default: false (content inserted exactly as provided).");

        // === BATCH OPERATIONS (single file, multiple edits) ===
        var ops = props.putObject("operations");
        ops.put("type", "array").put("description",
                "Multiple edits on SAME file in one call. Auto-sorted bottom-up (safe index handling). " +
                "Each: {operation, startLine, [endLine], [content]}. " +
                "Operations: 'replace' (default), 'insert_before', 'insert_after', 'delete'.");
        var opItems = ops.putObject("items");
        opItems.put("type", "object");
        var opItemProps = opItems.putObject("properties");
        opItemProps.putObject("operation").put("type", "string").put("description", "Operation type: replace, insert_before, insert_after, delete");
        opItemProps.putObject("startLine").put("type", "integer").put("description", "Start line (1-based)");
        opItemProps.putObject("endLine").put("type", "integer").put("description", "End line (1-based, inclusive)");
        opItemProps.putObject("content").put("type", "string").put("description", "New content for replace/insert operations");

        // === MULTI-FILE ATOMIC EDIT ===
        var edits = props.putObject("edits");
        edits.put("type", "array").put("description",
                "ATOMIC multi-file edit: ALL succeed or ALL rollback. Each item: {path, accessToken, startLine/operations, content}. " +
                "Use for refactoring across files (e.g., rename method + update all call sites).");
        var editItems = edits.putObject("items");
        editItems.put("type", "object");
        var editItemProps = editItems.putObject("properties");
        editItemProps.putObject("path").put("type", "string").put("description", "Target file path");
        editItemProps.putObject("accessToken").put("type", "string").put("description", "Access token from nts_file_read");
        editItemProps.putObject("startLine").put("type", "integer").put("description", "Start line (1-based)");
        editItemProps.putObject("content").put("type", "string").put("description", "New content");

        // === UTILITY OPTIONS ===
        props.putObject("contextStartPattern").put("type", "string").put("description",
                "Regex to find anchor line. startLine becomes RELATIVE offset from match: " +
                "0 = anchor line itself, 1 = next line, -1 = previous line. " +
                "Example: pattern='public void foo', startLine=0 -> edits the 'public void foo' line itself. " +
                "Example: pattern='public void foo', startLine=1, endLine=3 -> edits 3 lines AFTER the match.");

        props.putObject("instruction").put("type", "string").put("description",
                "Human-readable change description for task journal. Shown in undo history.");

        props.putObject("dryRun").put("type", "boolean").put("description",
                "Preview mode: returns unified diff WITHOUT writing to disk. Test your edit first!");

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        boolean dryRun = params.path("dryRun").asBoolean(false);

        validateOperationsContentConflict(params);

        // Выбор режима: мульти-файловое или одиночное редактирование
        if (params.has("edits")) {
            JsonNode editsNode = params.get("edits");
            // Поддержка наследования пути: если path указан в корне, но отсутствует в элементе edits, копируем его.
            if (params.has("path") && editsNode.isArray()) {
                String rootPath = params.get("path").asText();
                for (JsonNode edit : editsNode) {
                    if (!edit.has("path") && edit instanceof ObjectNode) {
                        ((ObjectNode) edit).put("path", rootPath);
                    }
                }
            }
            return executeMultiFileEdit(params, editsNode, dryRun);
        } else if (params.has("path")) {
            return executeSingleFileEdit(params, dryRun);
        } else {
            throw new IllegalArgumentException("Must specify 'path' or 'edits'.");
        }
    }

    /**
     * Выполняет пакетное редактирование нескольких файлов в рамках единой транзакции.
     * Если хотя бы одна правка в любом файле не пройдет валидацию, откатываются ВСЕ файлы.
     *
     * @param params     Корневые параметры запроса.
     * @param editsArray JSON-массив правок для файлов.
     * @param dryRun     Флаг предпросмотра изменений.
     *
     * @return Текстовый отчет об успешном завершении батча.
     */
    private JsonNode executeMultiFileEdit(JsonNode params, JsonNode editsArray, boolean dryRun) throws Exception {
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        if (!dryRun) {
            TransactionManager.startTransaction("Multi-file batch edit (" + editsArray.size() + " files)", instruction);
        }
        try {
            StringBuilder statusMsg = new StringBuilder();
            if (dryRun) {
                statusMsg.append("[DRY RUN] Would apply batch edit to ").append(editsArray.size()).append(" files:\n\n");
            } else {
                statusMsg.append("Multi-file batch edit successful (" + editsArray.size() + " files):\n\n");
            }

            for (JsonNode editNode : editsArray) {
                // Применяем правки к каждому файлу и собираем статистику
                FileEditStats stats = applyFileEdits(editNode, dryRun);
                String gitStatus = GitUtils.getFileStatus(stats.path);

                statusMsg.append(String.format("- %s [Git: %s]\n", stats.path.getFileName(), gitStatus.isEmpty() ? "Unchanged" : gitStatus));
                statusMsg.append(String.format("  Operations: %d (Ins: %d, Del: %d, Repl: %d)\n", stats.total(), stats.inserts, stats.deletes, stats.replaces));
                if (!dryRun) {
                    statusMsg.append(String.format("  CRC32C: %X | Lines: %d\n", stats.crc32, stats.newLineCount));
                    // Уведомление об автоматической смене кодировки
                    if (stats.encodingChanged) {
                        statusMsg.append(String.format("  [ENCODING CHANGED: %s -> %s]\n",
                                stats.originalEncoding, stats.newEncoding));
                    }
                    statusMsg.append(String.format("  [NEW TOKEN: %s]\n", stats.newToken));
                }

                if (stats.diff != null && !stats.diff.isEmpty()) {
                    statusMsg.append("\n```diff\n").append(stats.diff).append("\n```\n");
                }
                // Добавляем tips для этого файла
                if (!stats.tips.isEmpty()) {
                    for (String tip : stats.tips) {
                        statusMsg.append("  [TIP: ").append(tip).append("]\n");
                    }
                }
                statusMsg.append("\n");
            }
            if (!dryRun) {
                TransactionManager.commit();

                // Авто-проверка синтаксиса каждого файла после commit
                StringBuilder syntaxWarnings = new StringBuilder();
                for (JsonNode editNode : editsArray) {
                    String editPath = editNode.get("path").asText();
                    java.nio.file.Path filePath = PathSanitizer.sanitize(editPath, false);
                    var syntaxResult = ru.nts.tools.mcp.core.treesitter.SyntaxChecker.check(filePath);
                    if (syntaxResult.hasErrors()) {
                        syntaxWarnings.append("\n[SYNTAX WARNING: ").append(filePath.getFileName())
                                .append(" — ").append(syntaxResult.errorCount()).append(" error(s)]");
                        for (var error : syntaxResult.errors()) {
                            syntaxWarnings.append("\n  Line ").append(error.line())
                                    .append(": ").append(error.message());
                        }
                    }
                }
                if (!syntaxWarnings.isEmpty()) {
                    statusMsg.append(syntaxWarnings);
                    if (ru.nts.tools.mcp.core.TipFilter.canMention("nts_task")) {
                        statusMsg.append("\n[ACTION: Review changes. Use nts_task(action='undo') to revert if needed.]");
                    } else {
                        statusMsg.append("\n[ACTION: Review changes carefully. Revert manually if needed.]");
                    }
                }
            } else {
                statusMsg.append("[DRY RUN] No changes were applied.");
            }
            return createResponse(statusMsg.toString().trim());
        } catch (Exception e) {
            // Глобальный откат при любом сбое
            if (!dryRun) {
                TransactionManager.rollback();
            }
            throw e;
        }
    }

    /**
     * Выполняет редактирование одиночного файла.
     *
     * @param params Параметры запроса.
     * @param dryRun Флаг предпросмотра изменений.
     *
     * @return Текстовый отчет с Git-статусом и CRC32.
     */
    private JsonNode executeSingleFileEdit(JsonNode params, boolean dryRun) throws Exception {
        String pathStr = params.get("path").asText();
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        if (!dryRun) {
            TransactionManager.startTransaction("Edit file: " + pathStr, instruction);
        }
        try {
            FileEditStats stats = applyFileEdits(params, dryRun);
            if (!dryRun) {
                TransactionManager.commit();
            }

            String gitStatus = GitUtils.getFileStatus(stats.path);
            StringBuilder sb = new StringBuilder();
            if (dryRun) {
                sb.append("[DRY RUN] Would apply edits to file: ").append(pathStr);
            } else {
                sb.append("Edits applied: ").append(pathStr);
            }

            if (!gitStatus.isEmpty()) {
                sb.append(" [Git: ").append(gitStatus).append("]");
            }
            sb.append("\nOperations: ").append(stats.total());
            if (!dryRun) {
                sb.append(String.format(" | New CRC32C: %X | Lines: %d", stats.crc32, stats.newLineCount));
                // Уведомление об автоматической смене кодировки
                if (stats.encodingChanged) {
                    sb.append(String.format("\n[ENCODING CHANGED: %s -> %s (auto-switched for non-ASCII content)]",
                            stats.originalEncoding, stats.newEncoding));
                }
                sb.append("\n[NEW TOKEN: ").append(stats.newToken).append("]");
            }

            if (stats.diff != null && !stats.diff.isEmpty()) {
                sb.append("\n\n```diff\n").append(stats.diff).append("\n```");
            }

            if (dryRun) {
                sb.append("\n\n[DRY RUN] No changes were applied.");
            }

            // Авто-проверка синтаксиса после commit (не при dryRun)
            if (!dryRun) {
                var syntaxResult = ru.nts.tools.mcp.core.treesitter.SyntaxChecker.check(stats.path);
                if (syntaxResult.hasErrors()) {
                    sb.append("\n\n[SYNTAX WARNING: ").append(syntaxResult.errorCount())
                            .append(" error(s) detected after edit]");
                    for (var error : syntaxResult.errors()) {
                        sb.append("\n  Line ").append(error.line())
                                .append(": ").append(error.message());
                        if (!error.context().isEmpty()) {
                            sb.append(" → `").append(error.context()).append("`");
                        }
                    }
                    if (ru.nts.tools.mcp.core.TipFilter.canMention("nts_task")) {
                        sb.append("\n[ACTION: Review changes. Use nts_task(action='undo') to revert if needed.]");
                    } else {
                        sb.append("\n[ACTION: Review changes carefully. Revert manually if needed.]");
                    }
                }
            }

            // Добавляем tips если есть
            if (!stats.tips.isEmpty()) {
                sb.append("\n");
                for (String tip : stats.tips) {
                    sb.append("\n[TIP: ").append(tip).append("]");
                }
            }

            return createResponse(sb.toString());
        } catch (Exception e) {
            if (!dryRun) {
                TransactionManager.rollback();
            }
            throw e;
        }
    }

    /**
     * Логика применения правок к конкретному файлу.
     * Выполняет чтение, проверку прав (токенов), бэкап и последовательную обработку операций.
     *
     * @param fileParams Параметры (путь и действия).
     * @param dryRun     Флаг предпросмотра изменений.
     *
     * @return Объект со статистикой изменений.
     */
    private FileEditStats applyFileEdits(JsonNode fileParams, boolean dryRun) throws Exception {
        String pathStr = fileParams.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, false);

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: '" + pathStr + "'. Verify the path using nts_list_directory.");
        }

        // Предотвращение загрузки гигантских файлов (OOM Protection)
        PathSanitizer.checkFileSize(path);

        // Чтение файла и определение кодировки за один проход
        EncodingUtils.TextFileContent fileData;
        if (fileParams.has("encoding")) {
            try {
                Charset forced = Charset.forName(fileParams.get("encoding").asText());
                fileData = EncodingUtils.readTextFile(path, forced);
            } catch (Exception e) {
                fileData = EncodingUtils.readTextFile(path);
            }
        } else {
            fileData = EncodingUtils.readTextFile(path);
        }
        
        Charset charset = fileData.charset();
        if (fileParams.has("encoding")) {
            try {
                charset = Charset.forName(fileParams.get("encoding").asText());
            } catch (Exception ignored) {}
        }
        
        String content = fileData.content();
        String[] contentLines = content.split("\n", -1);
        int oldLineCount = contentLines.length;
        long currentCrc = calculateCRC32(path);

        // Определение разделителя строк
        String lineSeparator = content.contains("\r\n") ? "\r\n" : "\n";

        // Определяем диапазон редактирования с учётом contextStartPattern
        int editStart, editEnd;
        if (fileParams.has("operations")) {
            // Для operations находим общий диапазон с разрешением anchor для каждой операции
            int minLine = Integer.MAX_VALUE, maxLine = 0;
            for (JsonNode op : fileParams.get("operations")) {
                int[] resolved = resolveEditRange(op, contentLines);
                minLine = Math.min(minLine, resolved[0]);
                maxLine = Math.max(maxLine, resolved[1]);
            }
            editStart = minLine;
            editEnd = maxLine;
        } else {
            int[] resolved = resolveEditRange(fileParams, contentLines);
            editStart = resolved[0];
            editEnd = resolved[1];
        }

        // Валидация токена доступа (REQUIRED)
        if (!fileParams.has("accessToken")) {
            throw NtsTokenException.required(path);
        }

        String tokenStr = fileParams.get("accessToken").asText();
        LineAccessToken token;
        try {
            token = LineAccessToken.decode(tokenStr, path);
        } catch (Exception e) {
            throw NtsTokenException.invalidFormat(tokenStr);
        }

        // Извлекаем чистое содержимое диапазона токена для валидации CRC (без номеров строк)
        // Это должно совпадать с форматом в registerAccess (FileReadTool, ProjectReplaceTool, рефакторинг)
        int tokenStart = token.startLine();
        int tokenEnd = Math.min(token.endLine(), oldLineCount);
        String tokenRawContent = extractRawContent(contentLines, tokenStart, tokenEnd);

        // Проверяем валидность токена (сравниваем CRC диапазона)
        var validation = LineAccessTracker.validateToken(token, tokenRawContent, oldLineCount);
        if (!validation.valid()) {
            // Проверяем, является ли это внешним изменением
            ExternalChangeTracker externalTracker = TaskContext.currentOrDefault().externalChanges();
            ExternalChangeTracker.ExternalChangeResult externalChange = externalTracker.checkForExternalChange(
                path, currentCrc, content, charset, oldLineCount
            );

            if (externalChange.hasExternalChange()) {
                // Записываем внешнее изменение в журнал
                ExternalChangeTracker.FileSnapshot previous = externalChange.previousSnapshot();
                TransactionManager.recordExternalChange(
                    path,
                    previous.content(),
                    previous.crc32c(),
                    currentCrc,
                    String.format("External change: %s (detected during edit attempt)", path.getFileName())
                );
                // Обновляем снапшот текущим содержимым
                externalTracker.updateSnapshot(path, content, currentCrc, charset, oldLineCount);

                throw new SecurityException(
                    "EXTERNAL CHANGE DETECTED: File '" + path.getFileName() + "' was modified externally " +
                    "(by user, linter, IDE, or other tool). Change recorded in file history. " +
                    "Please re-read the file with nts_file_read to get a new token. " +
                    "Previous CRC: " + String.format("%X", previous.crc32c()) +
                    ", Current CRC: " + String.format("%X", currentCrc) + ". " +
                    "TIP: External changes may be intentional user edits (e.g., an emergency fix or refactoring). " +
                    "Review the changes carefully before proceeding. Your current plan may require adjustment."
                );
            }

            // Формируем информативное сообщение для LLM
            String tokenStatus = TaskContext.currentOrDefault().tokens()
                    .getTokenStatusForLLM(path, tokenStart, tokenEnd);

            throw new SecurityException(String.format(
                "Token validation failed: %s\n" +
                "[TOKEN: lines %d-%d | CRC in token: %X | CRC in file: %X]\n" +
                "[ACTION REQUIRED: Re-read lines %d-%d with nts_file_read to get a fresh token]\n" +
                "%s",
                validation.fullMessage(),
                tokenStart, tokenEnd,
                token.rangeCrc32c(),
                LineAccessToken.computeRangeCrc(tokenRawContent),
                tokenStart, tokenEnd,
                tokenStatus.isEmpty() ? "" : "\n" + tokenStatus
            ));
        }

        // Проверяем, что токен покрывает диапазон редактирования
        // InfinityRange: для файлов, созданных в текущей транзакции или таске, проверка границ отключена
        // Это решает проблему "статичных токенов в батчах" - когда файл создаётся через `create`,
        // а затем редактируется в последующих вызовах, токен автоматически покрывает весь файл
        boolean skipBoundaryCheck = TransactionManager.isFileCreatedInTransaction(path) ||
                                    TransactionManager.isFileCreatedInTask(path);
        if (!skipBoundaryCheck && !token.covers(editStart, editEnd)) {
            String tokenStatus = TaskContext.currentOrDefault().tokens()
                    .getTokenStatusForLLM(path, editStart, editEnd);

            throw new SecurityException(String.format(
                "Token does not cover edit range [%d-%d]. Token covers [%d-%d].\n" +
                "[ACTION REQUIRED: Read lines %d-%d with nts_file_read first]\n" +
                "%s",
                editStart, editEnd,
                token.startLine(), token.endLine(),
                editStart, editEnd,
                tokenStatus.isEmpty() ? "" : "\n" + tokenStatus
            ));
        }

        // Task Tokens: отмечаем файл как разблокированный в транзакции
        TransactionManager.markFileAccessedInTransaction(path);

        // Представление контента в виде списка строк для корректной манипуляции
        List<String> currentLines = new ArrayList<>(Arrays.asList(contentLines));

        // Регистрация состояния файла "ДО" в текущей транзакции
        if (!dryRun) {
            TransactionManager.backup(path);
        }

        FileEditStats stats = new FileEditStats(path);
        String newContent;

        if (fileParams.has("operations")) {
            // Обработка списка типизированных операций
            List<JsonNode> sortedOps = new ArrayList<>();
            fileParams.get("operations").forEach(sortedOps::add);

            // Сортировка по убыванию индекса (снизу вверх). 
            // Это критически важно: изменения в конце файла не влияют на номера строк в его начале.
            sortedOps.sort((a, b) -> {
                int lineA = a.path("startLine").asInt(a.path("line").asInt(0));
                int lineB = b.path("startLine").asInt(b.path("line").asInt(0));
                return Integer.compare(lineB, lineA);
            });

            for (JsonNode opNode : sortedOps) {
                applyTypedOperation(currentLines, opNode, 0, stats);
            }

            newContent = currentLines.stream()
                    .map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l)
                    .collect(Collectors.joining(lineSeparator));
            // Сохранение итогового состояния на диск
            if (!dryRun) {
                // Автоматическое переключение на UTF-8 если контент содержит non-ASCII
                Charset writeCharset = autoSwitchEncodingIfNeeded(charset, newContent, stats);
                FileUtils.safeWrite(path, newContent, writeCharset);
            }
        } else if (fileParams.has("startLine")) {
            // Одиночная правка по индексам
            applyTypedOperation(currentLines, fileParams, 0, stats);
            newContent = currentLines.stream()
                    .map(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l)
                    .collect(Collectors.joining(lineSeparator));
            // Сохранение итогового состояния на диск
            if (!dryRun) {
                // Автоматическое переключение на UTF-8 если контент содержит non-ASCII
                Charset writeCharset = autoSwitchEncodingIfNeeded(charset, newContent, stats);
                FileUtils.safeWrite(path, newContent, writeCharset);
            }
        } else {
            throw new IllegalArgumentException("Insufficient parameters for file: " + pathStr);
        }

        // Генерация diff
        stats.diff = DiffUtils.getUnifiedDiff(path.getFileName().toString(), content, newContent);

        // Вычисление итоговой контрольной суммы и обновление токенов
        if (!dryRun) {
            stats.crc32 = calculateCRC32(path);
            int newLineCount = currentLines.size();
            stats.newLineCount = newLineCount;
            int lineDelta = newLineCount - oldLineCount;

            // Извлекаем чистое содержимое отредактированного диапазона для нового токена (без номеров строк)
            int newEditEnd = editEnd + lineDelta;
            String editedRawContent = extractRawContent(currentLines.toArray(new String[0]),
                    editStart, Math.min(newEditEnd, newLineCount));

            // Обновляем токены после редактирования (с rangeCrc от чистого содержимого)
            LineAccessToken newToken = LineAccessTracker.updateAfterEdit(
                    path, editStart, editEnd, lineDelta, editedRawContent, newLineCount);
            stats.newToken = newToken.encode();

            // Обновляем снапшот для отслеживания будущих внешних изменений
            ExternalChangeTracker externalTracker = TaskContext.currentOrDefault().externalChanges();
            externalTracker.updateSnapshot(path, newContent, stats.crc32, charset, newLineCount);

            // Сохраняем виртуальный контент для batch-операций (refactor сможет использовать)
            TaskContext.currentOrDefault().transactions().setVirtualContent(path, newContent);

            // Добавляем полезные TIPs
            if (lineDelta != 0) {
                stats.tips.add(String.format(TOKEN_UPDATED_TIP, lineDelta));
            }

            // TIP о тестировании при значительных изменениях
            if (Math.abs(lineDelta) > 5 || stats.total() > 3) {
                stats.tips.add(TEST_REMINDER_TIP);
            }

            // TIP о проверке связанных файлов при изменении сигнатуры метода
            if (stats.diff != null && (stats.diff.contains("public ") || stats.diff.contains("private ") ||
                    stats.diff.contains("protected ")) && stats.diff.contains("(")) {
                stats.tips.add(SIGNATURE_CHANGE_TIP);
            }
        }
        return stats;
    }

    /**
     * Разрешает диапазон редактирования с учётом contextStartPattern.
     * Если указан паттерн, startLine/endLine интерпретируются как относительные офсеты.
     *
     * @param op параметры операции
     * @param lines строки файла для поиска anchor
     * @return [startLine, endLine] - абсолютные номера строк (1-based)
     */
    private int[] resolveEditRange(JsonNode op, String[] lines) {
        int requestedStart = op.path("startLine").asInt(op.path("line").asInt(1));
        int requestedEnd = op.path("endLine").asInt(requestedStart);
        String contextPattern = op.path("contextStartPattern").asText(null);

        if (contextPattern == null) {
            // Абсолютная адресация
            return new int[]{requestedStart, requestedEnd};
        }

        // Относительная адресация: ищем anchor
        int anchorIdx = findAnchorLine(Arrays.asList(lines), contextPattern);
        if (anchorIdx == -1) {
            // Паттерн не найден - используем как есть, ошибка будет позже
            return new int[]{requestedStart, requestedEnd};
        }

        // anchor в 1-based: anchorIdx + 1
        // startLine=0 означает саму anchor строку
        int start = (anchorIdx + 1) + requestedStart;
        int end = (anchorIdx + 1) + requestedEnd;

        return new int[]{start, end};
    }

    /**
     * Применяет одну типизированную операцию (replace, insert, delete) к списку строк.
     * Реализует поиск якоря, валидацию содержимого и автоматический отступ.
     */
    private void applyTypedOperation(List<String> lines, JsonNode op, int cumulativeOffset, FileEditStats stats) {
        String type = op.path("operation").asText("replace");
        int requestedStart = op.path("startLine").asInt(op.path("line").asInt(0));
        int requestedEnd = op.path("endLine").asInt(requestedStart);
        String newText = op.path("content").asText(op.path("newText").asText(""));
        String expected = op.path("expectedContent").asText(null);
        String contextPattern = op.path("contextStartPattern").asText(null);
        boolean ignoreIndentation = op.path("ignoreIndentation").asBoolean(false);
        boolean autoIndent = op.path("autoIndent").asBoolean(false);

        // 1. Поиск якоря для относительной адресации (если указан паттерн)
        // anchorLine: 0-based индекс найденной строки, или 0 если паттерн не указан
        int anchorLineIdx = 0;  // 0-based индекс
        boolean hasPattern = contextPattern != null;

        if (hasPattern) {
            anchorLineIdx = findAnchorLine(lines, contextPattern);
            if (anchorLineIdx == -1) {
                throw new IllegalArgumentException("Context pattern not found: " + contextPattern);
            }
        }

        // 2. Расчет абсолютных индексов (1-based)
        // Если есть паттерн: startLine - это ОТНОСИТЕЛЬНЫЙ офсет (0 = сама anchor строка)
        // Если нет паттерна: startLine - это абсолютный номер строки
        int start, end;
        if (hasPattern) {
            // anchor line в 1-based: anchorLineIdx + 1
            // startLine=0 означает саму anchor строку
            start = (anchorLineIdx + 1) + requestedStart + cumulativeOffset;
            end = (anchorLineIdx + 1) + requestedEnd + cumulativeOffset;
        } else {
            start = requestedStart + cumulativeOffset;
            end = requestedEnd + cumulativeOffset;
        }

        // 3. Адаптация индексов под специфику типа операции
        if ("insert_before".equals(type)) {
            end = start - 1; // Пустой диапазон перед строкой
            stats.inserts++;
        } else if ("insert_after".equals(type)) {
            start++;
            end = start - 1; // Пустой диапазон после строки
            stats.inserts++;
        } else if ("delete".equals(type)) {
            newText = null; // Маркер удаления
            stats.deletes++;
        } else {
            stats.replaces++;
            // TIP: предупреждаем если многострочный content заменяет одну строку без явного endLine
            if (newText != null && newText.contains("\n") && !op.has("endLine")) {
                stats.tips.add("Multi-line content replaces single line (startLine=" + requestedStart + "). " +
                        "Use operation='insert_after' for insertion, or specify endLine for range replacement.");
            }
        }

        // 4. Валидация границ в текущем состоянии файла
        if (start < 1 || (start > lines.size() + 1) || (end < start - 1) || (end > lines.size())) {
            throw new IllegalArgumentException("Addressing error: range " + start + "-" + end + " is out of bounds for file with " + lines.size() + " lines.");
        }

        // 5. Контроль ожидаемого содержимого (expectedContent) с fuzzy matching
        if (expected != null && end >= start) {
            String actual = lines.subList(start - 1, end).stream()
                    .map(l -> l.replace("\r", ""))
                    .collect(Collectors.joining("\n"));

            // Fuzzy matching: нормализуем whitespace и line endings
            String normActual = fuzzyNormalize(actual, ignoreIndentation);
            String normExpected = fuzzyNormalize(expected, ignoreIndentation);

            if (!normActual.equals(normExpected)) {
                // Если не совпало — выбрасываем ошибку с подробным дампом текущих строк
                throw new IllegalStateException(
                        "Content validation failed for range " + start + "-" + end + "!\n" +
                        "EXPECTED:\n[" + expected.replace("\r", "") + "]\n" +
                        "ACTUAL CONTENT IN FILE:\n[" + actual + "]\n" +
                        "Please adjust your request using the actual text from the file." +
                        (ignoreIndentation ? "" : "\n[TIP: Use ignoreIndentation=true if indentation differs]"));
            }
        }

        // 6. Определение отступа для вставляемого кода (Auto-indentation - только если autoIndent=true)
        int oldLineCount = (end >= start) ? (end - start + 1) : 0;
        String indentation = "";
        if (autoIndent) {
            // Берем отступ либо от строки выше, либо от текущей первой строки диапазона
            int indentLineIdx = (start > 1) ? (start - 2) : (start - 1);
            indentation = (indentLineIdx >= 0 && indentLineIdx < lines.size()) ? getIndentation(lines.get(indentLineIdx)) : "";
        }

        // 7. Манипуляция списком строк
        for (int i = 0; i < oldLineCount; i++) {
            lines.remove(start - 1);
        }

        if (newText != null) {
            // Применяем отступ только если autoIndent=true
            String textToInsert = autoIndent ? applyIndentation(newText, indentation) : newText;
            String[] newLines = textToInsert.split("\n", -1);
            for (int i = 0; i < newLines.length; i++) {
                lines.add(start - 1 + i, newLines[i]);
            }
        }
    }

    /**
     * Валидирует отсутствие конфликта между operations и content верхнего уровня.
     *
     * @param params параметры запроса
     * @throws IllegalArgumentException если обнаружен конфликт
     */
    private void validateOperationsContentConflict(JsonNode params) {
        boolean hasOperations = params.has("operations") && params.get("operations").isArray()
                && params.get("operations").size() > 0;
        boolean hasTopLevelContent = params.has("content") && !params.get("content").asText("").isEmpty();
        boolean hasStartLine = params.has("startLine");

        if (hasOperations && hasTopLevelContent) {
            throw new IllegalArgumentException(
                    "CONFLICT: Both 'operations' array and top-level 'content' are provided.\n" +
                    "When using 'operations', content must be specified INSIDE each operation object.\n" +
                    "Top-level 'content' is ignored when 'operations' is present.\n\n" +
                    "SOLUTION: Either:\n" +
                    "  1. Use operations[{startLine, content, ...}] without top-level content\n" +
                    "  2. Use startLine + content (without operations array) for single edit\n\n" +
                    "EXAMPLE (correct batch):\n" +
                    "  {\"operations\": [{\"startLine\": 5, \"content\": \"new line 5\"}, " +
                    "{\"startLine\": 10, \"content\": \"new line 10\"}]}");
        }

        // Предупреждение если есть operations и startLine верхнего уровня
        if (hasOperations && hasStartLine) {
            // startLine игнорируется при использовании operations, но это не ошибка
            // Можно добавить warning в лог, но не блокировать операцию
        }

        // Валидируем также edits массив
        if (params.has("edits") && params.get("edits").isArray()) {
            for (int i = 0; i < params.get("edits").size(); i++) {
                JsonNode edit = params.get("edits").get(i);
                boolean editHasOps = edit.has("operations") && edit.get("operations").isArray()
                        && edit.get("operations").size() > 0;
                boolean editHasContent = edit.has("content") && !edit.get("content").asText("").isEmpty();

                if (editHasOps && editHasContent) {
                    throw new IllegalArgumentException(
                            "CONFLICT in edits[" + i + "]: Both 'operations' and 'content' provided.\n" +
                            "When using 'operations', content must be specified INSIDE each operation object.");
                }
            }
        }
    }

    /**
     * Создает стандартный MCP ответ с текстовым содержимым.
     */
    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }

    /**
     * Извлекает последовательность пробельных символов в начале строки.
     */
    private String getIndentation(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * Добавляет указанный отступ ко всем строкам текста.
     */
    private String applyIndentation(String text, String indentation) {
        if (indentation.isEmpty()) {
            return text;
        }
        return Arrays.stream(text.split("\n", -1)).map(line -> line.isEmpty() ? line : indentation + line).collect(Collectors.joining("\n"));
    }

    /**
     * Fuzzy-нормализация текста для сравнения expectedContent.
     * Игнорирует:
     * - Trailing whitespace (пробелы в конце строк)
     * - Различия в переносах строк (\r\n vs \n)
     * - Пустые строки в конце
     * - Leading whitespace (если ignoreIndentation=true)
     */
    private String fuzzyNormalize(String text, boolean ignoreIndentation) {
        if (text == null) return "";

        // Нормализуем line endings
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");

        // Обрабатываем каждую строку
        String[] lines = normalized.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            String line = lines[i].stripTrailing();
            if (ignoreIndentation) {
                line = line.stripLeading();
            }
            sb.append(line);
        }

        // Удаляем trailing пустые строки
        String result = sb.toString();
        while (result.endsWith("\n")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    /**
     * Находит индекс строки (0-based), содержащей указанный паттерн.
     */
    private int findAnchorLine(List<String> lines, String patternStr) {
        Pattern pattern = Pattern.compile(patternStr);
        for (int i = 0; i < lines.size(); i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                return i;
            }
        }
        return -1;
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

    /**
     * Извлекает содержимое указанного диапазона строк.
     *
     * @param lines Массив строк файла
     * @param startLine Начало диапазона (1-based, включительно)
     * @param endLine Конец диапазона (1-based, включительно)
     * @return Содержимое диапазона с номерами строк
     */
    private String extractLines(String[] lines, int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.length, endLine);

        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append("\n");
            }
            sb.append(String.format("%4d\t%s", i + 1, lines[i].replace("\r", "")));
        }
        return sb.toString();
    }

    /**
     * Извлекает чистое содержимое диапазона строк (без номеров строк).
     * Используется для вычисления CRC токена - должно совпадать с форматом
     * в ProjectReplaceTool и операциях рефакторинга.
     */
    private String extractRawContent(String[] lines, int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, startLine - 1);
        int end = Math.min(lines.length, endLine);

        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append("\n");
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /**
     * Проверяет, содержит ли строка символы, выходящие за пределы ASCII (0-127).
     *
     * @param text проверяемая строка
     * @return true если найден хотя бы один non-ASCII символ
     */
    private boolean containsNonAscii(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, может ли данная кодировка закодировать строку.
     *
     * @param charset кодировка для проверки
     * @param text текст для кодирования
     * @return true если кодировка способна представить все символы текста
     */
    private boolean canEncode(Charset charset, String text) {
        if (text == null || text.isEmpty()) return true;
        CharsetEncoder encoder = charset.newEncoder();
        return encoder.canEncode(text);
    }

    /**
     * Автоматически переключает кодировку на UTF-8, если текущая не может закодировать контент.
     *
     * @param currentCharset текущая кодировка файла
     * @param content контент для записи
     * @param stats статистика редактирования (для записи изменения кодировки)
     * @return кодировку для записи (либо текущую, либо UTF-8)
     */
    private Charset autoSwitchEncodingIfNeeded(Charset currentCharset, String content, FileEditStats stats) {
        // Если контент содержит non-ASCII и текущая кодировка не может его закодировать
        if (containsNonAscii(content) && !canEncode(currentCharset, content)) {
            stats.encodingChanged = true;
            stats.originalEncoding = currentCharset.name();
            stats.newEncoding = StandardCharsets.UTF_8.name();
            return StandardCharsets.UTF_8;
        }
        return currentCharset;
    }

    /**
     * Вспомогательный объект для агрегации статистики изменений по файлу.
     */
    private static class FileEditStats {
        final Path path;
        int replaces = 0;
        int inserts = 0;
        int deletes = 0;
        long crc32 = 0;
        String diff = "";
        String newToken = null;
        int newLineCount = 0;
        // Поля для отслеживания изменения кодировки
        boolean encodingChanged = false;
        String originalEncoding = null;
        String newEncoding = null;
        // Tips для пользователя
        List<String> tips = new ArrayList<>();

        FileEditStats(Path path) {
            this.path = path;
        }

        int total() {
            return replaces + inserts + deletes;
        }
    }
}