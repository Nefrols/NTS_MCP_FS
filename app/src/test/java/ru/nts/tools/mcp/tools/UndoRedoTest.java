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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Расширенные тесты системы транзакций и функций отмены/повтора (UNDO/REDO).
 * Проверяют:
 * 1. Атомарность операций и автоматический откат при сбоях.
 * 2. Корректность восстановления файлов после удаления или перемещения.
 * 3. Логику управления стеком истории (очистка REDO при новой транзакции).
 * 4. Формирование журнала транзакций.
 */
class UndoRedoTest {

    /**
     * Экземпляры инструментов для тестирования транзакций.
     */
    private final EditFileTool editTool = new EditFileTool();
    private final DeleteFileTool deleteTool = new DeleteFileTool();
    private final MoveFileTool moveTool = new MoveFileTool();
    private final UndoTool undoTool = new UndoTool();
    private final RedoTool redoTool = new RedoTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Общая временная директория для всех тестов класса.
     */
    @TempDir
    Path sharedTempDir;

    /**
     * Сброс глобального состояния перед каждым тестом для обеспечения изоляции.
     */
    @BeforeEach
    void setUp() {
        PathSanitizer.setRoot(sharedTempDir);
        TransactionManager.reset();
        AccessTracker.reset();
    }

    /**
     * Проверяет автоматический откат всей транзакции при ошибке в середине пакета операций.
     */
    @Test
    void testTransactionRollbackOnError() throws Exception {
        Path file = sharedTempDir.resolve("atomic.txt");
        String original = "Original";
        Files.writeString(file, original);
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", "atomic.txt");
        var ops = params.putArray("operations");
        ops.addObject().put("operation", "replace").put("startLine", 1).put("endLine", 1).put("content", "New");
        // Ошибка во второй операции (неверный индекс)
        ops.addObject().put("operation", "replace").put("startLine", 100).put("content", "Error");

        assertThrows(Exception.class, () -> editTool.execute(params), "Должно быть выброшено исключение");

        // Файл должен вернуться к состоянию "Original"
        assertEquals(original, Files.readString(file).replace("\r", ""), "Файл должен быть откатан автоматически");
    }

    /**
     * Тестирует возможность отмены удаления файла.
     */
    @Test
    void testUndoFileDeletion() throws Exception {
        Path file = sharedTempDir.resolve("to_delete.txt");
        String content = "Vital Data";
        Files.writeString(file, content);
        AccessTracker.registerRead(file);

        // Шаг 1: Удаление
        ObjectNode params = mapper.createObjectNode();
        params.put("path", "to_delete.txt");
        deleteTool.execute(params);
        assertFalse(Files.exists(file), "Файл должен быть удален");

        // Шаг 2: Отмена (UNDO)
        undoTool.execute(mapper.createObjectNode());
        assertTrue(Files.exists(file), "Файл должен быть восстановлен");
        assertEquals(content, Files.readString(file), "Содержимое восстановленного файла должно совпадать");
    }

    /**
     * Тестирует отмену перемещения файла (UNDO Move).
     */
    @Test
    void testUndoMoveOperation() throws Exception {
        Path source = sharedTempDir.resolve("source.txt");
        Path target = sharedTempDir.resolve("sub/target.txt");
        Files.writeString(source, "move me");
        AccessTracker.registerRead(source);

        // Шаг 1: Перемещение
        ObjectNode params = mapper.createObjectNode();
        params.put("sourcePath", "source.txt");
        params.put("targetPath", "sub/target.txt");
        moveTool.execute(params);

        assertFalse(Files.exists(source));
        assertTrue(Files.exists(target));

        // Шаг 2: Отмена
        undoTool.execute(mapper.createObjectNode());
        assertTrue(Files.exists(source), "Файл должен вернуться на старое место");
        assertFalse(Files.exists(target), "Файл на новом месте должен быть удален");
    }

    /**
     * Проверяет правило очистки стека REDO при совершении новой транзакции.
     * После отмены старой операции и совершения новой — повтор старой невозможен.
     */
    @Test
    void testRedoStackInvalidation() throws Exception {
        Path file = sharedTempDir.resolve("test.txt");
        Files.writeString(file, "init");
        AccessTracker.registerRead(file);

        // Операция A
        ObjectNode p1 = mapper.createObjectNode();
        p1.put("path", "test.txt");
        p1.put("oldText", "init");
        p1.put("newText", "A");
        editTool.execute(p1);

        // UNDO A
        undoTool.execute(mapper.createObjectNode());
        assertEquals("init", Files.readString(file));

        // Новая операция B (инвалидирует REDO стека)
        ObjectNode p2 = mapper.createObjectNode();
        p2.put("path", "test.txt");
        p2.put("oldText", "init");
        p2.put("newText", "B");
        editTool.execute(p2);

        // Попытка REDO должна вернуть статус об отсутствии операций
        JsonNode redoResult = redoTool.execute(mapper.createObjectNode());
        String msg = redoResult.get("content").get(0).get("text").asText();
        assertTrue(msg.contains("No operations to redo"), "Стек REDO должен быть пуст");
        assertEquals("B", Files.readString(file), "Текущее состояние файла не должно измениться");
    }

    /**
     * Тестирует глубокую историю отмен и повторов (Cycle Test).
     */
    @Test
    void testLongHistoryCycle() throws Exception {
        Path file = sharedTempDir.resolve("history.txt");
        Files.writeString(file, "0");
        AccessTracker.registerRead(file);

        // Совершаем 3 последовательных изменения: 0 -> 1 -> 2 -> 3
        for (int i = 1; i <= 3; i++) {
            ObjectNode p = mapper.createObjectNode();
            p.put("path", "history.txt");
            p.put("oldText", String.valueOf(i - 1));
            p.put("newText", String.valueOf(i));
            editTool.execute(p);
        }
        assertEquals("3", Files.readString(file));

        // Откатываем все 3 раза до "0"
        undoTool.execute(mapper.createObjectNode());
        undoTool.execute(mapper.createObjectNode());
        undoTool.execute(mapper.createObjectNode());
        assertEquals("0", Files.readString(file));

        // Повторяем 2 раза до "2"
        redoTool.execute(mapper.createObjectNode());
        redoTool.execute(mapper.createObjectNode());
        assertEquals("2", Files.readString(file));
    }

    /**
     * Проверяет корректность формирования визуального журнала транзакций.
     */
    @Test
    void testTransactionJournal(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path f = tempDir.resolve("test.txt");
        Files.writeString(f, "init\n");
        AccessTracker.registerRead(f);

        // Совершаем действие для записи в журнал вручную через TransactionManager
        // чтобы гарантировать расчет статистики
        TransactionManager.startTransaction("Manual Edit");
        TransactionManager.backup(f);
        Files.writeString(f, "init\nnew line\n");
        TransactionManager.commit();

        // Запрос журнала
        TransactionJournalTool journalTool = new TransactionJournalTool();
        JsonNode result = journalTool.execute(mapper.createObjectNode());
        String text = result.get("content").get(0).get("text").asText();

        // Верификация ключевых разделов отчета
        assertTrue(text.contains("=== TRANSACTION JOURNAL ==="), "Должен быть заголовок журнала");
        assertTrue(text.contains("Manual Edit"), "Журнал должен содержать описание транзакции");
        assertTrue(text.contains("+1, -0 lines"), "Журнал должен содержать статистику изменений");
        assertTrue(text.contains("=== ACTIVE SESSION CONTEXT ==="), "Должен быть раздел контекста сессии");
        assertTrue(text.contains("- test.txt"), "Контекст должен содержать прочитанный файл");
    }
}