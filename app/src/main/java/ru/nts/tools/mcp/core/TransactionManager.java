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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Фасад для управления транзакциями.
 * Делегирует все операции к session-scoped SessionTransactionManager.
 *
 * Обеспечивает обратную совместимость со старым статическим API,
 * при этом изолируя состояние между сессиями.
 */
public class TransactionManager {

    // Делегирование к session-scoped менеджеру
    private static SessionTransactionManager ctx() {
        return SessionContext.currentOrDefault().transactions();
    }

    public static void startTransaction(String description) {
        ctx().startTransaction(description);
    }

    public static void startTransaction(String description, String instruction) {
        ctx().startTransaction(description, instruction);
    }

    public static void backup(Path path) throws IOException {
        ctx().backup(path);
    }

    public static void commit() {
        ctx().commit();
    }

    public static void rollback() {
        ctx().rollback();
    }

    public static void createCheckpoint(String name) {
        ctx().createCheckpoint(name);
    }

    public static String rollbackToCheckpoint(String name) throws IOException {
        return ctx().rollbackToCheckpoint(name);
    }

    public static String undo() throws IOException {
        return ctx().undo();
    }

    /**
     * Выполняет умный откат с поддержкой Path Lineage.
     * @return детальный результат отката
     */
    public static UndoResult smartUndo() throws IOException {
        return ctx().smartUndo();
    }

    public static String redo() throws IOException {
        return ctx().redo();
    }

    public static List<String> getFileHistory(Path path) {
        return ctx().getFileHistory(path);
    }

    public static String getSessionStats() {
        return ctx().getSessionStats();
    }

    public static List<String> getSessionInstructions() {
        return ctx().getSessionInstructions();
    }

    public static String getJournal() {
        return ctx().getJournal();
    }

    // ==================== Session Tokens & InfinityRange API ====================

    /**
     * Проверяет, выполняется ли сейчас транзакция.
     */
    public static boolean isInTransaction() {
        return ctx().isInTransaction();
    }

    /**
     * Регистрирует файл как созданный в текущей транзакции (InfinityRange).
     */
    public static void markFileCreatedInTransaction(Path path) {
        ctx().markFileCreatedInTransaction(path);
    }

    /**
     * Проверяет, был ли файл создан в текущей транзакции.
     */
    public static boolean isFileCreatedInTransaction(Path path) {
        return ctx().isFileCreatedInTransaction(path);
    }

    /**
     * Регистрирует файл как разблокированный в текущей транзакции (Session Tokens).
     */
    public static void markFileAccessedInTransaction(Path path) {
        ctx().markFileAccessedInTransaction(path);
    }

    /**
     * Проверяет, был ли файл разблокирован в текущей транзакции.
     */
    public static boolean isFileAccessedInTransaction(Path path) {
        return ctx().isFileAccessedInTransaction(path);
    }

    // ==================== Path Lineage API ====================

    /**
     * Записывает перемещение файла в FileLineageTracker.
     * Вызывается при move/rename операциях для отслеживания цепочек перемещений.
     */
    public static void recordFileMove(Path oldPath, Path newPath) {
        ctx().recordFileMove(oldPath, newPath);
    }

    /**
     * Регистрирует файл в FileLineageTracker.
     * Возвращает уникальный ID файла для отслеживания.
     */
    public static String registerFile(Path path) {
        return ctx().registerFile(path);
    }

    /**
     * Обновляет CRC файла в FileLineageTracker.
     * Вызывается после редактирования для корректного поиска по CRC.
     */
    public static void updateFileCrc(Path path) {
        ctx().updateFileCrc(path);
    }

    // ==================== External Change Tracking API ====================

    /**
     * Записывает внешнее изменение файла в журнал.
     * Создаёт специальную транзакцию, которую можно откатить через undo.
     *
     * @param path путь к файлу
     * @param previousContent содержимое файла до внешнего изменения
     * @param previousCrc CRC32C до изменения
     * @param currentCrc CRC32C после изменения
     * @param description описание изменения
     */
    public static void recordExternalChange(Path path, String previousContent, long previousCrc, long currentCrc, String description) {
        ctx().recordExternalChange(path, previousContent, previousCrc, currentCrc, description);
    }

    /**
     * Возвращает набор файлов, затронутых в текущей транзакции.
     * Используется для обновления снапшотов после выполнения операций.
     *
     * @return набор путей к затронутым файлам, или пустой набор если транзакция не активна
     */
    public static Set<Path> getCurrentTransactionAffectedPaths() {
        return ctx().getCurrentTransactionAffectedPaths();
    }

    /**
     * Сбрасывает состояние текущей сессии.
     */
    public static void reset() {
        SessionContext ctx = SessionContext.current();
        if (ctx != null) {
            ctx.transactions().reset();
            ctx.tokens().reset();
            ctx.search().clear();
        }
        // Для обратной совместимости с тестами - сбрасываем default сессию
        SessionContext.resetAll();
    }
}
