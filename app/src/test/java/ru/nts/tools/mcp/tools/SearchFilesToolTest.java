// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SearchFilesToolTest {
    private final SearchFilesTool tool = new SearchFilesTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testSearch(@TempDir Path tempDir) throws Exception {
        Path subDir = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("file1.txt"), "contains target string");
        Files.writeString(subDir.resolve("file2.txt"), "also target here");
        Files.writeString(tempDir.resolve("file3.txt"), "no match");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", tempDir.toString());
        params.put("query", "target");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("file1.txt"));
        assertTrue(text.contains("file2.txt"));
        assertFalse(text.contains("file3.txt"));
    }

    @Test
    void testParallelSearchStress(@TempDir Path tempDir) throws Exception {
        int fileCount = 100;
        for (int i = 0; i < fileCount; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), 
                (i % 10 == 0) ? "matching string" : "random content");
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("path", tempDir.toString());
        params.put("query", "matching");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        // Должно быть 10 совпадений (0, 10, 20... 90)
        assertTrue(text.contains("(10)"), "Должно быть найдено 10 совпадений");
        for (int i = 0; i < 100; i += 10) {
            assertTrue(text.contains("file" + i + ".txt"));
        }
    }
}
