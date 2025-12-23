// Aristo 22.12.2025
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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента чтения файлов (ReadFileTool).
 * Проверяют корректность извлечения содержимого, формирование заголовка метаданных,
 * поддержку диапазонов строк и умного чтения вокруг контекста.
 */
class ReadFileToolTest {

    /**
     * Тестируемый инструмент чтения.
     */
    private final ReadFileTool tool = new ReadFileTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует полное чтение файла.
     * Проверяет наличие всех полей метаданных (SIZE, CHARS, LINES, CRC32C) в ответе.
     */
    @Test
    void testReadFull(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        String content = "Line 1\nLine 2\nLine 3";
        Files.writeString(file, content);

        JsonNode params = mapper.createObjectNode().put("path", file.toString());
        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Верификация формата заголовка
        assertTrue(text.contains("[FILE: test.txt"), "Заголовок должен содержать имя файла");
        assertTrue(text.contains("SIZE:"), "Заголовок должен содержать размер");
        assertTrue(text.contains("CHARS:"), "Заголовок должен содержать кол-во символов");
        assertTrue(text.contains("LINES:"), "Заголовок должен содержать кол-во строк");
        assertTrue(text.contains("CRC32C:"), "Заголовок должен содержать контрольную сумму");
        assertTrue(text.endsWith(content), "Тело ответа должно содержать контент файла");
    }

    /**
     * Тестирует чтение одной конкретной строки.
     */
    @Test
    void testReadLine(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("line", 2);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.endsWith("Line 2"), "Должна быть возвращена только вторая строка");
    }

    /**
     * Тестирует чтение заданного диапазона строк (inclusive).
     */
    @Test
    void testReadRange(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Line 1\nLine 2\nLine 3\nLine 4");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2);
        params.put("endLine", 3);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.endsWith("Line 2\nLine 3"), "Диапазон строк должен быть извлечен верно");
    }

    /**
     * Тестирует умное чтение вокруг найденного паттерна.
     * Проверяет захват окружающего контекста (range).
     */
    @Test
    void testReadContext(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("context.txt");
        Files.writeString(file, "1\n2\n3\nTARGET\n5\n6\n7");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("contextStartPattern", "TARGET");
        params.put("contextRange", 2);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        // Ожидаем TARGET и по 2 строки сверху и снизу
        assertTrue(text.endsWith("2\n3\nTARGET\n5\n6"), "Окружающий контекст должен быть захвачен верно");
    }

    /**
     * Тестирует поведение при чтении контекста на границах файла.
     * Проверяет, что система не падает при выходе за пределы 1-й строки или конца файла.
     */
    @Test
    void testReadContextBoundaries(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("bound.txt");
        Files.writeString(file, "TOP\n2\n3");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("contextStartPattern", "TOP");
        params.put("contextRange", 5);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        // Ожидаем всё содержимое от начала до конца
        assertTrue(text.endsWith("TOP\n2\n3"), "Границы файла должны обрабатываться корректно");
    }
}