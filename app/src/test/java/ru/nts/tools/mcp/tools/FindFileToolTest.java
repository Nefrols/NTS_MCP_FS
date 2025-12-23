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
 * Тесты для инструмента nts_find_file.
 */
class FindFileToolTest {

    private final FindFileTool tool = new FindFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testFindByName(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createDirectories(tempDir.resolve("sub/folder"));
        Files.createFile(tempDir.resolve("sub/folder/target.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("pattern", "target.txt");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("sub/folder/target.txt"), "Файл должен быть найден по имени в любой подпапке");
    }

    @Test
    void testFindByGlob(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createDirectories(tempDir.resolve("app/src"));
        Files.createFile(tempDir.resolve("app/src/Main.java"));
        Files.createFile(tempDir.resolve("app/src/Utils.java"));
        Files.createFile(tempDir.resolve("README.md"));

        ObjectNode params = mapper.createObjectNode();
        params.put("pattern", "**/*.java");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("app/src/Main.java"));
        assertTrue(text.contains("app/src/Utils.java"));
        assertTrue(!text.contains("README.md"), "README не должен попасть в выборку .java");
    }
}