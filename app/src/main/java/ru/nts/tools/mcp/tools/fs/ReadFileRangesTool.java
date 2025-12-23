// Aristo 23.12.2025
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.EncodingUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Инструмент для выборочного чтения диапазонов строк из файла.
 * Позволяет экономить токены при изучении структуры больших файлов.
 */
public class ReadFileRangesTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() { return "nts_read_file_ranges"; }

    @Override
    public String getDescription() {
        return "Read multiple specific line ranges from a file. Efficient for large files.";
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
        props.putObject("path").put("type", "string").put("description", "File path.");
        
        var ranges = props.putObject("ranges").put("type", "array");
        var rangeItem = ranges.putObject("items").put("type", "object");
        var rangeProps = rangeItem.putObject("properties");
        rangeProps.putObject("startLine").put("type", "integer").put("description", "1-based start line.");
        rangeProps.putObject("endLine").put("type", "integer").put("description", "1-based end line.");
        rangeItem.putArray("required").add("startLine").add("endLine");

        schema.putArray("required").add("path").add("ranges");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.get("path").asText();
        JsonNode rangesNode = params.get("ranges");

        Path path = PathSanitizer.sanitize(pathStr, true);
        if (!Files.exists(path)) throw new IllegalArgumentException("File not found: " + pathStr);

        EncodingUtils.TextFileContent fileData = EncodingUtils.readTextFile(path);
        String[] lines = fileData.content().split("\n", -1);
        Charset charset = fileData.charset();

        List<Range> ranges = new ArrayList<>();
        for (JsonNode r : rangesNode) {
            ranges.add(new Range(r.get("startLine").asInt(), r.get("endLine").asInt()));
        }
        // Сортировка диапазонов для последовательного чтения
        ranges.sort(Comparator.comparingInt(r -> r.start));

        StringBuilder sb = new StringBuilder();
        sb.append("[FILE: ").append(pathStr).append(" | ENCODING: ").append(charset.name()).append("]\n");

        int lastEnd = 0;
        for (Range range : ranges) {
            int start = Math.max(1, range.start);
            int end = Math.min(lines.length, range.end);

            if (start > lastEnd + 1) {
                sb.append("... [lines ").append(lastEnd + 1).append("-").append(start - 1).append(" hidden] ...\n");
            }

            for (int i = start; i <= end; i++) {
                sb.append(i).append("| ").append(lines[i - 1].replace("\r", "")).append("\n");
            }
            lastEnd = end;
        }

        if (lastEnd < lines.length) {
            sb.append("... [lines ").append(lastEnd + 1).append("-").append(lines.length).append(" hidden] ...");
        }

        AccessTracker.registerRead(path);

        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", sb.toString().trim());
        return res;
    }

    private record Range(int start, int end) {}
}