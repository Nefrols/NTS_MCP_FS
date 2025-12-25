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
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Объединенный инструмент для работы с Git.
 * Поддерживает выполнение команд, получение диффов и создание коммитов сессии.
 */
public class GitCombinedTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Set<String> ALLOWED_CMDS = Set.of("status", "diff", "log", "add", "commit", "rev-parse", "branch", "init");

    @Override
    public String getName() { return "nts_git"; }

    @Override
    public String getDescription() {
        return """
            Git integration hub - version control operations.

            ACTIONS:
            - cmd - Execute Git commands (status, diff, log, add, commit, branch, rev-parse, init)
                    Only safe, local operations allowed. No push/pull/fetch.
            - diff - View changes in unified diff format
                    Use staged=true for indexed changes only
            - commit_session - Auto-generate commit from session activity
                    Includes completed TODO tasks and technical changes

            WORKFLOW EXAMPLES:
            1. Check status: cmd + command='status'
            2. View changes: diff (shows both staged and unstaged)
            3. Stage files: cmd + command='add' + args='.'
            4. Commit: cmd + command='commit' + args='-m "message"'
            5. Auto-commit: commit_session + header='Feature: search'

            SECURITY: Push/pull/fetch/remote operations blocked.
            """;
    }

    @Override
    public String getCategory() {
        return "external";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Operation: 'cmd' (run git command), 'diff' (show changes), 'commit_session' (auto-commit). Required.");

        props.putObject("command").put("type", "string").put("description",
                "For 'cmd': Git subcommand. Allowed: status, diff, log, add, commit, rev-parse, branch. " +
                "Example: command='status' or command='log' args='--oneline -5'");

        props.putObject("args").put("type", "string").put("description",
                "Additional arguments for git command. Space-separated. " +
                "Example: '-m \"Fix bug\"' for commit, '--oneline -10' for log.");

        props.putObject("timeout").put("type", "integer").put("description",
                "Max execution time in seconds. Default: 30. Increase for large repos.");

        props.putObject("path").put("type", "string").put("description",
                "For 'diff': limit scope to specific file or directory. Omit for full repo diff.");

        props.putObject("staged").put("type", "boolean").put("description",
                "For 'diff': true = show only staged changes (git diff --cached). " +
                "false/omit = show both staged and unstaged.");

        props.putObject("header").put("type", "string").put("description",
                "For 'commit_session': commit message header. Body auto-generated from: " +
                "completed TODO tasks + session change instructions. Required for commit_session.");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.get("action").asText().toLowerCase();

        return switch (action) {
            case "cmd" -> executeCmd(params);
            case "diff" -> executeDiff(params);
            case "commit_session" -> executeCommitSession(params);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        };
    }

    /**
     * Проверяет наличие .git директории в корне проекта.
     * Выбрасывает понятное исключение если Git не инициализирован.
     */
    private void checkGitRepository() {
        Path gitDir = PathSanitizer.getRoot().resolve(".git");
        if (!Files.exists(gitDir)) {
            throw new IllegalStateException(
                "Not a Git repository. No .git directory found in project root. " +
                "Use nts_git(action='cmd', command='init') to initialize a new repository.");
        }
    }

    private JsonNode executeCmd(JsonNode params) throws Exception {
        String subCmd = params.get("command").asText();
        String extraArgs = params.path("args").asText("");
        long timeout = params.path("timeout").asLong(30);

        // Сначала проверяем безопасность команды
        if (!ALLOWED_CMDS.contains(subCmd)) {
            throw new SecurityException("Git subcommand '" + subCmd + "' is forbidden.");
        }

        // Затем проверяем наличие .git (кроме init)
        if (!"init".equals(subCmd)) {
            checkGitRepository();
        }

        List<String> command = new ArrayList<>();
        command.add("git");
        command.add(subCmd);

        if (!extraArgs.isEmpty()) {
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }

        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, timeout);

        return createResponse("Git task [" + result.taskId() + "] " +
            (result.isRunning() ? "STILL RUNNING" : "exit: " + result.exitCode()) +
            "\n\nOutput:\n" + result.output());
    }

    private JsonNode executeDiff(JsonNode params) throws Exception {
        checkGitRepository();

        String pathStr = params.has("path") ? params.get("path").asText() : null;
        boolean stagedOnly = params.path("staged").asBoolean(false);

        Path path = (pathStr != null) ? PathSanitizer.sanitize(pathStr, true) : null;
        StringBuilder sb = new StringBuilder();

        if (stagedOnly) {
            String diff = GitUtils.getDiff(path, true);
            sb.append(diff.isEmpty() ? "No staged changes found." : "### Staged Changes:\n\n```diff\n" + diff + "\n```");
        } else {
            String stagedDiff = GitUtils.getDiff(path, true);
            String unstagedDiff = GitUtils.getDiff(path, false);
            if (stagedDiff.isEmpty() && unstagedDiff.isEmpty()) {
                sb.append("No changes found.");
            } else {
                if (!stagedDiff.isEmpty()) sb.append("### Staged Changes:\n\n```diff\n").append(stagedDiff).append("\n```\n\n");
                if (!unstagedDiff.isEmpty()) sb.append("### Unstaged Changes:\n\n```diff\n").append(unstagedDiff).append("\n```");
            }
        }
        return createResponse(sb.toString().trim());
    }

    private JsonNode executeCommitSession(JsonNode params) throws Exception {
        checkGitRepository();

        String header = params.get("header").asText();
        List<String> instructions = TransactionManager.getSessionInstructions();
        List<String> completedTasks = TodoManager.getCompletedTasks();

        StringBuilder body = new StringBuilder();
        if (!completedTasks.isEmpty()) {
            body.append("\n\nCompleted tasks:\n");
            for (String task : completedTasks) body.append("- ").append(task).append("\n");
        }
        if (!instructions.isEmpty()) {
            body.append("\nTechnical changes:\n");
            instructions.stream().distinct().forEach(ins -> body.append("- ").append(ins).append("\n"));
        }

        String fullMessage = header + body.toString();
        ProcessExecutor.execute(List.of("git", "add", "."), 10);
        ProcessExecutor.ExecutionResult res = ProcessExecutor.execute(List.of("git", "commit", "-m", fullMessage), 10);

        return createResponse("Commit successful:\n\n" + res.output());
    }

    private JsonNode createResponse(String msg) {
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", msg);
        return res;
    }
}