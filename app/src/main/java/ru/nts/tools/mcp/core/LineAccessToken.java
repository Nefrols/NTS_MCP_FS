// Aristo 24.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable токен доступа к диапазону строк файла.
 * Формат сериализации: LAT:pathHash:startLine:endLine:fileCrc32c:lineCount
 * <p>
 * Токен подтверждает, что LLM прочитала указанный диапазон строк файла
 * и имеет право редактировать эти строки, пока файл не изменился.
 */
public record LineAccessToken(Path path,          // Абсолютный нормализованный путь к файлу
                              int startLine,      // Начало диапазона (1-based, включительно)
                              int endLine,        // Конец диапазона (1-based, включительно)
                              long fileCrc32c,    // CRC32C файла на момент выдачи токена
                              int lineCount       // Количество строк в файле на момент выдачи
) {
    private static final String PREFIX = "LAT";
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

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
     * Формат: LAT:pathHash:startLine:endLine:crc32c:lineCount
     */
    public String encode() {
        String pathHash = hashPath(path);
        return String.format("%s:%s:%d:%d:%s:%d", PREFIX, pathHash, startLine, endLine, HEX.formatHex(longToBytes(fileCrc32c)), lineCount);
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
        if (!expectedHash.equals(parts[1])) {
            throw new SecurityException("Token path mismatch: token was issued for a different file");
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
    public LineAccessToken merge(LineAccessToken other) {
        if (!path.equals(other.path)) {
            throw new IllegalArgumentException("Cannot merge tokens from different files");
        }
        if (fileCrc32c != other.fileCrc32c) {
            throw new IllegalArgumentException("Cannot merge tokens with different CRC (file versions)");
        }

        // Проверяем смежность или перекрытие
        boolean canMerge = overlaps(other.startLine, other.endLine) || isAdjacentTo(other.startLine, other.endLine);
        if (!canMerge) {
            throw new IllegalArgumentException("Cannot merge non-adjacent tokens: [" + startLine + "-" + endLine + "] and [" + other.startLine + "-" + other.endLine + "]");
        }

        return new LineAccessToken(path, Math.min(startLine, other.startLine), Math.max(endLine, other.endLine), fileCrc32c, lineCount);
    }

    /**
     * Возвращает количество строк в диапазоне токена.
     */
    public int rangeSize() {
        return endLine - startLine + 1;
    }

    @Override
    public String toString() {
        return String.format("LineAccessToken[%s:%d-%d, crc=%X, lines=%d]", path.getFileName(), startLine, endLine, fileCrc32c, lineCount);
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
}
