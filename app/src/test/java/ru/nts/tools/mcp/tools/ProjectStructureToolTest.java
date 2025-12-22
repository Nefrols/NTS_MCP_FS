// Aristo 22.12.2025
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для ProjectStructureTool.
 */
class ProjectStructureToolTest {

    private final ProjectStructureTool tool = new ProjectStructureTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testBasicTree(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("rootFile.txt"));
        Files.createDirectory(tempDir.resolve("dir1"));
        Files.createFile(tempDir.resolve("dir1/fileInDir.txt"));

        JsonNode params = mapper.createObjectNode();
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();

        // Проверка ASCII графики
        assertTrue(text.contains("├── dir1/"));
        assertTrue(text.contains("│   └── fileInDir.txt"));
        assertTrue(text.contains("└── rootFile.txt") || text.contains("├── rootFile.txt"));
    }

    @Test
    void testMaxDepth(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path deep = Files.createDirectories(tempDir.resolve("a/b/c"));
        Files.createFile(deep.resolve("deep.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("maxDepth", 2);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("a"), "Should contain directory a");
        assertTrue(text.contains("b"), "Should contain directory b");
        assertFalse(text.contains("c/"), "Should stop before level c");
        assertFalse(text.contains("deep.txt"));
    }

    @Test
    void testIgnorePatterns(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("keep.txt"));
        Files.createFile(tempDir.resolve("ignore.log"));
        Files.createDirectory(tempDir.resolve("build"));
        Files.createFile(tempDir.resolve("build/out.class"));

        ObjectNode params = mapper.createObjectNode();
        ArrayNode ignores = params.putArray("ignorePatterns");
        ignores.add("*.log");
        ignores.add("build");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("keep.txt"));
        assertFalse(text.contains("ignore.log"));
        assertFalse(text.contains("build/"), "Should ignore build directory");
        assertFalse(text.contains("out.class"));
    }

    @Test
    void testProtectedFiles(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createDirectory(tempDir.resolve(".git"));
        Files.createFile(tempDir.resolve(".git/config"));
        Files.createFile(tempDir.resolve("normal.txt"));

        JsonNode params = mapper.createObjectNode();
        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("normal.txt"));
        assertFalse(text.contains(".git/"), "Protected directory should be hidden");
        assertFalse(text.contains("config"));
    }

    @Test
    void testAutoIgnore(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("important.txt"));
        Files.createDirectories(tempDir.resolve("build"));
        Files.createDirectories(tempDir.resolve(".gradle"));

        ObjectNode params = mapper.createObjectNode();
        params.put("autoIgnore", true);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("important.txt"));
        assertFalse(text.contains("build"), "build folder should be ignored");
        assertFalse(text.contains(".gradle"), "gradle folder should be ignored");
    }
}