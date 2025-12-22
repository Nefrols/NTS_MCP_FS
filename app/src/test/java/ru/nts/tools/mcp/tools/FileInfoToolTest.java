// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileInfoToolTest {
    private final FileInfoTool tool = new FileInfoTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testExecute(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        String contentStr = "Hello World\nLine 2";
        Files.writeString(file, contentStr);
        long expectedSize = Files.size(file);

        JsonNode params = mapper.createObjectNode().put("path", file.toString());
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("Размер: " + expectedSize + " байт"));
        assertTrue(text.contains("Строк: 2"));
        assertTrue(text.contains("CRC32:"));
    }

    @Test
    void testFileNotFound() {
        JsonNode params = mapper.createObjectNode().put("path", "non_existent_file.txt");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(params));
    }
}
