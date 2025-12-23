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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструментов манипуляции расположением объектов (Move и Rename).
 * Проверяют корректность перемещения файлов, создание папок назначения,
 * перенос статуса прочтения в трекере доступа и соблюдение границ безопасности.
 */
class MoveRenameTest {

    /**
     * Тестируемый инструмент перемещения.
     */
    private final MoveFileTool moveTool = new MoveFileTool();

    /**
     * Тестируемый инструмент переименования.
     */
    private final RenameFileTool renameTool = new RenameFileTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Тестирует локальное переименование файла.
     * Проверяет физическое отсутствие старого файла, наличие нового и наличие листинга в ответе.
     */
    @Test
    void testRenameWithoutPreviousRead(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "content");
        AccessTracker.reset();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "old.txt");
        params.put("newName", "new.txt");

        JsonNode result = renameTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertFalse(Files.exists(file), "Старый файл должен исчезнуть");
        assertTrue(Files.exists(tempDir.resolve("new.txt")), "Новый файл должен появиться");
        assertTrue(text.contains("Directory content"), "Ответ должен содержать листинг");
        assertTrue(text.contains("[FILE] new.txt"), "Листинг должен содержать новое имя");
    }

    /**
     * Тестирует перемещение файла с сохранением контекста [READ].
     * Проверяет, что трекер доступа корректно обновляет путь после перемещения объекта.
     */
    @Test
    void testMoveAndPreserveAccessStatus(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path source = tempDir.resolve("file.txt");
        Files.writeString(source, "content");
        AccessTracker.reset();

        // Регистрируем файл как прочитанный в исходном месте
        AccessTracker.registerRead(source);
        assertTrue(AccessTracker.hasBeenRead(source), "Файл должен быть помечен как прочитанный");

        // Выполняем перемещение в подпапку
        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "file.txt");
        params.put("targetPath", "dest/moved.txt");
        JsonNode result = moveTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        Path target = tempDir.resolve("dest/moved.txt");
        assertTrue(Files.exists(target), "Файл должен быть перемещен в целевую папку");

        // Верификация переноса статуса в AccessTracker
        assertTrue(AccessTracker.hasBeenRead(target), "Статус прочтения должен сохраниться после перемещения на новый путь");
        assertTrue(text.contains("Directory content"), "Ответ должен содержать листинг целевой папки");
        assertTrue(text.contains("[FILE] moved.txt"), "Листинг должен содержать перемещенный файл");
    }

    /**
     * Тестирует защиту от перемещения файлов за пределы проекта.
     * Проверяет срабатывание PathSanitizer при использовании опасных путей ('..').
     */
    @Test
    void testMoveProtection(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);

        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "file.txt");
        params.put("targetPath", "../outside.txt");

        // Ожидаем прерывание операции системой безопасности
        assertThrows(SecurityException.class, () -> moveTool.execute(params), "Система должна блокировать перемещение за пределы корня");
    }
}
