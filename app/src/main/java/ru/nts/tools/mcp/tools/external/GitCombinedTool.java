// Aristo 24.12.2025
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;

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
    private static final Set<String> ALLOWED_CMDS = Set.of("status", "diff", "log", "add", "commit", "rev-parse", "branch");

    @Override
    public String getName() { return "nts_git"; }

    @Override
    public String getDescription() {
        return "Consolidated Git tool. Supported actions: cmd, diff, commit_session.";
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

        props.putObject("action").put("type", "string").put("description", "Action type: 'cmd' (arbitrary command), 'diff' (view changes), 'commit_session' (auto-commit status).");
        props.putObject("command").put("type", "string").put("description", "For 'cmd': Git subcommand (status, diff, log, add, commit, rev-parse, branch). Only local operations allowed.");
        props.putObject("args").put("type", "string").put("description", "Arguments for the chosen Git command.");
        props.putObject("timeout").put("type", "integer").put("description", "Command execution time limit in seconds. Defaults to 30.");
        props.putObject("path").put("type", "string").put("description", "For 'diff': path to a file or directory to limit the diff scope.");
        props.putObject("staged").put("type", "boolean").put("description", "For 'diff': if true, show only indexed changes (git diff --cached).");
        props.putObject("header").put("type", "string").put("description", "For 'commit_session': short commit header (e.g., 'Feature: added search').");

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

    private JsonNode executeCmd(JsonNode params) throws Exception {
        String subCmd = params.get("command").asText();
        String extraArgs = params.path("args").asText("");
        long timeout = params.path("timeout").asLong(30);

        if (!ALLOWED_CMDS.contains(subCmd)) {
            throw new SecurityException("Git subcommand '" + subCmd + "' is forbidden.");
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