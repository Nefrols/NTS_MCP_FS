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
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.util.List;

/**
 * Workspace status tool for context recovery.
 * Returns compact summary of current task state: TODO progress, affected files,
 * recent journal entries, and suggested next action.
 *
 * LLMs call this to regain orientation after prompt compression or when confused.
 */
public class WorkspaceStatusTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_workspace_status";
    }

    @Override
    public String getDescription() {
        return """
            Get current workspace status for context recovery.

            Returns compact summary:
            - Current task ID and stats
            - TODO progress (if active)
            - Recently modified files
            - Recent journal entries (last 5 operations)
            - Suggested next action

            WHEN TO USE:
            - After context compression to recover orientation
            - When unsure about current state
            - Before finishing a task to verify completeness
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
        schema.putObject("properties");
        // No parameters needed
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        StringBuilder sb = new StringBuilder();

        // 1. Task info
        TaskContext ctx = TaskContext.current();
        String taskId = ctx != null ? ctx.getTaskId() : "default";
        sb.append("[TASK: ").append(taskId).append("]\n");

        // 2. Task stats
        String stats = TransactionManager.getTaskStats();
        sb.append("[STATS: ").append(stats).append("]\n");

        // 3. TODO progress
        TodoManager.HudInfo hud = TodoManager.getHudInfo();
        if (hud.title() != null) {
            sb.append("[TODO: ").append(hud.title()).append(" — ")
              .append(hud.done()).append("/").append(hud.total()).append(" done");
            if (hud.failed() > 0) {
                sb.append(", ").append(hud.failed()).append(" failed");
            }
            if (hud.nextTask() != null) {
                sb.append(" | Next: #").append(hud.nextId()).append(": ").append(hud.nextTask());
            }
            sb.append("]\n");
        }

        // 4. Affected files (modified in current task)
        List<String> affectedFiles = TransactionManager.getAffectedPaths();
        if (!affectedFiles.isEmpty()) {
            sb.append("[MODIFIED FILES: ").append(affectedFiles.size()).append("]\n");
            for (String file : affectedFiles) {
                sb.append("  - ").append(file).append("\n");
            }
        } else {
            sb.append("[MODIFIED FILES: none]\n");
        }

        // 5. Recent journal entries
        List<String> recentOps = TransactionManager.getRecentJournal(5);
        if (!recentOps.isEmpty()) {
            sb.append("[RECENT OPERATIONS]\n");
            for (String op : recentOps) {
                sb.append("  ").append(op).append("\n");
            }
        }

        // 6. Verify counter
        int editsSinceVerify = TransactionManager.getEditsSinceLastVerify();
        if (editsSinceVerify > 0) {
            sb.append("[EDITS SINCE LAST VERIFY: ").append(editsSinceVerify).append("]\n");
        }

        // 7. Suggested next action
        sb.append("\n").append(suggestNextAction(hud, editsSinceVerify));

        return createTextResponse(sb.toString().trim());
    }

    private String suggestNextAction(TodoManager.HudInfo hud, int editsSinceVerify) {
        if (hud.title() != null) {
            int pending = hud.total() - hud.done() - hud.failed();
            if (pending == 0 && hud.total() > 0) {
                if (editsSinceVerify > 0) {
                    return "[NEXT ACTION: All TODO items done. Run nts_verify(action='syntax') before finishing.]";
                }
                return "[NEXT ACTION: All TODO items done. Finish with nts_worker_finish(report='...')]";
            }
            if (hud.nextTask() != null) {
                return "[NEXT ACTION: Work on TODO #" + hud.nextId() + ": " + hud.nextTask() + "]";
            }
        }

        if (editsSinceVerify >= 3) {
            return "[NEXT ACTION: " + editsSinceVerify + " edits without verification. Run nts_verify(action='syntax')]";
        }

        return "[NEXT ACTION: Continue working on the current task.]";
    }

    private JsonNode createTextResponse(String text) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", text);
        return res;
    }
}
