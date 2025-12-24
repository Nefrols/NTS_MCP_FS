// Aristo 24.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Система отслеживания доступа к строкам файлов (Line Access Tracker).
 * Заменяет старый AccessTracker, реализуя гранулярный контроль на уровне строк.
 * <p>
 * Основные функции:
 * - Регистрация прочитанных диапазонов строк с выдачей токенов
 * - Валидация токенов перед редактированием
 * - Слияние смежных диапазонов для оптимизации
 * - Пересчёт смещений при вставке/удалении строк
 * - Инвалидация токенов при изменении файла
 */
public class LineAccessTracker {

    /**
     * Хранилище токенов: Path -> TreeMap<StartLine, Token>.
     * TreeMap обеспечивает эффективный поиск перекрытий O(log n).
     */
    private static final Map<Path, TreeMap<Integer, LineAccessToken>> tokens = new ConcurrentHashMap<>();

    /**
     * Регистрирует доступ к диапазону строк и возвращает токен.
     * <p>
     * Логика автослияния:
     * 1. Если существующий токен полностью покрывает запрошенный диапазон - возвращает его без изменений
     * 2. Если новый диапазон полностью поглощает существующие токены - удаляет их
     * 3. Смежные и частично перекрывающиеся токены объединяются
     *
     * @param path       Путь к файлу
     * @param startLine  Начало диапазона (1-based)
     * @param endLine    Конец диапазона (1-based, inclusive)
     * @param fileCrc32c CRC32C файла
     * @param lineCount  Количество строк в файле
     *
     * @return Токен доступа к диапазону (существующий или новый)
     */
    public static LineAccessToken registerAccess(Path path, int startLine, int endLine, long fileCrc32c, int lineCount) {

        Path absPath = path.toAbsolutePath().normalize();

        // 1. Сначала проверяем, есть ли существующий токен, полностью покрывающий запрос
        TreeMap<Integer, LineAccessToken> existing = tokens.get(absPath);
        if (existing != null) {
            for (LineAccessToken t : existing.values()) {
                if (t.fileCrc32c() == fileCrc32c && t.covers(startLine, endLine)) {
                    // Запрос внутри существующего диапазона - возвращаем старый токен
                    return t;
                }
            }
        }

        // 2. Создаём новый токен и обрабатываем слияние/поглощение
        final LineAccessToken newToken = new LineAccessToken(absPath, startLine, endLine, fileCrc32c, lineCount);

        tokens.compute(absPath, (p, fileTokens) -> {
            if (fileTokens == null) {
                fileTokens = new TreeMap<>();
                fileTokens.put(startLine, newToken);
                return fileTokens;
            }

            // Собираем токены для обработки
            List<Integer> keysToRemove = new ArrayList<>();
            List<LineAccessToken> tokensToMerge = new ArrayList<>();

            for (Map.Entry<Integer, LineAccessToken> entry : fileTokens.entrySet()) {
                LineAccessToken t = entry.getValue();
                if (t.fileCrc32c() != fileCrc32c) {
                    continue; // Разный CRC - не трогаем
                }

                // Новый диапазон полностью поглощает старый - удаляем старый
                if (startLine <= t.startLine() && endLine >= t.endLine()) {
                    keysToRemove.add(entry.getKey());
                }
                // Смежные или частично перекрывающиеся - мержим
                else if (t.overlaps(startLine, endLine) || t.isAdjacentTo(startLine, endLine)) {
                    keysToRemove.add(entry.getKey());
                    tokensToMerge.add(t);
                }
            }

            // Удаляем поглощённые/мержащиеся токены
            keysToRemove.forEach(fileTokens::remove);

            // Объединяем с новым токеном
            LineAccessToken merged = newToken;
            for (LineAccessToken t : tokensToMerge) {
                merged = merged.merge(t);
            }

            fileTokens.put(merged.startLine(), merged);
            return fileTokens;
        });

        // Возвращаем финальный токен
        TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
        if (fileTokens != null) {
            for (LineAccessToken t : fileTokens.values()) {
                if (t.covers(startLine, endLine) && t.fileCrc32c() == fileCrc32c) {
                    return t;
                }
            }
        }
        return newToken;
    }

