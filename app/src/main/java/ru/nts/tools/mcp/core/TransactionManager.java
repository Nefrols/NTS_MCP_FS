// Aristo 25.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
