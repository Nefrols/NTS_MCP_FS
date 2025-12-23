// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.ProcessExecutor;
import ru.nts.tools.mcp.core.TransactionManager;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Инструмент для автоматического формирования коммита на основе семантических меток сессии.
 */
public class GitCommitSessionTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_git_commit_session"; }

    @Override
    public String getDescription() { return "Auto-commit current changes with message generated from session instructions."; }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");
        props.putObject("header").put("type", "string").put("description", "Commit header (e.g. 'Feature: added auth').");
        schema.putArray("required").add("header");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String header = params.get("header").asText();
        List<String> instructions = TransactionManager.getSessionInstructions();

        StringBuilder body = new StringBuilder();
        if (!instructions.isEmpty()) {
            body.append("\n\nChanges in this session:\n");
            // Убираем дубликаты и форматируем
            List<String> unique = instructions.stream().distinct().collect(Collectors.toList());
            for (String ins : unique) {
                body.append("- ").append(ins).append("\n");
            }
        }

        String fullMessage = header + body.toString();

        // 1. Git Add .
        ProcessExecutor.execute(List.of("git", "add", "."), 10);
        
        // 2. Git Commit
        ProcessExecutor.ExecutionResult res = ProcessExecutor.execute(List.of("git", "commit", "-m", fullMessage), 10);

        ObjectNode response = mapper.createObjectNode();
        response.putArray("content").addObject().put("type", "text")
           .put("text", "Commit successful:\n\n" + res.output());
        return response;
    }
}