    /**
     * Проверяет валидность токена.
     *
     * @param token            Токен для проверки
     * @param currentCrc32c    Текущий CRC32C файла
     * @param currentLineCount Текущее количество строк
     *
     * @return Результат валидации с деталями
     */
    public static ValidationResult validateToken(LineAccessToken token, long currentCrc32c, int currentLineCount) {

        // 1. Проверка изменения количества строк (структурное изменение)
        if (token.lineCount() != currentLineCount) {
            return new ValidationResult(false, String.format("Line count changed: %d -> %d. File structure modified.", token.lineCount(), currentLineCount), ValidationFailureReason.LINE_COUNT_CHANGED);
        }

        // 2. Проверка CRC (содержимое изменилось)
        if (token.fileCrc32c() != currentCrc32c) {
            return new ValidationResult(false, "File content changed (CRC mismatch). Re-read required.", ValidationFailureReason.CRC_MISMATCH);
        }

        // 3. Проверка наличия токена в хранилище
        Path absPath = token.path().toAbsolutePath().normalize();
        TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
        if (fileTokens == null || fileTokens.isEmpty()) {
            return new ValidationResult(false, "No access tokens found for file. Read file first.", ValidationFailureReason.TOKEN_NOT_FOUND);
        }

        // 4. Проверка покрытия диапазона
        boolean covered = isRangeCovered(absPath, token.startLine(), token.endLine(), currentCrc32c);
        if (!covered) {
            return new ValidationResult(false, String.format("Lines %d-%d not covered by valid tokens.", token.startLine(), token.endLine()), ValidationFailureReason.RANGE_NOT_COVERED);
        }

        return new ValidationResult(true, "Token valid", null);
    }

    /**
     * Проверяет, покрыт ли указанный диапазон действительными токенами.
     */
    public static boolean isRangeCovered(Path path, int startLine, int endLine, long currentCrc32c) {

        Path absPath = path.toAbsolutePath().normalize();
        TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);

        if (fileTokens == null || fileTokens.isEmpty()) {
            return false;
        }

        // Собираем все валидные интервалы (с совпадающим CRC)
        List<int[]> intervals = fileTokens.values().stream().filter(t -> t.fileCrc32c() == currentCrc32c).map(t -> new int[]{t.startLine(), t.endLine()}).sorted(Comparator.comparingInt(a -> a[0])).collect(Collectors.toList());

