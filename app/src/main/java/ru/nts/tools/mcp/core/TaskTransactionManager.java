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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

/**
 * Per-task менеджер транзакций с хранением в H2.
 * Обеспечивает изоляцию undo/redo стеков между задачами.
 *
 * Каждая задача имеет собственный экземпляр этого класса.
 * Снапшоты файлов хранятся как BLOB в H2 (не как .bak файлы на диске).
 *
 * Поддерживает:
 * - Task Tokens: внутри транзакции CRC-проверка токенов отключена
 * - InfinityRange: для файлов, созданных в транзакции, проверка границ строк отключена
 */
public class TaskTransactionManager {

    public enum Status {
        COMMITTED,
        STUCK
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_HISTORY_SIZE = 50;

    private final String taskId;
    private final TaskContext taskContext;

    // H2 database and repository
    private final JournalDatabase db;
    private final JournalRepository repo;

    // Per-task счетчики (кеш из H2, атомарные для thread-safety)
    private final AtomicInteger totalEdits = new AtomicInteger(0);
    private final AtomicInteger totalUndos = new AtomicInteger(0);

    // Per-thread состояние текущей незакоммиченной транзакции
    private final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();
    private final ThreadLocal<Integer> nestingLevel = ThreadLocal.withInitial(() -> 0);

    // Файлы, созданные в текущей транзакции (InfinityRange)
    private final ThreadLocal<Set<Path>> filesCreatedInTransaction = ThreadLocal.withInitial(HashSet::new);

    // Файлы, к которым был получен доступ в текущей транзакции (Task Tokens)
    private final ThreadLocal<Set<Path>> filesAccessedInTransaction = ThreadLocal.withInitial(HashSet::new);

    // Виртуальный контент файлов в текущей транзакции (для batch refactoring)
    private final ThreadLocal<Map<Path, String>> virtualContents = ThreadLocal.withInitial(HashMap::new);

    // Файлы, созданные в текущей ЗАДАЧЕ (не очищается при commit/rollback)
    private final Set<Path> filesCreatedInTask = Collections.synchronizedSet(new HashSet<>());

    public TaskTransactionManager(String taskId, TaskContext taskContext) {
        this.taskId = taskId;
        this.taskContext = taskContext;
        // In-memory DB for "default" task and when forceInMemoryDb is set (tests)
        this.db = ("default".equals(taskId) || TaskContext.isForceInMemoryDb())
                ? JournalDatabase.inMemory()
                : new JournalDatabase(taskContext.getTaskDir());
        this.repo = new JournalRepository();
    }

    /**
     * Возвращает JournalDatabase для внешнего доступа (например, из TaskContext при миграции).
     */
    public JournalDatabase getDatabase() {
        return db;
    }

    /**
     * Инициализирует H2 базу и загружает счетчики.
     * Вызывается при первом обращении к менеджеру или при реактивации задачи.
     */
    public void initializeDb() {
        try {
            db.initialize();
            try (Connection conn = db.getInitializedConnection()) {
                totalEdits.set(repo.getCounter(conn, "totalEdits"));
                totalUndos.set(repo.getCounter(conn, "totalUndos"));
            }
        } catch (SQLException e) {
            System.err.println("Warning: Failed to initialize journal DB: " + e.getMessage());
        }
    }

    // ==================== Transaction Lifecycle ====================

    public void startTransaction(String description) {
        startTransaction(description, null);
    }

    public void startTransaction(String description, String instruction) {
        ensureDbInitialized();
        if (currentTransaction.get() == null) {
            currentTransaction.set(new Transaction(description, instruction, LocalDateTime.now()));
        }
        nestingLevel.set(nestingLevel.get() + 1);
    }

    public void backup(Path path) throws IOException {
        Transaction tx = currentTransaction.get();
        if (tx != null) {
            tx.addFile(path);
        }
    }

    public void commit() {
        Transaction tx = currentTransaction.get();
        if (tx == null) return;

        int level = nestingLevel.get() - 1;
        nestingLevel.set(level);

        if (level <= 0) {
            if (!tx.isEmpty()) {
                tx.updateStats();
                commitToDb(tx);
                totalEdits.incrementAndGet();
                editsSinceLastVerify.incrementAndGet();
            }
            currentTransaction.remove();
            nestingLevel.set(0);
            filesCreatedInTransaction.get().clear();
            filesAccessedInTransaction.get().clear();
            virtualContents.get().clear();
        }
    }

