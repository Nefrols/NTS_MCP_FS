// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Расширенные тесты системы транзакций и функций отмены/повтора (UNDO/REDO).
 */
class UndoRedoTest {

    private final EditFileTool editTool = new EditFileTool();
    private final FileManageTool manageTool = new FileManageTool();
    private final SessionTool sessionTool = new SessionTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path sharedTempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(sharedTempDir);
        TransactionManager.reset();
        AccessTracker.reset();
    }

    @Test
    void testTransactionRollbackOnError() throws Exception {
        Path file = sharedTempDir.resolve("atomic.txt");
        String original = "Original";
        Files.writeString(file, original);
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "atomic.txt");
        var ops = params.putArray("operations");
        ops.addObject().put("operation", "replace").put("startLine", 1).put("endLine", 1).put("content", "New");
        ops.addObject().put("operation", "replace").put("startLine", 100).put("content", "Error");

        assertThrows(Exception.class, () -> editTool.execute(params));
        assertEquals(original, Files.readString(file).replace("\r", ""));
    }

    @Test
    void testUndoFileDeletion() throws Exception {
        Path file = sharedTempDir.resolve("to_delete.txt");
        String content = "Vital Data";
        Files.writeString(file, content);
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "delete");
        params.put("path", "to_delete.txt");
        manageTool.execute(params);
        assertFalse(Files.exists(file));

        ObjectNode sessionParams = mapper.createObjectNode();
        sessionParams.put("action", "undo");
        sessionTool.execute(sessionParams);
        assertTrue(Files.exists(file));
        assertEquals(content, Files.readString(file));
    }

    @Test
    void testUndoMoveOperation() throws Exception {
        Path source = sharedTempDir.resolve("source.txt");
        Path target = sharedTempDir.resolve("sub/target.txt");
        Files.writeString(source, "move me");
        AccessTracker.registerRead(source);

        ObjectNode params = mapper.createObjectNode();
        params.put("action", "move");
        params.put("path", "source.txt");
        params.put("targetPath", "sub/target.txt");
        manageTool.execute(params);

        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target));

        ObjectNode sessionParams = mapper.createObjectNode();
        sessionParams.put("action", "undo");
        sessionTool.execute(sessionParams);
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(target));
    }

    @Test
    void testRedoStackInvalidation() throws Exception {
        Path file = sharedTempDir.resolve("test.txt");
        Files.writeString(file, "init");
        AccessTracker.registerRead(file);

        ObjectNode p1 = mapper.createObjectNode();
        p1.put("path", "test.txt");
        p1.put("oldText", "init");
        p1.put("newText", "A");
        editTool.execute(p1);

        ObjectNode undoP = mapper.createObjectNode();
        undoP.put("action", "undo");
        sessionTool.execute(undoP);
        assertEquals("init", Files.readString(file));

        ObjectNode p2 = mapper.createObjectNode();
        p2.put("path", "test.txt");
        p2.put("oldText", "init");
        p2.put("newText", "B");
        editTool.execute(p2);

        ObjectNode redoP = mapper.createObjectNode();
        redoP.put("action", "redo");
        JsonNode redoResult = sessionTool.execute(redoP);
        String msg = redoResult.get("content").get(0).get("text").asText();
        assertTrue(msg.contains("No operations to redo"));
        assertEquals("B", Files.readString(file));
    }

    @Test
    void testLongHistoryCycle() throws Exception {
        Path file = sharedTempDir.resolve("history.txt");
        Files.writeString(file, "0");
        AccessTracker.registerRead(file);

        for (int i = 1; i <= 3; i++) {
            ObjectNode p = mapper.createObjectNode();
            p.put("path", "history.txt");
            p.put("oldText", String.valueOf(i - 1));
            p.put("newText", String.valueOf(i));
            editTool.execute(p);
        }
        assertEquals("3", Files.readString(file));

        ObjectNode undoP = mapper.createObjectNode();
        undoP.put("action", "undo");
        sessionTool.execute(undoP);
        sessionTool.execute(undoP);
        sessionTool.execute(undoP);
        assertEquals("0", Files.readString(file));

        ObjectNode redoP = mapper.createObjectNode();
        redoP.put("action", "redo");
        sessionTool.execute(redoP);
        sessionTool.execute(redoP);
        assertEquals("2", Files.readString(file));
    }

    @Test
    void testTransactionJournal(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        Path f = tempDir.resolve("test.txt");
        Files.writeString(f, "init\n");
        AccessTracker.registerRead(f);

        TransactionManager.startTransaction("Manual Edit");
        TransactionManager.backup(f);
        Files.writeString(f, "init\nnew line\n");
        TransactionManager.commit();

        ObjectNode p = mapper.createObjectNode();
        p.put("action", "journal");
        JsonNode result = sessionTool.execute(p);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("=== TRANSACTION JOURNAL ==="));
        assertTrue(text.contains("Manual Edit"));
        assertTrue(text.contains("+1, -0 lines"));
    }

    @Test
    void testCheckpoints() throws Exception {
        Path file = sharedTempDir.resolve("check.txt");
        Files.writeString(file, "initial");
        AccessTracker.registerRead(file);

        ObjectNode cp1 = mapper.createObjectNode();
        cp1.put("action", "checkpoint");
        cp1.put("name", "PointA");
        sessionTool.execute(cp1);

        ObjectNode edit = mapper.createObjectNode();
        edit.put("path", "check.txt");
        edit.put("oldText", "initial");
        edit.put("newText", "modified");
        editTool.execute(edit);
        assertEquals("modified", Files.readString(file));

        ObjectNode rb = mapper.createObjectNode();
        rb.put("action", "rollback");
        rb.put("name", "PointA");
        sessionTool.execute(rb);

        assertEquals("initial", Files.readString(file));
    }
}
