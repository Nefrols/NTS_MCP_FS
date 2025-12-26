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

         private static byte[] stripBom(byte[] allBytes, Charset charset) {
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
          * Считывает полный текст файла за один проход с автоопределением кодировки.
          *
          * @param path Путь к целевому файлу.
          * @return Объект {@link TextFileContent} с текстом файла.
          * @throws IOException Если файл недоступен или является бинарным.
          */
         public static TextFileContent readTextFile(Path path) throws IOException {
             byte[] allBytes = FileUtils.safeReadAllBytes(path);

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
                     charset = Charset.forName("windows-1251");
                 }
             }
             // --------------------------------------------

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
        try {
            byte[] buffer = new byte[4096];
            UniversalDetector detector = new UniversalDetector(null);

            int bytesRead = FileUtils.executeWithRetry(() -> {
                try (var inputStream = Files.newInputStream(path)) {
                    return inputStream.read(buffer);
                }
            });

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
                        return Charset.forName("windows-1251");
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