    public void rollback() {
        Transaction tx = currentTransaction.get();
        if (tx == null) return;
        try {
            tx.restore();
        } catch (IOException e) {
            throw new RuntimeException("CRITICAL: Transaction rollback failed! " + e.getMessage(), e);
        } finally {
            currentTransaction.remove();
            nestingLevel.set(0);
            filesCreatedInTransaction.get().clear();
            filesAccessedInTransaction.get().clear();
            virtualContents.get().clear();
        }
    }

    /**
     * Записывает закоммиченную транзакцию в H2.
     */
    private void commitToDb(Transaction tx) {
        try (Connection conn = db.getInitializedConnection()) {
            conn.setAutoCommit(false);

            // Очищаем REDO стек
            repo.clearStack(conn, "REDO");

            // Получаем следующий position
            int pos = repo.getMaxPosition(conn, "UNDO") + 1;

            // Вставляем journal_entry
            long entryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", pos,
                    tx.timestamp, tx.description, Status.COMMITTED.name(),
                    tx.instruction, null, null, null, null);

            // Вставляем file_snapshots
            for (Map.Entry<Path, byte[]> e : tx.snapshots.entrySet()) {
                Path absPath = e.getKey();
                byte[] content = e.getValue(); // null if file was created
                String relPath = toRelativePath(absPath);
                long size = content != null ? content.length : 0;
                long crc = content != null ? computeCrc32c(content) : 0;
                repo.insertSnapshot(conn, entryId, relPath, content, size, crc);
            }

            // Вставляем diff_stats
            for (Map.Entry<Path, FileDiffStats> e : tx.stats.entrySet()) {
                FileDiffStats s = e.getValue();
                String relPath = toRelativePath(e.getKey());
                String blocks = s.affectedBlocks.isEmpty() ? null : String.join(",", s.affectedBlocks);
                repo.insertDiffStats(conn, entryId, relPath, s.added, s.deleted, blocks, s.unifiedDiff);
            }

            // Обрезаем UNDO стек если превышен лимит
            int undoSize = repo.getStackSize(conn, "UNDO");
            while (undoSize > MAX_HISTORY_SIZE) {
                repo.deleteOldestEntry(conn, "UNDO");
                undoSize--;
            }

            // Обновляем счетчик
            repo.setCounter(conn, "totalEdits", totalEdits.get() + 1);

            conn.commit();
        } catch (SQLException e) {
            System.err.println("Warning: Failed to commit transaction to DB: " + e.getMessage());
        }
    }

    // ==================== Task Tokens & InfinityRange API ====================

    public boolean isInTransaction() {
        return currentTransaction.get() != null;
    }

    public void markFileCreatedInTransaction(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (isInTransaction()) {
            filesCreatedInTransaction.get().add(normalized);
        }
        filesCreatedInTask.add(normalized);
    }

    public boolean isFileCreatedInTransaction(Path path) {
        return filesCreatedInTransaction.get().contains(path.toAbsolutePath().normalize());
    }

    public boolean isFileCreatedInTask(Path path) {
        return filesCreatedInTask.contains(path.toAbsolutePath().normalize());
    }

    public void markFileAccessedInTransaction(Path path) {
        if (isInTransaction()) {
            filesAccessedInTransaction.get().add(path.toAbsolutePath().normalize());
        }
    }

    public boolean isFileAccessedInTransaction(Path path) {
        return filesAccessedInTransaction.get().contains(path.toAbsolutePath().normalize());
    }

    // ==================== Virtual Content API ====================

    public void setVirtualContent(Path path, String content) {
        if (isInTransaction()) {
            virtualContents.get().put(path.toAbsolutePath().normalize(), content);
        }
    }

    public String getVirtualContent(Path path) {
        return virtualContents.get().get(path.toAbsolutePath().normalize());
    }

    public boolean hasVirtualContent(Path path) {
        return virtualContents.get().containsKey(path.toAbsolutePath().normalize());
    }

    // ==================== File Lineage API ====================

