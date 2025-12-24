// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

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

/**
 * Тесты для инструмента пакетного выполнения (BatchToolsTool).
 */
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
        router.registerTool(new FileManageTool());
        router.registerTool(new EditFileTool());
        router.registerTool(new FileReadTool());
        batchTool = new BatchToolsTool(router);
    }

    @Test
    void testAccessTransferInBatch() throws Exception {
        Path file = tempDir.resolve("access.txt");
        Files.writeString(file, "content");

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Чтение (дает права)
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_file_read");
        ObjectNode p1 = a1.putObject("params");
        p1.put("action", "read");
        p1.put("path", "access.txt");

        // Шаг 2: Праквка
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "nts_edit_file");
        a2.putObject("params").put("path", "access.txt").put("oldText", "content").put("newText", "updated");

        batchTool.execute(params);
        assertEquals("updated", Files.readString(file));
    }

    @Test
    void testRenameAndEditBatch() throws Exception {
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "Original Content");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Переименование
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_file_manage");
        ObjectNode p1 = a1.putObject("params");
        p1.put("action", "rename");
        p1.put("path", "old.txt");
        p1.put("newName", "new.txt");

        // Шаг 2: Редактирование
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "nts_edit_file");
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

        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_edit_file");
        a1.putObject("params").put("path", "safe.txt").put("oldText", "Untouched").put("newText", "MODIFIED");

        ObjectNode a2 = actions.addObject();
        a2.put("tool", "nts_edit_file");
        a2.putObject("params").put("path", "missing.txt").put("oldText", "any").put("newText", "fail");

        assertThrows(Exception.class, () -> batchTool.execute(params));
        assertEquals("Untouched", Files.readString(file));
    }
}
