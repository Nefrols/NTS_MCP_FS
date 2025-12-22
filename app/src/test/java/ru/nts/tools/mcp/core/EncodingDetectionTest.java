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
 * Проверяют корректность работы {@link EncodingUtils} с различными наборами символов (UTF-8, UTF-16, Windows-1251, CJK).
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
     */
    @Test
    void testDetectUtf16(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("utf16.txt");
        String content = "Привет, мир! UTF-16 content with BOM. " + "A".repeat(100);
        Files.writeString(file, content, StandardCharsets.UTF_16);

        Charset detected = EncodingUtils.detectEncoding(file);
        assertTrue(detected.name().startsWith("UTF-16"), "Должна быть определена одна из вариаций UTF-16");

        String readContent = Files.readString(file, detected);
        assertTrue(readContent.contains("Привет, мир!"), "Содержимое должно быть читаемым");
    }

    /**
     * Проверяет детекцию кириллической кодировки Windows-1251 (CP1251).
     */
    @Test
    void testDetectWindows1251(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("win1251.txt");
        String content = "Это русский текст в кодировке Windows-1251. " + "Проверка кириллицы. ".repeat(20);
        Charset cp1251 = Charset.forName("windows-1251");
        Files.write(file, content.getBytes(cp1251));

        Charset detected = EncodingUtils.detectEncoding(file);
        String readContent = Files.readString(file, detected);
        assertEquals(content, readContent, "Текст в кодировке Windows-1251 должен читаться без потерь");
    }

    /**
     * Проверяет детекцию китайской кодировки (GBK/GB18030).
     */
    @Test
    void testDetectChinese(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("chinese.txt");
        String content = "你好，世界！这是中文测试。" + "你好".repeat(200);
        Charset gbk = Charset.forName("GBK");
        Files.write(file, content.getBytes(gbk));

        Charset detected = EncodingUtils.detectEncoding(file);
        assertTrue(detected.name().toUpperCase().startsWith("GB"), "Должна быть определена китайская кодировка. Получено: " + detected.name());
        assertEquals(content, Files.readString(file, detected));
    }

    /**
     * Проверяет детекцию японской кодировки Shift_JIS.
     */
    @Test
    void testDetectJapaneseShiftJIS(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("japanese.txt");
        String content = "こんにちは、世界！これは日本語のテストです。" + "こんに".repeat(200);
        Charset sjis = Charset.forName("Shift_JIS");
        Files.write(file, content.getBytes(sjis));

        Charset detected = EncodingUtils.detectEncoding(file);
        assertTrue(detected.name().equalsIgnoreCase("Shift_JIS") || detected.name().equalsIgnoreCase("MS932"), "Должна быть определена японская кодировка Shift_JIS. Получено: " + detected.name());
        assertEquals(content, Files.readString(file, detected));
    }

    /**
     * Проверяет детекцию корейской кодировки EUC-KR.
     */
    @Test
    void testDetectKoreanEucKR(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("korean.txt");
        // Больше корейского текста для уверенной детекции
        String content = "안녕하세요, 세상! 이것은 한국어 테스트입니다. " 
            + "대한민국은 동아시아의 한반도 남부에 위치한 국가이다. "
            + "한글은 세종대왕이 창제한 한국의 고유 문자이다. "
            + "가나다라마바사아자차카타파하 ".repeat(100);
        Charset euckr = Charset.forName("EUC-KR");
        Files.write(file, content.getBytes(euckr));

        Charset detected = EncodingUtils.detectEncoding(file);
        assertTrue(detected.name().equalsIgnoreCase("EUC-KR") || detected.name().equalsIgnoreCase("MS949") || detected.name().equalsIgnoreCase("EUCKR") || detected.name().startsWith("EUC-KR"), "Должна быть определена корейская кодировка. Получено: " + detected.name());
        assertEquals(content, Files.readString(file, detected));
    }
}
