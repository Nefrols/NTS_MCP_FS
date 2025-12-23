// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для проверки инъекции AI-HUD в вывод инструментов.
 */
class HudInjectionTest {

    private final ListDirectoryTool listTool = new ListDirectoryTool();
    private final TodoCreateTool createTodoTool = new TodoCreateTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testHudPresence(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);

        // 1. Сначала без плана
        JsonNode res1 = listTool.executeWithFeedback(mapper.createObjectNode().put("path", "."));
        JsonNode content1 = res1.get("content");
        
        // Ожидаем 2 элемента: HUD и листинг
        assertTrue(content1.size() >= 2, "Массив контента должен содержать как минимум 2 элемента (HUD + Result)");
        String hud1 = content1.get(0).get("text").asText();
        assertTrue(hud1.contains("[HUD] No active plan."), "Первый элемент должен быть HUD");

        // 2. Создаем план
        ObjectNode p = mapper.createObjectNode();
        p.put("title", "Global Goal");
        p.put("content", "- [ ] Step 1");
        createTodoTool.executeWithFeedback(p);

        // 3. Проверяем HUD снова
        JsonNode res2 = listTool.executeWithFeedback(mapper.createObjectNode().put("path", "."));
        JsonNode content2 = res2.get("content");
        String hud2 = content2.get(0).get("text").asText();
        
        assertTrue(hud2.contains("[HUD] Plan: Global Goal"), "HUD должен содержать заголовок плана");
        assertTrue(hud2.contains("Progress: 0/1"), "HUD должен содержать прогресс");
        assertTrue(hud2.contains("Next: Step 1"), "HUD должен содержать следующую задачу");
    }
}