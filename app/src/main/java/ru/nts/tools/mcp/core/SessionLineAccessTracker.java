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
 * Per-session трекер токенов доступа к строкам файлов.
 * Обеспечивает изоляцию токенов между сессиями.
 *
 * Каждая сессия имеет собственный экземпляр этого класса.
 */
public class SessionLineAccessTracker {

    // Per-session хранилище токенов (Path -> TreeMap<startLine, Token>)
    private final Map<Path, TreeMap<Integer, LineAccessToken>> tokens = new HashMap<>();

    // Path aliasing: отслеживает перемещения файлов (oldPath -> currentPath)
    // Позволяет использовать старые токены после move/rename вне батча
    private final Map<Path, Path> pathAliases = new HashMap<>();

    // Reverse alias map: currentPath -> Set<oldPaths>
    // Для проверки токенов с хешем старого пути против нового пути
    private final Map<Path, Set<Path>> reverseAliases = new HashMap<>();

    // Синхронизация для потокобезопасности внутри сессии
    private final Object lock = new Object();

    /**
     * Регистрирует доступ к диапазону строк и возвращает токен.
     * Реализует автослияние: если запрошенный диапазон внутри существующего - возвращает старый токен.
     *
     * @param path Путь к файлу
     * @param startLine Начало диапазона (1-based)
     * @param endLine Конец диапазона (1-based)
     * @param rangeContent Содержимое диапазона строк (для вычисления CRC)
     * @param lineCount Общее количество строк в файле
     * @return Токен доступа к диапазону
     */
    public LineAccessToken registerAccess(Path path, int startLine, int endLine, String rangeContent, int lineCount) {
        Path absPath = path.toAbsolutePath().normalize();
        long rangeCrc = LineAccessToken.computeRangeCrc(rangeContent);

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.computeIfAbsent(absPath, k -> new TreeMap<>());

            // Проверяем, есть ли существующий токен, покрывающий запрошенный диапазон
            // Range CRC MUST match - if content changed (undo/redo/external), need new token
            for (LineAccessToken existing : fileTokens.values()) {
                if (existing.lineCount() == lineCount &&
                        existing.covers(startLine, endLine)) {
                    // Токен покрывает диапазон - проверяем CRC для валидности
                    if (existing.rangeCrc32c() == rangeCrc) {
                        // CRC совпадает - содержимое не изменилось, возвращаем существующий токен
                        return existing;
                    } else {
                        // CRC не совпадает - содержимое изменилось (undo/redo/external)
                        // Удаляем стейл токен и продолжаем создание нового
                        fileTokens.remove(existing.startLine());
                        break;
                    }
                }
            }

            // Удаляем токены, которые полностью внутри нового диапазона
            fileTokens.entrySet().removeIf(entry -> {
                LineAccessToken t = entry.getValue();
                return startLine <= t.startLine() && t.endLine() <= endLine;
            });

            // Объединяем с перекрывающимися/смежными токенами
            int mergedStart = startLine;
            int mergedEnd = endLine;

            List<Integer> toRemove = new ArrayList<>();
            for (Map.Entry<Integer, LineAccessToken> entry : fileTokens.entrySet()) {
                LineAccessToken t = entry.getValue();
                if (t.lineCount() != lineCount) {
                    continue;
                }
                // Смежные или перекрывающиеся
                if (t.startLine() <= mergedEnd + 1 && t.endLine() >= mergedStart - 1) {
                    mergedStart = Math.min(mergedStart, t.startLine());
                    mergedEnd = Math.max(mergedEnd, t.endLine());
                    toRemove.add(entry.getKey());
                }
            }

            for (Integer key : toRemove) {
                fileTokens.remove(key);
            }

            // Для объединённого диапазона нужен новый CRC
            // Если диапазон расширился, используем переданный CRC (вызывающий код должен передать правильный)
            LineAccessToken newToken = new LineAccessToken(absPath, mergedStart, mergedEnd, rangeCrc, lineCount);
            fileTokens.put(mergedStart, newToken);

            return newToken;
        }
    }

    /**
     * Валидирует токен против текущего состояния диапазона.
     *
     * Session Tokens: Если файл был разблокирован в текущей транзакции,
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

        // Session Tokens + InfinityRange: внутри транзакции токен автоматически валиден
        // для файлов с зарегистрированным доступом или созданных в транзакции
        boolean sessionUnlocked = TransactionManager.isInTransaction() &&
                (TransactionManager.isFileAccessedInTransaction(path) ||
                 TransactionManager.isFileCreatedInTransaction(path));

        if (sessionUnlocked) {
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
            int newEditEnd = editEnd + lineDelta;
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
     * Сбрасывает все токены и алиасы путей.
     */
    public void reset() {
        synchronized (lock) {
            tokens.clear();
            pathAliases.clear();
            reverseAliases.clear();
        }
    }
}
