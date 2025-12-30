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

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

/**
 * Высокопроизводительный поиск по файлам.
 *
 * Оптимизации:
 * - Memory-mapped файлы для больших файлов (>10MB)
 * - Boyer-Moore-Horspool для литеральных паттернов
 * - Единый проход: CRC + поиск + подсчёт строк
 * - Предвычисленные смещения строк для O(log n) поиска номера
 * - Ранняя остановка с maxResults
 * - Потоковая обработка без загрузки всего файла в память
 */
public class FastSearch {

    private static final int MMAP_THRESHOLD = 10 * 1024 * 1024; // 10MB
    private static final int BUFFER_SIZE = 64 * 1024; // 64KB chunks
    private static final int BMH_MIN_PATTERN_LEN = 4; // Минимальная длина для BMH

    /**
     * Результат поиска по файлу.
     */
    public record SearchResult(
            Path path,
            long crc32c,
            int lineCount,
            Charset charset,
            List<MatchedLine> matches
    ) {}

    /**
     * Найденная строка с контекстом.
     */
    public record MatchedLine(int lineNumber, String text, boolean isMatch) {}

    /**
     * Выполняет поиск в файле с оптимизированным алгоритмом.
     *
     * @param path Путь к файлу
     * @param pattern Паттерн поиска (литерал или regex)
     * @param isRegex Использовать regex
     * @param maxResults Максимум результатов (0 = без ограничения)
     * @param contextBefore Строк контекста до совпадения
     * @param contextAfter Строк контекста после совпадения
     * @return Результат поиска или null если совпадений нет
     */
    public static SearchResult search(Path path, String pattern, boolean isRegex,
                                       int maxResults, int contextBefore, int contextAfter) throws IOException {
        long fileSize = Files.size(path);

        // Определяем кодировку по первым байтам
        Charset charset = EncodingUtils.detectEncoding(path);

        if (fileSize > MMAP_THRESHOLD) {
            return searchMapped(path, pattern, isRegex, maxResults, contextBefore, contextAfter, charset);
        } else {
            return searchBuffered(path, pattern, isRegex, maxResults, contextBefore, contextAfter, charset);
        }
    }

