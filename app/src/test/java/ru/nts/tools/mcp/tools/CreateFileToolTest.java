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

class CreateFileToolTest {
    private final CreateFileTool tool = new CreateFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testCreateFileWithListing(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("existing.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "new.txt");
        params.put("content", "hello");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("File created successfully: new.txt"));
        assertTrue(text.contains("Directory content"));
        assertTrue(text.contains("[FILE] existing.txt"));
        assertTrue(text.contains("[FILE] new.txt"));
    }
}
