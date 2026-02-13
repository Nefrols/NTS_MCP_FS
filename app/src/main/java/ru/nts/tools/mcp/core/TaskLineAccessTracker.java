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
import java.util.*;

/**
 * Per-task трекер токенов доступа к строкам файлов.
 * Обеспечивает изоляцию токенов между задачами.
 *
 * Каждая задача имеет собственный экземпляр этого класса.
 */
public class TaskLineAccessTracker {

    // Per-task хранилище токенов (Path -> TreeMap<startLine, Token>)
    private final Map<Path, TreeMap<Integer, LineAccessToken>> tokens = new HashMap<>();

    // Per-file CRC кэш для инвалидации покрывающих токенов при изменении файла
    private final Map<Path, Long> fileCrcCache = new HashMap<>();

    // Path aliasing: отслеживает перемещения файлов (oldPath -> currentPath)
    // Позволяет использовать старые токены после move/rename вне батча
    private final Map<Path, Path> pathAliases = new HashMap<>();

    // Reverse alias map: currentPath -> Set<oldPaths>
    // Для проверки токенов с хешем старого пути против нового пути
    private final Map<Path, Set<Path>> reverseAliases = new HashMap<>();

    // Синхронизация для потокобезопасности внутри задачи
    private final Object lock = new Object();

