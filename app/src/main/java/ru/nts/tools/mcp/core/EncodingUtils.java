// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Утилита для определения кодировки и эффективного чтения файлов.
 */
public class EncodingUtils {

    /**
     * Результат чтения текстового файла.
     */
    public record TextFileContent(String content, Charset charset) {}

    /**
     * Выполняет чтение текстового файла за один проход (Single-pass IO).
     * Определяет кодировку, проверяет на бинарность и возвращает контент.
     */
    public static TextFileContent readTextFile(Path path) throws IOException {
        byte[] allBytes = Files.readAllBytes(path);
        
        // Определение кодировки сначала
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(allBytes, 0, allBytes.length);
        detector.dataEnd();
        
        String encoding = detector.getDetectedCharset();
        Charset charset = StandardCharsets.UTF_8;
        if (encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (Exception ignored) {}
        }

        // Проверка на бинарность: если кодировка не UTF-16/32 и есть NULL-байты
        if (!charset.name().startsWith("UTF-16") && !charset.name().startsWith("UTF-32")) {
            int checkLimit = Math.min(allBytes.length, 8192);
            for (int i = 0; i < checkLimit; i++) {
                if (allBytes[i] == 0) {
                    throw new IOException("Binary file detected (contains NULL bytes).");
                }
            }
        }

        return new TextFileContent(new String(allBytes, charset), charset);
    }

    /**
     * Определяет кодировку файла (облегченная версия).
     */
    public static Charset detectEncoding(Path path) throws IOException {
        try (var inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[4096];
            UniversalDetector detector = new UniversalDetector(null);

            int bytesRead = inputStream.read(buffer);
            if (bytesRead > 0) {
                detector.handleData(buffer, 0, bytesRead);
            }
            detector.dataEnd();
            String encoding = detector.getDetectedCharset();
            return (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}