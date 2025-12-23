package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileExistsToolTest {
    private final FileExistsTool tool = new FileExistsTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testFileExists(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "content");

        var params = mapper.createObjectNode().put("path", "test.txt");
        JsonNode res = tool.execute(params);
        String text = res.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Exists: true"));
        assertTrue(text.contains("Type: File"));
    }

    @Test
    void testDirExists(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path dir = tempDir.resolve("subdir");
        Files.createDirectories(dir);

        var params = mapper.createObjectNode().put("path", "subdir");
        JsonNode res = tool.execute(params);
        String text = res.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Exists: true"));
        assertTrue(text.contains("Type: Directory"));
    }

    @Test
    void testNotExists(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        var params = mapper.createObjectNode().put("path", "missing.txt");
        JsonNode res = tool.execute(params);
        String text = res.get("content").get(0).get("text").asText();

        assertTrue(text.contains("Exists: false"));
    }
}
