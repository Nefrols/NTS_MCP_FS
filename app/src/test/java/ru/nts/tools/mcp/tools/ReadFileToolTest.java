// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReadFileToolTest {
    private final ReadFileTool tool = new ReadFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReadFull(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        String content = "Line 1\nLine 2\nLine 3";
        Files.writeString(file, content);

        JsonNode params = mapper.createObjectNode().put("path", file.toString());
        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("[FILE: test.txt"));
        assertTrue(text.contains("SIZE:"));
        assertTrue(text.contains("CHARS:"));
        assertTrue(text.contains("LINES:"));
        assertTrue(text.contains("CRC32:"));
        assertTrue(text.endsWith(content));
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
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.endsWith("Line 2"));
    }

    @Test
    void testReadRange(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3\nLine 4");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 3); // Читаем 2 и 3

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.endsWith("Line 2\nLine 3"));
    }

    @Test
    void testReadContext(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("context.txt");
        Files.writeString(file, "1\n2\n3\nTARGET\n5\n6\n7");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("contextStartPattern", "TARGET");
        params.put("contextRange", 2);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.endsWith("2\n3\nTARGET\n5\n6"));
    }

    @Test
    void testReadContextBoundaries(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("bound.txt");
        Files.writeString(file, "TOP\n2\n3");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("contextStartPattern", "TOP");
        params.put("contextRange", 5);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.endsWith("TOP\n2\n3"));
    }
}
