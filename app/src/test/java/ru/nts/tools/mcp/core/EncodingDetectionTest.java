// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для проверки механизмов автоматического определения кодировки файлов.
 * Проверяют корректность работы {@link EncodingUtils} с различными наборами символов (UTF-8, UTF-16, Windows-1251).
 */
class EncodingDetectionTest {

    /**
     * Проверяет детекцию стандартной кодировки UTF-8.
     */
    @Test
    void testDetectUtf8(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("utf8.txt");
        String content = "Привет, мир! UTF-8 content.";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        Charset detected = EncodingUtils.detectEncoding(file);
        assertEquals(StandardCharsets.UTF_8, detected, "Кодировка UTF-8 должна определяться точно");
    }

    /**
     * Проверяет детекцию UTF-16 с маркером порядка байтов (BOM).
     * Важно для поддержки файлов, созданных в специфичных текстовых редакторах.
     */
    @Test
    void testDetectUtf16(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("utf16.txt");
        // Создаем длинную строку для повышения вероятности правильного определения
        String content = "Привет, мир! UTF-16 content with BOM. " + "A".repeat(100);
        Files.writeString(file, content, StandardCharsets.UTF_16);

        Charset detected = EncodingUtils.detectEncoding(file);
        // Библиотека может вернуть конкретную вариацию UTF-16 (BE или LE), 
        // поэтому проверяем только префикс семейства.
        assertTrue(detected.name().startsWith("UTF-16"), "Должна быть определена одна из вариаций UTF-16");

        String readContent = Files.readString(file, detected);
        // Убеждаемся, что данные читаются корректно, даже если в начале остался символ \uFEFF (BOM)
        assertTrue(readContent.contains("Привет, мир!"), "Содержимое должно быть читаемым");
    }

    /**
     * Проверяет детекцию кириллической кодировки Windows-1251 (CP1251).
     * Критично для работы с legacy-файлами и кодом, написанным в Windows-окружении.
     */
    @Test
    void testDetectWindows1251(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("win1251.txt");
        // Используем повторяющийся русский текст для обеспечения достаточной статистической выборки для детектора
        String content = "Это русский текст в кодировке Windows-1251. " + "Проверка кириллицы. ".repeat(20);
        Charset cp1251 = Charset.forName("windows-1251");
        Files.write(file, content.getBytes(cp1251));

        Charset detected = EncodingUtils.detectEncoding(file);
        // Проверяем возможность корректного восстановления текста с использованием обнаруженной кодировки
        String readContent = Files.readString(file, detected);
        assertEquals(content, readContent, "Текст в кодировке Windows-1251 должен читаться без потерь");
    }
}