    public void recordFileMove(Path oldPath, Path newPath) {
        TaskContext ctx = TaskContext.currentOrDefault();
        ctx.lineage().recordMove(oldPath, newPath);
    }

    public String registerFile(Path path) {
        TaskContext ctx = TaskContext.currentOrDefault();
        return ctx.lineage().registerFile(path);
    }

    public void updateFileCrc(Path path) {
        TaskContext ctx = TaskContext.currentOrDefault();
        ctx.lineage().updateCrc(path);
    }

    public Set<Path> getCurrentTransactionAffectedPaths() {
        Transaction tx = currentTransaction.get();
        if (tx != null) {
            return new HashSet<>(tx.getAffectedPaths());
        }
        return Set.of();
    }

    // ==================== External Changes ====================

    public void recordExternalChange(Path path, String previousContent, long previousCrc, long currentCrc, String description) {
        Path absPath = path.toAbsolutePath().normalize();
        ensureDbInitialized();

        try (Connection conn = db.getInitializedConnection()) {
            conn.setAutoCommit(false);

            // Очищаем redo — внешнее изменение начинает новую ветку
            repo.clearStack(conn, "REDO");

            int pos = repo.getMaxPosition(conn, "UNDO") + 1;
            String relPath = toRelativePath(absPath);

            byte[] prevBytes = previousContent != null ? previousContent.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
            long entryId = repo.insertEntry(conn, "UNDO", "EXTERNAL", pos,
                    LocalDateTime.now(), description, Status.COMMITTED.name(),
                    null, relPath, previousCrc, currentCrc, null);

            // Снапшот предыдущего содержимого
            if (prevBytes != null) {
                repo.insertSnapshot(conn, entryId, relPath, prevBytes, prevBytes.length, previousCrc);
            }

            // Обрезаем стек
            int undoSize = repo.getStackSize(conn, "UNDO");
            while (undoSize > MAX_HISTORY_SIZE) {
                repo.deleteOldestEntry(conn, "UNDO");
                undoSize--;
            }

            conn.commit();
        } catch (SQLException e) {
            System.err.println("Failed to record external change: " + e.getMessage());
        }
    }

    // ==================== Checkpoints ====================

