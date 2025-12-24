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

    private final TodoTool todoTool = new TodoTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testTodoLifecycle(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);

        // 1. Create
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("action", "create");
        p1.put("title", "My Plan");
        p1.put("content", "- [ ] task 1");
        todoTool.execute(p1);

        // 2. Status
        ObjectNode pStatus = mapper.createObjectNode();
        pStatus.put("action", "status");
        JsonNode s1 = todoTool.execute(pStatus);
        String text1 = s1.get("content").get(0).get("text").asText();
        assertTrue(text1.contains("task 1"));

        // 3. Update
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("action", "update");
        p2.put("content", "- [x] task 1\n- [ ] task 2");
        todoTool.execute(p2);

        // 4. Final status
        JsonNode s2 = todoTool.execute(pStatus);
        String text2 = s2.get("content").get(0).get("text").asText();
        assertTrue(text2.contains("task 2"));
        assertTrue(text2.contains("[x] task 1"));
    }
}
