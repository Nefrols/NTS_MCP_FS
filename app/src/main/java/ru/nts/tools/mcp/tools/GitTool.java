// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.ProcessExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Инструмент для базовых операций с Git.
 * Ограничен безопасным набором команд.
 */
public class GitTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();
    
    // Список разрешенных подкоманд для безопасности
    private static final Set<String> ALLOWED_CMDS = Set.of("status", "diff", "log", "add", "commit", "rev-parse", "branch");

    @Override
    public String getName() {
        return "git_cmd";
    }

    @Override
    public String getDescription() {
        return "Executes limited Git commands: status, diff, log, add, commit. No remote operations allowed.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("command").put("type", "string").put("description", "Git subcommand (e.g., 'status').");
        props.putObject("args").put("type", "string").put("description", "Optional arguments.");
        
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String subCmd = params.get("command").asText();
        String extraArgs = params.path("args").asText("");

        if (!ALLOWED_CMDS.contains(subCmd)) {
            throw new SecurityException("Command 'git " + subCmd + "' is not allowed for security reasons.");
        }

        List<String> command = new ArrayList<>();
        command.add("git");
        command.add(subCmd);
        
        if (!extraArgs.isEmpty()) {
            // Реализация защиты от инъекций: передаем аргументы как отдельные элементы списка.
            // Даже если внутри extraArgs есть ';' или '|', они будут трактоваться Git как часть аргумента.
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }

        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Git command finished with exit code: ").append(result.exitCode()).append("\n\n");
        sb.append("Output:\n").append(result.output());
        
        content.put("text", sb.toString());
        return response;
    }
}
