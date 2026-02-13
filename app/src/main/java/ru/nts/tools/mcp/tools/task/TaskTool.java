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
package ru.nts.tools.mcp.tools.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.sql.Connection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Инструмент для управления задачей и транзакциями.
 * Поддерживает создание контрольных точек, откат к ним, отмену (undo) и повтор (redo) операций,
 * а также просмотр журнала транзакций.
 */
public class TaskTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_task"; }

    @Override
    public String getDescription() {
        return """
            Task management, metadata exchange, and undo/redo system with Deep Undo support.

            ACTIONS:
            - checkpoint       - Save named restore point BEFORE risky changes
            - rollback         - Revert ALL changes back to checkpoint
            - undo             - Smart undo with Path Lineage support (tracks moved files!)
            - redo             - Step forward one operation (like Ctrl+Y)
            - journal          - View full task activity log
            - journal_entries  - List journal entries as structured JSON (filter: all|undo|redo)
            - journal_diff     - Get unified diff for a journal entry (by entryId, optional path)
            - file_history      - Get change history for a specific file path
            - diff_checkpoints - Compare two named checkpoints (from -> to) to see progress
            - git_checkpoint   - Create Git stash as fallback restore point
            - git_restore      - Restore files from Git HEAD (when undo fails)
            - set_metadata     - Store key-value metadata in task journal (for CLI/agent sync)
            - get_info         - Get full task info: id, workingDirectory, dates, metadata, stats

            JOURNAL QUERY ACTIONS:
            journal_entries returns structured JSON array of all journal entries with metadata/stats.
            journal_diff returns the unified diff text for a specific entry (entryId required).
            file_history returns all entries touching a given file path with diffs.

            SMART UNDO FEATURES:
            - Path Lineage: tracks file moves (A->B->C) and undoes to correct location
            - Partial Undo: skips dirty directories, undoes what's possible
            - CRC Recovery: finds "lost" files by content hash
            - Git Fallback: suggests git checkout when recovery impossible

            BEST PRACTICES:
            1. Create checkpoint before complex refactoring
            2. Use undo for quick fixes (wrong edit)
            3. Use rollback to abandon failed approach
            4. Check journal/journal_entries to understand what changed

            EXAMPLE: checkpoint('before-refactor') -> make changes -> rollback('before-refactor')
            """;
    }

    @Override
    public String getCategory() {
        return "task";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'checkpoint', 'rollback', 'undo', 'redo', 'journal', 'journal_entries', " +
                "'journal_diff', 'file_history', 'diff_checkpoints', 'git_checkpoint', 'git_restore', 'set_metadata', 'get_info'. Required.");

        props.putObject("name").put("type", "string").put("description",
                "For 'checkpoint'/'git_checkpoint': descriptive name to identify restore point (e.g., 'before-api-change'). " +
                "For 'rollback': exact name of checkpoint to restore.");

        props.putObject("from").put("type", "string").put("description",
                "For 'diff_checkpoints': name of the earlier checkpoint.");

        props.putObject("to").put("type", "string").put("description",
                "For 'diff_checkpoints': name of the later checkpoint.");

        props.putObject("path").put("type", "string").put("description",
                "For 'git_restore': specific file path to restore from Git HEAD. " +
                "For 'file_history': file path to get change history for. " +
                "For 'journal_diff': optional file path filter within an entry.");

        props.putObject("data").put("type", "string").put("description",
                "For 'set_metadata': JSON string with key-value pairs to merge into task metadata.");

        props.putObject("filter").put("type", "string").put("description",
                "For 'journal_entries': filter by stack — 'all' (default), 'undo', or 'redo'.");

        props.putObject("entryId").put("type", "integer").put("description",
                "For 'journal_diff': the journal entry ID to get diff for. Required for journal_diff.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();

        return switch (action) {
            case "checkpoint" -> executeCheckpoint(params);
            case "rollback" -> executeRollback(params);
            case "undo" -> executeUndo();
            case "redo" -> executeRedo();
            case "journal" -> executeJournal();
            case "journal_entries" -> executeJournalEntries(params);
            case "journal_diff" -> executeJournalDiff(params);
            case "file_history" -> executeFileHistory(params);
            case "diff_checkpoints" -> executeDiffCheckpoints(params);
            case "git_checkpoint" -> executeGitCheckpoint(params);
            case "git_restore" -> executeGitRestore(params);
            case "set_metadata" -> executeSetMetadata(params);
            case "get_info" -> executeGetInfo();
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    private JsonNode executeCheckpoint(JsonNode params) {
        String name = params.get("name").asText();
        TransactionManager.createCheckpoint(name);
        return createResponse("Checkpoint created: " + name);
    }

    private JsonNode executeRollback(JsonNode params) throws Exception {
        String name = params.get("name").asText();
        String msg = TransactionManager.rollbackToCheckpoint(name);
        return createResponse(msg);
    }

    private JsonNode executeUndo() throws Exception {
        UndoResult result = TransactionManager.smartUndo();
        String journal = TransactionManager.getJournal();
        return createResponse(result.format() + "\n\n" + journal);
    }

    private JsonNode executeRedo() throws Exception {
        String status = TransactionManager.redo();
        String journal = TransactionManager.getJournal();
        return createResponse(status + "\n\n" + journal);
    }

    private JsonNode executeJournal() {
        return createResponse(TransactionManager.getJournal());
    }

    private JsonNode executeGitCheckpoint(JsonNode params) {
        if (!GitUtils.isGitRepo(PathSanitizer.getRoot())) {
            return createResponse("ERROR: Not a Git repository. git_checkpoint requires Git.");
        }

        String name = params.path("name").asText("before-changes");
        String stashId = GitUtils.createStashCheckpoint(name);

        if (stashId != null) {
            return createResponse(String.format(
                    "Git checkpoint created: %s\n" +
                    "Stash ID: %s\n" +
                    "To restore: nts_task(action='git_restore')", name, stashId));
        } else {
            // No changes to stash - that's ok
            String headCommit = GitUtils.getHeadCommit();
            return createResponse(String.format(
                    "No uncommitted changes to stash.\n" +
                    "Current HEAD: %s\n" +
                    "Use git_restore to revert to HEAD if needed.", headCommit));
        }
    }

    private JsonNode executeGitRestore(JsonNode params) {
        if (!GitUtils.isGitRepo(PathSanitizer.getRoot())) {
            return createResponse("ERROR: Not a Git repository. git_restore requires Git.");
        }

        // Если указан путь - восстанавливаем конкретный файл
        String pathStr = params.path("path").asText(null);
        if (pathStr != null) {
            java.nio.file.Path path = PathSanitizer.getRoot().resolve(pathStr);
            String result = GitUtils.restoreFiles(java.util.List.of(path), null);
            return createResponse(result);
        }

        // Иначе показываем инструкции
        String headCommit = GitUtils.getHeadCommit();
        return createResponse(String.format(
                "Git Restore Options:\n\n" +
                "Current HEAD: %s\n\n" +
                "To restore specific file:\n" +
                "  nts_task(action='git_restore', path='path/to/file.java')\n\n" +
                "To restore all files (CAUTION - discards all changes!):\n" +
                "  Run in terminal: git checkout HEAD -- .\n\n" +
                "To restore from stash:\n" +
                "  Run in terminal: git stash pop", headCommit));
    }

    private JsonNode executeSetMetadata(JsonNode params) throws Exception {
        String dataStr = params.path("data").asText(null);
        if (dataStr == null || dataStr.isBlank()) {
            throw new IllegalArgumentException("'data' parameter is required for set_metadata action. " +
                    "Provide a JSON string with key-value pairs.");
        }

        TaskContext ctx = TaskContext.current();
        if (ctx == null) {
            throw new IllegalStateException("No active task context");
        }

        // Парсим JSON строку и мержим в метаданные
        JsonNode dataNode = mapper.readTree(dataStr);
        int count = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = dataNode.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String value = field.getValue().isTextual()
                    ? field.getValue().asText()
                    : field.getValue().toString();
            ctx.setMetadata(field.getKey(), value);
            count++;
        }

        // Сохраняем обновленный журнал
        ctx.saveJournal();

        return createResponse(String.format("Metadata updated: %d key(s) merged into task journal.", count));
    }

    private JsonNode executeGetInfo() {
        TaskContext ctx = TaskContext.current();
        if (ctx == null) {
            throw new IllegalStateException("No active task context");
        }

        ObjectNode info = mapper.createObjectNode();
        info.put("taskId", ctx.getTaskId());
        info.put("workingDirectory", ctx.getWorkingDirectory() != null
                ? ctx.getWorkingDirectory().toString() : "");
        info.put("createdAt", ctx.getCreatedAt().toString());
        info.put("lastActivity", ctx.getLastActivityAt().toString());

        String activeTodo = ctx.getActiveTodoFile();
        if (activeTodo != null) {
            info.put("activeTodo", activeTodo);
        }

        // Метаданные
        Map<String, String> allMeta = ctx.getAllMetadata();
        if (!allMeta.isEmpty()) {
            ObjectNode metaNode = info.putObject("metadata");
            allMeta.forEach(metaNode::put);
        }

        // Формируем текстовый ответ с JSON данными
        ObjectNode res = mapper.createObjectNode();
        var content = res.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        try {
            textNode.put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(info));
        } catch (Exception e) {
            textNode.put("text", info.toString());
        }
        return res;
    }

    private JsonNode executeJournalEntries(JsonNode params) throws Exception {
        TaskContext ctx = requireContext();
        TaskTransactionManager txm = ctx.transactions();
        JournalDatabase db = txm.getDatabase();
        JournalRepository repo = new JournalRepository();

        String filter = params.path("filter").asText("all");

        ArrayNode entries = mapper.createArrayNode();
        try (Connection conn = db.getInitializedConnection()) {
            List<JournalRepository.JournalEntry> list;
            if ("undo".equals(filter)) {
                list = repo.getEntries(conn, "UNDO");
            } else if ("redo".equals(filter)) {
                list = repo.getEntries(conn, "REDO");
            } else {
                list = repo.getAllEntries(conn);
            }

            for (var entry : list) {
                ObjectNode node = mapper.createObjectNode();
                node.put("id", entry.id());
                node.put("stack", entry.stack());
                node.put("type", entry.entryType());
                node.put("position", entry.position());
                node.put("timestamp", entry.timestamp().toString());
                if (entry.description() != null) node.put("description", entry.description());
                if (entry.status() != null) node.put("status", entry.status());
                if (entry.instruction() != null) node.put("instruction", entry.instruction());
                if (entry.checkpointName() != null) node.put("checkpointName", entry.checkpointName());
                if (entry.affectedPath() != null) node.put("affectedPath", entry.affectedPath());

                // Stats
                List<JournalRepository.DiffStat> stats = repo.getDiffStats(conn, entry.id());
                if (!stats.isEmpty()) {
                    ArrayNode statsArr = node.putArray("diffStats");
                    for (var s : stats) {
                        ObjectNode sn = statsArr.addObject();
                        sn.put("filePath", s.filePath());
                        sn.put("linesAdded", s.linesAdded());
                        sn.put("linesDeleted", s.linesDeleted());
                        if (s.affectedBlocks() != null) sn.put("affectedBlocks", s.affectedBlocks());
                    }
                }

                // File count
                Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entry.id());
                node.put("fileCount", snapshots.size());

                entries.add(node);
            }
        }

        ObjectNode res = mapper.createObjectNode();
        var content = res.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries));
        return res;
    }

    private JsonNode executeJournalDiff(JsonNode params) throws Exception {
        if (!params.has("entryId")) {
            throw new IllegalArgumentException("'entryId' parameter is required for journal_diff action.");
        }

        TaskContext ctx = requireContext();
        JournalDatabase db = ctx.transactions().getDatabase();
        JournalRepository repo = new JournalRepository();
        long entryId = params.get("entryId").asLong();
        String pathFilter = params.path("path").asText(null);

        try (Connection conn = db.getInitializedConnection()) {
            if (pathFilter != null) {
                String diff = repo.getUnifiedDiff(conn, entryId, pathFilter);
                return createResponse(diff != null ? diff : "No diff found for path: " + pathFilter);
            } else {
                List<JournalRepository.DiffStat> stats = repo.getDiffStats(conn, entryId);
                StringBuilder sb = new StringBuilder();
                for (var s : stats) {
                    sb.append("--- ").append(s.filePath()).append(" (+").append(s.linesAdded())
                            .append("/-").append(s.linesDeleted()).append(")\n");
                    if (s.unifiedDiff() != null) {
                        sb.append(s.unifiedDiff()).append("\n\n");
                    }
                }
                return createResponse(sb.isEmpty() ? "No diffs found for entry " + entryId : sb.toString());
            }
        }
    }

    private JsonNode executeFileHistory(JsonNode params) throws Exception {
        String pathStr = params.path("path").asText(null);
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalArgumentException("'path' parameter is required for file_history action.");
        }

        TaskContext ctx = requireContext();
        java.nio.file.Path path = PathSanitizer.sanitize(pathStr, true);
        List<String> history = ctx.transactions().getFileHistory(path);

        if (history.isEmpty()) {
            return createResponse("No history found for: " + pathStr);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== FILE HISTORY: ").append(pathStr).append(" ===\n");
        for (String line : history) {
            sb.append(line).append("\n");
        }
        return createResponse(sb.toString());
    }

    private JsonNode executeDiffCheckpoints(JsonNode params) throws Exception {
        String fromName = params.path("from").asText(null);
        String toName = params.path("to").asText(null);
        if (fromName == null || fromName.isBlank() || toName == null || toName.isBlank()) {
            throw new IllegalArgumentException(
                    "'from' and 'to' parameters are required for diff_checkpoints action.");
        }

        TaskContext ctx = requireContext();
        JournalDatabase db = ctx.transactions().getDatabase();
        JournalRepository repo = new JournalRepository();

        try (Connection conn = db.getInitializedConnection()) {
            int fromPos = repo.findCheckpointPosition(conn, "UNDO", fromName);
            if (fromPos < 0) {
                throw new IllegalArgumentException("Checkpoint not found: " + fromName);
            }
            int toPos = repo.findCheckpointPosition(conn, "UNDO", toName);
            if (toPos < 0) {
                throw new IllegalArgumentException("Checkpoint not found: " + toName);
            }
            if (fromPos >= toPos) {
                throw new IllegalArgumentException(
                        "'" + fromName + "' (pos=" + fromPos + ") must be earlier than '" + toName + "' (pos=" + toPos + ")");
            }

            // Get all TRANSACTION entries between the two checkpoints
            List<JournalRepository.JournalEntry> allAfterFrom = repo.getEntriesAfterPosition(conn, "UNDO", fromPos);
            List<JournalRepository.JournalEntry> between = allAfterFrom.stream()
                    .filter(e -> e.position() <= toPos && "TRANSACTION".equals(e.entryType()))
                    .toList();

            // Aggregate diffs per file
            Map<String, int[]> fileStats = new java.util.LinkedHashMap<>(); // path -> [added, deleted, edits]
            Map<String, StringBuilder> fileDiffs = new java.util.LinkedHashMap<>();
            int txCount = 0;

            for (var entry : between) {
                txCount++;
                List<JournalRepository.DiffStat> stats = repo.getDiffStats(conn, entry.id());
                for (var s : stats) {
                    fileStats.computeIfAbsent(s.filePath(), k -> new int[3]);
                    int[] counts = fileStats.get(s.filePath());
                    counts[0] += s.linesAdded();
                    counts[1] += s.linesDeleted();
                    counts[2]++;
                    if (s.unifiedDiff() != null) {
                        fileDiffs.computeIfAbsent(s.filePath(), k -> new StringBuilder());
                        fileDiffs.get(s.filePath()).append(s.unifiedDiff()).append("\n");
                    }
                }
            }

            // Format output
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== DIFF: '%s' -> '%s' (%d transactions, %d files) ===\n\n",
                    fromName, toName, txCount, fileStats.size()));

            for (var e : fileStats.entrySet()) {
                int[] c = e.getValue();
                sb.append(String.format("%-60s +%d/-%d  (%d edits)\n", e.getKey(), c[0], c[1], c[2]));
            }

            // Append unified diffs
            if (!fileDiffs.isEmpty()) {
                sb.append("\n--- Unified Diffs ---\n");
                for (var e : fileDiffs.entrySet()) {
                    sb.append("\n=== ").append(e.getKey()).append(" ===\n");
                    sb.append(e.getValue());
                }
            }

            return createResponse(sb.toString());
        }
    }

    private TaskContext requireContext() {
        TaskContext ctx = TaskContext.current();
        if (ctx == null) {
            throw new IllegalStateException("No active task context");
        }
        return ctx;
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
