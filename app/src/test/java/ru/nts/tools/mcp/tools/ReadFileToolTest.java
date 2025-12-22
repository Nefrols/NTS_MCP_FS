// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ReadFileToolTest {
    private final ReadFileTool tool = new ReadFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReadFull(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3");

        JsonNode params = mapper.createObjectNode().put("path", file.toString());
        JsonNode result = tool.execute(params);
        assertEquals("Line 1\nLine 2\nLine 3", result.get("content").get(0).get("text").asText());
    }

    @Test
    void testReadLine(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("line", 2);

        JsonNode result = tool.execute(params);
        assertEquals("Line 2", result.get("content").get(0).get("text").asText());
    }

    @Test
    void testReadRange(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3\nLine 4");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 4);

        JsonNode result = tool.execute(params);
        assertEquals("Line 2\nLine 3", result.get("content").get(0).get("text").asText());
    }
}