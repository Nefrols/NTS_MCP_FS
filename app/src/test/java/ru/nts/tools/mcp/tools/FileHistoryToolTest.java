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
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileHistoryToolTest {
    private final FileHistoryTool historyTool = new FileHistoryTool();
    private final EditFileTool editTool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testHistoryTracking(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        
        Path file = tempDir.resolve("history.txt");
        Files.writeString(file, "line 1\n");
        AccessTracker.registerRead(file);

        // 1. Первая правка
        ObjectNode editParams = mapper.createObjectNode();
        editParams.put("path", "history.txt");
        editParams.put("instruction", "First change");
        editParams.put("oldText", "line 1");
        editParams.put("newText", "modified 1");
        editTool.execute(editParams);

        // 2. Вторая правка
        editParams.put("instruction", "Second change");
        editParams.put("oldText", "modified 1");
        editParams.put("newText", "final state");
        editTool.execute(editParams);

        // 3. Запрос истории
        var historyParams = mapper.createObjectNode().put("path", "history.txt");
        JsonNode res = historyTool.execute(historyParams);
        String text = res.get("content").get(0).get("text").asText();

        assertTrue(text.contains("First change"));
        assertTrue(text.contains("Second change"));
        assertTrue(text.contains("(+1/-1 lines)"));
    }
}
