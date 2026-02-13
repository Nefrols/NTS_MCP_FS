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

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32C;

/**
 * Immutable токен доступа к диапазону строк файла.
 * Формат сериализации: LAT:pathHash:startLine:endLine:rangeCrc32c:lineCount
 * <p>
 * Токен подтверждает, что LLM прочитала указанный диапазон строк файла
 * и имеет право редактировать эти строки, пока содержимое диапазона не изменилось.
 * <p>
 * Ключевое отличие: rangeCrc32c хранит CRC только диапазона строк, а не всего файла.
 * Это позволяет токенам оставаться валидными при изменениях в других частях файла.
 */
public record LineAccessToken(Path path,          // Абсолютный нормализованный путь к файлу
                              int startLine,      // Начало диапазона (1-based, включительно)
                              int endLine,        // Конец диапазона (1-based, включительно)
                              long rangeCrc32c,   // CRC32C содержимого диапазона на момент выдачи токена
                              int lineCount       // Количество строк в файле на момент выдачи
) {
    private static final String PREFIX = "LAT";
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    /**
     * Результат валидации токена с детальной диагностикой.
     */
    public enum ValidationResult {
        /** Токен валиден */
        VALID("Token is valid", null),

        /** CRC диапазона не совпадает (содержимое диапазона изменено) */
        CRC_MISMATCH(
            "Range content has changed since token was issued (CRC mismatch)",
            "Possible causes: (1) Range was modified by external process, " +
            "(2) 'undo' operation reverted changes, " +
            "(3) Another tool modified this range. " +
            "Solution: Re-read the range with nts_file_read to get a fresh token."
        ),

        /** Количество строк изменилось */
        LINE_COUNT_MISMATCH(
            "File structure changed since token was issued (line count mismatch)",
            "Possible causes: (1) Lines were added or removed by another operation, " +
            "(2) 'undo' operation changed file structure. " +
            "Solution: Re-read the affected range with nts_file_read."
        ),

        /** Токен не найден в реестре */
        NOT_FOUND(
            "Token not registered or has expired",
            "Possible causes: (1) Task was reset via nts_task reset, " +
            "(2) Token was never issued for this range, " +
            "(3) Token format is corrupted. " +
            "Solution: Read the file range first with nts_file_read to obtain a valid token."
        );

        private final String message;
        private final String suggestion;

        ValidationResult(String message, String suggestion) {
            this.message = message;
            this.suggestion = suggestion;
        }

        public boolean valid() {
            return this == VALID;
        }

        public String message() {
            return message;
        }

        /**
         * Возвращает подсказку для исправления ситуации.
         */
        public String suggestion() {
            return suggestion;
        }

        /**
         * Возвращает полное сообщение с подсказкой.
         */
        public String fullMessage() {
            if (suggestion == null) {
                return message;
            }
            return message + " | " + suggestion;
        }
    }

    /**
     * Валидация параметров при создании.
     */
    public LineAccessToken {
        Objects.requireNonNull(path, "Path cannot be null");
        path = path.toAbsolutePath().normalize();
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >= 1, got: " + startLine);
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException("endLine (" + endLine + ") must be >= startLine (" + startLine + ")");
        }
        if (lineCount < 0) {
            throw new IllegalArgumentException("lineCount must be >= 0, got: " + lineCount);
        }
    }

    /**
     * Сериализует токен в строку.
     * Формат: LAT:pathHash:startLine:endLine:rangeCrc32c:lineCount
     */
    public String encode() {
        String pathHash = hashPath(path);
        return String.format("%s:%s:%d:%d:%s:%d", PREFIX, pathHash, startLine, endLine, HEX.formatHex(longToBytes(rangeCrc32c)), lineCount);
    }

    /**
     * Десериализует токен из строки.
     *
     * @param token        Строковое представление токена
     * @param expectedPath Ожидаемый путь к файлу (для валидации)
     *
     * @return Десериализованный токен
     *
     * @throws IllegalArgumentException при неверном формате
     * @throws SecurityException        при несовпадении пути
     */
    public static LineAccessToken decode(String token, Path expectedPath) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }

        String[] parts = token.split(":");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid token format: expected 6 parts, got " + parts.length);
        }

        if (!PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("Invalid token prefix: expected '" + PREFIX + "', got '" + parts[0] + "'");
        }

        Path normalizedPath = expectedPath.toAbsolutePath().normalize();
        String expectedHash = hashPath(normalizedPath);
        String tokenHash = parts[1];

        // Task Tokens: пропускаем проверку пути если файл разблокирован в транзакции
        // Это позволяет использовать токен после rename/move в рамках батча
        boolean skipPathCheck = TransactionManager.isInTransaction() &&
                TransactionManager.isFileAccessedInTransaction(normalizedPath);

        // Проверка хеша пути
        if (!skipPathCheck && !expectedHash.equals(tokenHash)) {
            // Path Aliasing: проверяем, может ли токен быть для предыдущего пути этого файла
            // Это позволяет использовать токены после rename/move ВНЕ батча
            boolean aliasMatch = false;
            java.util.Set<Path> previousPaths = LineAccessTracker.getPreviousPaths(normalizedPath);
            for (Path oldPath : previousPaths) {
                String oldHash = hashPath(oldPath);
                if (oldHash.equals(tokenHash)) {
                    aliasMatch = true;
                    break;
                }
            }

            if (!aliasMatch) {
                throw new SecurityException("Token path mismatch: token was issued for a different file");
            }
        }

        try {
            int start = Integer.parseInt(parts[2]);
            int end = Integer.parseInt(parts[3]);
            long crc = Long.parseUnsignedLong(parts[4], 16);
            int lines = Integer.parseInt(parts[5]);

            return new LineAccessToken(normalizedPath, start, end, crc, lines);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid token: malformed numeric values", e);
        }
    }

    /**
     * Проверяет, перекрывается ли токен с указанным диапазоном.
     * Два диапазона перекрываются, если у них есть хотя бы одна общая строка.
     */
    public boolean overlaps(int otherStart, int otherEnd) {
        return startLine <= otherEnd && endLine >= otherStart;
    }

    /**
     * Проверяет, полностью ли покрывает токен указанный диапазон.
     * Токен покрывает диапазон, если все строки диапазона входят в токен.
     */
    public boolean covers(int otherStart, int otherEnd) {
        return startLine <= otherStart && endLine >= otherEnd;
    }

    /**
     * Проверяет, является ли диапазон смежным с токеном.
     * Диапазоны смежны, если между ними нет промежутка (можно объединить).
     */
    public boolean isAdjacentTo(int otherStart, int otherEnd) {
        return endLine + 1 == otherStart || otherEnd + 1 == startLine;
    }

    /**
     * Создаёт новый токен со смещённым диапазоном.
     * Используется при пересчёте смещений после вставки/удаления строк.
     *
     * @param delta Смещение (положительное при вставке, отрицательное при удалении)
     *
     * @return Новый токен со смещёнными границами
     */
    public LineAccessToken shift(int delta, long newCrc, int newLineCount) {
        return new LineAccessToken(path, startLine + delta, endLine + delta, newCrc, newLineCount);
    }

    /**
     * Создаёт объединённый токен из двух перекрывающихся/смежных токенов.
     *
     * @param other Другой токен для объединения
     *
     * @return Новый токен, покрывающий оба диапазона
     *
     * @throws IllegalArgumentException если токены нельзя объединить
     */
    public LineAccessToken merge(LineAccessToken other, long mergedRangeCrc) {
        if (!path.equals(other.path)) {
            throw new IllegalArgumentException("Cannot merge tokens from different files");
        }

        // Проверяем смежность или перекрытие
        boolean canMerge = overlaps(other.startLine, other.endLine) || isAdjacentTo(other.startLine, other.endLine);
        if (!canMerge) {
            throw new IllegalArgumentException("Cannot merge non-adjacent tokens: [" + startLine + "-" + endLine + "] and [" + other.startLine + "-" + other.endLine + "]");
        }

        return new LineAccessToken(path, Math.min(startLine, other.startLine), Math.max(endLine, other.endLine), mergedRangeCrc, lineCount);
    }

    /**
     * Возвращает количество строк в диапазоне токена.
     */
    public int rangeSize() {
        return endLine - startLine + 1;
    }

    @Override
    public String toString() {
        return String.format("LineAccessToken[%s:%d-%d, rangeCrc=%X, lines=%d]", path.getFileName(), startLine, endLine, rangeCrc32c, lineCount);
    }

    // ============ Вспомогательные методы ============

    /**
     * Вычисляет 8-символьный хеш пути для компактного представления в токене.
     */
    private static String hashPath(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(path.toAbsolutePath().normalize().toString().getBytes());
            return HEX.formatHex(hash).substring(0, 8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash path", e);
        }
    }

    /**
     * Конвертирует long в массив байтов (4 младших байта).
     */
    private static byte[] longToBytes(long value) {
        return new byte[]{
                (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    /**
     * Вычисляет CRC32C для содержимого диапазона строк.
     * Используется для создания и валидации токенов.
     *
     * @param rangeContent Содержимое диапазона строк (текст)
     * @return CRC32C хеш содержимого
     */
    public static long computeRangeCrc(String rangeContent) {
        if (rangeContent == null || rangeContent.isEmpty()) {
            return 0L;
        }
        CRC32C crc = new CRC32C();
        byte[] bytes = rangeContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        crc.update(bytes);
        return crc.getValue();
    }

    /**
     * Создаёт новый токен с обновлённым CRC диапазона.
     * Используется при сохранении позиции токена, но обновлении CRC.
     *
     * @param newRangeCrc Новый CRC диапазона
     * @return Новый токен с обновлённым CRC
     */
    public LineAccessToken withRangeCrc(long newRangeCrc) {
        return new LineAccessToken(path, startLine, endLine, newRangeCrc, lineCount);
    }

    /**
     * Создаёт новый токен с обновлённым количеством строк.
     * Используется при изменении размера файла без изменения содержимого диапазона.
     *
     * @param newLineCount Новое количество строк в файле
     * @return Новый токен с обновлённым lineCount
     */
    public LineAccessToken withLineCount(int newLineCount) {
        return new LineAccessToken(path, startLine, endLine, rangeCrc32c, newLineCount);
    }

    /**
     * Создаёт новый токен с расширенным диапазоном (auto-expand).
     * Используется при добавлении строк внутри диапазона токена.
     *
     * @param lineDelta Количество добавленных строк (положительное)
     * @param newRangeCrc Новый CRC расширенного диапазона
     * @param newLineCount Новое количество строк в файле
     * @return Новый токен с расширенным диапазоном
     */
    public LineAccessToken expand(int lineDelta, long newRangeCrc, int newLineCount) {
        return new LineAccessToken(path, startLine, endLine + lineDelta, newRangeCrc, newLineCount);
    }
}
