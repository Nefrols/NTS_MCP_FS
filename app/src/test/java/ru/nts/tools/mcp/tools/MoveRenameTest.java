// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструментов перемещения и переименования файлов.
 */
class MoveRenameTest {
    private final MoveFileTool moveTool = new MoveFileTool();
    private final RenameFileTool renameTool = new RenameFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testRenameWithoutPreviousRead(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "content");
        AccessTracker.reset();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "old.txt");
        params.put("newName", "new.txt");

        JsonNode result = renameTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertFalse(Files.exists(file));
        assertTrue(Files.exists(tempDir.resolve("new.txt")));
        assertTrue(text.contains("Directory content"));
        assertTrue(text.contains("[FILE] new.txt"));
    }

    @Test
    void testMoveAndPreserveAccessStatus(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path source = tempDir.resolve("file.txt");
        Files.writeString(source, "content");
        AccessTracker.reset();
        
        // Сначала регистрируем чтение
        AccessTracker.registerRead(source);
        assertTrue(AccessTracker.hasBeenRead(source));

        // Затем перемещаем
        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "file.txt");
        params.put("targetPath", "dest/moved.txt");
        JsonNode result = moveTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        Path target = tempDir.resolve("dest/moved.txt");
        assertTrue(Files.exists(target));
        // Статус прочтения должен переехать вместе с файлом
        assertTrue(AccessTracker.hasBeenRead(target), "Read status must be preserved after move");
        assertTrue(text.contains("Directory content"));
        assertTrue(text.contains("[FILE] moved.txt"));
    }

    @Test
    void testMoveProtection(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        
        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "file.txt");
        params.put("targetPath", "../outside.txt");

        // Проверка безопасности через PathSanitizer
        assertThrows(SecurityException.class, () -> moveTool.execute(params));
    }
}