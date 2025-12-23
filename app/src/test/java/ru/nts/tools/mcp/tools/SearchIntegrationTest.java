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
import ru.nts.tools.mcp.core.SearchTracker;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционный тест для проверки связи поиска и листинга директорий.
 */
class SearchIntegrationTest {

    private final SearchFilesTool searchTool = new SearchFilesTool();
    private final ListDirectoryTool listTool = new ListDirectoryTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testSearchMatchesInListing(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        SearchTracker.clear();

        // 1. Создаем файлы с разным количеством совпадений
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "target\ntarget\nother"); // 2 совпадения
        Files.writeString(file2, "target\nother");         // 1 совпадение

        // 2. Выполняем поиск
        ObjectNode searchParams = mapper.createObjectNode();
        searchParams.put("path", ".");
        searchParams.put("query", "target");
        searchTool.execute(searchParams);

        // 3. Проверяем листинг
        ObjectNode listParams = mapper.createObjectNode();
        listParams.put("path", ".");
        JsonNode listResult = listTool.execute(listParams);
        String listText = listResult.get("content").get(0).get("text").asText();

        assertTrue(listText.contains("file1.txt [MATCHES: 2]"), "Листинг должен содержать маркер совпадений для file1");
        assertTrue(listText.contains("file2.txt [MATCHES: 1]"), "Листинг должен содержать маркер совпадений для file2");

        // 4. Очищаем кэш и проверяем снова
        ObjectNode resetParams = mapper.createObjectNode();
        resetParams.put("path", ".");
        resetParams.put("query", "");
        resetParams.put("resetCache", true);
        searchTool.execute(resetParams);

        JsonNode cleanListResult = listTool.execute(listParams);
        String cleanListText = cleanListResult.get("content").get(0).get("text").asText();

        assertFalse(cleanListText.contains("[MATCHES:"), "После сброса кэша маркеры должны исчезнуть");
    }
}