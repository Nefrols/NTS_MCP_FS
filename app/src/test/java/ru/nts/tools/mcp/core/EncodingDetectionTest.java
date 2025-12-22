// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EncodingDetectionTest {

    @Test
    void testDetectUtf8(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("utf8.txt");
        String content = "Привет, мир! UTF-8 content.";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        Charset detected = EncodingUtils.detectEncoding(file);
        assertEquals(StandardCharsets.UTF_8, detected);
    }

    @Test
    void testDetectUtf16(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("utf16.txt");
        String content = "Привет, мир! UTF-16 content with BOM. " + "A".repeat(100);
        Files.writeString(file, content, StandardCharsets.UTF_16);

        Charset detected = EncodingUtils.detectEncoding(file);
        // UTF-16 с BOM обычно определяется как UTF-16BE или UTF-16LE в зависимости от реализации
        assertTrue(detected.name().startsWith("UTF-16"));
        
        String readContent = Files.readString(file, detected);
        // Files.readString с определенным Charset может оставить BOM в начале строки как \uFEFF
        assertTrue(readContent.contains("Привет, мир!"));
    }

    @Test
    void testDetectWindows1251(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("win1251.txt");
        // Увеличим объем данных для более надежного определения
        String content = "Это русский текст в кодировке Windows-1251. " + "Проверка кириллицы. ".repeat(20);
        Charset cp1251 = Charset.forName("windows-1251");
        Files.write(file, content.getBytes(cp1251));

        Charset detected = EncodingUtils.detectEncoding(file);
        // Проверяем, что кодировка кириллическая
        String readContent = Files.readString(file, detected);
        assertEquals(content, readContent);
    }
}
