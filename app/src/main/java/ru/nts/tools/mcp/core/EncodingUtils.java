// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Утилита для определения кодировки и типа файла (текстовый/бинарный).
 */
public class EncodingUtils {

    /**
     * Определяет кодировку файла.
     * Также выполняет базовую проверку на бинарность.
     *
     * @param path Путь к файлу.
     * @return Обнаруженная кодировка или UTF-8 по умолчанию.
     * @throws IOException Если произошла ошибка ввода-вывода или файл бинарный.
     */
    public static Charset detectEncoding(Path path) throws IOException {
        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[4096];
            UniversalDetector detector = new UniversalDetector(null);

            int bytesRead = inputStream.read(buffer);
            if (bytesRead > 0) {
                // Базовая проверка на бинарный файл (поиск NULL-байта)
                for (int i = 0; i < bytesRead; i++) {
                    if (buffer[i] == 0) {
                        throw new IOException("Binary file detected (contains NULL bytes).");
                    }
                }
                detector.handleData(buffer, 0, bytesRead);
            }

            // Дочитываем остальное если нужно (хотя для кодировки обычно хватает 4кб)
            int read;
            while ((read = inputStream.read(buffer)) > 0 && !detector.isDone()) {
                detector.handleData(buffer, 0, read);
            }

            detector.dataEnd();
            String encoding = detector.getDetectedCharset();
            if (encoding == null) {
                return StandardCharsets.UTF_8; // fallback
            }
            try {
                return Charset.forName(encoding);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}