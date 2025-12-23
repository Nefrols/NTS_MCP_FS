// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.fs.ReadFileTool;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Тесты на отказоустойчивость и устойчивость к некорректным данным (Resilience Tests).
 * Проверяют механизмы защиты сервера от переполнения памяти (OOM) и некорректной обработки бинарных файлов.
 */
class ResilienceTest {

    /**
     * Инструмент чтения, используемый для проверки защитных механизмов ядра.
     */
    private final ReadFileTool readFileTool = new ReadFileTool();

    /**
     * Проверяет механизм обнаружения бинарных файлов.
     * Ожидается, что при наличии NULL-байтов в начале файла система выбросит исключение,
     * предотвращая попытку текстовой интерпретации данных.
     */
    @Test
    void testBinaryFileDetection(@TempDir Path tempDir) throws Exception {
        // Изоляция окружения
        PathSanitizer.setRoot(tempDir);
        Path binFile = tempDir.resolve("binary.dat");

        // Создаем классический бинарный файл с NULL-байтом
        try (FileOutputStream fos = new FileOutputStream(binFile.toFile())) {
            fos.write(new byte[]{0, 1, 2, 3});
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var params = mapper.createObjectNode().put("path", "binary.dat");

        // Ожидаем IOException с сообщением о детекции бинарного содержимого
        assertThrows(java.io.IOException.class, () -> readFileTool.execute(params), "Система должна блокировать чтение бинарных файлов");
    }

    /**
     * Проверяет защиту от загрузки сверхбольших файлов (OOM Protection).
     * Ожидается, что файлы, превышающие установленный лимит (10 MB), будут заблокированы.
     */
    @Test
    void testLargeFileProtection(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path largeFile = tempDir.resolve("huge.txt");

        // Генерируем файл размером 11 MB (больше лимита в 10 MB)
        byte[] data = new byte[11 * 1024 * 1024];
        Files.write(largeFile, data);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var params = mapper.createObjectNode().put("path", "huge.txt");

        // Ожидаем SecurityException, так как файл слишком велик для безопасной обработки
        assertThrows(SecurityException.class, () -> readFileTool.execute(params), "Система должна блокировать файлы, превышающие лимит размера");
    }
}