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
     *
     * @param path Путь к файлу
     * @param startLine Начало диапазона (1-based)
     * @param endLine Конец диапазона (1-based)
     * @param rangeContent Содержимое диапазона (для вычисления CRC)
     * @param lineCount Общее количество строк в файле
     * @return Токен доступа к диапазону
     */
    public static LineAccessToken registerAccess(Path path, int startLine, int endLine, String rangeContent, int lineCount) {
        return ctx().registerAccess(path, startLine, endLine, rangeContent, lineCount);
    }

    /**
     * Валидирует токен против текущего содержимого диапазона.
     *
     * @param token Токен для валидации
     * @param currentRangeContent Текущее содержимое диапазона
     * @param currentLineCount Текущее количество строк в файле
     * @return Результат валидации
     */
    public static LineAccessToken.ValidationResult validateToken(LineAccessToken token, String currentRangeContent, int currentLineCount) {
        return ctx().validateToken(token, currentRangeContent, currentLineCount);
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
    public static boolean isRangeCovered(Path path, int startLine, int endLine, String rangeContent) {
        return ctx().isRangeCovered(path, startLine, endLine, rangeContent);
    }

    /**
     * Инвалидирует все токены для файла.
     */
    public static void invalidateFile(Path path) {
        ctx().invalidateFile(path);
    }

    /**
     * Сдвигает токены после указанной строки.
     *
     * @param path Путь к файлу
     * @param afterLine Строка, после которой произошла вставка/удаление
     * @param delta Смещение
     * @param newLineCount Новое количество строк в файле
     */
    public static void shiftTokensAfterLine(Path path, int afterLine, int delta, int newLineCount) {
        ctx().shiftTokensAfterLine(path, afterLine, delta, newLineCount);
    }

    /**
     * Обновляет токены после редактирования и возвращает новый токен для изменённого диапазона.
     *
     * @param path Путь к файлу
     * @param editStart Начало редактируемого диапазона (1-based)
     * @param editEnd Конец редактируемого диапазона (1-based)
     * @param lineDelta Изменение количества строк
     * @param editedRangeContent Содержимое изменённого диапазона
     * @param newLineCount Новое общее количество строк
     * @return Новый токен для изменённого диапазона
     */
    public static LineAccessToken updateAfterEdit(Path path, int editStart, int editEnd,
                                                  int lineDelta, String editedRangeContent, int newLineCount) {
        return ctx().updateAfterEdit(path, editStart, editEnd, lineDelta, editedRangeContent, newLineCount);
    }

    /**
     * Переносит токены при перемещении/переименовании файла.
     * Также регистрирует alias для использования старых токенов вне batch.
     */
    public static void moveTokens(Path oldPath, Path newPath) {
        ctx().moveTokens(oldPath, newPath);
    }

    /**
     * Регистрирует alias пути: oldPath -> newPath.
     */
    public static void registerPathAlias(Path oldPath, Path newPath) {
        ctx().registerPathAlias(oldPath, newPath);
    }

    /**
     * Резолвит текущий путь по возможному старому пути.
     * Следует цепочке алиасов до конечного пути.
     */
    public static Path resolveCurrentPath(Path path) {
        return ctx().resolveCurrentPath(path);
    }

    /**
     * Проверяет, является ли один путь алиасом другого.
     */
    public static boolean isAliasOf(Path possibleOldPath, Path currentPath) {
        return ctx().isAliasOf(possibleOldPath, currentPath);
    }

    /**
     * Возвращает все известные предыдущие пути для текущего пути.
     * Используется для валидации токенов со старыми хешами.
     */
    public static java.util.Set<Path> getPreviousPaths(Path currentPath) {
        return ctx().getPreviousPaths(currentPath);
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
