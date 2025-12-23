// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента создания файлов (CreateFileTool).
 */
class CreateFileToolTest {

    private final CreateFileTool tool = new CreateFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testCreateFileWithListing(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("existing.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "new.txt");
        params.put("content", "hello");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("File created/overwritten successfully: new.txt"));
        assertTrue(text.contains("New CRC32C:"));
        assertTrue(text.contains("[FILE] existing.txt"));
        assertTrue(text.contains("[FILE] new.txt"));
    }

    @Test
    void testOverwriteWithHash(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("overwrite.txt");
        Files.writeString(file, "old content");
        String oldHash = Long.toHexString(FileUtils.calculateCRC32(file)).toUpperCase();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "overwrite.txt");
        params.put("content", "new content");
        params.put("expectedChecksum", oldHash);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("File created/overwritten successfully"));
        assertTrue(Files.readString(file).equals("new content"));
    }

    @Test
    void testOverwriteFailsWithoutHash(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("fail.txt"), "data");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "fail.txt");
        params.put("content", "new data");

        assertThrows(SecurityException.class, () -> tool.execute(params), 
            "Должно быть выброшено исключение при попытке перезаписи без хеша");
    }
}