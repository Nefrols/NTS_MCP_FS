// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {
    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReplaceText(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "World");
        params.put("newText", "Java");

        tool.execute(params);
        assertEquals("Hello Java", Files.readString(file));
    }

    @Test
    void testMultiFileBatchEdit(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        
        Path f1 = tempDir.resolve("file1.txt");
        Path f2 = tempDir.resolve("file2.txt");
        Files.writeString(f1, "Original 1");
        Files.writeString(f2, "Original 2");
        AccessTracker.registerRead(f1);
        AccessTracker.registerRead(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        ObjectNode e1 = edits.addObject();
        e1.put("path", f1.toString());
        e1.put("oldText", "Original 1");
        e1.put("newText", "Changed 1");

        ObjectNode e2 = edits.addObject();
        e2.put("path", f2.toString());
        e2.put("oldText", "Original 2");
        e2.put("newText", "Changed 2");

        tool.execute(params);

        assertEquals("Changed 1", Files.readString(f1));
        assertEquals("Changed 2", Files.readString(f2));
    }

    @Test
    void testMultiFileRollback(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path f1 = tempDir.resolve("file1.txt");
        Path f2 = tempDir.resolve("file2.txt");
        Files.writeString(f1, "Safe");
        Files.writeString(f2, "Danger");
        AccessTracker.registerRead(f1);
        AccessTracker.registerRead(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        // Первая правка валидна
        ObjectNode e1 = edits.addObject();
        e1.put("path", f1.toString());
        e1.put("oldText", "Safe");
        e1.put("newText", "Broken");

        // Вторая правка содержит ошибку контроля
        ObjectNode e2 = edits.addObject();
        e2.put("path", f2.toString());
        e2.put("startLine", 1);
        e2.put("endLine", 1);
        e2.put("expectedContent", "WRONG_CONTENT");
        e2.put("newText", "ShouldNotChange");

        assertThrows(IllegalStateException.class, () -> tool.execute(params));

        // Оба файла должны остаться в исходном состоянии
        assertEquals("Safe", Files.readString(f1));
        assertEquals("Danger", Files.readString(f2));
    }

    @Test
    void testExpectedContentFailure(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("AAA", "BBB", "CCC"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 2);
        params.put("expectedContent", "WRONG");
        params.put("newText", "XXX");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("ACTUAL CONTENT IN RANGE 2-2:"));
        assertTrue(ex.getMessage().contains("[BBB]"));
    }

    @Test
    void testBatchOperationsWithOffsets(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("batch.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode ops = params.putArray("operations");

        ObjectNode op1 = ops.addObject();
        op1.put("operation", "replace");
        op1.put("startLine", 1);
        op1.put("endLine", 1);
        op1.put("content", "A\nB\nC");

        ObjectNode op2 = ops.addObject();
        op2.put("operation", "delete");
        op2.put("startLine", 2);
        op2.put("endLine", 2);

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals(4, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
        assertEquals("C", result.get(2));
        assertEquals("Line 3", result.get(3));
    }

    @Test
    void testInsertAfter(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("insert.txt");
        Files.write(file, List.of("Start", "End"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode ops = params.putArray("operations");

        ObjectNode op = ops.addObject();
        op.put("operation", "insert_after");
        op.put("line", 1);
        op.put("content", "Middle");

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals(3, result.size());
        assertEquals("Start", result.get(0));
        assertEquals("Middle", result.get(1));
        assertEquals("End", result.get(2));
    }

    @Test
    void testBatchAtomicity(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("atomic.txt");
        String originalContent = "Line 1\nLine 2\nLine 3";
        Files.writeString(file, originalContent);
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode ops = params.putArray("operations");

        ObjectNode op1 = ops.addObject();
        op1.put("operation", "replace");
        op1.put("startLine", 1);
        op1.put("endLine", 1);
        op1.put("content", "MODIFIED");

        ObjectNode op2 = ops.addObject();
        op2.put("operation", "replace");
        op2.put("startLine", 2);
        op2.put("endLine", 2);
        op2.put("expectedContent", "WRONG_STUFF");
        op2.put("content", "SHOULD_NOT_HAPPEN");

        assertThrows(IllegalStateException.class, () -> tool.execute(params));

                String currentContent = Files.readString(file).replace("\r", "");

                assertEquals(originalContent, currentContent);

            }

        

            @Test

            void testComplexMultiFileBatch(@TempDir Path tempDir) throws Exception {

                PathSanitizer.setRoot(tempDir);

                TransactionManager.reset();

                

                Path f1 = tempDir.resolve("f1.txt");

                Path f2 = tempDir.resolve("f2.txt");

                Files.writeString(f1, "A\nB\nC");

                Files.writeString(f2, "1\n2\n3");

                AccessTracker.registerRead(f1);

                AccessTracker.registerRead(f2);

        

                ObjectNode params = mapper.createObjectNode();

                ArrayNode edits = params.putArray("edits");

        

                // Файл 1: Удаляем B, вставляем X после A

                ObjectNode e1 = edits.addObject();

                e1.put("path", f1.toString());

                ArrayNode ops1 = e1.putArray("operations");

                ops1.addObject().put("operation", "delete").put("startLine", 2).put("endLine", 2);

                ops1.addObject().put("operation", "insert_after").put("line", 1).put("content", "X");

        

                // Файл 2: Заменяем 2 на "TWO"

                ObjectNode e2 = edits.addObject();

                e2.put("path", f2.toString());

                e2.put("startLine", 2);

                e2.put("endLine", 2);

                e2.put("newText", "TWO");

        

                tool.execute(params);

        

                assertEquals("A\nX\nC", Files.readString(f1).replace("\r", ""));

                assertEquals("1\nTWO\n3", Files.readString(f2).replace("\r", ""));

            }

        

            @Test

            void testMultiFileRollbackOnSecurityException(@TempDir Path tempDir) throws Exception {

                PathSanitizer.setRoot(tempDir);

                TransactionManager.reset();

        

                Path safeFile = tempDir.resolve("safe.txt");

                Files.writeString(safeFile, "Safe Content");

                AccessTracker.registerRead(safeFile);

        

                ObjectNode params = mapper.createObjectNode();

                ArrayNode edits = params.putArray("edits");

        

                // 1. Валидная правка

                ObjectNode e1 = edits.addObject();

                e1.put("path", safeFile.toString());

                e1.put("oldText", "Safe");

                e1.put("newText", "Hacked");

        

                // 2. Попытка изменить защищенный файл проекта

                ObjectNode e2 = edits.addObject();

                e2.put("path", "gradlew"); // PathSanitizer его защищает

                e2.put("oldText", "any");

                e2.put("newText", "bad");

        

                assertThrows(SecurityException.class, () -> tool.execute(params));

        

                // Первый файл не должен измениться!

                assertEquals("Safe Content", Files.readString(safeFile));

            }

        

            @Test

            void testMultiFileRollbackOnAccessError(@TempDir Path tempDir) throws Exception {

                PathSanitizer.setRoot(tempDir);

                TransactionManager.reset();

        

                Path f1 = tempDir.resolve("f1.txt");

                Path f2 = tempDir.resolve("f2.txt");

                Files.writeString(f1, "Content 1");

                Files.writeString(f2, "Content 2");

                

                // Регистрируем ТОЛЬКО первый файл

                AccessTracker.registerRead(f1);

        

                ObjectNode params = mapper.createObjectNode();

                ArrayNode edits = params.putArray("edits");

        

                edits.addObject().put("path", f1.toString()).put("oldText", "Content 1").put("newText", "Modified");

                edits.addObject().put("path", f2.toString()).put("oldText", "Content 2").put("newText", "Modified");

        

                // Должно упасть на втором файле

                assertThrows(SecurityException.class, () -> tool.execute(params));

        

                // Первый файл должен откатиться

                assertEquals("Content 1", Files.readString(f1));

            }

        }

        