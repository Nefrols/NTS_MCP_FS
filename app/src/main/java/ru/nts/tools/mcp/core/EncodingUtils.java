// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.mozilla.universalchardet.UniversalDetector;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Утилита для определения кодировки и эффективного чтения содержимого файлов.
 * Интегрирует библиотеку UniversalDetector (juniversalchardet) для автоматического распознавания Charset.
 * Обеспечивает защиту от обработки бинарных файлов.
 */
public class EncodingUtils {

    /**
     * Контейнер для хранения считанного текстового контента и обнаруженной кодировки.
     *
     * @param content Содержимое файла в виде строки.
     * @param charset Кодировка, использованная для декодирования байтов.
     */
    public record TextFileContent(String content, Charset charset) {
    }

    /**
     * Выполняет чтение текстового файла за один системный проход (Single-pass IO).
     * Оптимизирует производительность за счет объединения этапов детекции кодировки,
     * проверки на бинарность и декодирования в строку.
     *
     * @param path Путь к целевому файлу.
     *
     * @return Объект {@link TextFileContent} с данными файла.
     *
     * @throws IOException Если файл является бинарным (содержит NULL-байты) или произошла ошибка ввода-вывода.
     */
    public static TextFileContent readTextFile(Path path) throws IOException {
        // Читаем все байты файла в память (ограничено лимитом размера в вызывающем коде)
        byte[] allBytes = Files.readAllBytes(path);

        // Определение кодировки на основе всего массива байтов для максимальной точности
        UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(allBytes, 0, allBytes.length);
        detector.dataEnd();

        String encoding = detector.getDetectedCharset();
        Charset charset = StandardCharsets.UTF_8; // Значение по умолчанию
        if (encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (Exception ignored) {
                // Если библиотека вернула неизвестное Java имя кодировки, используем UTF-8
            }
        }

        // Проверка на бинарность: если это не многобайтная текстовая кодировка (UTF-16/32),
        // наличие NULL-байтов в начале файла однозначно указывает на бинарный формат.
        if (!charset.name().startsWith("UTF-16") && !charset.name().startsWith("UTF-32")) {
            int checkLimit = Math.min(allBytes.length, 8192); // Проверяем первые 8Кб
            for (int i = 0; i < checkLimit; i++) {
                if (allBytes[i] == 0) {
                    throw new IOException("Binary file detected (contains NULL bytes).");
                }
            }
        }

        // Декодируем байты в строку с использованием определенной кодировки
        return new TextFileContent(new String(allBytes, charset), charset);
    }

    /**
     * Определяет кодировку файла без полной вычитки его содержимого.
     * Облегченная версия для получения метаданных (file_info) или быстрой проверки.
     *
     * @param path Путь к файлу.
     *
     * @return Обнаруженная кодировка или UTF-8 в случае неудачи.
     */
    public static Charset detectEncoding(Path path) {
        try (var inputStream = Files.newInputStream(path)) {
            byte[] buffer = new byte[4096]; // Читаем первые 4Кб для анализа
            UniversalDetector detector = new UniversalDetector(null);

            int bytesRead = inputStream.read(buffer);
            if (bytesRead > 0) {
                detector.handleData(buffer, 0, bytesRead);
            }
            detector.dataEnd();
            String encoding = detector.getDetectedCharset();
            return (encoding != null) ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (Exception e) {
            // В случае любой ошибки (файл занят, недоступен) возвращаем стандарт
            return StandardCharsets.UTF_8;
        }
    }
}
