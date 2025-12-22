// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента пакетного выполнения (BatchToolsTool).
 * Проверяют кросс-инструментальную атомарность: способность объединять разные инструменты
 * (например, переименование и редактирование) в одну неделимую транзакцию.
 */
class BatchToolsTest {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Экземпляр роутера для регистрации зависимых инструментов.
     */
    private McpRouter router;

    /**
     * Тестируемый инструмент-оркестратор.
     */
    private BatchToolsTool batchTool;

    /**
     * Временная директория для изоляции файловых операций.
     */
    @TempDir
    Path tempDir;

    /**
     * Подготовка окружения перед каждым тестом.
     * Инициализирует роутер, регистрирует инструменты и сбрасывает состояние менеджеров.
     */
    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset();

        // Создаем локальный роутер и регистрируем в нем инструменты, необходимые для тестов
        router = new McpRouter(mapper);
        router.registerTool(new RenameFileTool());
        router.registerTool(new EditFileTool());
        batchTool = new BatchToolsTool(router);
    }

    /**
     * Тестирует цепочку из переименования файла и последующего изменения его содержимого.
     * Проверяет, что вторая операция успешно находит файл по новому пути внутри одного батча.
     */
    @Test
    void testRenameAndEditBatch() throws Exception {
        Path file = tempDir.resolve("old.txt");
        Files.writeString(file, "Original Content");
        // Регистрируем чтение исходного файла
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Переименование
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_rename_file");
        a1.putObject("params").put("path", "old.txt").put("newName", "new.txt");

        // Шаг 2: Редактирование (используем уже новое имя файла)
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "nts_edit_file");
        a2.putObject("params").put("path", "new.txt").put("oldText", "Original").put("newText", "Updated");

        // Выполнение батча
        batchTool.execute(params);

        // Верификация итогового состояния
        Path newFile = tempDir.resolve("new.txt");
        assertTrue(Files.exists(newFile), "Новый файл должен существовать");
        assertFalse(Files.exists(file), "Старый файл должен быть удален");
        assertEquals("Updated Content", Files.readString(newFile), "Содержимое должно быть обновлено");
    }

    /**
     * Тестирует атомарный откат всей цепочки при сбое в одном из звеньев.
     * Проверяет, что если вторая операция в батче провалилась, изменения первой операции также отменяются.
     */
    @Test
    void testBatchRollbackOnFailure() throws Exception {
        Path file = tempDir.resolve("safe.txt");
        Files.writeString(file, "Untouched");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode actions = params.putArray("actions");

        // Шаг 1: Валидная правка (должна быть откатана)
        ObjectNode a1 = actions.addObject();
        a1.put("tool", "nts_edit_file");
        a1.putObject("params").put("path", "safe.txt").put("oldText", "Untouched").put("newText", "MODIFIED");

        // Шаг 2: Заведомо ошибочная операция (несуществующий файл)
        ObjectNode a2 = actions.addObject();
        a2.put("tool", "nts_edit_file");
        a2.putObject("params").put("path", "missing.txt").put("oldText", "any").put("newText", "fail");

        // Ожидаем исключение
        assertThrows(Exception.class, () -> batchTool.execute(params), "Батч должен выбросить исключение при ошибке в любом действии");

        // Проверяем, что первый файл вернулся к исходному состоянию
        assertEquals("Untouched", Files.readString(file), "Изменения первого файла должны быть откатаны из-за ошибки во втором");
    }
}