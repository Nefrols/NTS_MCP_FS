// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.tools.fs.FileReadTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента чтения файлов (FileReadTool).
 */
class FileReadToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FileReadTool tool = new FileReadTool();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset();
    }

    @Test
    void testFileReadActions() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3");

        // Action: exists
        ObjectNode pExists = mapper.createObjectNode();
        pExists.put("action", "exists");
        pExists.put("path", "test.txt");
        JsonNode resExists = tool.execute(pExists);
        assertTrue(resExists.get("content").get(0).get("text").asText().contains("exists: true"));

        // Action: info
        ObjectNode pInfo = mapper.createObjectNode();
        pInfo.put("action", "info");
        pInfo.put("path", "test.txt");
        JsonNode resInfo = tool.execute(pInfo);
        assertTrue(resInfo.get("content").get(0).get("text").asText().contains("Size:"));

        // Action: read with context
        ObjectNode pRead = mapper.createObjectNode();
        pRead.put("action", "read");
        pRead.put("path", "test.txt");
        pRead.put("contextStartPattern", "Line 2");
        pRead.put("contextRange", 1);
        JsonNode resRead = tool.execute(pRead);
        String text = resRead.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Line 1"));
        assertTrue(text.contains("Line 2"));
        assertTrue(text.contains("Line 3"));
    }

    @Test
    void testReadRanges() throws Exception {
        Path file = tempDir.resolve("ranges.txt");
        Files.writeString(file, "1\n2\n3\n4\n5\n6");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "ranges.txt");
        ArrayNode ranges = params.putArray("ranges");
        ranges.addObject().put("startLine", 1).put("endLine", 2);
        ranges.addObject().put("startLine", 5).put("endLine", 6);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("1"));
        assertTrue(text.contains("2"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("6"));
        assertFalse(text.contains("3"));
    }

    @Test
    void testHistoryAction() throws Exception {
        Path file = tempDir.resolve("hist.txt");
        Files.writeString(file, "init");
        
        TransactionManager.startTransaction("Change 1");
        TransactionManager.backup(file);
        Files.writeString(file, "v1");
        TransactionManager.commit();

        ObjectNode p = mapper.createObjectNode();
        p.put("action", "history");
        p.put("path", "hist.txt");
        JsonNode res = tool.execute(p);
        assertTrue(res.get("content").get(0).get("text").asText().contains("Change 1"));
    }
}