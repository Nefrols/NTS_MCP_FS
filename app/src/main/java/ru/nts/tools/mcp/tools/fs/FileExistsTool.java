// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Легковесный инструмент для проверки существования файла или директории.
 * Позволяет быстро проверить путь без чтения содержимого или получения полной информации.
 */
public class FileExistsTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_file_exists";
    }

    @Override
    public String getDescription() {
        return "Lightweight check if a file or directory exists.";
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
        props.putObject("path").put("type", "string").put("description", "Path to check.");
        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        Path path = PathSanitizer.sanitize(pathStr, true);

        boolean exists = Files.exists(path);
        boolean isFile = exists && Files.isRegularFile(path);
        boolean isDirectory = exists && Files.isDirectory(path);

        ObjectNode res = mapper.createObjectNode();
        var content = res.putArray("content").addObject();
        content.put("type", "text");
        
        StringBuilder sb = new StringBuilder();
        sb.append("Path: ").append(pathStr).append("\n");
        sb.append("Exists: ").append(exists);
        if (exists) {
            sb.append("\nType: ").append(isFile ? "File" : (isDirectory ? "Directory" : "Other"));
        }

        content.put("text", sb.toString());
        return res;
    }
}
