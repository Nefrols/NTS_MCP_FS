/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.core;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Утилиты для определения кодировки и безопасного чтения текстовых файлов.
 * Использует ICU4J CharsetDetector для автоматического определения Charset.
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
     * Считывает полный текст файла за один проход с принудительно указанной кодировкой.
     *
     * @param path    Путь к целевому файлу.
     * @param charset Кодировка для декодирования.
     * @return Объект {@link TextFileContent}.
     * @throws IOException Если файл недоступен.
     */
    public static TextFileContent readTextFile(Path path, Charset charset) throws IOException {
        byte[] allBytes = FileUtils.safeReadAllBytes(path);
        allBytes = stripBom(allBytes, charset);
        return new TextFileContent(new String(allBytes, charset), charset);
    }

    /**
     * Считывает полный текст файла за один проход с автоопределением кодировки.
     *
     * @param path Путь к целевому файлу.
     * @return Объект {@link TextFileContent} с текстом файла.
     * @throws IOException Если файл недоступен или является бинарным.
     */
    public static TextFileContent readTextFile(Path path) throws IOException {
        byte[] allBytes = FileUtils.safeReadAllBytes(path);
        Charset charset = detectEncoding(allBytes);

        allBytes = stripBom(allBytes, charset);

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
     * Определяет кодировку файла по его содержимому.
     *
     * @param path Путь к файлу.
     * @return Определенный Charset или UTF-8 по умолчанию.
     */
    public static Charset detectEncoding(Path path) {
        try {
            byte[] bytes = FileUtils.safeReadAllBytes(path);
            return detectEncoding(bytes);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Центральный метод определения кодировки по байтовому массиву.
     * Стратегия: BOM → ICU4J (высокая уверенность) → UTF-8 валидация → ICU4J (низкая уверенность) → fallback.
     *
     * @param bytes Байты файла.
     * @return Определённый Charset.
     */
    public static Charset detectEncoding(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return StandardCharsets.UTF_8;
        }

        // 1. BOM — детерминированный ответ
        Charset bomCharset = detectByBom(bytes);
        if (bomCharset != null) return bomCharset;

        // 2. ICU4J CharsetDetector с высокой уверенностью
        CharsetDetector detector = new CharsetDetector();
        detector.setText(bytes);
        CharsetMatch match = detector.detect();

        if (match != null && match.getConfidence() >= 50) {
            try {
                return Charset.forName(match.getName());
            } catch (Exception ignored) {}
        }

        // 3. Строгая UTF-8 валидация (покрывает чистый ASCII и корректный UTF-8)
        if (isValidUtf8(bytes)) return StandardCharsets.UTF_8;

        // 4. ICU4J с низкой уверенностью (лучше чем слепой fallback)
        if (match != null && match.getConfidence() >= 10) {
            try {
                return Charset.forName(match.getName());
            } catch (Exception ignored) {}
        }

        // 5. Fallback
        return Charset.forName("windows-1251");
    }

    /**
     * Определяет кодировку по BOM (Byte Order Mark).
     * Порядок проверки важен: UTF-32LE перед UTF-16LE (у обоих FF FE, но UTF-32LE имеет 00 00 после).
     *
     * @return Charset если BOM найден, null если нет.
     */
    private static Charset detectByBom(byte[] bytes) {
        if (bytes.length >= 4) {
            // UTF-32BE: 00 00 FE FF
            if (bytes[0] == 0x00 && bytes[1] == 0x00 && (bytes[2] & 0xFF) == 0xFE && (bytes[3] & 0xFF) == 0xFF) {
                return Charset.forName("UTF-32BE");
            }
            // UTF-32LE: FF FE 00 00 (проверяем ДО UTF-16LE!)
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE && bytes[2] == 0x00 && bytes[3] == 0x00) {
                return Charset.forName("UTF-32LE");
            }
        }
        if (bytes.length >= 3) {
            // UTF-8 BOM: EF BB BF
            if ((bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                return StandardCharsets.UTF_8;
            }
        }
        if (bytes.length >= 2) {
            // UTF-16BE: FE FF
            if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                return StandardCharsets.UTF_16BE;
            }
            // UTF-16LE: FF FE (после проверки UTF-32LE выше)
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                return StandardCharsets.UTF_16LE;
            }
        }
        return null;
    }

    /**
     * Удаляет BOM из байтового массива.
     * Package-private для использования из FastSearch.
     */
    static byte[] stripBom(byte[] allBytes, Charset charset) {
        if (allBytes.length < 2) return allBytes;

        String name = charset.name().toUpperCase();
        if (!name.startsWith("UTF-")) return allBytes; // BOM только для UTF

        int offset = 0;
        if (name.equals("UTF-8")) {
            if (allBytes.length >= 3 && (allBytes[0] & 0xFF) == 0xEF && (allBytes[1] & 0xFF) == 0xBB && (allBytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
        } else if (name.equals("UTF-16BE")) {
            if ((allBytes[0] & 0xFF) == 0xFE && (allBytes[1] & 0xFF) == 0xFF) offset = 2;
        } else if (name.equals("UTF-16LE")) {
            if ((allBytes[0] & 0xFF) == 0xFF && (allBytes[1] & 0xFF) == 0xFE) offset = 2;
        } else if (name.equals("UTF-16")) {
            if ((allBytes[0] & 0xFF) == 0xFE && (allBytes[1] & 0xFF) == 0xFF) offset = 2;
            else if ((allBytes[0] & 0xFF) == 0xFF && (allBytes[1] & 0xFF) == 0xFE) offset = 2;
        } else if (name.equals("UTF-32BE")) {
            if (allBytes.length >= 4 && (allBytes[0] & 0xFF) == 0x00 && (allBytes[1] & 0xFF) == 0x00 && (allBytes[2] & 0xFF) == 0xFE && (allBytes[3] & 0xFF) == 0xFF) offset = 4;
        } else if (name.equals("UTF-32LE")) {
            if (allBytes.length >= 4 && (allBytes[0] & 0xFF) == 0xFF && (allBytes[1] & 0xFF) == 0xFE && (allBytes[2] & 0xFF) == 0x00 && (allBytes[3] & 0xFF) == 0x00) offset = 4;
        } else if (name.equals("UTF-32")) {
            if (allBytes.length >= 4) {
                if ((allBytes[0] & 0xFF) == 0x00 && (allBytes[1] & 0xFF) == 0x00 && (allBytes[2] & 0xFF) == 0xFE && (allBytes[3] & 0xFF) == 0xFF) offset = 4;
                else if ((allBytes[0] & 0xFF) == 0xFF && (allBytes[1] & 0xFF) == 0xFE && (allBytes[2] & 0xFF) == 0x00 && (allBytes[3] & 0xFF) == 0x00) offset = 4;
            }
        }

        if (offset > 0) {
            byte[] withoutBom = new byte[allBytes.length - offset];
            System.arraycopy(allBytes, offset, withoutBom, 0, withoutBom.length);
            return withoutBom;
        }
        return allBytes;
    }

    /**
     * Проверяет, является ли массив байтов валидной последовательностью UTF-8.
     */
    static boolean isValidUtf8(byte[] bytes) {
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
}
