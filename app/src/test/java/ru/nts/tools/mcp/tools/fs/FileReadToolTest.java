/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.tools.fs.FileReadTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        LineAccessTracker.reset();
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
        Files.writeString(file, "AAA\nBBB\nCCC\nDDD\nEEE\nFFF");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "ranges.txt");
        ArrayNode ranges = params.putArray("ranges");
        ranges.addObject().put("startLine", 1).put("endLine", 2);
        ranges.addObject().put("startLine", 5).put("endLine", 6);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("AAA"), "Should contain line 1");
        assertTrue(text.contains("BBB"), "Should contain line 2");
        assertTrue(text.contains("EEE"), "Should contain line 5");
        assertTrue(text.contains("FFF"), "Should contain line 6");
        assertFalse(text.contains("CCC"), "Should NOT contain line 3");
        assertFalse(text.contains("DDD"), "Should NOT contain line 4");
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

    // ============ Bulk Read Tests ============

    @Test
    void testBulkReadMultipleFiles() throws Exception {
        // Create test files
        Path file1 = tempDir.resolve("bulk1.txt");
        Path file2 = tempDir.resolve("bulk2.txt");
        Path file3 = tempDir.resolve("bulk3.txt");
        Files.writeString(file1, "File 1 Content");
        Files.writeString(file2, "File 2 Line 1\nFile 2 Line 2");
        Files.writeString(file3, "File 3 Data");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode bulk = params.putArray("bulk");

        // File 1: read line 1
        bulk.addObject().put("path", "bulk1.txt").put("line", 1);

        // File 2: read lines 1-2
        bulk.addObject().put("path", "bulk2.txt").put("startLine", 1).put("endLine", 2);

        // File 3: read line 1
        bulk.addObject().put("path", "bulk3.txt").put("line", 1);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Check header
        assertTrue(text.contains("[BULK READ: 3 files | 3 succeeded | 0 failed]"), "Should show bulk read header");

        // Check all files are present
        assertTrue(text.contains("FILE 1: bulk1.txt"), "Should contain file 1");
        assertTrue(text.contains("File 1 Content"), "Should contain file 1 content");
        assertTrue(text.contains("FILE 2: bulk2.txt"), "Should contain file 2");
        assertTrue(text.contains("File 2 Line 1"), "Should contain file 2 content");
        assertTrue(text.contains("FILE 3: bulk3.txt"), "Should contain file 3");
        assertTrue(text.contains("File 3 Data"), "Should contain file 3 content");

        // Check tokens are issued
        assertTrue(text.contains("TOKEN:"), "Should contain tokens");
    }

    @Test
    void testBulkReadWithErrors() throws Exception {
        // Create only one file
        Path file1 = tempDir.resolve("exists.txt");
        Files.writeString(file1, "Existing file");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode bulk = params.putArray("bulk");

        // Existing file
        bulk.addObject().put("path", "exists.txt").put("line", 1);

        // Non-existing file
        bulk.addObject().put("path", "missing.txt").put("line", 1);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Check header shows partial success
        assertTrue(text.contains("[BULK READ: 2 files | 1 succeeded | 1 failed]"), "Should show partial success");

        // Check successful file
        assertTrue(text.contains("[SUCCESS]"), "Should have success marker");
        assertTrue(text.contains("Existing file"), "Should contain successful file content");

        // Check error file
        assertTrue(text.contains("[ERROR]"), "Should have error marker");
        assertTrue(text.contains("missing.txt"), "Should reference missing file");
    }

    @Test
    void testBulkReadWithRanges() throws Exception {
        Path file = tempDir.resolve("ranges_bulk.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3\nLine 4\nLine 5");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode bulk = params.putArray("bulk");

        // File with ranges
        ObjectNode fileSpec = bulk.addObject();
        fileSpec.put("path", "ranges_bulk.txt");
        ArrayNode ranges = fileSpec.putArray("ranges");
        ranges.addObject().put("startLine", 1).put("endLine", 2);
        ranges.addObject().put("startLine", 4).put("endLine", 5);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Line 1"), "Should contain range 1");
        assertTrue(text.contains("Line 2"), "Should contain range 1");
        assertTrue(text.contains("Line 4"), "Should contain range 2");
        assertTrue(text.contains("Line 5"), "Should contain range 2");
    }

    @Test
    void testBulkReadEmpty() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.putArray("bulk"); // Empty bulk array

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("[BULK READ: 0 files | 0 succeeded | 0 failed]"), "Should handle empty bulk");
    }
}