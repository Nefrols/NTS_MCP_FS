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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
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
    private final FileLineageTracker fileLineageTracker;
    private final ExternalChangeTracker externalChangeTracker;
    private volatile String activeTodoFile;

    // Текущий вызываемый инструмент (для диагностики)
    private volatile String currentToolName;

    // Время создания сессии
    private final Instant createdAt;

    // Время последней активности
    private volatile Instant lastActivityAt;

    // Имя файла метаданных сессии
    private static final String SESSION_METADATA_FILE = "session.meta";

    /**
     * Создает новый контекст сессии.
     */
    private SessionContext(String sessionId) {
        this.sessionId = sessionId;
        this.transactionManager = new SessionTransactionManager(sessionId);
        this.lineAccessTracker = new SessionLineAccessTracker();
        this.searchTracker = new SessionSearchTracker();
        this.fileLineageTracker = new FileLineageTracker();
        this.externalChangeTracker = new ExternalChangeTracker();
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    /**
     * Создает контекст сессии с восстановленными временными метками.
     * Используется при реактивации существующей сессии.
     */
    private SessionContext(String sessionId, Instant createdAt) {
        this.sessionId = sessionId;
        this.transactionManager = new SessionTransactionManager(sessionId);
        this.lineAccessTracker = new SessionLineAccessTracker();
        this.searchTracker = new SessionSearchTracker();
        this.fileLineageTracker = new FileLineageTracker();
        this.externalChangeTracker = new ExternalChangeTracker();
        this.createdAt = createdAt;
        this.lastActivityAt = Instant.now();
    }

    // ==================== Static API ====================

    /**
     * Получает или создает контекст для указанной сессии.
     * Если sessionId == null или пустой, используется "default" сессия.
     * Если сессия существует на диске, но не в памяти — реактивирует её.
     */
    public static SessionContext getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "default";
        }
        String id = sessionId;
        return sessions.computeIfAbsent(id, key -> {
            // Проверяем, существует ли сессия на диске
            if (existsOnDisk(key)) {
                // Реактивируем без добавления в map (computeIfAbsent сам добавит)
                SessionMetadata meta = getSessionMetadata(key);
                if (meta != null) {
                    SessionContext ctx = new SessionContext(key, meta.createdAt());
                    ctx.transactionManager.loadJournal();
                    if (meta.activeTodoFile() != null) {
                        ctx.setActiveTodoFile(meta.activeTodoFile());
                    }
                    return ctx;
                }
            }
            // Создаём новый контекст
            return new SessionContext(key);
        });
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

    /**
     * Проверяет, существует ли сессия на диске (была создана ранее).
     * Это позволяет определить, можно ли реактивировать сессию после перезапуска сервера.
     *
     * @param sessionId ID сессии для проверки
     * @return true если директория сессии существует на диске
     */
    public static boolean existsOnDisk(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || "default".equals(sessionId)) {
            return false;
        }
        Path sessionDir = PathSanitizer.getRoot().resolve(".nts/sessions/" + sessionId);
        Path metaFile = sessionDir.resolve(SESSION_METADATA_FILE);
        return Files.exists(metaFile);
    }

    /**
     * Возвращает информацию о сессии на диске.
     *
     * @param sessionId ID сессии
     * @return информация о сессии или null если не найдена
     */
    public static SessionMetadata getSessionMetadata(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path sessionDir = PathSanitizer.getRoot().resolve(".nts/sessions/" + sessionId);
        Path metaFile = sessionDir.resolve(SESSION_METADATA_FILE);

        if (!Files.exists(metaFile)) {
            return null;
        }

        try {
            String content = Files.readString(metaFile);
            String[] lines = content.split("\n");
            Instant created = null;
            Instant lastActivity = null;
            String activeTodo = null;

            for (String line : lines) {
                if (line.startsWith("created=")) {
                    created = Instant.parse(line.substring(8));
                } else if (line.startsWith("lastActivity=")) {
                    lastActivity = Instant.parse(line.substring(13));
                } else if (line.startsWith("activeTodo=")) {
                    activeTodo = line.substring(11);
                }
            }

            return new SessionMetadata(sessionId, created, lastActivity, activeTodo);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Реактивирует существующую сессию из директории на диске.
     * Восстанавливает контекст сессии и журнал транзакций (undo/redo стеки).
     * Токены доступа к файлам начинаются с чистого состояния.
     *
     * @param sessionId ID сессии для реактивации
     * @return восстановленный контекст сессии
     * @throws IllegalArgumentException если сессия не найдена на диске
     */
    public static SessionContext reactivateSession(String sessionId) {
        SessionMetadata meta = getSessionMetadata(sessionId);
        if (meta == null) {
            throw new IllegalArgumentException("Session not found on disk: " + sessionId);
        }

        // Создаём контекст с восстановленным временем создания
        SessionContext ctx = new SessionContext(sessionId, meta.createdAt());
        sessions.put(sessionId, ctx);

        // Восстанавливаем журнал транзакций (undo/redo стеки)
        ctx.transactionManager.loadJournal();

        // Восстанавливаем активный TODO
        if (meta.activeTodoFile() != null) {
            ctx.setActiveTodoFile(meta.activeTodoFile());
        }

        return ctx;
    }

    /**
     * Проверяет, активна ли сессия в памяти.
     */
    public static boolean isActiveInMemory(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Метаданные сессии для хранения на диске.
     */
    public record SessionMetadata(String sessionId, Instant createdAt, Instant lastActivityAt, String activeTodoFile) {}

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

    public FileLineageTracker lineage() {
        return fileLineageTracker;
    }

    public ExternalChangeTracker externalChanges() {
        return externalChangeTracker;
    }

    public String getActiveTodoFile() {
        return activeTodoFile;
    }

    public void setActiveTodoFile(String fileName) {
        this.activeTodoFile = fileName;
        // Сохраняем метаданные для персистентности TODO между сессиями
        saveMetadata();
    }

    /**
     * Возвращает текущий вызываемый инструмент.
     */
    public String getCurrentToolName() {
        return currentToolName;
    }

    /**
     * Устанавливает текущий вызываемый инструмент.
     */
    public void setCurrentToolName(String toolName) {
        this.currentToolName = toolName;
    }

    /**
     * Возвращает путь к директории сессии.
     * Структура: .nts/sessions/{sessionId}/
     */
    public java.nio.file.Path getSessionDir() {
        return PathSanitizer.getRoot().resolve(".nts/sessions/" + sessionId);
    }

    /**
     * Возвращает путь к директории todos сессии.
     */
    public java.nio.file.Path getTodosDir() {
        return getSessionDir().resolve("todos");
    }

    /**
     * Возвращает путь к директории snapshots сессии.
     */
    public java.nio.file.Path getSnapshotsDir() {
        return getSessionDir().resolve("snapshots");
    }

    /**
     * Возвращает время создания сессии.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Возвращает время последней активности.
     */
    public Instant getLastActivityAt() {
        return lastActivityAt;
    }

    /**
     * Обновляет время последней активности.
     * Вызывается автоматически при каждом вызове инструмента.
     */
    public void touchActivity() {
        this.lastActivityAt = Instant.now();
        // Синхронно сохраняем метаданные для надёжности при перезапусках
        saveMetadata();
    }

    /**
     * Сохраняет метаданные сессии на диск.
     * Вызывается при создании сессии и при обновлении активности.
     */
    public void saveMetadata() {
        if ("default".equals(sessionId)) {
            return; // default сессия не сохраняется
        }

        Path metaFile = getSessionDir().resolve(SESSION_METADATA_FILE);
        try {
            Files.createDirectories(metaFile.getParent());
            StringBuilder content = new StringBuilder();
            content.append("sessionId=").append(sessionId).append("\n");
            content.append("created=").append(createdAt).append("\n");
            content.append("lastActivity=").append(lastActivityAt).append("\n");
            if (activeTodoFile != null) {
                content.append("activeTodo=").append(activeTodoFile).append("\n");
            }
            Files.writeString(metaFile, content.toString());
        } catch (IOException e) {
            // Логируем, но не прерываем операцию
            System.err.println("Warning: Failed to save session metadata: " + e.getMessage());
        }
    }

    /**
     * Очистка ресурсов сессии.
     */
    private void cleanup() {
        transactionManager.reset();
        lineAccessTracker.reset();
        searchTracker.clear();
        fileLineageTracker.reset();
        externalChangeTracker.reset();
        activeTodoFile = null;
    }

    @Override
    public String toString() {
        return "SessionContext[" + sessionId + "]";
    }
}
