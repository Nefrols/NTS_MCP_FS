// Aristo 25.12.2025
package ru.nts.tools.mcp.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Контекст сессии для изоляции состояния между параллельными LLM-клиентами.
 *
 * Каждая LLM-сессия получает собственный изолированный контекст с:
 * - Отдельным менеджером транзакций (undo/redo стеки)
 * - Отдельным трекером токенов доступа
 * - Отдельным TODO-планом
 * - Отдельным кешем поиска
 *
 * Архитектура:
 * - Глобальный реестр сессий (по sessionId)
 * - ThreadLocal для доступа к текущему контексту в рамках обработки запроса
 * - Автоматическая очистка при завершении сессии
 */
public class SessionContext {

    // Глобальный реестр всех активных сессий
    private static final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    // ThreadLocal для доступа к контексту в текущем потоке
    private static final ThreadLocal<SessionContext> currentContext = new ThreadLocal<>();

    // Идентификатор сессии
    private final String sessionId;

    // Per-session компоненты (создаются лениво)
    private final SessionTransactionManager transactionManager;
    private final SessionLineAccessTracker lineAccessTracker;
    private final SessionSearchTracker searchTracker;
    private volatile String activeTodoFile;

    /**
     * Создает новый контекст сессии.
     */
    private SessionContext(String sessionId) {
        this.sessionId = sessionId;
        this.transactionManager = new SessionTransactionManager();
        this.lineAccessTracker = new SessionLineAccessTracker();
        this.searchTracker = new SessionSearchTracker();
    }

    // ==================== Static API ====================

    /**
     * Получает или создает контекст для указанной сессии.
     * Если sessionId == null, генерируется новый UUID.
     */
    public static SessionContext getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, SessionContext::new);
    }

    /**
     * Устанавливает контекст для текущего потока.
     * Вызывается в начале обработки каждого запроса.
     */
    public static void setCurrent(SessionContext ctx) {
        currentContext.set(ctx);
    }

    /**
     * Получает контекст текущего потока.
     * Возвращает null если контекст не установлен.
     */
    public static SessionContext current() {
        return currentContext.get();
    }

    /**
     * Получает контекст текущего потока или создает default.
     * Используется для обратной совместимости с кодом, который не знает о сессиях.
     */
    public static SessionContext currentOrDefault() {
        SessionContext ctx = currentContext.get();
        if (ctx == null) {
            // Fallback: создаем/используем default сессию для обратной совместимости
            ctx = getOrCreate("default");
            currentContext.set(ctx);
        }
        return ctx;
    }

    /**
     * Очищает контекст текущего потока.
     * Вызывается в finally блоке после обработки запроса.
     */
    public static void clearCurrent() {
        currentContext.remove();
    }

    /**
     * Удаляет сессию из реестра и освобождает ресурсы.
     */
    public static void destroySession(String sessionId) {
        SessionContext ctx = sessions.remove(sessionId);
        if (ctx != null) {
            ctx.cleanup();
        }
    }

    /**
     * Сбрасывает все сессии (для тестов).
     */
    public static void resetAll() {
        for (SessionContext ctx : sessions.values()) {
            ctx.cleanup();
        }
        sessions.clear();
        currentContext.remove();
    }

    /**
     * Возвращает количество активных сессий.
     */
    public static int getActiveSessionCount() {
        return sessions.size();
    }

    // ==================== Instance API ====================

    public String getSessionId() {
        return sessionId;
    }

    public SessionTransactionManager transactions() {
        return transactionManager;
    }

    public SessionLineAccessTracker tokens() {
        return lineAccessTracker;
    }

    public SessionSearchTracker search() {
        return searchTracker;
    }

    public String getActiveTodoFile() {
        return activeTodoFile;
    }

    public void setActiveTodoFile(String fileName) {
        this.activeTodoFile = fileName;
    }

    /**
     * Очистка ресурсов сессии.
     */
    private void cleanup() {
        transactionManager.reset();
        lineAccessTracker.reset();
        searchTracker.clear();
        activeTodoFile = null;
    }

    @Override
    public String toString() {
        return "SessionContext[" + sessionId + "]";
    }
}
