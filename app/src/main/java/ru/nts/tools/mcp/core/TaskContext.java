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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Контекст задачи для изоляции состояния между параллельными LLM-клиентами.
 *
 * Каждая задача получает собственный изолированный контекст с:
 * - Отдельным менеджером транзакций (undo/redo стеки)
 * - Отдельным трекером токенов доступа
 * - Отдельным TODO-планом
 * - Отдельным кешем поиска
 *
 * Архитектура:
 * - Глобальный реестр задач (по taskId)
 * - ThreadLocal для доступа к текущему контексту в рамках обработки запроса
 * - Автоматическая очистка при завершении задачи
 */
public class TaskContext {

    // Глобальный реестр всех активных задач
    private static final Map<String, TaskContext> tasks = new ConcurrentHashMap<>();

    // ThreadLocal для доступа к контексту в текущем потоке
    private static final ThreadLocal<TaskContext> currentContext = new ThreadLocal<>();

    // Флаг для принудительного использования in-memory DB (для тестов)
    private static volatile boolean forceInMemoryDb;

    // Идентификатор задачи
    private final String taskId;

    // Per-task компоненты (создаются лениво)
    private final TaskTransactionManager transactionManager;
    private final TaskLineAccessTracker lineAccessTracker;
    private final TaskSearchTracker searchTracker;
    private final FileLineageTracker fileLineageTracker;
    private final ExternalChangeTracker externalChangeTracker;
    private volatile String activeTodoFile;

    // Текущий вызываемый инструмент (для диагностики)
    private volatile String currentToolName;

    // Время создания задачи
    private final Instant createdAt;

    // Время последней активности
    private volatile Instant lastActivityAt;

    // Рабочая директория проекта (для идентификации задачи)
    private volatile Path workingDirectory;

    // Произвольные метаданные от CLI/внешних инструментов
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    // Repository для метаданных в H2
    private final JournalRepository journalRepo = new JournalRepository();

    /**
     * Создает новый контекст задачи.
     */
    private TaskContext(String taskId) {
        this.taskId = taskId;
        this.transactionManager = new TaskTransactionManager(taskId, this);
        this.lineAccessTracker = new TaskLineAccessTracker();
        this.searchTracker = new TaskSearchTracker();
        this.fileLineageTracker = new FileLineageTracker();
        this.externalChangeTracker = new ExternalChangeTracker();
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
        this.workingDirectory = PathSanitizer.getRoot();
    }

    /**
     * Создает контекст задачи с восстановленными временными метками.
     * Используется при реактивации существующей задачи.
     */
    private TaskContext(String taskId, Instant createdAt) {
        this.taskId = taskId;
        this.transactionManager = new TaskTransactionManager(taskId, this);
        this.lineAccessTracker = new TaskLineAccessTracker();
        this.searchTracker = new TaskSearchTracker();
        this.fileLineageTracker = new FileLineageTracker();
        this.externalChangeTracker = new ExternalChangeTracker();
        this.createdAt = createdAt;
        this.lastActivityAt = Instant.now();
        this.workingDirectory = PathSanitizer.getRoot();
    }

    // ==================== Static API ====================

