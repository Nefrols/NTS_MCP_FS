// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BatchToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private McpRouter router;
    private BatchToolsTool batchTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset();
        
        router = new McpRouter(mapper);
        router.registerTool(new RenameFileTool());
        router.registerTool(new EditFileTool());
        batchTool = new BatchToolsTool(router);
    }

    @Test
    void testRenameAndEditBatch() throws Exception {
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "Original Content");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Действие 1: Rename
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "rename_file");
        a1.putObject("params").put("path", "old.txt").put("newName", "new.txt");

        // Действие 2: Edit (уже по новому пути)
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "edit_file");
        a2.putObject("params").put("path", "new.txt").put("oldText", "Original").put("newText", "Updated");

        batchTool.execute(params);

        Path newFile = tempDir.resolve("new.txt");
        assertTrue(Files.exists(newFile));
        assertFalse(Files.exists(file));
        assertEquals("Updated Content", Files.readString(newFile));
    }

    @Test
    void testBatchRollbackOnFailure() throws Exception {
        Path file = tempDir.resolve("safe.txt");
        Files.writeString(file, "Untouched");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Действие 1: Валидное
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "edit_file");
        a1.putObject("params").put("path", "safe.txt").put("oldText", "Untouched").put("newText", "MODIFIED");

        // Действие 2: Ошибочное (несуществующий файл)
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "edit_file");
        a2.putObject("params").put("path", "missing.txt").put("oldText", "any").put("newText", "fail");

        assertThrows(Exception.class, () -> batchTool.execute(params));

        // Первый файл должен откатиться к оригиналу
        assertEquals("Untouched", Files.readString(file));
    }
}
