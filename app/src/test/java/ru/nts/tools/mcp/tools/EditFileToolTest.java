// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.core.AccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для расширенного инструмента редактирования файлов (EditFileTool).
 * Проверяют:
 * 1. Одиночные замены текста и строк.
 * 2. Многофайловое пакетное редактирование (Multi-file batching).
 * 3. Атомарность транзакций и корректный откат при сбоях.
 * 4. Валидацию содержимого через expectedContent.
 * 5. Автоматический расчет смещений строк при последовательных правках.
 */
class EditFileToolTest {

    /**
     * Тестируемый инструмент редактирования.
     */
    private final EditFileTool tool = new EditFileTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Проверяет базовую замену текста по содержимому.
     */
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
        assertEquals("Hello Java", Files.readString(file), "Текст должен быть заменен");
    }

    /**
     * Проверяет успешное выполнение правок в нескольких файлах в рамках одного вызова.
     */
    @Test
    void testMultiFileBatchEdit(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path f1 = tempDir.resolve("file1.txt");
        Path f2 = tempDir.resolve("file2.txt");
        Files.writeString(f1, "Original 1");
        Files.writeString(f2, "Original 2");
        AccessTracker.registerRead(f1);
        AccessTracker.registerRead(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        edits.addObject().put("path", f1.toString()).put("oldText", "Original 1").put("newText", "Changed 1");
        edits.addObject().put("path", f2.toString()).put("oldText", "Original 2").put("newText", "Changed 2");

        tool.execute(params);

        assertEquals("Changed 1", Files.readString(f1));
        assertEquals("Changed 2", Files.readString(f2));
    }

    /**
     * Проверяет, что при ошибке в одном из файлов батча, изменения во всех остальных файлах откатываются.
     */
    @Test
    void testMultiFileRollback(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path f1 = tempDir.resolve("file1.txt");
        Path f2 = tempDir.resolve("file2.txt");
        Files.writeString(f1, "Safe");
        Files.writeString(f2, "Danger");
        AccessTracker.registerRead(f1);
        AccessTracker.registerRead(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        // Валидная первая правка
        edits.addObject().put("path", f1.toString()).put("oldText", "Safe").put("newText", "Broken");

        // Ошибочная вторая правка
        ObjectNode e2 = edits.addObject();
        e2.put("path", f2.toString());
        e2.put("startLine", 1).put("endLine", 1);
        e2.put("expectedContent", "WRONG_CONTENT");
        e2.put("newText", "ShouldNotChange");

        assertThrows(IllegalStateException.class, () -> tool.execute(params));

        // Оба файла должны вернуться в исходное состояние
        assertEquals("Safe", Files.readString(f1), "Первый файл должен откатиться");
        assertEquals("Danger", Files.readString(f2), "Второй файл не должен измениться");
    }

    /**
     * Проверяет механизм проактивной диагностики: возврат актуального текста при ошибке валидации.
     */
    @Test
    void testExpectedContentFailure(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("test.txt");
        Files.write(file, List.of("AAA", "BBB", "CCC"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("startLine", 2).put("endLine", 2);
        params.put("expectedContent", "WRONG");
        params.put("newText", "XXX");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> tool.execute(params));
        // Убеждаемся, что в ошибке есть информация о реальном содержимом
        assertTrue(ex.getMessage().contains("ACTUAL CONTENT IN FILE:"), "Ошибка должна содержать диагностику");
        assertTrue(ex.getMessage().contains("[BBB]"), "Ошибка должна содержать актуальную строку");
    }

    /**
     * Проверяет автоматическую коррекцию индексов строк при выполнении нескольких операций в одном файле.
     */
    @Test
    void testBatchOperationsWithOffsets(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("batch.txt");
        Files.write(file, List.of("Line 1", "Line 2", "Line 3"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode ops = params.putArray("operations");

        // Операция 1 увеличивает файл (1 строка -> 3 строки)
        ops.addObject().put("operation", "replace").put("startLine", 1).put("endLine", 1).put("content", "A\nB\nC");
        // Операция 2 удаляет строку (которая сместилась из-за Оп 1)
        ops.addObject().put("operation", "delete").put("startLine", 2).put("endLine", 2);

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals(4, result.size());
        assertEquals("A", result.get(0));
        assertEquals("B", result.get(1));
        assertEquals("C", result.get(2));
        assertEquals("Line 3", result.get(3));
    }

    /**
     * Тестирует операцию вставки после указанной строки.
     */
    @Test
    void testInsertAfter(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("insert.txt");
        Files.write(file, List.of("Start", "End"));
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode ops = params.putArray("operations");

        ops.addObject().put("operation", "insert_after").put("line", 1).put("content", "Middle");

        tool.execute(params);

        List<String> result = Files.readAllLines(file);
        assertEquals("Middle", result.get(1), "Текст должен быть вставлен на вторую позицию");
    }

    /**
     * Проверяет атомарность батча: отмена всех правок в файле при сбое одной из них.
     */
    @Test
    void testBatchAtomicity(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("atomic.txt");
        String originalContent = "Line 1\nLine 2\nLine 3";
        Files.writeString(file, originalContent);
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode ops = params.putArray("operations");

        ops.addObject().put("operation", "replace").put("startLine", 1).put("endLine", 1).put("content", "MODIFIED");
        // Ошибка во второй операции
        ops.addObject().put("operation", "replace").put("startLine", 2).put("endLine", 2).put("expectedContent", "WRONG").put("content", "ERROR");

        assertThrows(IllegalStateException.class, () -> tool.execute(params));

        String currentContent = Files.readString(file).replace("\r", "");
        assertEquals(originalContent, currentContent, "Файл не должен измениться при частичном сбое батча");
    }

    /**
     * Сложный тест мульти-файловой транзакции с разнородными операциями.
     */
    @Test
    void testComplexMultiFileBatch(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path f1 = tempDir.resolve("f1.txt");
        Path f2 = tempDir.resolve("f2.txt");
        Files.writeString(f1, "A\nB\nC");
        Files.writeString(f2, "1\n2\n3");
        AccessTracker.registerRead(f1);
        AccessTracker.registerRead(f2);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        // Файл 1: Удаляем B, вставляем X после A
        ObjectNode e1 = edits.addObject();
        e1.put("path", f1.toString());
        ArrayNode ops1 = e1.putArray("operations");
        ops1.addObject().put("operation", "delete").put("startLine", 2).put("endLine", 2);
        ops1.addObject().put("operation", "insert_after").put("line", 1).put("content", "X");

        // Файл 2: Простая замена
        ObjectNode e2 = edits.addObject();
        e2.put("path", f2.toString());
        e2.put("startLine", 2).put("endLine", 2).put("newText", "TWO");

        tool.execute(params);

        assertEquals("A\nX\nC", Files.readString(f1).replace("\r", ""), "Файл 1 обработан некорректно");
        assertEquals("1\nTWO\n3", Files.readString(f2).replace("\r", ""), "Файл 2 обработан некорректно");
    }

    /**
     * Проверяет откат батча при нарушении политик безопасности (PathSanitizer).
     */
    @Test
    void testMultiFileRollbackOnSecurityException(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path safeFile = tempDir.resolve("safe.txt");
        Files.writeString(safeFile, "Safe Content");
        AccessTracker.registerRead(safeFile);

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        edits.addObject().put("path", safeFile.toString()).put("oldText", "Safe").put("newText", "Hacked");
        // Попытка изменить защищенный файл gradlew
        edits.addObject().put("path", "gradlew").put("oldText", "any").put("newText", "bad");

        assertThrows(SecurityException.class, () -> tool.execute(params));
        assertEquals("Safe Content", Files.readString(safeFile), "Изменения должны быть откатаны");
    }

    /**
     * Проверяет откат батча при отсутствии регистрации чтения для одного из файлов.
     */
    @Test
    void testMultiFileRollbackOnAccessError(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path f1 = tempDir.resolve("f1.txt");
        Path f2 = tempDir.resolve("f2.txt");
        Files.writeString(f1, "Content 1");
        Files.writeString(f2, "Content 2");
        AccessTracker.registerRead(f1);
        // f2 НЕ регистрируем

        ObjectNode params = mapper.createObjectNode();
        ArrayNode edits = params.putArray("edits");

        edits.addObject().put("path", f1.toString()).put("oldText", "Content 1").put("newText", "Modified");
        edits.addObject().put("path", f2.toString()).put("oldText", "Content 2").put("newText", "Modified");

        assertThrows(SecurityException.class, () -> tool.execute(params));
        assertEquals("Content 1", Files.readString(f1), "Первый файл должен быть откатан");
    }

    /**
     * Проверяет, что нечеткая замена (oldText) выбрасывает исключение, если найдено более одного совпадения.
     */
    @Test
    void testAmbiguousOldText(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("ambiguous.txt");
        Files.writeString(file, "Line A\nLine A\nLine B");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "Line A");
        params.put("newText", "Line X");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("Multiple matches"), "Должно быть выброшено исключение о множественных совпадениях");
    }

    /**
     * Проверяет, что предоставление правильного expectedChecksum позволяет редактировать файл без предварительного чтения.
     */
    @Test
    void testEditWithValidChecksumBypass(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset(); // Убеждаемся, что трекер пуст

        Path file = tempDir.resolve("bypass.txt");
        Files.writeString(file, "Original Content");

        // Вычисляем CRC32, который должен быть известен LLM
        long crc = calculateCRC32(file);
        String crcHex = Long.toHexString(crc).toUpperCase();

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "Original").put("newText", "Modified");
        params.put("expectedChecksum", crcHex);

        // Должно пройти успешно, несмотря на то, что AccessTracker пуст
        tool.execute(params);

        assertEquals("Modified Content", Files.readString(file));
        assertTrue(AccessTracker.hasBeenRead(file), "Файл должен быть автоматически зарегистрирован как прочитанный");
    }

    /**
     * Проверяет, что неверный expectedChecksum блокирует редактирование, даже если файл не был прочитан.
     */
    @Test
    void testEditWithInvalidChecksum(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();
        AccessTracker.reset();

        Path file = tempDir.resolve("invalid_sum.txt");
        Files.writeString(file, "Content");

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "Content").put("newText", "New");
        params.put("expectedChecksum", "DEADBEEF"); // Неверный хеш

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("Checksum mismatch"), "Должно быть сообщение о несовпадении хеша");
    }

    /**
     * Проверяет Optimistic Locking: если файл был прочитан, но изменился на диске (хеш не совпадает),
     * операция должна быть отклонена.
     */
    @Test
    void testOptimisticLocking(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path file = tempDir.resolve("opt_lock.txt");
        Files.writeString(file, "Version 1");
        AccessTracker.registerRead(file); // Файл "прочитан"

        // Имитируем внешнее изменение файла
        Files.writeString(file, "Version 2");

        // Пытаемся редактировать, думая, что там Version 1 (вычисляем хеш от Version 1)
        Path v1Tmp = tempDir.resolve("v1.tmp");
        Files.writeString(v1Tmp, "Version 1");
        long v1Crc = calculateCRC32(v1Tmp);
        
        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        params.put("oldText", "Version 1").put("newText", "Version 3");
        params.put("expectedChecksum", Long.toHexString(v1Crc).toUpperCase());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> tool.execute(params));
        assertTrue(ex.getMessage().contains("Checksum mismatch"), "Операция должна быть отклонена из-за изменения файла");
    }

    /**
     * Regression test: Ensures that when 'edits' array is used with a root 'path',
     * but items inside 'edits' do not specify 'path', the root path is inherited.
     */
    @Test
    void testRootPathWithEditsArrayMissingPath(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        TransactionManager.reset();

        Path file = tempDir.resolve("repro.txt");
        Files.writeString(file, "Original Content");
        AccessTracker.registerRead(file);

        ObjectNode params = mapper.createObjectNode();
        params.put("path", file.toString());
        ArrayNode edits = params.putArray("edits");
        edits.addObject().put("oldText", "Original").put("newText", "Modified");

        tool.execute(params);

        String content = Files.readString(file);
        assertTrue(content.contains("Modified"), "Content should be modified to include 'Modified'");
        assertFalse(content.contains("Original"), "Original content should be replaced");
    }

    /**
     * Вспомогательный метод для расчета CRC32.
     */
    private long calculateCRC32(Path path) throws Exception {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        try (java.io.BufferedInputStream bis = new java.io.BufferedInputStream(new java.io.FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = bis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return crc.getValue();
    }
}