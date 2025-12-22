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
    void testReplaceLines(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3", "Line 4"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 3);
        params.put("newText", "New Line 2 and 3");

        tool.execute(params);
        
        List<String> actualLines = Files.readAllLines(file);
        assertEquals(3, actualLines.size());
        assertEquals("Line 1", actualLines.get(0));
        assertEquals("New Line 2 and 3", actualLines.get(1));
        assertEquals("Line 4", actualLines.get(2));
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
        assertTrue(ex.getMessage().contains("АКТУАЛЬНОЕ СОДЕРЖИМОЕ В ДИАПАЗОНЕ 2-2:"));
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

        // Операция 1: Заменяем Line 1 на 3 строки
        ObjectNode op1 = ops.addObject();
        op1.put("operation", "replace");
        op1.put("startLine", 1);
        op1.put("endLine", 1);
        op1.put("content", "A\nB\nC");

        // Операция 2: Удаляем Line 2. 
        // В оригинале это строка 2. После первой операции Line 2 стала строкой 4.
        // Сервер должен сам вычислить offset (+2 строки).
        ObjectNode op2 = ops.addObject();
        op2.put("operation", "delete");
        op2.put("startLine", 2);
        op2.put("endLine", 2);

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        // Ожидаем: A, B, C (вместо Line 1), Line 3 (Line 2 удалена)
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

        // Операция 1: Валидная замена
        ObjectNode op1 = ops.addObject();
        op1.put("operation", "replace");
        op1.put("startLine", 1);
        op1.put("endLine", 1);
        op1.put("content", "MODIFIED");

        // Операция 2: Ошибочная (неверный expectedContent)
        ObjectNode op2 = ops.addObject();
        op2.put("operation", "replace");
        op2.put("startLine", 2);
        op2.put("endLine", 2);
        op2.put("expectedContent", "WRONG_STUFF");
        op2.put("content", "SHOULD_NOT_HAPPEN");

        // Выполнение должно упасть
        assertThrows(IllegalStateException.class, () -> tool.execute(params));

        // Файл должен остаться нетронутым (Line 1 не должна стать MODIFIED)
        String currentContent = Files.readString(file).replace("\r", "");
        assertEquals(originalContent, currentContent, "Файл не должен измениться, если одна из операций батча провалена");
    }
}
