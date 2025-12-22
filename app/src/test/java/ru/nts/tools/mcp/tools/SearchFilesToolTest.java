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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента полнотекстового поиска (SearchFilesTool).
 * Проверяют:
 * 1. Базовый поиск строк и поддержку регулярных выражений.
 * 2. Вывод строк контекста (before/after).
 * 3. Производительность при параллельном сканировании большого количества файлов.
 * 4. Визуальную индикацию прочитанных файлов ([READ]).
 */
class SearchFilesToolTest {

    /**
     * Тестируемый инструмент поиска.
     */
    private final SearchFilesTool tool = new SearchFilesTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует базовый поиск строки.
     * Проверяет корректность вывода номера строки и сохранение отступов.
     */
    @Test
    void testSearch(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        // Файл с отступом в 4 пробела
        Files.writeString(tempDir.resolve("file1.txt"), "    indented string");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("query", "indented");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("file1.txt:"), "Отчет должен содержать имя файла");
        // Проверка формата: номер строки | текст (с учетом добавленного пробела в префикс)
        assertTrue(text.contains("1|     indented string"), "Должна быть найдена первая строка с сохранением отступа");
    }

    /**
     * Тестирует поиск с выводом окружающего контекста.
     */
    @Test
    void testSearchWithContext(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("context.txt"), "line 1\nline 2\nTARGET\nline 4\nline 5");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("query", "TARGET");
        params.put("beforeContext", 1);
        params.put("afterContext", 1);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Верификация маркеров: '|' для совпадения, ':' для контекста
        assertTrue(text.contains("2: line 2"), "Должна присутствовать строка контекста ДО");
        assertTrue(text.contains("3| TARGET"), "Должна присутствовать целевая строка");
        assertTrue(text.contains("4: line 4"), "Должна присутствовать строка контекста ПОСЛЕ");
        assertFalse(text.contains("1: line 1"), "Строки вне диапазона range не должны выводиться");
    }

    /**
     * Стресс-тест параллельного поиска.
     * Проверяет стабильность работы виртуальных потоков при сканировании 100 файлов одновременно.
     */
    @Test
    void testParallelSearchStress(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        int fileCount = 100;
        for (int i = 0; i < fileCount; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), (i % 10 == 0) ? "matching string" : "random content");
        }

        ObjectNode params = mapper.createObjectNode();
        params.put("path", tempDir.toString());
        params.put("query", "matching");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Каждое 10-е вхождение совпадает (0, 10... 90) = 10 файлов
        assertTrue(text.contains("(10)"), "Должно быть найдено ровно 10 совпадений");
        for (int i = 0; i < 100; i += 10) {
            assertTrue(text.contains("file" + i + ".txt"), "Файл " + i + " должен быть в списке");
        }
    }

    /**
     * Тестирует поиск с использованием регулярных выражений.
     */
    @Test
    void testRegexSearch(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.writeString(tempDir.resolve("test1.txt"), "The quick brown fox");
        Files.writeString(tempDir.resolve("test2.txt"), "The slow red fox");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", tempDir.toString());
        params.put("query", "q...k"); // Регулярка для слова 'quick'
        params.put("isRegex", true);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("test1.txt"), "Файл с совпадением должен быть найден");
        assertFalse(text.contains("test2.txt"), "Файл без совпадения должен быть пропущен");
    }

    /**
     * Тестирует отображение маркера [READ] в результатах поиска.
     */
    @Test
    void testSearchReadMarker(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("known.txt");
        Files.writeString(file, "target string");

        // Предварительная регистрация в трекере
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("query", "target");

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("known.txt [READ]:"), "Файл должен быть помечен как прочитанный в сессии");
    }
}