        return checkCoverage(intervals, startLine, endLine);
    }

    /**
     * Полностью инвалидирует все токены для файла.
     * Вызывается при критических изменениях структуры файла.
     */
    public static void invalidateFile(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        tokens.remove(absPath);
    }

    /**
     * Пересчитывает смещения токенов после вставки/удаления строк.
     * Токены выше точки изменения остаются неизменными.
     * Токены на точке изменения или ниже сдвигаются на delta.
     * Перекрывающиеся с точкой изменения токены инвалидируются.
     *
     * @param path         Путь к файлу
     * @param afterLine    Строка, после которой произошла вставка/удаление
     * @param delta        Смещение (+N для вставки, -N для удаления)
     * @param newCrc       Новый CRC файла после изменения
     * @param newLineCount Новое количество строк
     */
    public static void shiftTokensAfterLine(Path path, int afterLine, int delta, long newCrc, int newLineCount) {

        Path absPath = path.toAbsolutePath().normalize();
        TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);

        if (fileTokens == null || fileTokens.isEmpty()) {
            return;
        }

        // Разделяем токены на: выше точки, перекрывающие, ниже точки
        List<LineAccessToken> toKeep = new ArrayList<>();
        List<LineAccessToken> toShift = new ArrayList<>();

        for (LineAccessToken t : fileTokens.values()) {
            if (t.endLine() < afterLine) {
                // Токен полностью выше точки изменения - сохраняем как есть
                // Но обновляем lineCount
                toKeep.add(new LineAccessToken(t.path(), t.startLine(), t.endLine(), newCrc, newLineCount));
            } else if (t.startLine() > afterLine) {
                // Токен полностью ниже точки - сдвигаем
                toShift.add(t.shift(delta, newCrc, newLineCount));
            }
            // Токены, перекрывающие точку изменения, удаляются (не добавляем)
        }

        // Пересоздаём хранилище для файла
        TreeMap<Integer, LineAccessToken> newFileTokens = new TreeMap<>();
        for (LineAccessToken t : toKeep) {
            newFileTokens.put(t.startLine(), t);
        }
        for (LineAccessToken t : toShift) {
            newFileTokens.put(t.startLine(), t);
        }

        if (newFileTokens.isEmpty()) {
            tokens.remove(absPath);
        } else {
            tokens.put(absPath, newFileTokens);
        }
    }

    /**
     * Обновляет токены после редактирования.
     * Вызывается после успешного применения правок.
     *
     * @param path            Путь к файлу
     * @param editedStartLine Начало отредактированного диапазона
     * @param editedEndLine   Конец отредактированного диапазона
     * @param lineDelta       Изменение количества строк (+N/-N или 0)
     * @param newCrc          Новый CRC файла
     * @param newLineCount    Новое количество строк
     *
     * @return Новый токен на отредактированный диапазон
     */
    public static LineAccessToken updateAfterEdit(Path path, int editedStartLine, int editedEndLine, int lineDelta, long newCrc, int newLineCount) {

        Path absPath = path.toAbsolutePath().normalize();

        if (lineDelta != 0) {
            // Структурное изменение - пересчитываем смещения
            shiftTokensAfterLine(absPath, editedStartLine, lineDelta, newCrc, newLineCount);
        } else {
            // Только содержимое изменилось - инвалидируем перекрывающиеся токены
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            if (fileTokens != null) {
                List<Integer> toRemove = fileTokens.values().stream().filter(t -> t.overlaps(editedStartLine, editedEndLine)).map(LineAccessToken::startLine).collect(Collectors.toList());
                toRemove.forEach(fileTokens::remove);

                // Обновляем CRC для оставшихся токенов
                List<LineAccessToken> updated = new ArrayList<>();
                for (Integer key : new ArrayList<>(fileTokens.keySet())) {
                    LineAccessToken t = fileTokens.remove(key);
                    updated.add(new LineAccessToken(t.path(), t.startLine(), t.endLine(), newCrc, newLineCount));
                }
                for (LineAccessToken t : updated) {
                    fileTokens.put(t.startLine(), t);
                }
            }
        }

        // Выдаём новый токен на отредактированный диапазон
        int newEndLine = editedEndLine + lineDelta;
        return registerAccess(absPath, editedStartLine, newEndLine, newCrc, newLineCount);
    }

    /**
     * Переносит токены при перемещении/переименовании файла.
     */
    public static void moveTokens(Path source, Path target) {
        Path sourceAbs = source.toAbsolutePath().normalize();
        Path targetAbs = target.toAbsolutePath().normalize();

        TreeMap<Integer, LineAccessToken> sourceTokens = tokens.remove(sourceAbs);
        if (sourceTokens != null && !sourceTokens.isEmpty()) {
            // Пересоздаём токены с новым путём
            TreeMap<Integer, LineAccessToken> targetTokens = new TreeMap<>();
            for (LineAccessToken t : sourceTokens.values()) {
                LineAccessToken newToken = new LineAccessToken(targetAbs, t.startLine(), t.endLine(), t.fileCrc32c(), t.lineCount());
                targetTokens.put(newToken.startLine(), newToken);
            }
            tokens.put(targetAbs, targetTokens);
        }
    }

    /**
     * Возвращает список активных токенов для файла.
     */
    public static List<LineAccessToken> getTokensForFile(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
        if (fileTokens == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(fileTokens.values());
    }

    /**
     * Возвращает все файлы с активными токенами.
     */
    public static Set<Path> getAccessedFiles() {
        return Collections.unmodifiableSet(tokens.keySet());
    }

    /**
     * Форматирует информацию о прочитанных диапазонах для отображения.
     * Формат: [1-10], [50-60]
     */
    public static String formatAccessedRanges(Path path) {
        List<LineAccessToken> fileTokens = getTokensForFile(path);
        if (fileTokens.isEmpty()) {
            return "";
        }

        return fileTokens.stream().sorted(Comparator.comparingInt(LineAccessToken::startLine)).map(t -> String.format("[%d-%d]", t.startLine(), t.endLine())).collect(Collectors.joining(", "));
    }

    /**
     * Форматирует информацию о прочитанных диапазонах с токенами (одна строка).
     * Формат: [1-10 | TOKEN: LAT:...], [50-60 | TOKEN: LAT:...]
     */
    public static String formatAccessedRangesWithTokens(Path path) {
        List<LineAccessToken> fileTokens = getTokensForFile(path);
        if (fileTokens.isEmpty()) {
            return "";
        }

        return fileTokens.stream().sorted(Comparator.comparingInt(LineAccessToken::startLine)).map(t -> String.format("[%d-%d | TOKEN: %s]", t.startLine(), t.endLine(), t.encode())).collect(Collectors.joining(", "));
    }

    /**
     * Возвращает список форматированных строк токенов для отображения построчно.
     * Каждая строка: "  [Lines 1-10 | TOKEN: LAT:...]"
     *
     * @param path   Путь к файлу
     * @param indent Отступ для форматирования
     *
     * @return Список строк токенов, пустой если токенов нет
     */
    public static List<String> getFormattedTokenLines(Path path, String indent) {
        List<LineAccessToken> fileTokens = getTokensForFile(path);
        if (fileTokens.isEmpty()) {
            return Collections.emptyList();
        }

        return fileTokens.stream().sorted(Comparator.comparingInt(LineAccessToken::startLine)).map(t -> String.format("%s  [Lines %d-%d | TOKEN: %s]", indent, t.startLine(), t.endLine(), t.encode())).collect(Collectors.toList());
    }

    /**
     * Проверяет, есть ли хотя бы один токен для файла (для обратной совместимости).
     */
    public static boolean hasAnyAccess(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
        return fileTokens != null && !fileTokens.isEmpty();
    }

    /**
     * Полная очистка (для тестов и сброса сессии).
     */
    public static void reset() {
        tokens.clear();
    }

    // ============ Вспомогательные методы ============

    /**
     * Проверяет, покрывают ли объединённые интервалы указанный диапазон.
     */
    private static boolean checkCoverage(List<int[]> intervals, int start, int end) {
        if (intervals.isEmpty()) {
            return false;
        }

        // Объединяем перекрывающиеся/смежные интервалы
        List<int[]> merged = new ArrayList<>();
        int[] current = intervals.get(0).clone();

        for (int i = 1; i < intervals.size(); i++) {
            int[] next = intervals.get(i);
            if (next[0] <= current[1] + 1) {
                // Перекрытие или смежность - расширяем
                current[1] = Math.max(current[1], next[1]);
            } else {
                // Разрыв - сохраняем текущий и начинаем новый
                merged.add(current);
                current = next.clone();
            }
        }
        merged.add(current);

        // Проверяем, есть ли интервал, полностью покрывающий [start, end]
        for (int[] interval : merged) {
            if (interval[0] <= start && interval[1] >= end) {
                return true;
            }
        }
        return false;
    }

    // ============ Вложенные типы ============

    /**
     * Результат валидации токена.
     */
    public record ValidationResult(boolean valid, String message, ValidationFailureReason reason) {
    }

    /**
     * Причина неудачной валидации.
     */
    public enum ValidationFailureReason {
        /**
         * Количество строк в файле изменилось
         */
        LINE_COUNT_CHANGED,
        /**
         * CRC файла не совпадает (содержимое изменено)
         */
        CRC_MISMATCH,
        /**
         * Токен не найден в хранилище
         */
        TOKEN_NOT_FOUND,
        /**
         * Запрошенный диапазон не покрыт действительными токенами
         */
        RANGE_NOT_COVERED
    }
}
