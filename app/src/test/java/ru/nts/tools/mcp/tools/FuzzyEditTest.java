// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FuzzyEditTest {
    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testFuzzyWhitespace(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.java");
        // В файле табы и много пробелов
        Files.writeString(file, "public    void    test() {\n\treturn;\n}");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        // LLM присылает один пробел вместо многих
        params.put("oldText", "public void test() {");
        params.put("newText", "public void replacement() {");

        tool.execute(params);
        
        String content = Files.readString(file);
        assertTrue(content.contains("public void replacement() {"));
        assertTrue(content.contains("return;"));
    }

    @Test
    void testLineEndingAgnostic(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        // Файл с CRLF
        Files.write(file, "Line 1\r\nLine 2\r\nLine 3".getBytes());
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        // LLM присылает с LF
        params.put("oldText", "Line 1\nLine 2");
        params.put("newText", "Modified lines");

        tool.execute(params);
        
        String content = Files.readString(file);
        assertTrue(content.startsWith("Modified lines\nLine 3"));
    }
}
