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
import java.util.List;
import java.util.Set;

/**
 * Фасад для управления токенами доступа к строкам.
 * Делегирует все операции к session-scoped SessionLineAccessTracker.
 *
 * Обеспечивает обратную совместимость со старым статическим API,
 * при этом изолируя токены между сессиями.
 */
public class LineAccessTracker {

    // Делегирование к session-scoped трекеру
    private static SessionLineAccessTracker ctx() {
        return SessionContext.currentOrDefault().tokens();
    }

    /**
     * Регистрирует доступ к диапазону строк и возвращает токен.
     */
    public static LineAccessToken registerAccess(Path path, int startLine, int endLine, long fileCrc32c, int lineCount) {
        return ctx().registerAccess(path, startLine, endLine, fileCrc32c, lineCount);
    }

    /**
     * Валидирует токен против текущего состояния файла.
     */
    public static LineAccessToken.ValidationResult validateToken(LineAccessToken token, long currentCrc, int currentLineCount) {
        return ctx().validateToken(token, currentCrc, currentLineCount);
    }

    /**
     * Проверяет, покрыт ли диапазон существующим токеном.
     */
    public static boolean isRangeCovered(Path path, int startLine, int endLine, long crc) {
        return ctx().isRangeCovered(path, startLine, endLine, crc);
    }

    /**
     * Инвалидирует все токены для файла.
     */
    public static void invalidateFile(Path path) {
        ctx().invalidateFile(path);
    }

    /**
     * Сдвигает токены после указанной строки.
     */
    public static void shiftTokensAfterLine(Path path, int afterLine, int delta) {
        ctx().shiftTokensAfterLine(path, afterLine, delta);
    }

    /**
     * Обновляет токены после редактирования и возвращает новый токен для изменённого диапазона.
     */
    public static LineAccessToken updateAfterEdit(Path path, int editStart, int editEnd, int lineDelta, long newCrc, int newLineCount) {
        return ctx().updateAfterEdit(path, editStart, editEnd, lineDelta, newCrc, newLineCount);
    }

    /**
     * Переносит токены при перемещении/переименовании файла.
     */
    public static void moveTokens(Path oldPath, Path newPath) {
        ctx().moveTokens(oldPath, newPath);
    }

    /**
     * Возвращает токены для файла.
     */
    public static List<LineAccessToken> getTokensForFile(Path path) {
        return ctx().getTokensForFile(path);
    }

    /**
     * Возвращает множество файлов с зарегистрированными токенами.
     */
    public static Set<Path> getAccessedFiles() {
        return ctx().getAccessedFiles();
    }

    /**
     * Проверяет, есть ли хотя бы один токен доступа для файла.
     */
    public static boolean hasAnyAccess(Path path) {
        return ctx().hasAnyAccess(path);
    }

    /**
     * Форматирует информацию о прочитанных диапазонах.
     */
    public static String formatAccessedRanges(Path path) {
        return ctx().formatAccessedRanges(path);
    }

    /**
     * Возвращает отформатированные строки токенов для листинга.
     */
    public static List<String> getFormattedTokenLines(Path path, String indent) {
        return ctx().getFormattedTokenLines(path, indent);
    }

    /**
     * Сбрасывает все токены текущей сессии.
     */
    public static void reset() {
        ctx().reset();
    }
}
