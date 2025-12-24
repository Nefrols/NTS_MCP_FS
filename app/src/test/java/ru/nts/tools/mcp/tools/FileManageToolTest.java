// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;
import ru.nts.tools.mcp.tools.fs.FileManageTool;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента управления файлами (FileManageTool).
 */
class FileManageToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FileManageTool tool = new FileManageTool();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset();
    }

    @Test
    void testFileManageActions() throws Exception {
        // Action: create
        ObjectNode pCreate = mapper.createObjectNode();
        pCreate.put("action", "create");
        pCreate.put("path", "new.txt");
        pCreate.put("content", "hello");
        tool.execute(pCreate);
        assertTrue(Files.exists(tempDir.resolve("new.txt")));

        // Action: move
        ObjectNode pMove = mapper.createObjectNode();
        pMove.put("action", "move");
        pMove.put("path", "new.txt");
        pMove.put("targetPath", "sub/moved.txt");
        tool.execute(pMove);
        assertTrue(Files.exists(tempDir.resolve("sub/moved.txt")));
        assertFalse(Files.exists(tempDir.resolve("new.txt")));

        // Action: rename
        ObjectNode pRename = mapper.createObjectNode();
        pRename.put("action", "rename");
        pRename.put("path", "sub/moved.txt");
        pRename.put("newName", "renamed.txt");
        tool.execute(pRename);
        assertTrue(Files.exists(tempDir.resolve("sub/renamed.txt")));
        assertFalse(Files.exists(tempDir.resolve("sub/moved.txt")));

        // Action: delete
        ObjectNode pDelete = mapper.createObjectNode();
        pDelete.put("action", "delete");
        pDelete.put("path", "sub/renamed.txt");
        tool.execute(pDelete);
        assertFalse(Files.exists(tempDir.resolve("sub/renamed.txt")));
    }
}