    public void createCheckpoint(String name) {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            int pos = repo.getMaxPosition(conn, "UNDO") + 1;
            repo.insertEntry(conn, "UNDO", "CHECKPOINT", pos,
                    LocalDateTime.now(), null, Status.COMMITTED.name(),
                    null, null, null, null, name);
        } catch (SQLException e) {
            System.err.println("Failed to create checkpoint: " + e.getMessage());
        }
    }

    public String rollbackToCheckpoint(String name) throws IOException {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            int cpPos = repo.findCheckpointPosition(conn, "UNDO", name);
            if (cpPos < 0) {
                throw new IllegalArgumentException("Checkpoint not found: " + name);
            }

            List<JournalRepository.JournalEntry> entries = repo.getEntriesAfterPosition(conn, "UNDO", cpPos);
            StringBuilder sb = new StringBuilder("Rolling back to checkpoint: " + name + "\n");

            for (JournalRepository.JournalEntry entry : entries) {
                if (entry.isTransaction()) {
                    undoEntry(conn, entry);
                    String label = entry.instruction() != null ? entry.instruction() : entry.description();
                    sb.append("- Undone: ").append(label).append("\n");
                } else if (entry.isExternal()) {
                    undoExternalEntry(conn, entry);
                    sb.append("- Undone external: ").append(entry.description()).append("\n");
                } else {
                    // Checkpoint — just delete
                    repo.deleteEntry(conn, entry.id());
                }
            }

            return sb.toString().trim();
        } catch (SQLException e) {
            throw new IOException("Rollback to checkpoint failed: " + e.getMessage(), e);
        }
    }

    // ==================== Undo / Redo ====================

    public String undo() throws IOException {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            conn.setAutoCommit(false);
            JournalRepository.JournalEntry entry = repo.getLastEntry(conn, "UNDO");
            if (entry == null) {
                return "No operations to undo.";
            }

            if (entry.isCheckpoint()) {
                repo.deleteEntry(conn, entry.id());
                conn.commit();
                return "Passed checkpoint: " + entry.checkpointName();
            }

            if (entry.isExternal()) {
                String result = undoExternalEntry(conn, entry);
                conn.commit();
                totalUndos.incrementAndGet();
                updateCounter(conn, "totalUndos", totalUndos.get());
                conn.commit();
                return result;
            }

            // TRANSACTION
            String result = undoEntry(conn, entry);
            conn.commit();
            totalUndos.incrementAndGet();
            updateCounter(conn, "totalUndos", totalUndos.get());
            conn.commit();
            return result;
        } catch (SQLException e) {
            throw new IOException("Undo failed: " + e.getMessage(), e);
        }
    }

    public UndoResult smartUndo() throws IOException {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            conn.setAutoCommit(false);
            JournalRepository.JournalEntry entry = repo.getLastEntry(conn, "UNDO");
            if (entry == null) {
                return UndoResult.nothingToUndo();
            }

            if (entry.isCheckpoint()) {
                repo.deleteEntry(conn, entry.id());
                conn.commit();
                return UndoResult.builder()
                        .status(UndoResult.Status.SUCCESS)
                        .message("Passed checkpoint: " + entry.checkpointName())
                        .build();
            }

            if (entry.isExternal()) {
                String message = undoExternalEntry(conn, entry);
                conn.commit();
                totalUndos.incrementAndGet();
                return UndoResult.builder()
                        .status(UndoResult.Status.SUCCESS)
                        .message(message)
                        .build();
            }

            // TRANSACTION — use SmartUndoEngine
            Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entry.id());
            Map<Path, byte[]> pathSnapshots = new LinkedHashMap<>();
            for (var snap : snapshots.values()) {
                Path absPath = toAbsolutePath(snap.filePath());
                pathSnapshots.put(absPath, snap.content());
            }

            TaskContext ctx = TaskContext.currentOrDefault();
            FileLineageTracker lineage = ctx.lineage();
            SmartUndoEngine engine = new SmartUndoEngine(lineage, PathSanitizer.getRoot());
            String description = entry.instruction() != null ? entry.instruction() : entry.description();

            // Создаём redo запись ДО отката
            createRedoEntryFromCurrent(conn, entry, pathSnapshots.keySet());

            UndoResult result = engine.smartUndo(pathSnapshots, description);

            if (result.isSuccess() || result.isPartial()) {
                repo.deleteEntry(conn, entry.id());
                // Invalidate ExternalChangeTracker
                ExternalChangeTracker externalTracker = ctx.externalChanges();
                for (Path path : pathSnapshots.keySet()) {
                    externalTracker.removeSnapshot(path);
                }
                conn.commit();
                totalUndos.incrementAndGet();
            } else if (result.isFailed()) {
                repo.updateEntryStatus(conn, entry.id(), Status.STUCK.name());
                conn.commit();
            }

            return result;
        } catch (SQLException e) {
            throw new IOException("Smart undo failed: " + e.getMessage(), e);
        }
    }

    public String redo() throws IOException {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            conn.setAutoCommit(false);
            JournalRepository.JournalEntry entry = repo.getLastEntry(conn, "REDO");
            if (entry == null) {
                return "No operations to redo.";
            }

            // Читаем снапшоты redo-записи (текущее состояние до redo)
            Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entry.id());

            // Создаём undo запись с текущим состоянием файлов
            int undoPos = repo.getMaxPosition(conn, "UNDO") + 1;
            long undoEntryId = repo.insertEntry(conn, "UNDO", "TRANSACTION", undoPos,
                    LocalDateTime.now(), "UNDO REDO: " + entry.description(), Status.COMMITTED.name(),
                    entry.instruction(), null, null, null, null);

            for (var snap : snapshots.values()) {
                Path absPath = toAbsolutePath(snap.filePath());
                byte[] currentContent = Files.exists(absPath) ? Files.readAllBytes(absPath) : null;
                long size = currentContent != null ? currentContent.length : 0;
                long crc = currentContent != null ? computeCrc32c(currentContent) : 0;
                repo.insertSnapshot(conn, undoEntryId, snap.filePath(), currentContent, size, crc);
            }

            // Восстанавливаем файлы из redo снапшотов
            for (var snap : snapshots.values()) {
                Path absPath = toAbsolutePath(snap.filePath());
                restoreFileFromSnapshot(absPath, snap.content());
            }

            // Invalidate ExternalChangeTracker
            ExternalChangeTracker externalTracker = TaskContext.currentOrDefault().externalChanges();
            for (var snap : snapshots.values()) {
                externalTracker.removeSnapshot(toAbsolutePath(snap.filePath()));
            }

            // Удаляем redo запись
            repo.deleteEntry(conn, entry.id());
            conn.commit();

            return "Redone: " + (entry.instruction() != null ? entry.instruction() : entry.description());
        } catch (SQLException e) {
            throw new IOException("Redo failed: " + e.getMessage(), e);
        }
    }

    // ==================== Internal Undo Helpers ====================

    private String undoEntry(Connection conn, JournalRepository.JournalEntry entry) throws IOException, SQLException {
        Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entry.id());

        // Создаём redo запись с текущим состоянием
        int redoPos = repo.getMaxPosition(conn, "REDO") + 1;
        long redoEntryId = repo.insertEntry(conn, "REDO", "TRANSACTION", redoPos,
                LocalDateTime.now(), "REDO: " + entry.description(), Status.COMMITTED.name(),
                entry.instruction(), null, null, null, null);

        for (var snap : snapshots.values()) {
            Path absPath = toAbsolutePath(snap.filePath());
            byte[] currentContent = Files.exists(absPath) ? Files.readAllBytes(absPath) : null;
            long size = currentContent != null ? currentContent.length : 0;
            long crc = currentContent != null ? computeCrc32c(currentContent) : 0;
            repo.insertSnapshot(conn, redoEntryId, snap.filePath(), currentContent, size, crc);
        }

        // Восстанавливаем файлы из undo снапшотов
        for (var snap : snapshots.values()) {
            Path absPath = toAbsolutePath(snap.filePath());
            restoreFileFromSnapshot(absPath, snap.content());
        }

        // Invalidate ExternalChangeTracker
        ExternalChangeTracker externalTracker = TaskContext.currentOrDefault().externalChanges();
        for (var snap : snapshots.values()) {
            externalTracker.removeSnapshot(toAbsolutePath(snap.filePath()));
        }

        // Удаляем undo запись
        repo.deleteEntry(conn, entry.id());

        return "Undone: " + (entry.instruction() != null ? entry.instruction() : entry.description());
    }

    private String undoExternalEntry(Connection conn, JournalRepository.JournalEntry entry) throws IOException, SQLException {
        Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entry.id());
        Path affectedPath = toAbsolutePath(entry.affectedPath());

        // Создаём redo с текущим состоянием
        int redoPos = repo.getMaxPosition(conn, "REDO") + 1;
        long redoEntryId = repo.insertEntry(conn, "REDO", "TRANSACTION", redoPos,
                LocalDateTime.now(), "REDO EXTERNAL: " + entry.description(), Status.COMMITTED.name(),
                null, null, null, null, null);

        byte[] currentContent = Files.exists(affectedPath) ? Files.readAllBytes(affectedPath) : null;
        long size = currentContent != null ? currentContent.length : 0;
        long crc = currentContent != null ? computeCrc32c(currentContent) : 0;
        repo.insertSnapshot(conn, redoEntryId, entry.affectedPath(), currentContent, size, crc);

        // Восстанавливаем из снапшота
        if (!snapshots.isEmpty()) {
            JournalRepository.FileSnapshot snap = snapshots.values().iterator().next();
            restoreFileFromSnapshot(affectedPath, snap.content());
        }

        repo.deleteEntry(conn, entry.id());
        return "Undone external change: " + entry.description();
    }

    private void createRedoEntryFromCurrent(Connection conn, JournalRepository.JournalEntry undoEntry,
                                             Set<Path> affectedPaths) throws IOException, SQLException {
        int redoPos = repo.getMaxPosition(conn, "REDO") + 1;
        long redoEntryId = repo.insertEntry(conn, "REDO", "TRANSACTION", redoPos,
                LocalDateTime.now(), "REDO: " + undoEntry.description(), Status.COMMITTED.name(),
                undoEntry.instruction(), null, null, null, null);

        for (Path absPath : affectedPaths) {
            byte[] currentContent = Files.exists(absPath) ? Files.readAllBytes(absPath) : null;
            String relPath = toRelativePath(absPath);
            long size = currentContent != null ? currentContent.length : 0;
            long crc = currentContent != null ? computeCrc32c(currentContent) : 0;
            repo.insertSnapshot(conn, redoEntryId, relPath, currentContent, size, crc);
        }
    }

    private void restoreFileFromSnapshot(Path absPath, byte[] content) throws IOException {
        Path projectRoot = PathSanitizer.getRoot();
        if (content != null) {
            Files.createDirectories(absPath.getParent());
            Files.write(absPath, content);
        } else {
            FileUtils.safeDelete(absPath);
            FileUtils.deleteEmptyParents(absPath, projectRoot);
        }
    }

    // ==================== Query API ====================

    public List<String> getFileHistory(Path path) {
        ensureDbInitialized();
        List<String> history = new ArrayList<>();
        String relPath = toRelativePath(path.toAbsolutePath().normalize());

        try (Connection conn = db.getInitializedConnection()) {
            List<JournalRepository.JournalEntry> entries = repo.getEntriesForFile(conn, relPath);
            for (var entry : entries) {
                if (entry.isExternal()) {
                    history.add(String.format("[%s] [EXTERNAL] %s (CRC: %X -> %X)",
                            entry.timestamp().format(FORMATTER), entry.description(),
                            entry.previousCrc(), entry.currentCrc()));
                } else if (entry.isTransaction()) {
                    String label = entry.instruction() != null ? entry.instruction() : entry.description();
                    List<JournalRepository.DiffStat> stats = repo.getDiffStats(conn, entry.id());
                    String lines = "";
                    for (var s : stats) {
                        if (s.filePath().equals(relPath)) {
                            lines = String.format(" (+%d/-%d lines)", s.linesAdded(), s.linesDeleted());
                            break;
                        }
                    }
                    if (lines.isEmpty()) lines = " (structural)";
                    history.add(String.format("[%s] %s%s", entry.timestamp().format(FORMATTER), label, lines));
                }
            }
        } catch (SQLException e) {
            history.add("Error reading history: " + e.getMessage());
        }
        return history;
    }

    public String getTaskStats() {
        TaskContext ctx = TaskContext.current();
        int unlocked = ctx != null ? ctx.tokens().getAccessedFilesCount() : 0;
        return String.format("Task: %d edits, %d undos | Unlocked: %d files",
                totalEdits.get(), totalUndos.get(), unlocked);
    }

    public int getTotalEdits() {
        return totalEdits.get();
    }

    public int getTotalUndos() {
        return totalUndos.get();
    }

    public List<String> getTaskInstructions() {
        ensureDbInitialized();
        List<String> instructions = new ArrayList<>();
        try (Connection conn = db.getInitializedConnection()) {
            List<JournalRepository.JournalEntry> entries = repo.getEntries(conn, "UNDO");
            for (var entry : entries) {
                if (entry.isTransaction() && entry.instruction() != null) {
                    instructions.add(entry.instruction());
                }
            }
        } catch (SQLException e) {
            // silently fail
        }
        return instructions;
    }

    public String getJournal() {
        ensureDbInitialized();
        StringBuilder sb = new StringBuilder();
        sb.append("=== TRANSACTION JOURNAL ===\n");

        TaskContext ctx = TaskContext.current();
        String activeTodo = ctx != null ? ctx.getActiveTodoFile() : null;
        if (activeTodo != null) {
            sb.append("Active TODO: ").append(activeTodo).append("\n");
        }

        try (Connection conn = db.getInitializedConnection()) {
            sb.append("\nAvailable for UNDO:\n");
            List<JournalRepository.JournalEntry> undoEntries = repo.getEntries(conn, "UNDO");
            if (undoEntries.isEmpty()) {
                sb.append("  (empty)\n");
            }
            for (int i = undoEntries.size() - 1; i >= 0; i--) {
                appendEntryInfo(sb, conn, undoEntries.get(i));
            }

            sb.append("\nAvailable for REDO:\n");
            List<JournalRepository.JournalEntry> redoEntries = repo.getEntries(conn, "REDO");
            if (redoEntries.isEmpty()) {
                sb.append("  (empty)\n");
            }
            for (int i = redoEntries.size() - 1; i >= 0; i--) {
                appendEntryInfo(sb, conn, redoEntries.get(i));
            }
        } catch (SQLException e) {
            sb.append("  Error reading journal: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    private void appendEntryInfo(StringBuilder sb, Connection conn, JournalRepository.JournalEntry entry) throws SQLException {
        if (entry.isCheckpoint()) {
            sb.append(String.format("  [%s] [CHECKPOINT] >>> %s <<<\n",
                    entry.timestamp().format(FORMATTER), entry.checkpointName()));
        } else if (entry.isExternal()) {
            String status = "STUCK".equals(entry.status()) ? " [STUCK]" : "";
            sb.append(String.format("  [%s]%s [EXTERNAL] %s\n",
                    entry.timestamp().format(FORMATTER), status, entry.description()));
            sb.append(String.format("    - %s: CRC %X -> %X (external modification)\n",
                    entry.affectedPath(), entry.previousCrc(), entry.currentCrc()));
        } else if (entry.isTransaction()) {
            String status = "STUCK".equals(entry.status()) ? " [STUCK]" : "";
            String label = entry.instruction() != null ? entry.instruction() + ": " : "";

            Map<String, JournalRepository.FileSnapshot> snapshots = repo.getSnapshots(conn, entry.id());
            sb.append(String.format("  [%s]%s %s%s (%d files)\n",
                    entry.timestamp().format(FORMATTER), status, label, entry.description(), snapshots.size()));

            List<JournalRepository.DiffStat> stats = repo.getDiffStats(conn, entry.id());
            Map<String, JournalRepository.DiffStat> statsMap = new HashMap<>();
            for (var s : stats) statsMap.put(s.filePath(), s);

            for (String filePath : snapshots.keySet()) {
                Path absPath = toAbsolutePath(filePath);
                String gitStatus = GitUtils.getFileStatus(absPath);
                String gitMark = gitStatus.isEmpty() ? "" : " [" + gitStatus + "]";
                JournalRepository.DiffStat s = statsMap.get(filePath);
                if (s != null) {
                    sb.append(String.format("    - %s%s: +%d, -%d lines", filePath, gitMark, s.linesAdded(), s.linesDeleted()));
                    if (s.affectedBlocks() != null && !s.affectedBlocks().isEmpty()) {
                        sb.append(" | Blocks: ").append(s.affectedBlocks());
                    }
                } else {
                    sb.append(String.format("    - %s%s: (meta/structure change)", filePath, gitMark));
                }
                sb.append("\n");
            }
        }
    }

    // ==================== Verify Counter ====================

    private final AtomicInteger editsSinceLastVerify = new AtomicInteger(0);

    public void resetVerifyCounter() {
        editsSinceLastVerify.set(0);
    }

    public int getEditsSinceLastVerify() {
        return editsSinceLastVerify.get();
    }

    // ==================== Affected Paths ====================

    /**
     * Returns all file paths affected by edits in this task (from journal).
     */
    public List<String> getAffectedPaths() {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            return repo.getAffectedPaths(conn);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Returns recent journal entries (last N operations).
     */
    public List<String> getRecentJournal(int limit) {
        ensureDbInitialized();
        try (Connection conn = db.getInitializedConnection()) {
            return repo.getRecentEntries(conn, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== Reset / Cleanup ====================

    public void reset() {
        Transaction tx = currentTransaction.get();
        // In-memory rollback of uncommitted transaction (no DB interaction needed)
        currentTransaction.remove();
        nestingLevel.set(0);
        filesCreatedInTask.clear();
        totalEdits.set(0);
        totalUndos.set(0);
        db.close();
    }

    // ==================== Utilities ====================

    private void ensureDbInitialized() {
        if (!db.isInitialized()) {
            initializeDb();
        }
    }

    private String toRelativePath(Path absolutePath) {
        if (absolutePath == null) return null;
        Path root = PathSanitizer.getRoot();
        Path normalized = absolutePath.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString();
    }

    private Path toAbsolutePath(String pathStr) {
        if (pathStr == null) return null;
        Path path = Path.of(pathStr.replace('/', java.io.File.separatorChar));
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return PathSanitizer.getRoot().resolve(path).normalize();
    }

    private static long computeCrc32c(byte[] data) {
        CRC32C crc = new CRC32C();
        crc.update(data);
        return crc.getValue();
    }

    private void updateCounter(Connection conn, String name, int value) {
        try {
            repo.setCounter(conn, name, value);
        } catch (SQLException e) {
            // non-critical
        }
    }

    // ==================== Inner Classes ====================

    /**
     * In-memory транзакция, накапливающая изменения до commit().
     * Снапшоты файлов хранятся как byte[] в памяти.
     * При commit() данные записываются в H2 как BLOB.
     */
    static class Transaction {
        final String description;
        final String instruction;
        final LocalDateTime timestamp;
        private Status status = Status.COMMITTED;
        // Снапшоты: путь файла -> его содержимое ДО изменения (null если файл не существовал)
        final Map<Path, byte[]> snapshots = new LinkedHashMap<>();
        final Map<Path, FileDiffStats> stats = new HashMap<>();

        Transaction(String description, String instruction, LocalDateTime timestamp) {
            this.description = description;
            this.instruction = instruction;
            this.timestamp = timestamp;
        }

        void addFile(Path path) throws IOException {
            Path absPath = path.toAbsolutePath().normalize();
            if (snapshots.containsKey(absPath)) return;
            if (Files.exists(absPath)) {
                snapshots.put(absPath, Files.readAllBytes(absPath));
            } else {
                snapshots.put(absPath, null); // File will be created
            }
        }

        void updateStats() {
            for (Map.Entry<Path, byte[]> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                byte[] oldBytes = entry.getValue();
                try {
                    String oldContent = (oldBytes != null) ? new String(oldBytes, java.nio.charset.StandardCharsets.UTF_8) : "";
                    String newContent = Files.exists(original)
                            ? EncodingUtils.readTextFile(original).content()
                            : "";
                    if (!oldContent.equals(newContent)) {
                        stats.put(original, calculateStats(original.getFileName().toString(), oldContent, newContent));
                    }
                } catch (IOException ignored) {
                }
            }
        }

        private FileDiffStats calculateStats(String fileName, String oldContent, String newContent) {
            String normOld = oldContent.replace("\r\n", "\n");
            String normNew = newContent.replace("\r\n", "\n");
            String[] oldLines = normOld.isEmpty() ? new String[0] : normOld.split("\n", -1);
            String[] newLines = normNew.isEmpty() ? new String[0] : normNew.split("\n", -1);
            int added = 0, deleted = 0;
            Set<String> oldLineSet = new HashSet<>(Arrays.asList(oldLines));
            Set<String> newLineSet = new HashSet<>(Arrays.asList(newLines));
            for (String line : newLines) {
                if (!oldLineSet.contains(line)) added++;
            }
            for (String line : oldLines) {
                if (!newLineSet.contains(line)) deleted++;
            }
            Set<String> blocks = new TreeSet<>();
            for (String line : newLines) {
                String trimmed = line.trim();
                if (!oldLineSet.contains(line) && (trimmed.contains("class ") || (trimmed.contains("(") && trimmed.endsWith("{")))) {
                    String name = extractName(trimmed);
                    if (name != null) blocks.add(name);
                }
            }

            // Вычисляем unified diff
            String unifiedDiff = DiffUtils.getUnifiedDiff(fileName, normOld, normNew);

            return new FileDiffStats(added, deleted, new ArrayList<>(blocks), unifiedDiff);
        }

        private String extractName(String line) {
            String[] parts = line.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("(")) return parts[i].split("\\(")[0];
                if ("class".equals(parts[i]) && i + 1 < parts.length) return "class " + parts[i + 1].split("\\{")[0];
            }
            return null;
        }

        void restore() throws IOException {
            Path projectRoot = PathSanitizer.getRoot();
            for (Map.Entry<Path, byte[]> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                byte[] content = entry.getValue();
                if (content != null) {
                    Files.createDirectories(original.getParent());
                    Files.write(original, content);
                } else {
                    FileUtils.safeDelete(original);
                    FileUtils.deleteEmptyParents(original, projectRoot);
                }
            }
        }

        boolean isEmpty() {
            return snapshots.isEmpty();
        }

        Set<Path> getAffectedPaths() {
            return snapshots.keySet();
        }

        Status getStatus() { return status; }
        void setStatus(Status status) { this.status = status; }
    }

    record FileDiffStats(int added, int deleted, List<String> affectedBlocks, String unifiedDiff) {}
}
