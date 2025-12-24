// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.DiffUtils;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Инструмент для сравнения двух произвольных файлов.
 * Позволяет получить Unified Diff между любыми текстовыми файлами в проекте.
 */
public class CompareFilesTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_compare_files";
    }

    @Override
    public String getDescription() {
        return """
            File comparison tool - shows differences between two files.

            OUTPUT: Unified Diff format (same as git diff)
            • Lines starting with '-' = removed from path1
            • Lines starting with '+' = added in path2
            • Context lines shown for reference

            USE CASES:
            • Compare original vs modified file
            • Verify changes before commit
            • Diff two versions of same file
            • Check if files are identical

            RETURNS: "Files are identical." if no differences found.
            """;
    }

    @Override
    public String getCategory() {
        return "fs";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path1").put("type", "string").put("description",
                "First file (baseline/original). Diff shows what changed FROM this file.");

        props.putObject("path2").put("type", "string").put("description",
                "Second file (modified/new). Diff shows what changed TO this file.");

        schema.putArray("required").add("path1").add("path2");
        return schema;
    }
@Override
    public JsonNode execute(JsonNode params) throws Exception {
        String p1Str = params.get("path1").asText();
        String p2Str = params.get("path2").asText();

        Path p1 = PathSanitizer.sanitize(p1Str, true);
        Path p2 = PathSanitizer.sanitize(p2Str, true);

        if (!Files.exists(p1)) throw new IllegalArgumentException("File not found: " + p1Str);
        if (!Files.exists(p2)) throw new IllegalArgumentException("File not found: " + p2Str);

        PathSanitizer.checkFileSize(p1);
        PathSanitizer.checkFileSize(p2);

        String content1 = EncodingUtils.readTextFile(p1).content();
        String content2 = EncodingUtils.readTextFile(p2).content();

        String diff = DiffUtils.getUnifiedDiff(p1.getFileName().toString() + " <-> " + p2.getFileName().toString(), content1, content2);

        ObjectNode res = mapper.createObjectNode();
        var content = res.putArray("content").addObject();
        content.put("type", "text");
        
        if (diff.isEmpty()) {
            content.put("text", "Files are identical.");
        } else {
            content.put("text", "Comparison results:\n\n```diff\n" + diff + "\n```");
        }

        return res;
    }
}
