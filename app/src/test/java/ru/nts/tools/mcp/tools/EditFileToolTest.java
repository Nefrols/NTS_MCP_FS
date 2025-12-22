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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EditFileToolTest {
    private final EditFileTool tool = new EditFileTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testReplaceText(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "World");
        params.put("newText", "Java");

        tool.execute(params);
        assertEquals("Hello Java", Files.readString(file));
    }

    @Test
    void testReplaceLines(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3", "Line 4"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 3);
        params.put("newText", "New Line 2 and 3");

        tool.execute(params);
        
        List<String> actualLines = Files.readAllLines(file);
        assertEquals(3, actualLines.size());
        assertEquals("Line 1", actualLines.get(0));
        assertEquals("New Line 2 and 3", actualLines.get(1));
        assertEquals("Line 4", actualLines.get(2));
    }

    @Test
    void testExpectedContentSuccess(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("AAA", "BBB", "CCC"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 2);
        params.put("expectedContent", "BBB");
        params.put("newText", "XXX");

        tool.execute(params);
        assertEquals("AAA\nXXX\nCCC\n", Files.readString(file).replace("\r", ""));
    }

    @Test
    void testContextAddressing(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.java");
        Files.write(file, List.of(
            "public class Test {",
            "    public void method() {",
            "        System.out.println(\"Old\");",
            "    }",
            "}"
        ));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("contextStartPattern", "void method");
        params.put("startLine", 2); // Строка внутри метода
        params.put("endLine", 2);
        params.put("expectedContent", "        System.out.println(\"Old\");");
        params.put("newText", "        System.out.println(\"New\");");

        tool.execute(params);
        
        String content = Files.readString(file).replace("\r", "");
        assertTrue(content.contains("System.out.println(\"New\");"));
        assertFalse(content.contains("System.out.println(\"Old\");"));
    }

    @Test
    void testAutoIndentation(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.java");
        Files.write(file, List.of(
            "public class Test {",
            "    public void method() {",
            "    }"
        ));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 3); // Вставляем перед закрывающей скобкой
        params.put("endLine", 2);   // По сути вставка между 2 и 3
        params.put("newText", "System.out.println(\"Hi\");\nreturn;");

        tool.execute(params);
        
        List<String> actualLines = Files.readAllLines(file);
        // Ожидаем, что новые строки получили отступ в 4 пробела от строки 2
        assertEquals("    System.out.println(\"Hi\");", actualLines.get(2));
        assertEquals("    return;", actualLines.get(3));
    }

    @Test
    void testAutoIndentationWithTabs(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.java");
        Files.writeString(file, "class T {\n\tvoid m() {\n\t}");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 3);
        params.put("endLine", 2);
        params.put("newText", "int x;");

        tool.execute(params);
        
        String content = Files.readString(file);
        assertTrue(content.contains("\tvoid m() {\n\tint x;\n\t}"));
    }
}
