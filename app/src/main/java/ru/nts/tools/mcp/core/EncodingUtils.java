// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Утилиты для определения кодировки и безопасного чтения текстовых файлов.
 * Использует UniversalDetector (juniversalchardet) для автоматического определения Charset.
 */
public class EncodingUtils {

    /**
     * Результат чтения текстового файла с определенной кодировкой.
     *
     * @param content Содержимое файла в виде строки.
     * @param charset Кодировка, использованная для декодирования байтов.
     */
    public record TextFileContent(String content, Charset charset) {
    }

    /**
     * Считывает полный текст файла за один проход с автоопределением кодировки.
     *
     * @param path Путь к целевому файлу.
     * @return Объект {@link TextFileContent} с текстом файла.
     * @throws IOException Если файл недоступен или является бинарным.
     */
    public static TextFileContent readTextFile(Path path) throws IOException {
        byte[] allBytes = Files.readAllBytes(path);

        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(allBytes, 0, allBytes.length);
        detector.dataEnd();

        String encoding = detector.getDetectedCharset();
        Charset charset = StandardCharsets.UTF_8;

        if (encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (Exception ignored) {
            }
        }

        // --- FIX: Улучшенная логика для кириллицы и CJK ---
        if (encoding == null || charset.equals(StandardCharsets.UTF_8)) {
            if (!isValidUtf8(allBytes)) {
                if (encoding == null) {
                    charset = Charset.forName("windows-1251");
                }
            }
        }
        // --------------------------------------------

        // Проверка на бинарный файл (наличие NULL-байтов), кроме многобайтовых кодировок UTF
        if (!charset.name().startsWith("UTF-16") && !charset.name().startsWith("UTF-32")) {
            int checkLimit = Math.min(allBytes.length, 8192);
            for (int i = 0; i < checkLimit; i++) {
                if (allBytes[i] == 0) {
                    throw new IOException("Обнаружен бинарный файл (содержит NULL байты).");
                }
            }
        }

        return new TextFileContent(new String(allBytes, charset), charset);
    }

    /**
     * Проверяет, является ли массив байтов валидной последовательностью UTF-8.
     */
    private static boolean isValidUtf8(byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i++] & 0xFF;
            if (b <= 0x7F) continue; // ASCII
            
            int count;
            if (b >= 0xC2 && b <= 0xDF) count = 1;
            else if (b >= 0xE0 && b <= 0xEF) count = 2;
            else if (b >= 0xF0 && b <= 0xF4) count = 3;
            else return false;
            
            if (i + count > bytes.length) return false;
            
            for (int j = 0; j < count; j++) {
                int next = bytes[i++] & 0xFF;
                if (next < 0x80 || next > 0xBF) return false;
            }
        }
        return true;
    }

    /**
     * Определяет кодировку файла по первым байтам.
     *
     * @param path Путь к файлу.
     * @return Определенный Charset или UTF-8 по умолчанию.
     */
    public static Charset detectEncoding(Path path) {
        try (var inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[4096];
            UniversalDetector detector = new UniversalDetector(null);

            int bytesRead = inputStream.read(buffer);
            if (bytesRead > 0) {
                detector.handleData(buffer, 0, bytesRead);
                detector.dataEnd();
                
                String encoding = detector.getDetectedCharset();
                Charset charset = StandardCharsets.UTF_8;

                if (encoding != null) {
                    try {
                        charset = Charset.forName(encoding);
                    } catch (Exception ignored) {
                    }
                }

                // --- FIX: Улучшенная логика для кириллицы и CJK ---
                if (encoding == null || charset.equals(StandardCharsets.UTF_8)) {
                    byte[] actualBytes = new byte[bytesRead];
                    System.arraycopy(buffer, 0, actualBytes, 0, bytesRead);
                    if (!isValidUtf8(actualBytes)) {
                        if (encoding == null) {
                            return Charset.forName("windows-1251");
                        }
                    }
                }
                // --------------------------------------------
                
                return charset;
            }
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
