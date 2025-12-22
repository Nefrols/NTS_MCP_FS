// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ListDirectoryToolTest {
    private final ListDirectoryTool tool = new ListDirectoryTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testExecute(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        JsonNode params = mapper.createObjectNode().put("path", tempDir.toString());
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("file1.txt"));
        assertTrue(text.contains("[DIR] subdir"));
    }
}
