// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.GitUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Path;

/**
 * Специализированный инструмент для получения диффов Git.
 * Позволяет агенту проводить качественный self-review изменений.
 */
public class GitDiffTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_git_diff";
    }

    @Override
    public String getDescription() {
        return "Returns a clean Unified Diff for changes (staged, unstaged, or specific paths). Filters binary files and respects .gitignore.";
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

        props.putObject("path").put("type", "string").put("description", "Optional: Limit diff to a specific file or directory.");
        props.putObject("staged").put("type", "boolean").put("description", "Optional: If true, show only staged changes (git diff --cached). Defaults to false (staged + unstaged).");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.has("path") ? params.get("path").asText() : null;
        boolean stagedOnly = params.path("staged").asBoolean(false);

        Path path = null;
        if (pathStr != null) {
            path = PathSanitizer.sanitize(pathStr, true);
        }

        StringBuilder sb = new StringBuilder();
        if (stagedOnly) {
            // Только staged
            String diff = GitUtils.getDiff(path, true);
            if (diff.isEmpty()) {
                sb.append("No staged changes found.");
            } else {
                sb.append("### Staged Changes:\n\n```diff\n").append(diff).append("\n```");
            }
        } else {
            // Staged + Unstaged
            String stagedDiff = GitUtils.getDiff(path, true);
            String unstagedDiff = GitUtils.getDiff(path, false);

            if (stagedDiff.isEmpty() && unstagedDiff.isEmpty()) {
                sb.append("No changes found.");
            } else {
                if (!stagedDiff.isEmpty()) {
                    sb.append("### Staged Changes:\n\n```diff\n").append(stagedDiff).append("\n```\n\n");
                }
                if (!unstagedDiff.isEmpty()) {
                    sb.append("### Unstaged Changes:\n\n```diff\n").append(unstagedDiff).append("\n```");
                }
            }
        }

        ObjectNode response = mapper.createObjectNode();
        response.putArray("content").addObject().put("type", "text").put("text", sb.toString().trim());
        return response;
    }
}