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
 * Утилита для определения кодировки файлов.
 */
public class EncodingUtils {

    /**
     * Определяет кодировку файла.
     *
     * @param path Путь к файлу.
     * @return Обнаруженная кодировка или UTF-8 по умолчанию.
     * @throws IOException Если произошла ошибка ввода-вывода.
     */
    public static Charset detectEncoding(Path path) throws IOException {
        try (InputStream inputStream = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            byte[] buffer = new byte[4096];
            UniversalDetector detector = new UniversalDetector(null);

            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) > 0 && !detector.isDone()) {
                detector.handleData(buffer, 0, bytesRead);
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
