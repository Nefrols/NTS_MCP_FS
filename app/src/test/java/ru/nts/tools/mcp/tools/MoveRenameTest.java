// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MoveRenameTest {
    private final MoveFileTool moveTool = new MoveFileTool();
    private final RenameFileTool renameTool = new RenameFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testRenameWithoutPreviousRead(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "content");
        // Чтение НЕ регистрируем

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "old.txt");
        params.put("newName", "new.txt");

        renameTool.execute(params);

        assertFalse(Files.exists(file));
        assertTrue(Files.exists(tempDir.resolve("new.txt")));
    }

    @Test
    void testMoveAndPreserveAccessStatus(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path source = tempDir.resolve("file.txt");
        Files.writeString(source, "content");
        
        // Сначала читаем
        AccessTracker.registerRead(source);
        assertTrue(AccessTracker.hasBeenRead(source));

        // Затем перемещаем
        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "file.txt");
        params.put("targetPath", "moved.txt");
        moveTool.execute(params);

        Path target = tempDir.resolve("moved.txt");
        assertTrue(Files.exists(target));
        // Проверяем, что статус прочтения переехал
        assertTrue(AccessTracker.hasBeenRead(target), "Статус прочтения должен сохраниться после перемещения");
    }

    @Test
    void testMoveProtection(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        
        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "file.txt");
        params.put("targetPath", "../outside.txt");

        // PathSanitizer должен отсечь попытку выхода за корень
        assertThrows(SecurityException.class, () -> moveTool.execute(params));
    }
}