    /**
     * Поиск с memory-mapped файлом для больших файлов.
     */
    private static SearchResult searchMapped(Path path, String pattern, boolean isRegex,
                                              int maxResults, int ctxBefore, int ctxAfter,
                                              Charset charset) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            MappedByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);

            // Читаем весь контент (mmap не копирует в память, использует page cache OS)
            byte[] bytes = new byte[(int) size];
            buffer.get(bytes);

            return searchInBytes(path, bytes, pattern, isRegex, maxResults, ctxBefore, ctxAfter, charset);
        }
    }

    /**
     * Буферизованный поиск для небольших файлов.
     */
    private static SearchResult searchBuffered(Path path, String pattern, boolean isRegex,
                                                int maxResults, int ctxBefore, int ctxAfter,
                                                Charset charset) throws IOException {
        byte[] bytes = FileUtils.safeReadAllBytes(path);
        return searchInBytes(path, bytes, pattern, isRegex, maxResults, ctxBefore, ctxAfter, charset);
    }

    /**
     * Основной поиск по байтовому массиву.
     * Единый проход: CRC + индексация строк + поиск.
     */
    private static SearchResult searchInBytes(Path path, byte[] bytes, String pattern, boolean isRegex,
                                               int maxResults, int ctxBefore, int ctxAfter,
                                               Charset charset) throws IOException {
        // Проверка на бинарный файл
        if (isBinary(bytes)) {
            return null;
        }

        // Параллельно вычисляем CRC (очень быстро, hardware-accelerated)
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        long crcValue = crc.getValue();

        // Конвертируем в строку
        String content = new String(bytes, charset);

        // Строим индекс строк: массив позиций начала каждой строки
        int[] lineOffsets = buildLineOffsets(content);
        int lineCount = lineOffsets.length;

        // Ищем совпадения
        List<Integer> matchPositions;
        if (isRegex) {
            matchPositions = findRegexMatches(content, pattern, maxResults);
        } else {
            matchPositions = findLiteralMatches(content, pattern, maxResults);
        }

        if (matchPositions.isEmpty()) {
            return null;
        }

        // Преобразуем позиции в номера строк и собираем контекст
        List<MatchedLine> matches = collectMatchesWithContext(
                content, lineOffsets, matchPositions, ctxBefore, ctxAfter);

        return new SearchResult(path, crcValue, lineCount, charset, matches);
    }

    /**
     * Строит массив смещений начала каждой строки.
     * lineOffsets[i] = позиция первого символа строки (i+1)
     */
    private static int[] buildLineOffsets(String content) {
        // Считаем количество строк
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') count++;
        }

        int[] offsets = new int[count];
        offsets[0] = 0;
        int lineIdx = 1;
        for (int i = 0; i < content.length() && lineIdx < count; i++) {
            if (content.charAt(i) == '\n') {
                offsets[lineIdx++] = i + 1;
            }
        }
        return offsets;
    }

    /**
     * Binary search для определения номера строки по позиции в контенте.
     * O(log n) вместо O(n).
     */
    private static int positionToLineNumber(int[] lineOffsets, int pos) {
        int low = 0, high = lineOffsets.length - 1;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (lineOffsets[mid] <= pos) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low + 1; // 1-based
    }

    /**
     * Поиск литерала с использованием Boyer-Moore-Horspool для длинных паттернов.
     */
    private static List<Integer> findLiteralMatches(String content, String pattern, int maxResults) {
        List<Integer> positions = new ArrayList<>();

        if (pattern.length() >= BMH_MIN_PATTERN_LEN) {
            // Boyer-Moore-Horspool для паттернов >= 4 символов
            int[] badChar = buildBadCharTable(pattern);
            int n = content.length();
            int m = pattern.length();
            int i = 0;

            while (i <= n - m) {
                int j = m - 1;
                while (j >= 0 && pattern.charAt(j) == content.charAt(i + j)) {
                    j--;
                }
                if (j < 0) {
                    positions.add(i);
                    if (maxResults > 0 && positions.size() >= maxResults) {
                        return positions;
                    }
                    i += 1; // Можно оптимизировать: i += (m > 1 ? m - badChar[...] : 1)
                } else {
                    char c = content.charAt(i + m - 1);
                    i += badChar[c & 0xFF];
                }
            }
        } else {
            // Стандартный indexOf для коротких паттернов
            int idx = content.indexOf(pattern);
            while (idx >= 0) {
                positions.add(idx);
                if (maxResults > 0 && positions.size() >= maxResults) {
                    return positions;
                }
                idx = content.indexOf(pattern, idx + 1);
            }
        }

        return positions;
    }

    /**
     * Таблица сдвигов для Boyer-Moore-Horspool.
     */
    private static int[] buildBadCharTable(String pattern) {
        int[] table = new int[256];
        int m = pattern.length();
        Arrays.fill(table, m);
        for (int i = 0; i < m - 1; i++) {
            table[pattern.charAt(i) & 0xFF] = m - 1 - i;
        }
        return table;
    }

    /**
     * Поиск regex с ранней остановкой.
     */
    private static List<Integer> findRegexMatches(String content, String pattern, int maxResults) {
        List<Integer> positions = new ArrayList<>();
        Pattern p = Pattern.compile(pattern, Pattern.MULTILINE);
        Matcher m = p.matcher(content);

        while (m.find()) {
            positions.add(m.start());
            if (maxResults > 0 && positions.size() >= maxResults) {
                break;
            }
        }

        return positions;
    }

    /**
     * Собирает строки с контекстом, исключая дубликаты.
     */
    private static List<MatchedLine> collectMatchesWithContext(String content, int[] lineOffsets,
                                                                List<Integer> matchPositions,
                                                                int ctxBefore, int ctxAfter) {
        int lineCount = lineOffsets.length;

        // Множество номеров строк с совпадениями
        Set<Integer> matchLineNumbers = new HashSet<>();
        for (int pos : matchPositions) {
            matchLineNumbers.add(positionToLineNumber(lineOffsets, pos));
        }

        // Собираем все нужные строки (matches + context)
        Set<Integer> neededLines = new TreeSet<>(); // TreeSet для автосортировки
        for (int lineNum : matchLineNumbers) {
            int start = Math.max(1, lineNum - ctxBefore);
            int end = Math.min(lineCount, lineNum + ctxAfter);
            for (int i = start; i <= end; i++) {
                neededLines.add(i);
            }
        }

        // Извлекаем текст строк
        List<MatchedLine> result = new ArrayList<>();
        for (int lineNum : neededLines) {
            String lineText = extractLine(content, lineOffsets, lineNum);
            boolean isMatch = matchLineNumbers.contains(lineNum);
            result.add(new MatchedLine(lineNum, lineText, isMatch));
        }

        return result;
    }

    /**
     * Извлекает текст строки по номеру.
     */
    private static String extractLine(String content, int[] lineOffsets, int lineNum) {
        int idx = lineNum - 1;
        if (idx < 0 || idx >= lineOffsets.length) {
            return "";
        }

        int start = lineOffsets[idx];
        int end;
        if (idx + 1 < lineOffsets.length) {
            end = lineOffsets[idx + 1] - 1; // До \n
        } else {
            end = content.length();
        }

        if (end > start && content.charAt(end - 1) == '\r') {
            end--; // Удаляем \r
        }

        return content.substring(start, Math.max(start, end));
    }

    /**
     * Проверяет, является ли файл бинарным.
     */
    private static boolean isBinary(byte[] bytes) {
        int checkLimit = Math.min(bytes.length, 8192);
        for (int i = 0; i < checkLimit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Быстрая проверка, содержит ли файл текст, без полного чтения в память.
     * Использует Boyer-Moore-Horspool для больших паттернов.
     *
     * @param path путь к файлу
     * @param text искомый текст
     * @return true если файл содержит текст
     * @throws IOException при ошибке чтения
     */
    public static boolean containsText(Path path, String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return true;
        }

        byte[] pattern = text.getBytes(StandardCharsets.UTF_8);
        int patternLen = pattern.length;

        // Для коротких паттернов используем простой поиск
        if (patternLen < BMH_MIN_PATTERN_LEN) {
            return containsTextSimple(path, pattern);
        }

        // Для длинных паттернов используем Boyer-Moore-Horspool
        return containsTextBMH(path, pattern);
    }

    /**
     * Простой потоковый поиск для коротких паттернов.
     */
    private static boolean containsTextSimple(Path path, byte[] pattern) throws IOException {
        int patternLen = pattern.length;

        try (InputStream is = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE)) {
            byte[] window = new byte[patternLen];
            int windowFill = 0;

            // Заполняем начальное окно
            int b;
            while (windowFill < patternLen && (b = is.read()) != -1) {
                window[windowFill++] = (byte) b;
            }

            if (windowFill < patternLen) return false;
            if (Arrays.equals(window, pattern)) return true;

            // Скользящее окно
            while ((b = is.read()) != -1) {
                System.arraycopy(window, 1, window, 0, patternLen - 1);
                window[patternLen - 1] = (byte) b;

                if (Arrays.equals(window, pattern)) return true;
            }
            return false;
        }
    }

    /**
     * Boyer-Moore-Horspool поиск для длинных паттернов.
     * Читает файл блоками для эффективности.
     */
    private static boolean containsTextBMH(Path path, byte[] pattern) throws IOException {
        int m = pattern.length;

        // Строим таблицу сдвигов
        int[] badChar = new int[256];
        Arrays.fill(badChar, m);
        for (int i = 0; i < m - 1; i++) {
            badChar[pattern[i] & 0xFF] = m - 1 - i;
        }

        try (InputStream is = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE)) {
            // Буфер с перекрытием для обработки границ блоков
            byte[] buffer = new byte[BUFFER_SIZE + m - 1];
            int overlap = 0;

            while (true) {
                // Копируем перекрытие с предыдущего блока
                if (overlap > 0) {
                    System.arraycopy(buffer, BUFFER_SIZE, buffer, 0, overlap);
                }

                // Читаем новый блок
                int bytesRead = is.readNBytes(buffer, overlap, BUFFER_SIZE);
                if (bytesRead == 0) break;

                int totalLen = overlap + bytesRead;
                int searchEnd = totalLen - m;

                // Boyer-Moore-Horspool поиск в буфере
                int i = 0;
                while (i <= searchEnd) {
                    int j = m - 1;
                    while (j >= 0 && buffer[i + j] == pattern[j]) {
                        j--;
                    }
                    if (j < 0) {
                        return true; // Найдено!
                    }
                    i += badChar[buffer[i + m - 1] & 0xFF];
                }

                // Сохраняем перекрытие для следующего блока
                overlap = Math.min(m - 1, totalLen);
                if (overlap > 0 && totalLen > overlap) {
                    System.arraycopy(buffer, totalLen - overlap, buffer, BUFFER_SIZE, overlap);
                }
            }
        }

        return false;
    }
}
