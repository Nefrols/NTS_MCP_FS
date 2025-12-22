// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UndoRedoTest {
    private final EditFileTool editTool = new EditFileTool();
    private final DeleteFileTool deleteTool = new DeleteFileTool();
    private final MoveFileTool moveTool = new MoveFileTool();
    private final UndoTool undoTool = new UndoTool();
    private final RedoTool redoTool = new RedoTool();
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
        params.put("path", "to_delete.txt");
        deleteTool.execute(params);
        assertFalse(Files.exists(file));

        undoTool.execute(mapper.createObjectNode());
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
        params.put("sourcePath", "source.txt");
        params.put("targetPath", "sub/target.txt");
        moveTool.execute(params);

        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target));

        undoTool.execute(mapper.createObjectNode());
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

        undoTool.execute(mapper.createObjectNode());
        assertEquals("init", Files.readString(file));

        ObjectNode p2 = mapper.createObjectNode();
        p2.put("path", "test.txt");
        p2.put("oldText", "init");
        p2.put("newText", "B");
        editTool.execute(p2);

        JsonNode redoResult = redoTool.execute(mapper.createObjectNode());
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

        undoTool.execute(mapper.createObjectNode());
        undoTool.execute(mapper.createObjectNode());
        undoTool.execute(mapper.createObjectNode());
        assertEquals("0", Files.readString(file));

                redoTool.execute(mapper.createObjectNode());

                redoTool.execute(mapper.createObjectNode());

                assertEquals("2", Files.readString(file));

            }

        

            @Test

            void testTransactionJournal(@TempDir Path tempDir) throws Exception {

                PathSanitizer.setRoot(tempDir);

                TransactionManager.reset();

                

                Path f = tempDir.resolve("test.txt");

                Files.writeString(f, "init");

                AccessTracker.registerRead(f);

        

                // Делаем одну операцию

                ObjectNode p = mapper.createObjectNode();

                p.put("path", "test.txt");

                p.put("oldText", "init");

                p.put("newText", "new");

                editTool.execute(p);

        

                        // Проверяем журнал

        

                        TransactionJournalTool journalTool = new TransactionJournalTool();

        

                        JsonNode result = journalTool.execute(mapper.createObjectNode());

        

                        String text = result.get("content").get(0).get("text").asText();

        

                        

        

                                assertTrue(text.contains("=== TRANSACTION JOURNAL ==="));

        

                        

        

                                assertTrue(text.contains("Edit file: test.txt"));

        

                        

        

                                assertTrue(text.contains("=== ACTIVE SESSION CONTEXT ==="));

        

                        

        

                                assertTrue(text.contains("- test.txt"));

        

                        

        

                            }

        

                        

        

                        }

        

                        

        

                        

        