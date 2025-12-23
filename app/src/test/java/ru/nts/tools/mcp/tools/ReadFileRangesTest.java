// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента разреженного чтения (nts_read_file_ranges).
 */
class ReadFileRangesTest {

    private final ReadFileRangesTool tool = new ReadFileRangesTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReadRanges(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("large.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) sb.append("Line ").append(i).append("\n");
        Files.writeString(file, sb.toString());

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "large.txt");
        ArrayNode ranges = params.putArray("ranges");
        ranges.addObject().put("startLine", 1).put("endLine", 2);
        ranges.addObject().put("startLine", 99).put("endLine", 100);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("1| Line 1"));
        assertTrue(text.contains("2| Line 2"));
        assertTrue(text.contains("... [lines 3-98 hidden] ..."));
        assertTrue(text.contains("99| Line 99"));
        assertTrue(text.contains("100| Line 100"));
    }
}