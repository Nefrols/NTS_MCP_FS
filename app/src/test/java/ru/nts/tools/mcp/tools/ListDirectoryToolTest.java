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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструмента листинга директорий (ListDirectoryTool).
 * Проверяют корректность формирования дерева файлов, поддержку рекурсии
 * и визуализацию статуса прочитанных файлов.
 */
class ListDirectoryToolTest {

    /**
     * Тестируемый инструмент.
     */
    private final ListDirectoryTool tool = new ListDirectoryTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует базовый листинг файлов и папок в текущей директории.
     * Проверяет корректность типов объектов ([FILE] / [DIR]).
     */
    @Test
    void testExecute(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        JsonNode params = mapper.createObjectNode().put("path", ".");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("file1.txt"), "Вывод должен содержать имя файла");
        assertTrue(text.contains("[DIR] subdir"), "Вывод должен содержать имя папки с правильной меткой");
    }

    /**
     * Тестирует рекурсивный листинг с ограничением глубины.
     * Проверяет иерархическое отображение вложенных структур с использованием отступов.
     */
    @Test
    void testRecursiveListing(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        // Создаем глубокую вложенность
        Path sub = Files.createDirectories(tempDir.resolve("a/b/c"));
        Files.createFile(sub.resolve("leaf.txt"));

        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("depth", 5);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Верификация уровней вложенности
        assertTrue(text.contains("[DIR] a"), "Корень дерева должен присутствовать");
        assertTrue(text.contains("  [DIR] b"), "Второй уровень должен иметь отступ в 2 пробела");
        assertTrue(text.contains("    [DIR] c"), "Третий уровень должен иметь отступ в 4 пробела");
        assertTrue(text.contains("      [FILE] leaf.txt"), "Файл в глубине должен иметь максимальный отступ");
    }

    /**
     * Тестирует индикатор статуса прочтения файла ([READ]).
     * Убеждается, что файлы, зарегистрированные в трекере доступа, визуально выделяются в списке.
     */
    @Test
    void testReadStatusIndicator(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("known.txt");
        Files.writeString(file, "content");

        // Регистрация факта чтения
        AccessTracker.registerRead(file);

        JsonNode params = mapper.createObjectNode().put("path", ".");
        JsonNode result = tool.execute(params);

        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("[FILE] known.txt [READ]"), "Прочитанный файл должен иметь маркер [READ]");
    }

    /**
     * Тестирует автоматическое игнорирование служебных папок (autoIgnore).
     */
    @Test
    void testAutoIgnore(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Files.createFile(tempDir.resolve("important.txt"));
        Files.createDirectories(tempDir.resolve("build"));
        Files.createDirectories(tempDir.resolve(".gradle"));

        // С включенным autoIgnore
        ObjectNode params = mapper.createObjectNode();
        params.put("path", ".");
        params.put("autoIgnore", true);

        JsonNode result = tool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("important.txt"));
        assertFalse(text.contains("build"), "Папка build должна быть проигнорирована");
        assertFalse(text.contains(".gradle"), "Папка .gradle должна быть проигнорирована");
    }
}
