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

import static org.junit.jupiter.api.Assertions.*;

class SearchFilesToolTest {
    private final SearchFilesTool tool = new SearchFilesTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testSearch(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("file1.txt"), "    indented string");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("query", "indented");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("file1.txt:"));
        assertTrue(text.contains("1|     indented string"));
    }

    @Test
    void testSearchWithContext(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("context.txt"), "line 1\nline 2\nTARGET\nline 4\nline 5");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("query", "TARGET");
        params.put("beforeContext", 1);
        params.put("afterContext", 1);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("2: line 2"));
        assertTrue(text.contains("3| TARGET"));
        assertTrue(text.contains("4: line 4"));
        assertFalse(text.contains("1: line 1"));
        assertFalse(text.contains("5: line 5"));
    }

    @Test
    void testParallelSearchStress(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
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
        
        assertTrue(text.contains("(10)"), "Should find 10 matches");
        for (int i = 0; i < 100; i += 10) {
            assertTrue(text.contains("file" + i + ".txt"));
        }
    }

    @Test
    void testRegexSearch(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("test1.txt"), "The quick brown fox");
        Files.writeString(tempDir.resolve("test2.txt"), "The slow red fox");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", tempDir.toString());
        params.put("query", "q...k"); // regex for quick
        params.put("isRegex", true);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("test1.txt"));
        assertFalse(text.contains("test2.txt"));
    }

    @Test
    void testSearchReadMarker(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("known.txt");
        Files.writeString(file, "target string");
        
        // Регистрируем как прочитанный
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("query", "target");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("known.txt [READ]:"), "Должен отображаться маркер [READ] в поиске");
    }
}