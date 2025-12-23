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
 * Тесты для проверки логики Peeking (Head/Tail) в FileInfoTool.
 */
class FileInfoPeekingTest {

    private final FileInfoTool tool = new FileInfoTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testPeeking(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("peek.txt");
        
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            content.append("Line ").append(i).append("\n");
        }
        Files.writeString(file, content.toString());

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "peek.txt");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Проверка Head
        assertTrue(text.contains("### Head (First 5 lines):"));
        assertTrue(text.contains("Line 1"));
        assertTrue(text.contains("Line 5"));
        
        // Проверка Tail
        assertTrue(text.contains("### Tail (Last 5 lines):"));
        assertTrue(text.contains("Line 16"));
        assertTrue(text.contains("Line 20"));
    }
}