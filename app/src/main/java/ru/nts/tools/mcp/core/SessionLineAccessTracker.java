// Aristo 25.12.2025
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

    // Синхронизация для потокобезопасности внутри сессии
    private final Object lock = new Object();

    /**
     * Регистрирует доступ к диапазону строк и возвращает токен.
     * Реализует автослияние: если запрошенный диапазон внутри существующего - возвращает старый токен.
     */
    public LineAccessToken registerAccess(Path path, int startLine, int endLine, long fileCrc32c, int lineCount) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.computeIfAbsent(absPath, k -> new TreeMap<>());

            // Проверяем, есть ли существующий токен, покрывающий запрошенный диапазон
            for (LineAccessToken existing : fileTokens.values()) {
                if (existing.fileCrc32c() == fileCrc32c &&
                        existing.lineCount() == lineCount &&
                        existing.covers(startLine, endLine)) {
                    return existing;
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
                if (t.fileCrc32c() != fileCrc32c || t.lineCount() != lineCount) {
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

            LineAccessToken newToken = new LineAccessToken(absPath, mergedStart, mergedEnd, fileCrc32c, lineCount);
            fileTokens.put(mergedStart, newToken);

            return newToken;
        }
    }

    /**
     * Валидирует токен против текущего состояния файла.
     */
    public LineAccessToken.ValidationResult validateToken(LineAccessToken token, long currentCrc, int currentLineCount) {
        if (token.fileCrc32c() != currentCrc) {
            return LineAccessToken.ValidationResult.CRC_MISMATCH;
        }
        if (token.lineCount() != currentLineCount) {
            return LineAccessToken.ValidationResult.LINE_COUNT_MISMATCH;
        }

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(token.path());
            if (fileTokens == null) {
                return LineAccessToken.ValidationResult.NOT_FOUND;
            }

            for (LineAccessToken stored : fileTokens.values()) {
                if (stored.covers(token.startLine(), token.endLine()) &&
                        stored.fileCrc32c() == currentCrc &&
                        stored.lineCount() == currentLineCount) {
                    return LineAccessToken.ValidationResult.VALID;
                }
            }
        }
        return LineAccessToken.ValidationResult.NOT_FOUND;
    }

    /**
     * Проверяет, покрыт ли диапазон существующим токеном.
     */
    public boolean isRangeCovered(Path path, int startLine, int endLine, long crc) {
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.get(absPath);
            if (fileTokens == null) {
                return false;
            }

            for (LineAccessToken token : fileTokens.values()) {
                if (token.fileCrc32c() == crc && token.covers(startLine, endLine)) {
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
     */
    public void shiftTokensAfterLine(Path path, int afterLine, int delta) {
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
                    newTokens.put(t.startLine(), t);
                } else if (t.startLine() > afterLine) {
                    int newStart = t.startLine() + delta;
                    int newEnd = t.endLine() + delta;
                    if (newStart > 0 && newEnd > 0) {
                        LineAccessToken shifted = new LineAccessToken(
                                t.path(), newStart, newEnd, t.fileCrc32c(), t.lineCount() + delta
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
     */
    public LineAccessToken updateAfterEdit(Path path, int editStart, int editEnd, int lineDelta, long newCrc, int newLineCount) {
        Path absPath = path.toAbsolutePath().normalize();

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
                    LineAccessToken updated = new LineAccessToken(
                            t.path(), t.startLine(), t.endLine(), newCrc, newLineCount
                    );
                    newTokens.put(t.startLine(), updated);
                } else if (t.startLine() > editEnd) {
                    int newStart = t.startLine() + lineDelta;
                    int newEnd = t.endLine() + lineDelta;
                    if (newStart > 0 && newEnd > 0) {
                        LineAccessToken shifted = new LineAccessToken(
                                t.path(), newStart, newEnd, newCrc, newLineCount
                        );
                        newTokens.put(newStart, shifted);
                    }
                }
                // Перекрывающиеся токены инвалидируются (не добавляются в newTokens)
            }

            // Создаём новый токен для изменённого диапазона
            int newEditEnd = editEnd + lineDelta;
            LineAccessToken editToken = new LineAccessToken(absPath, editStart, newEditEnd, newCrc, newLineCount);
            newTokens.put(editStart, editToken);

            tokens.put(absPath, newTokens);
            return editToken;
        }
    }

    /**
     * Переносит токены при перемещении/переименовании файла.
     */
    public void moveTokens(Path oldPath, Path newPath) {
        Path absOld = oldPath.toAbsolutePath().normalize();
        Path absNew = newPath.toAbsolutePath().normalize();

        synchronized (lock) {
            TreeMap<Integer, LineAccessToken> fileTokens = tokens.remove(absOld);
            if (fileTokens != null) {
                TreeMap<Integer, LineAccessToken> newFileTokens = new TreeMap<>();
                for (Map.Entry<Integer, LineAccessToken> entry : fileTokens.entrySet()) {
                    LineAccessToken t = entry.getValue();
                    LineAccessToken moved = new LineAccessToken(
                            absNew, t.startLine(), t.endLine(), t.fileCrc32c(), t.lineCount()
                    );
                    newFileTokens.put(t.startLine(), moved);
                }
                tokens.put(absNew, newFileTokens);
            }
        }
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
     * Сбрасывает все токены.
     */
    public void reset() {
        synchronized (lock) {
            tokens.clear();
        }
    }
}
