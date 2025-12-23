// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты механизма нечеткого сопоставления текста (Fuzzy Matching) в EditFileTool.
 * Проверяют устойчивость инструмента к типичным ошибкам LLM:
 * 1. Различия в количестве пробелов и табуляций.
 * 2. Различия в типах окончаний строк (LF vs CRLF).
 */
class FuzzyEditTest {

    /**
     * Тестируемый инструмент.
     */
    private final EditFileTool tool = new EditFileTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Проверяет игнорирование избыточных пробелов и табуляций при поиске заменяемого текста.
     * Убеждается, что запрос с нормализованными пробелами находит текст с оригинальным сложным форматированием.
     */
    @Test
    void testFuzzyWhitespace(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.java");
        // Исходный файл содержит специфические отступы (табы и группы пробелов)
        Files.writeString(file, "public    void    test() {\n\treturn;\n}");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        // Модель присылает "чистый" текст без учета точного количества пробелов
        params.put("oldText", "public void test() {");
        params.put("newText", "public void replacement() {");

        tool.execute(params);

        String content = Files.readString(file);
        assertTrue(content.contains("public void replacement() {"), "Текст должен быть заменен несмотря на разницу в пробелах");
        assertTrue(content.contains("return;"), "Остальное содержимое файла должно сохраниться");
    }

    /**
     * Проверяет независимость поиска от типов переноса строк.
     * Убеждается, что текст, найденный с использованием \n (LF), успешно заменяется в файле с \r\n (CRLF).
     */
    @Test
    void testLineEndingAgnostic(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        // Создаем файл с Windows-переносами (CRLF)
        Files.write(file, "Line 1\r\nLine 2\r\nLine 3".getBytes());
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        // Модель присылает текст с Unix-переносами (LF)
        params.put("oldText", "Line 1\nLine 2");
        params.put("newText", "Modified lines");

        tool.execute(params);

        String content = Files.readString(file);
        // Результат должен быть консистентным
        assertTrue(content.startsWith("Modified lines\nLine 3"), "Замена должна сработать для разных типов переносов");
    }
}