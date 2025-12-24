// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.tools.external.GitCombinedTool;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для консолидированного инструмента Git (GitCombinedTool).
 */
class GitCombinedToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GitCombinedTool tool = new GitCombinedTool();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
    }

    @Test
    void testForbiddenCmd() {
        ObjectNode pCmd = mapper.createObjectNode();
        pCmd.put("action", "cmd");
        pCmd.put("command", "push");
        assertThrows(SecurityException.class, () -> tool.execute(pCmd));
    }
}
