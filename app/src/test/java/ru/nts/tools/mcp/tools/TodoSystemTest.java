// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для системы планирования (nts_todo).
 */
class TodoSystemTest {

    private final TodoCreateTool createTool = new TodoCreateTool();
    private final TodoUpdateTool updateTool = new TodoUpdateTool();
    private final TodoStatusTool statusTool = new TodoStatusTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testTodoLifecycle(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);

        // 1. Create
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("title", "My Plan");
        p1.put("content", "- [ ] task 1");
        createTool.execute(p1);

        // 2. Status
        JsonNode s1 = statusTool.execute(mapper.createObjectNode());
        String text1 = s1.get("content").get(0).get("text").asText();
        assertTrue(text1.contains("task 1"));

        // 3. Update
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("content", "- [x] task 1\n- [ ] task 2");
        updateTool.execute(p2);

        // 4. Final status
        JsonNode s2 = statusTool.execute(mapper.createObjectNode());
        String text2 = s2.get("content").get(0).get("text").asText();
        assertTrue(text2.contains("task 2"));
        assertTrue(text2.contains("[x] task 1"));
    }
}