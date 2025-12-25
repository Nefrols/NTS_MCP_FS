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
package ru.nts.tools.mcp.tools.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.GitUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.core.UndoResult;

/**
 * Инструмент для управления сессией и транзакциями.
 * Поддерживает создание контрольных точек, откат к ним, отмену (undo) и повтор (redo) операций, 
 * а также просмотр журнала транзакций.
 */
public class SessionTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_session"; }

    @Override
    public String getDescription() {
        return """
            Session recovery and undo/redo system with Deep Undo support.

            ACTIONS:
            • checkpoint     - Save named restore point BEFORE risky changes
            • rollback       - Revert ALL changes back to checkpoint
            • undo           - Smart undo with Path Lineage support (tracks moved files!)
            • redo           - Step forward one operation (like Ctrl+Y)
            • journal        - View full session activity log
            • git_checkpoint - Create Git stash as fallback restore point
            • git_restore    - Restore files from Git HEAD (when undo fails)

            SMART UNDO FEATURES:
            • Path Lineage: tracks file moves (A→B→C) and undoes to correct location
            • Partial Undo: skips dirty directories, undoes what's possible
            • CRC Recovery: finds "lost" files by content hash
            • Git Fallback: suggests git checkout when recovery impossible

            UNDO STATUSES:
            • SUCCESS: fully restored
            • RESOLVED_MOVE: file found at relocated path
            • PARTIAL: some files skipped (check details)
            • STUCK: cannot undo - use git fallback

            GIT INTEGRATION:
            When undo shows STUCK status, use git_restore to recover from HEAD.
            For major refactoring, create git_checkpoint first as safety net.

            BEST PRACTICES:
            1. Create checkpoint before complex refactoring
            2. Use undo for quick fixes (wrong edit)
            3. Use rollback to abandon failed approach
            4. Check journal to understand what changed

            EXAMPLE: checkpoint('before-refactor') → make changes → rollback('before-refactor')
            """;
    }

    @Override
    public String getCategory() {
        return "session";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'checkpoint', 'rollback', 'undo', 'redo', 'journal', 'git_checkpoint', 'git_restore'. Required.");

        props.putObject("name").put("type", "string").put("description",
                "For 'checkpoint'/'git_checkpoint': descriptive name to identify restore point (e.g., 'before-api-change'). " +
                "For 'rollback': exact name of checkpoint to restore. " +
                "Not needed for undo/redo/journal.");

        props.putObject("path").put("type", "string").put("description",
                "For 'git_restore': specific file path to restore from Git HEAD. " +
                "If omitted, shows available restore options.");

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
            case "git_checkpoint" -> executeGitCheckpoint(params);
            case "git_restore" -> executeGitRestore(params);
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
                    "To restore: nts_session(action='git_restore')", name, stashId));
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
                "  nts_session(action='git_restore', path='path/to/file.java')\n\n" +
                "To restore all files (CAUTION - discards all changes!):\n" +
                "  Run in terminal: git checkout HEAD -- .\n\n" +
                "To restore from stash:\n" +
                "  Run in terminal: git stash pop", headCommit));
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}
