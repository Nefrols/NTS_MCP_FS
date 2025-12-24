// Aristo 24.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.tools.system.TaskTool;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента управления задачами (TaskTool).
 */
class TaskToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskTool tool = new TaskTool();

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
    }

    @Test
    void testUnknownTask() throws Exception {
        ObjectNode pLog = mapper.createObjectNode();
        pLog.put("action", "log");
        pLog.put("taskId", "none");
        JsonNode res = tool.execute(pLog);
        assertTrue(res.get("content").get(0).get("text").asText().contains("not found"));
    }
}
