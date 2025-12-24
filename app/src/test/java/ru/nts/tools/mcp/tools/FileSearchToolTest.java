// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.tools.fs.FileSearchTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента поиска (FileSearchTool).
 */
class FileSearchToolTest {

    private final FileSearchTool tool = new FileSearchTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testList(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "list");
        params.put("path", ".");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("file1.txt"));
        assertTrue(text.contains("[DIR] subdir"));
    }

    @Test
    void testFind(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.createFile(tempDir.resolve("a/b/find_me.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "find");
        params.put("path", ".");
        params.put("pattern", "find_me.txt");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("find_me.txt"));
    }

    @Test
    void testGrep(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("grep.txt"), "Hello World");

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "grep");
        params.put("path", ".");
        params.put("pattern", "Hello");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("grep.txt"));
        assertTrue(text.contains("Hello World"));
    }

    @Test
    void testStructure(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createDirectories(tempDir.resolve("src/main"));

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "structure");
        params.put("path", ".");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("src"));
        assertTrue(text.contains("main"));
    }
}