    /**
     * Регистрирует доступ к диапазону строк и возвращает токен.
     *
     * Логика регистрации:
     * 1. Точное совпадение диапазона + CRC совпадает -> возвращаем существующий токен
     * 2. Точное совпадение диапазона + CRC изменился -> создаём новый (файл изменился)
     * 3. Существующий токен ПОКРЫВАЕТ запрос -> возвращаем покрывающий токен
     * 4. Иначе -> создаём новый токен
     *
     * ВАЖНО про CRC:
     * - CRC вычисляется для конкретного диапазона строк
     * - CRC токена [1-100] != CRC диапазона [50-60] (это разные диапазоны!)
     * - Проверка CRC при ИСПОЛЬЗОВАНИИ токена (validateToken) гарантирует,
     *   что содержимое диапазона токена не изменилось с момента чтения
     * - При возврате покрывающего токена, его CRC будет проверен при использовании
     *
     * @param path Путь к файлу
     * @param startLine Начало диапазона (1-based)
     * @param endLine Конец диапазона (1-based)
     * @param rangeContent Содержимое диапазона строк (для вычисления CRC)
     * @param lineCount Общее количество строк в файле
     * @return Токен доступа к диапазону (может быть шире запрошенного!)
     */
    public LineAccessToken registerAccess(Path path, int startLine, int endLine, String rangeContent, int lineCount, long fileCrc) {
        Path absPath = path.toAbsolutePath().normalize();
        long rangeCrc = LineAccessToken.computeRangeCrc(rangeContent);

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.computeIfAbsent(absPath, k -> new TreeMap<>());

            // 0. Инвалидация: если CRC файла изменился, все токены этого файла протухли
            Long cachedCrc = fileCrcCache.get(absPath);
            if (cachedCrc != null && cachedCrc != fileCrc) {
                fileTokens.clear();
            }
            fileCrcCache.put(absPath, fileCrc);

            // 1. Проверяем точное совпадение диапазона
            LineAccessToken exactMatch = fileTokens.get(startLine);
            if (exactMatch != null &&
                    exactMatch.endLine() == endLine &&
                    exactMatch.lineCount() == lineCount) {
                // Точное совпадение диапазона - проверяем CRC
                if (exactMatch.rangeCrc32c() == rangeCrc) {
                    // Содержимое не изменилось - возвращаем существующий токен
                    return exactMatch;
                }
                // CRC изменился (undo/redo/external) - удаляем устаревший токен
                fileTokens.remove(startLine);
            }

            // 2. Проверяем, есть ли токен, покрывающий запрошенный диапазон
            // Если да - возвращаем его (LLM получит более широкий доступ)
            // CRC покрывающего токена будет проверен при использовании (validateToken)
            for (LineAccessToken existing : fileTokens.values()) {
                if (existing.lineCount() == lineCount && existing.covers(startLine, endLine)) {
                    // Покрывающий токен найден - возвращаем его
                    return existing;
                }
            }

            // 3. Удаляем токены, которые полностью внутри нового диапазона
            fileTokens.entrySet().removeIf(entry -> {
                LineAccessToken t = entry.getValue();
                return t.lineCount() == lineCount &&
                       startLine <= t.startLine() && t.endLine() <= endLine;
            });

            // 4. Удаляем пересекающиеся токены (не сливаем - каждый токен имеет свой CRC)
            fileTokens.entrySet().removeIf(entry -> {
                LineAccessToken t = entry.getValue();
                return t.lineCount() == lineCount && t.overlaps(startLine, endLine);
            });

            // 5. Создаём новый токен с CRC для запрошенного диапазона
            LineAccessToken newToken = new LineAccessToken(absPath, startLine, endLine, rangeCrc, lineCount);
            fileTokens.put(startLine, newToken);

            return newToken;
        }
    }

    /**
     * Валидирует токен против текущего состояния диапазона.
     *
     * Task Tokens: Если файл был разблокирован в текущей транзакции,
     * CRC-проверка пропускается и токен автоматически валиден (все изменения контролируемы).
     *
     * InfinityRange: Если файл создан в текущей транзакции,
     * токен автоматически валиден (нет предыдущего состояния для сравнения).
     *
     * @param token Токен для валидации
     * @param currentRangeContent Текущее содержимое диапазона (для вычисления CRC)
     * @param currentLineCount Текущее количество строк в файле
     * @return Результат валидации
     */
    public LineAccessToken.ValidationResult validateToken(LineAccessToken token, String currentRangeContent, int currentLineCount) {
        Path path = token.path();

        // Task Tokens + InfinityRange: внутри транзакции токен автоматически валиден
        // для файлов с зарегистрированным доступом или созданных в транзакции
        boolean taskUnlocked = TransactionManager.isInTransaction() &&
                (TransactionManager.isFileAccessedInTransaction(path) ||
                 TransactionManager.isFileCreatedInTransaction(path));

        if (taskUnlocked) {
            // Внутри транзакции токен для разблокированного файла всегда валиден
            return LineAccessToken.ValidationResult.VALID;
        }

        // Проверка количества строк (структура файла)
        if (token.lineCount() != currentLineCount) {
            return LineAccessToken.ValidationResult.LINE_COUNT_MISMATCH;
        }

        // Вычисляем CRC текущего содержимого диапазона
        long currentRangeCrc = LineAccessToken.computeRangeCrc(currentRangeContent);

        // Проверка CRC диапазона
        if (token.rangeCrc32c() != currentRangeCrc) {
            return LineAccessToken.ValidationResult.CRC_MISMATCH;
        }

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(path);
            if (fileTokens == null) {
                return LineAccessToken.ValidationResult.NOT_FOUND;
            }

            for (LineAccessToken stored : fileTokens.values()) {
                if (stored.covers(token.startLine(), token.endLine()) &&
                        stored.lineCount() == currentLineCount) {
                    // Токен найден в реестре и покрывает диапазон
                    return LineAccessToken.ValidationResult.VALID;
                }
            }
        }
        return LineAccessToken.ValidationResult.NOT_FOUND;
    }

    /**
     * Проверяет, покрыт ли диапазон существующим токеном.
     *
     * @param path Путь к файлу
     * @param startLine Начало диапазона (1-based)
     * @param endLine Конец диапазона (1-based)
     * @param rangeContent Содержимое диапазона для проверки CRC
     * @return true если диапазон покрыт валидным токеном
     */
    public boolean isRangeCovered(Path path, int startLine, int endLine, String rangeContent) {
        Path absPath = path.toAbsolutePath().normalize();
        long rangeCrc = LineAccessToken.computeRangeCrc(rangeContent);

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            if (fileTokens == null) {
                return false;
            }

            for (LineAccessToken token : fileTokens.values()) {
                if (token.rangeCrc32c() == rangeCrc && token.covers(startLine, endLine)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Инвалидирует все токены для файла.
     */
    public void invalidateFile(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        synchronized (lock) {
            tokens.remove(absPath);
            fileCrcCache.remove(absPath);
        }
    }

    /**
     * Сдвигает токены после указанной строки.
     * rangeCrc сохраняется, так как содержимое диапазона не меняется, только позиция.
     *
     * @param path Путь к файлу
     * @param afterLine Строка, после которой произошла вставка/удаление
     * @param delta Смещение (положительное при вставке, отрицательное при удалении)
     * @param newLineCount Новое количество строк в файле
     */
    public void shiftTokensAfterLine(Path path, int afterLine, int delta, int newLineCount) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            if (fileTokens == null || delta == 0) {
                return;
            }

            TreeMap<Integer, LineAccessToken> newTokens = new TreeMap<>();
            for (Map.Entry<Integer, LineAccessToken> entry : fileTokens.entrySet()) {
                LineAccessToken t = entry.getValue();

                if (t.endLine() < afterLine) {
                    // Токен выше точки изменения - сохраняем rangeCrc, обновляем только lineCount
                    newTokens.put(t.startLine(), t.withLineCount(newLineCount));
                } else if (t.startLine() > afterLine) {
                    // Токен ниже точки изменения - сдвигаем, rangeCrc сохраняется
                    int newStart = t.startLine() + delta;
                    int newEnd = t.endLine() + delta;
                    if (newStart > 0 && newEnd > 0) {
                        LineAccessToken shifted = new LineAccessToken(
                                t.path(), newStart, newEnd, t.rangeCrc32c(), newLineCount
                        );
                        newTokens.put(newStart, shifted);
                    }
                } else {
                    // Токен пересекается с точкой вставки - инвалидируем
                }
            }

            tokens.put(absPath, newTokens);
        }
    }

    /**
     * Обновляет токены после редактирования и возвращает новый токен для изменённого диапазона.
     *
     * Ключевая логика Smart Token Invalidation:
     * - Токены ВЫШЕ точки редактирования: сохраняют свой rangeCrc (содержимое не изменилось!)
     * - Токены НИЖЕ точки редактирования: сдвигаются, rangeCrc сохраняется
     * - Токены ПЕРЕСЕКАЮЩИЕСЯ: AUTO-EXPAND если строки добавляются внутри токена
     *
     * @param path Путь к файлу
     * @param editStart Начало редактируемого диапазона (1-based)
     * @param editEnd Конец редактируемого диапазона (1-based)
     * @param lineDelta Изменение количества строк (положительное при добавлении)
     * @param editedRangeContent Содержимое изменённого диапазона (для CRC нового токена)
     * @param newLineCount Новое общее количество строк в файле
     * @return Новый токен для изменённого диапазона
     */
    public LineAccessToken updateAfterEdit(Path path, int editStart, int editEnd,
                                           int lineDelta, String editedRangeContent, int newLineCount) {
        Path absPath = path.toAbsolutePath().normalize();
        long newRangeCrc = LineAccessToken.computeRangeCrc(editedRangeContent);

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            if (fileTokens == null) {
                fileTokens = new TreeMap<>();
                tokens.put(absPath, fileTokens);
            }

            TreeMap<Integer, LineAccessToken> newTokens = new TreeMap<>();
            for (Map.Entry<Integer, LineAccessToken> entry : fileTokens.entrySet()) {
                LineAccessToken t = entry.getValue();

                if (t.endLine() < editStart) {
                    // Токен ВЫШЕ точки редактирования
                    // Содержимое диапазона не изменилось, сохраняем rangeCrc!
                    // Обновляем только lineCount
                    newTokens.put(t.startLine(), t.withLineCount(newLineCount));

                } else if (t.startLine() > editEnd) {
                    // Токен НИЖЕ точки редактирования
                    // Сдвигаем позицию, rangeCrc сохраняется (содержимое не изменилось)
                    int newStart = t.startLine() + lineDelta;
                    int newEnd = t.endLine() + lineDelta;
                    if (newStart > 0 && newEnd > 0) {
                        LineAccessToken shifted = new LineAccessToken(
                                t.path(), newStart, newEnd, t.rangeCrc32c(), newLineCount
                        );
                        newTokens.put(newStart, shifted);
                    }

                } else if (lineDelta > 0 && t.startLine() <= editStart && t.endLine() >= editEnd) {
                    // AUTO-EXPAND: токен полностью содержит редактируемый диапазон
                    // и были ДОБАВЛЕНЫ строки (lineDelta > 0)
                    // Расширяем токен, но нужен новый CRC для расширенного диапазона
                    // Это будет сделано вызывающим кодом, который знает новое содержимое
                    // Пока просто расширяем endLine
                    // ВАЖНО: CRC станет невалидным, но это ожидаемо - нужно перечитать
                    // Оставляем старый CRC, при следующей валидации будет CRC_MISMATCH
                    LineAccessToken expanded = t.expand(lineDelta, t.rangeCrc32c(), newLineCount);
                    newTokens.put(t.startLine(), expanded);

                }
                // Остальные пересекающиеся токены инвалидируются (не добавляются в newTokens)
            }

            // Создаём новый токен для изменённого диапазона
            // При удалении (lineDelta < 0) newEditEnd может стать < editStart
            // Гарантируем что newEditEnd >= editStart (минимум одна строка покрытия)
            int newEditEnd = Math.max(editStart, editEnd + lineDelta);
            LineAccessToken editToken = new LineAccessToken(absPath, editStart, newEditEnd, newRangeCrc, newLineCount);
            newTokens.put(editStart, editToken);

            tokens.put(absPath, newTokens);
            return editToken;
        }
    }

    /**
     * Переносит токены при перемещении/переименовании файла.
     * rangeCrc сохраняется, так как содержимое файла не меняется при перемещении.
     * Также регистрирует alias для того, чтобы старые токены работали вне batch.
     */
    public void moveTokens(Path oldPath, Path newPath) {
        Path absOld = oldPath.toAbsolutePath().normalize();
        Path absNew = newPath.toAbsolutePath().normalize();

        synchronized (lock) {
            // Регистрируем alias: старый путь -> новый путь
            // Это позволяет использовать старые токены после move/rename вне батча
            pathAliases.put(absOld, absNew);

            // Reverse alias для проверки токенов со старым хешем
            reverseAliases.computeIfAbsent(absNew, k -> new HashSet<>()).add(absOld);

            TreeMap<Integer, LineAccessToken> fileTokens = tokens.remove(absOld);
            if (fileTokens != null) {
                TreeMap<Integer, LineAccessToken> newFileTokens = new TreeMap<>();
                for (Map.Entry<Integer, LineAccessToken> entry : fileTokens.entrySet()) {
                    LineAccessToken t = entry.getValue();
                    LineAccessToken moved = new LineAccessToken(
                            absNew, t.startLine(), t.endLine(), t.rangeCrc32c(), t.lineCount()
                    );
                    newFileTokens.put(t.startLine(), moved);
                }
                tokens.put(absNew, newFileTokens);
            }
        }
    }

    /**
     * Регистрирует alias пути: oldPath -> newPath.
     * Позволяет использовать токены с oldPath для файлов по newPath.
     */
    public void registerPathAlias(Path oldPath, Path newPath) {
        Path absOld = oldPath.toAbsolutePath().normalize();
        Path absNew = newPath.toAbsolutePath().normalize();

        synchronized (lock) {
            pathAliases.put(absOld, absNew);
            reverseAliases.computeIfAbsent(absNew, k -> new HashSet<>()).add(absOld);
        }
    }

    /**
     * Возвращает все известные предыдущие пути для текущего пути (транзитивно).
     * Следует по цепочке алиасов, собирая все старые пути.
     * Используется для валидации токенов со старыми хешами.
     *
     * @param currentPath Текущий путь файла
     * @return Множество всех предыдущих путей (может быть пустым)
     */
    public Set<Path> getPreviousPaths(Path currentPath) {
        Path absPath = currentPath.toAbsolutePath().normalize();

        synchronized (lock) {
            Set<Path> allPreviousPaths = new HashSet<>();
            collectPreviousPathsRecursively(absPath, allPreviousPaths);
            return allPreviousPaths;
        }
    }

    /**
     * Рекурсивно собирает все предыдущие пути.
     */
    private void collectPreviousPathsRecursively(Path path, Set<Path> collected) {
        Set<Path> directPrevious = reverseAliases.get(path);
        if (directPrevious == null) {
            return;
        }

        for (Path prev : directPrevious) {
            if (!collected.contains(prev)) {
                collected.add(prev);
                // Рекурсивно собираем предыдущие пути для каждого найденного
                collectPreviousPathsRecursively(prev, collected);
            }
        }
    }

    /**
     * Резолвит текущий путь по возможному старому пути.
     * Следует цепочке алиасов до конечного пути.
     *
     * @param path Путь (возможно устаревший)
     * @return Актуальный путь или исходный, если алиасов нет
     */
    public Path resolveCurrentPath(Path path) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            Path current = absPath;
            Set<Path> visited = new HashSet<>();

            // Следуем по цепочке алиасов (защита от циклов)
            while (pathAliases.containsKey(current) && !visited.contains(current)) {
                visited.add(current);
                current = pathAliases.get(current);
            }

            return current;
        }
    }

    /**
     * Проверяет, является ли один путь алиасом другого (прямо или транзитивно).
     *
     * @param possibleOldPath Возможный старый путь
     * @param currentPath Текущий путь
     * @return true если possibleOldPath является алиасом currentPath
     */
    public boolean isAliasOf(Path possibleOldPath, Path currentPath) {
        Path resolved = resolveCurrentPath(possibleOldPath);
        Path normalizedCurrent = currentPath.toAbsolutePath().normalize();
        return resolved.equals(normalizedCurrent);
    }

    /**
     * Возвращает токены для файла.
     */
    public List<LineAccessToken> getTokensForFile(Path path) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            if (fileTokens == null) {
                return Collections.emptyList();
            }
            return new ArrayList<>(fileTokens.values());
        }
    }

    /**
     * Возвращает множество файлов с зарегистрированными токенами.
     */
    public Set<Path> getAccessedFiles() {
        synchronized (lock) {
            return new HashSet<>(tokens.keySet());
        }
    }

    /**
     * Возвращает количество файлов с токенами.
     */
    public int getAccessedFilesCount() {
        synchronized (lock) {
            return tokens.size();
        }
    }

    /**
     * Проверяет, есть ли хотя бы один токен доступа для файла.
     */
    public boolean hasAnyAccess(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            return fileTokens != null && !fileTokens.isEmpty();
        }
    }

    /**
     * Форматирует информацию о прочитанных диапазонах.
     */
    public String formatAccessedRanges(Path path) {
        List<LineAccessToken> fileTokens = getTokensForFile(path);
        if (fileTokens.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("[READ: ");
        for (int i = 0; i < fileTokens.size(); i++) {
            if (i > 0) sb.append(", ");
            LineAccessToken t = fileTokens.get(i);
            sb.append("[").append(t.startLine()).append("-").append(t.endLine()).append("]");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Возвращает отформатированные строки токенов для листинга.
     */
    public List<String> getFormattedTokenLines(Path path, String indent) {
        List<String> result = new ArrayList<>();
        List<LineAccessToken> fileTokens = getTokensForFile(path);

        for (LineAccessToken t : fileTokens) {
            result.add(String.format("%s  [TOKEN %d-%d: %s]",
                    indent, t.startLine(), t.endLine(), t.encode()));
        }
        return result;
    }

    /**
     * Возвращает компактный статус токенов для файла в формате удобном для LLM.
     *
     * Формат:
     * [YOUR ACCESS: filename.java]
     *   * lines 1-50: token_short_id (covers requested range)
     *   * lines 100-150: token_short_id
     *
     * @param path Путь к файлу
     * @param requestedStart Начало запрошенного диапазона (для пометки покрывающего токена), 0 если не нужно
     * @param requestedEnd Конец запрошенного диапазона, 0 если не нужно
     * @return Форматированная строка статуса или пустая строка если токенов нет
     */
    public String getTokenStatusForLLM(Path path, int requestedStart, int requestedEnd) {
        List<LineAccessToken> fileTokens = getTokensForFile(path);
        if (fileTokens.isEmpty()) {
            return "";
        }

        String fileName = path.getFileName().toString();
        StringBuilder sb = new StringBuilder();
        sb.append("[YOUR ACCESS: ").append(fileName).append("]\n");

        for (LineAccessToken t : fileTokens) {
            sb.append("  \u2022 lines ").append(t.startLine()).append("-").append(t.endLine());

            // Показываем ПОЛНЫЙ токен - LLM нужен полный токен для использования!
            String encoded = t.encode();
            sb.append(": ").append(encoded);

            // Пометка если этот токен покрывает запрошенный диапазон
            if (requestedStart > 0 && requestedEnd > 0 && t.covers(requestedStart, requestedEnd)) {
                if (t.startLine() != requestedStart || t.endLine() != requestedEnd) {
                    sb.append(" \u2190 covers your request [").append(requestedStart).append("-").append(requestedEnd).append("]");
                }
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Возвращает TIP для LLM когда возвращается покрывающий токен.
     *
     * @param token Возвращённый токен
     * @param requestedStart Запрошенное начало диапазона
     * @param requestedEnd Запрошенный конец диапазона
     * @return TIP строка или пустая строка если диапазоны совпадают
     */
    public static String getCoveringTokenTip(LineAccessToken token, int requestedStart, int requestedEnd) {
        if (token.startLine() == requestedStart && token.endLine() == requestedEnd) {
            return ""; // Диапазоны совпадают, TIP не нужен
        }

        return String.format(
            "[TIP: You requested lines %d-%d, but you already have access to lines %d-%d. " +
            "Returning existing token. Use this token for editing any lines within %d-%d.]",
            requestedStart, requestedEnd,
            token.startLine(), token.endLine(),
            token.startLine(), token.endLine()
        );
    }

    /**
     * Сбрасывает все токены и алиасы путей.
     */
    public void reset() {
        synchronized (lock) {
            tokens.clear();
            fileCrcCache.clear();
            pathAliases.clear();
            reverseAliases.clear();
        }
    }
}
