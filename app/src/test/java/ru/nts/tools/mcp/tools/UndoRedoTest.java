// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.tools.editing.EditFileTool;
import ru.nts.tools.mcp.tools.fs.FileManageTool;
import ru.nts.tools.mcp.tools.session.SessionTool;

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
        LineAccessTracker.reset();
    }

    private String registerFullAccess(Path file) throws Exception {
        long crc = calculateCRC32(file);
        String content = Files.readString(file);
        int lineCount = content.split("\n", -1).length;
        LineAccessToken token = LineAccessTracker.registerAccess(file, 1, lineCount, crc, lineCount);
        return token.encode();
    }

    private long calculateCRC32(Path path) throws Exception {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(new java.io.FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }

    @Test
    void testTransactionRollbackOnError() throws Exception {
        Path file = sharedTempDir.resolve("atomic.txt");
        String original = "Original";
        Files.writeString(file, original);
        String token = registerFullAccess(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "atomic.txt");
        params.put("accessToken", token);
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
        String token1 = registerFullAccess(file);

        ObjectNode p1 = mapper.createObjectNode();
        p1.put("path", "test.txt");
        p1.put("startLine", 1);
        p1.put("content", "A");
        p1.put("accessToken", token1);
        editTool.execute(p1);

        ObjectNode undoP = mapper.createObjectNode();
        undoP.put("action", "undo");
        sessionTool.execute(undoP);
        assertEquals("init", Files.readString(file));

        // После undo нужно получить новый токен
        String token2 = registerFullAccess(file);
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("path", "test.txt");
        p2.put("startLine", 1);
        p2.put("content", "B");
        p2.put("accessToken", token2);
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

        for (int i = 1; i <= 3; i++) {
            String token = registerFullAccess(file);
            ObjectNode p = mapper.createObjectNode();
            p.put("path", "history.txt");
            p.put("startLine", 1);
            p.put("content", String.valueOf(i));
            p.put("accessToken", token);
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
        LineAccessTracker.reset();

        Path f = tempDir.resolve("test.txt");
        Files.writeString(f, "init\n");

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

        ObjectNode cp1 = mapper.createObjectNode();
        cp1.put("action", "checkpoint");
        cp1.put("name", "PointA");
        sessionTool.execute(cp1);

        String token = registerFullAccess(file);
        ObjectNode edit = mapper.createObjectNode();
        edit.put("path", "check.txt");
        edit.put("startLine", 1);
        edit.put("content", "modified");
        edit.put("accessToken", token);
        editTool.execute(edit);
        assertEquals("modified", Files.readString(file));

        ObjectNode rb = mapper.createObjectNode();
        rb.put("action", "rollback");
        rb.put("name", "PointA");
        sessionTool.execute(rb);

        assertEquals("initial", Files.readString(file));
    }
}