    /**
     * Получает или создает контекст для указанной задачи.
     * Если taskId == null или пустой, используется "default" задача.
     * Если задача существует на диске, но не в памяти — реактивирует её.
     */
    public static TaskContext getOrCreate(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            taskId = "default";
        }
        String id = taskId;
        return tasks.computeIfAbsent(id, key -> {
            // Проверяем, существует ли задача на диске
            if (existsOnDisk(key)) {
                TaskMetadata meta = getTaskMetadata(key);
                if (meta != null) {
                    TaskContext ctx = new TaskContext(key,
                            meta.createdAt() != null ? meta.createdAt() : Instant.now());
                    if (meta.workingDirectory() != null) {
                        ctx.workingDirectory = Path.of(meta.workingDirectory());
                    }
                    ctx.loadJournal();
                    return ctx;
                }
            }
            // Создаём новый контекст
            return new TaskContext(key);
        });
    }

    /**
     * Устанавливает контекст для текущего потока.
     * Вызывается в начале обработки каждого запроса.
     */
    public static void setCurrent(TaskContext ctx) {
        currentContext.set(ctx);
    }

    /**
     * Получает контекст текущего потока.
     * Возвращает null если контекст не установлен.
     */
    public static TaskContext current() {
        return currentContext.get();
    }

    /**
     * Получает контекст текущего потока или создает default.
     * Используется как fallback для кода, который не передаёт taskId.
     */
    public static TaskContext currentOrDefault() {
        TaskContext ctx = currentContext.get();
        if (ctx == null) {
            // Fallback: создаем/используем default задачу
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
     * Удаляет задачу из реестра и освобождает ресурсы.
     */
    public static void destroyTask(String taskId) {
        TaskContext ctx = tasks.remove(taskId);
        if (ctx != null) {
            ctx.cleanup();
        }
    }

    /**
     * Сбрасывает все задачи (для тестов).
     */
    public static void resetAll() {
        for (TaskContext ctx : tasks.values()) {
            ctx.cleanup();
        }
        tasks.clear();
        currentContext.remove();
    }

    /**
     * Включает/отключает принудительное использование in-memory DB для всех задач.
     * Используется в тестах для изоляции от файловой системы.
     */
    public static void setForceInMemoryDb(boolean force) {
        forceInMemoryDb = force;
    }

    /**
     * Проверяет, включён ли режим in-memory DB.
     */
    public static boolean isForceInMemoryDb() {
        return forceInMemoryDb;
    }

    /**
     * Возвращает количество активных задач.
     */
    public static int getActiveTaskCount() {
        return tasks.size();
    }

    /**
     * Проверяет, существует ли задача на диске (была создана ранее).
     *
     * @param taskId ID задачи для проверки
     * @return true если H2 база задачи существует на диске
     */
    public static boolean existsOnDisk(String taskId) {
        if (taskId == null || taskId.isBlank() || "default".equals(taskId)) {
            return false;
        }
        Path taskDir = PathSanitizer.getTaskRoot().resolve("tasks/" + taskId);
        return Files.exists(taskDir.resolve("journal.mv.db"));
    }

    /**
     * Возвращает информацию о задаче на диске (из H2 task_metadata).
     *
     * @param taskId ID задачи
     * @return информация о задаче или null если не найдена
     */
    public static TaskMetadata getTaskMetadata(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        Path taskDir = PathSanitizer.getTaskRoot().resolve("tasks/" + taskId);

        if (!Files.exists(taskDir.resolve("journal.mv.db"))) {
            return null;
        }

        JournalDatabase tempDb = new JournalDatabase(taskDir);
        try {
            tempDb.initialize();
            try (Connection conn = tempDb.getConnection()) {
                JournalRepository repo = new JournalRepository();
                String createdStr = repo.getMetadata(conn, "createdAt");
                String lastActivityStr = repo.getMetadata(conn, "lastActivity");
                String activeTodo = repo.getMetadata(conn, "activeTodo");
                String workDir = repo.getMetadata(conn, "workingDirectory");

                Instant created = createdStr != null ? Instant.parse(createdStr) : null;
                Instant lastActivity = lastActivityStr != null ? Instant.parse(lastActivityStr) : null;
                return new TaskMetadata(taskId, created, lastActivity, activeTodo, workDir);
            }
        } catch (Exception e) {
            return null;
        } finally {
            tempDb.close();
        }
    }

    /**
     * Реактивирует существующую задачу из H2 базы на диске.
     * Восстанавливает контекст задачи и журнал транзакций (undo/redo стеки).
     * Токены доступа к файлам начинаются с чистого состояния.
     *
     * @param taskId ID задачи для реактивации
     * @return восстановленный контекст задачи
     * @throws IllegalArgumentException если задача не найдена на диске
     */
    public static TaskContext reactivateTask(String taskId) {
        TaskMetadata meta = getTaskMetadata(taskId);
        if (meta == null) {
            throw new IllegalArgumentException("Task not found on disk: " + taskId);
        }

        // Создаём контекст с восстановленным временем создания
        TaskContext ctx = new TaskContext(taskId, meta.createdAt() != null ? meta.createdAt() : Instant.now());
        tasks.put(taskId, ctx);

        // Восстанавливаем workingDirectory
        if (meta.workingDirectory() != null) {
            ctx.workingDirectory = Path.of(meta.workingDirectory());
        }

        // Загружаем журнал из H2
        ctx.loadJournal();

        // Восстанавливаем активный TODO
        if (meta.activeTodoFile() != null) {
            ctx.activeTodoFile = meta.activeTodoFile();
        }

        return ctx;
    }

    /**
     * Проверяет, активна ли задача в памяти.
     */
    public static boolean isActiveInMemory(String taskId) {
        return tasks.containsKey(taskId);
    }

    /**
     * Метаданные задачи для хранения на диске.
     */
    public record TaskMetadata(String taskId, Instant createdAt, Instant lastActivityAt,
                                     String activeTodoFile, String workingDirectory) {}

    // ==================== Instance API ====================

    public String getTaskId() {
        return taskId;
    }

    public TaskTransactionManager transactions() {
        return transactionManager;
    }

    public TaskLineAccessTracker tokens() {
        return lineAccessTracker;
    }

    public TaskSearchTracker search() {
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
        saveJournal();
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
     * Возвращает путь к директории задачи.
     * Структура: ~/.nts/tasks/{taskId}/
     */
    public Path getTaskDir() {
        return PathSanitizer.getTaskRoot().resolve("tasks/" + taskId);
    }

    /**
     * Возвращает рабочую директорию проекта для этой задачи.
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
     * Возвращает путь к директории todos задачи.
     */
    public Path getTodosDir() {
        return getTaskDir().resolve("todos");
    }



    /**
     * Возвращает время создания задачи.
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
        saveJournal();
    }

    /**
     * Сохраняет метаданные задачи в H2 task_metadata.
     * Транзакции сохраняются в H2 автоматически при commit/undo/redo.
     */
    public void saveJournal() {
        if ("default".equals(taskId)) {
            return;
        }

        try {
            JournalDatabase database = transactionManager.getDatabase();
            try (Connection conn = database.getInitializedConnection()) {
                journalRepo.setMetadata(conn, "taskId", taskId);
                journalRepo.setMetadata(conn, "workingDirectory",
                        workingDirectory != null ? workingDirectory.toString() : "");
                journalRepo.setMetadata(conn, "createdAt", createdAt.toString());
                journalRepo.setMetadata(conn, "lastActivity", lastActivityAt.toString());
                if (activeTodoFile != null) {
                    journalRepo.setMetadata(conn, "activeTodo", activeTodoFile);
                }
                for (var entry : metadata.entrySet()) {
                    journalRepo.setMetadata(conn, "custom." + entry.getKey(), entry.getValue());
                }
            }
        } catch (SQLException e) {
            System.err.println("Warning: Failed to save task metadata: " + e.getMessage());
        }
    }

    /**
     * Backward compatibility alias.
     */
    public void saveMetadata() {
        saveJournal();
    }

    /**
     * Загружает журнал задачи из H2.
     * Инициализирует базу и восстанавливает метаданные.
     */
    public void loadJournal() {
        try {
            transactionManager.initializeDb();
            JournalDatabase database = transactionManager.getDatabase();
            try (Connection conn = database.getConnection()) {
                String wd = journalRepo.getMetadata(conn, "workingDirectory");
                if (wd != null && !wd.isEmpty()) {
                    this.workingDirectory = Path.of(wd);
                }
                String todo = journalRepo.getMetadata(conn, "activeTodo");
                if (todo != null) {
                    this.activeTodoFile = todo;
                }
                loadCustomMetadata(conn);
            }
        } catch (SQLException e) {
            System.err.println("Warning: Failed to load journal from H2: " + e.getMessage());
        }
    }

    /**
     * Загружает custom metadata из H2 (ключи с префиксом "custom.").
     */
    private void loadCustomMetadata(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery(
                     "SELECT meta_key, meta_value FROM task_metadata WHERE meta_key LIKE 'custom.%'")) {
            while (rs.next()) {
                String key = rs.getString("meta_key").substring("custom.".length());
                String value = rs.getString("meta_value");
                if (value != null) {
                    metadata.put(key, value);
                }
            }
        }
    }

    /**
     * Очистка ресурсов задачи.
     */
    private void cleanup() {
        transactionManager.getDatabase().close();
        transactionManager.reset();
        lineAccessTracker.reset();
        searchTracker.clear();
        fileLineageTracker.reset();
        externalChangeTracker.reset();
        activeTodoFile = null;
    }

    @Override
    public String toString() {
        return "TaskContext[" + taskId + "]";
    }
}
