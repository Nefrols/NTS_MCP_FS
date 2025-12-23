// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента контрольных точек (nts_checkpoint).
 */
class CheckpointTest {

    private final CreateFileTool createTool = new CreateFileTool();
    private final CheckpointTool checkpointTool = new CheckpointTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testCheckpointRollback(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        // 1. Создаем первый файл и чекпоинт
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("path", "file1.txt"); p1.put("content", "one");
        createTool.execute(p1);
        
        ObjectNode cp1 = mapper.createObjectNode();
        cp1.put("name", "point1");
        checkpointTool.execute(cp1);

        // 2. Создаем еще два файла
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("path", "file2.txt"); p2.put("content", "two");
        createTool.execute(p2);

        ObjectNode p3 = mapper.createObjectNode();
        p3.put("path", "file3.txt"); p3.put("content", "three");
        createTool.execute(p3);

        assertTrue(Files.exists(tempDir.resolve("file1.txt")));
        assertTrue(Files.exists(tempDir.resolve("file2.txt")));
        assertTrue(Files.exists(tempDir.resolve("file3.txt")));

        // 3. Откатываемся к point1
        ObjectNode rb = mapper.createObjectNode();
        rb.put("name", "point1");
        rb.put("rollback", true);
        checkpointTool.execute(rb);

        // file1 должен остаться (он был до чекпоинта), остальные удалиться
        assertTrue(Files.exists(tempDir.resolve("file1.txt")));
        assertFalse(Files.exists(tempDir.resolve("file2.txt")));
        assertFalse(Files.exists(tempDir.resolve("file3.txt")));
    }
}