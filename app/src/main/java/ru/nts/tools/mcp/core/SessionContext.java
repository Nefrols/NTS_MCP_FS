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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    // Рабочая директория проекта (для идентификации сессии)
    private volatile Path workingDirectory;

    // Произвольные метаданные от CLI/внешних инструментов
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    // Имя файла журнала сессии
    private static final String JOURNAL_FILE = "journal.json";
    private static final int JOURNAL_VERSION = 2;
    private static final ObjectMapper JOURNAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Блокировка для атомарной записи журнала
    private final Object journalLock = new Object();

    /**
     * Создает новый контекст сессии.
     */
    private SessionContext(String sessionId) {
        this.sessionId = sessionId;
        this.transactionManager = new SessionTransactionManager(sessionId, this);
        this.lineAccessTracker = new SessionLineAccessTracker();
        this.searchTracker = new SessionSearchTracker();
        this.fileLineageTracker = new FileLineageTracker();
        this.externalChangeTracker = new ExternalChangeTracker();
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.workingDirectory = PathSanitizer.getRoot();
    }

    /**
     * Создает контекст сессии с восстановленными временными метками.
     * Используется при реактивации существующей сессии.
     */
    private SessionContext(String sessionId, Instant createdAt) {
        this.sessionId = sessionId;
        this.transactionManager = new SessionTransactionManager(sessionId, this);
        this.lineAccessTracker = new SessionLineAccessTracker();
        this.searchTracker = new SessionSearchTracker();
        this.fileLineageTracker = new FileLineageTracker();
        this.externalChangeTracker = new ExternalChangeTracker();
        this.createdAt = createdAt;
        this.lastActivityAt = Instant.now();
        this.workingDirectory = PathSanitizer.getRoot();
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
                SessionMetadata meta = getSessionMetadata(key);
                if (meta != null) {
                    SessionContext ctx = new SessionContext(key,
                            meta.createdAt() != null ? meta.createdAt() : Instant.now());
                    if (meta.workingDirectory() != null) {
                        ctx.workingDirectory = Path.of(meta.workingDirectory());
                    }
                    ctx.loadJournal();
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
        Path sessionDir = PathSanitizer.getSessionRoot().resolve("sessions/" + sessionId);
        Path journalFile = sessionDir.resolve(JOURNAL_FILE);
        // Also check legacy format for backward compatibility
        Path legacyMeta = sessionDir.resolve("session.meta");
        return Files.exists(journalFile) || Files.exists(legacyMeta);
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
        Path sessionDir = PathSanitizer.getSessionRoot().resolve("sessions/" + sessionId);
        Path journalFile = sessionDir.resolve(JOURNAL_FILE);

        if (Files.exists(journalFile)) {
            try {
                JsonNode root = JOURNAL_MAPPER.readTree(journalFile.toFile());
                Instant created = root.has("createdAt") ? Instant.parse(root.get("createdAt").asText()) : null;
                Instant lastActivity = root.has("lastActivity") ? Instant.parse(root.get("lastActivity").asText()) : null;
                String activeTodo = root.has("activeTodo") ? root.get("activeTodo").asText() : null;
                String workDir = root.has("workingDirectory") ? root.get("workingDirectory").asText() : null;
                return new SessionMetadata(sessionId, created, lastActivity, activeTodo, workDir);
            } catch (Exception e) {
                return null;
            }
        }

        // Legacy format: session.meta (key=value)
        Path legacyMeta = sessionDir.resolve("session.meta");
        if (Files.exists(legacyMeta)) {
            try {
                String content = Files.readString(legacyMeta);
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

                return new SessionMetadata(sessionId, created, lastActivity, activeTodo, null);
            } catch (Exception e) {
                return null;
            }
        }

        return null;
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
        SessionContext ctx = new SessionContext(sessionId, meta.createdAt() != null ? meta.createdAt() : Instant.now());
        sessions.put(sessionId, ctx);

        // Восстанавливаем workingDirectory
        if (meta.workingDirectory() != null) {
            ctx.workingDirectory = Path.of(meta.workingDirectory());
        }

        // Загружаем журнал (транзакции + метаданные)
        ctx.loadJournal();

        // Восстанавливаем активный TODO
        if (meta.activeTodoFile() != null) {
            ctx.activeTodoFile = meta.activeTodoFile();
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
    public record SessionMetadata(String sessionId, Instant createdAt, Instant lastActivityAt,
                                     String activeTodoFile, String workingDirectory) {}

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
     * Структура: ~/.nts/sessions/{sessionId}/
     */
    public java.nio.file.Path getSessionDir() {
        return PathSanitizer.getSessionRoot().resolve("sessions/" + sessionId);
    }

    /**
     * Возвращает рабочую директорию проекта для этой сессии.
     */
    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Устанавливает рабочую директорию проекта.
     */
    public void setWorkingDirectory(Path dir) {
        this.workingDirectory = dir;
    }

    /**
     * Устанавливает произвольные метаданные (от CLI или внешних инструментов).
     */
    public void setMetadata(String key, String value) {
        if (value != null) {
            metadata.put(key, value);
        } else {
            metadata.remove(key);
        }
    }

    /**
     * Возвращает значение метаданных по ключу.
     */
    public String getMetadata(String key) {
        return metadata.get(key);
    }

    /**
     * Возвращает все метаданные.
     */
    public Map<String, String> getAllMetadata() {
        return Map.copyOf(metadata);
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
     * Сохраняет полный журнал сессии на диск (journal.json).
     * Единый мастер-файл: метаданные + транзакции + статистика.
     * Вызывается при создании сессии, обновлении активности, commit/undo/redo.
     */
    public void saveJournal() {
        if ("default".equals(sessionId)) {
            return;
        }

        synchronized (journalLock) {
            Path journalFile = getSessionDir().resolve(JOURNAL_FILE);
            try {
                Files.createDirectories(journalFile.getParent());

                ObjectNode root = JOURNAL_MAPPER.createObjectNode();
                root.put("version", JOURNAL_VERSION);
                root.put("sessionId", sessionId);
                root.put("workingDirectory", workingDirectory != null ? workingDirectory.toString() : "");
                root.put("createdAt", createdAt.toString());
                root.put("lastActivity", lastActivityAt.toString());
                if (activeTodoFile != null) {
                    root.put("activeTodo", activeTodoFile);
                }

                // Metadata map
                if (!metadata.isEmpty()) {
                    ObjectNode metaNode = root.putObject("metadata");
                    for (var entry : metadata.entrySet()) {
                        metaNode.put(entry.getKey(), entry.getValue());
                    }
                }

                // Delegate transaction data to SessionTransactionManager
                transactionManager.writeTransactionsTo(root);

                JOURNAL_MAPPER.writeValue(journalFile.toFile(), root);
            } catch (IOException e) {
                System.err.println("Warning: Failed to save session journal: " + e.getMessage());
            }
        }
    }

    /**
     * Загружает журнал сессии с диска.
     * Восстанавливает метаданные и делегирует транзакции в SessionTransactionManager.
     */
    public void loadJournal() {
        Path journalFile = getSessionDir().resolve(JOURNAL_FILE);
        if (!Files.exists(journalFile)) {
            // Try loading transactions from legacy journal.json (v1 format)
            transactionManager.loadJournal();
            return;
        }

        try {
            JsonNode root = JOURNAL_MAPPER.readTree(journalFile.toFile());

            // Restore metadata
            if (root.has("workingDirectory")) {
                String wd = root.get("workingDirectory").asText();
                if (!wd.isEmpty()) {
                    this.workingDirectory = Path.of(wd);
                }
            }
            if (root.has("activeTodo")) {
                this.activeTodoFile = root.get("activeTodo").asText();
            }
            if (root.has("metadata") && root.get("metadata").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = root.get("metadata").fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    metadata.put(field.getKey(), field.getValue().asText());
                }
            }

            // Delegate transaction loading
            int version = root.path("version").asInt(1);
            if (version >= 2) {
                transactionManager.loadTransactionsFrom(root);
            } else {
                // v1 format: transactions at top level (backward compatibility)
                transactionManager.loadTransactionsFrom(root);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to load session journal: " + e.getMessage());
        }
    }

    /**
     * Backward compatibility: delegates to saveJournal().
     */
    public void saveMetadata() {
        saveJournal();
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
