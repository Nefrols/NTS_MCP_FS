// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {
    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReplaceText(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "World");
        params.put("newText", "Java");

        tool.execute(params);
        assertEquals("Hello Java", Files.readString(file));
    }

    @Test
    void testReplaceLines(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("Line 0", "Line 1", "Line 2", "Line 3"));

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 1);
        params.put("endLine", 2);
        params.put("newText", "New Line 1 and 2");

        tool.execute(params);
        
        List<String> actualLines = Files.readAllLines(file);
        assertEquals(3, actualLines.size());
        assertEquals("Line 0", actualLines.get(0));
        assertEquals("New Line 1 and 2", actualLines.get(1));
        assertEquals("Line 3", actualLines.get(2));
    }
}
