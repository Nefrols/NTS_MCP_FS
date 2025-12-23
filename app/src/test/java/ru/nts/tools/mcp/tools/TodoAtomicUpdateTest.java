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
 * Тесты для атомарного обновления пунктов плана.
 */
class TodoAtomicUpdateTest {

    private final TodoCreateTool createTool = new TodoCreateTool();
    private final TodoUpdateTool updateTool = new TodoUpdateTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testAtomicUpdate(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);

        // 1. Создаем план
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("title", "Plan");
        p1.put("content", "- [ ] task 1\n- [ ] task 2\n- [ ] task 3");
        createTool.execute(p1);

        // 2. Обновляем второй пункт
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("id", 2);
        p2.put("status", "done");
        p2.put("comment", "finished this");
        updateTool.execute(p2);

        // 3. Проверяем содержимое файла
        Path todoFile = Files.list(tempDir.resolve(".nts/todos")).findFirst().get();
        String content = Files.readString(todoFile);

        assertTrue(content.contains("- [ ] task 1"), "Первый пункт не должен измениться");
        assertTrue(content.contains("- [x] task 2 (finished this)"), "Второй пункт должен быть обновлен");
        assertTrue(content.contains("- [ ] task 3"), "Третий пункт не должен измениться");
    }
}