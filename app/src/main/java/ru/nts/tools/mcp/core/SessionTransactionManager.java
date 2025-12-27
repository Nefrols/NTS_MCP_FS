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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session менеджер транзакций.
 * Обеспечивает изоляцию undo/redo стеков между сессиями.
 *
 * Каждая сессия имеет собственный экземпляр этого класса.
 *
 * Поддерживает:
 * - Session Tokens: внутри транзакции CRC-проверка токенов отключена
 * - InfinityRange: для файлов, созданных в транзакции, проверка границ строк отключена
 */
public class SessionTransactionManager {

    public enum Status {
        COMMITTED,
        STUCK
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_HISTORY_SIZE = 50;

    // Per-session стеки (не static!)
    private final List<TransactionEntry> undoStack = new ArrayList<>();
    private final List<TransactionEntry> redoStack = new ArrayList<>();

    // Per-session счетчики (AtomicInteger для thread-safety внутри сессии)
    private final AtomicInteger totalEdits = new AtomicInteger(0);
    private final AtomicInteger totalUndos = new AtomicInteger(0);

    // Per-thread состояние транзакции (для вложенных транзакций)
    private final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();
    private final ThreadLocal<Integer> nestingLevel = ThreadLocal.withInitial(() -> 0);

    // Файлы, созданные в текущей транзакции (InfinityRange)
    private final ThreadLocal<Set<Path>> filesCreatedInTransaction = ThreadLocal.withInitial(HashSet::new);

    // Файлы, к которым был получен доступ в текущей транзакции (Session Tokens)
    private final ThreadLocal<Set<Path>> filesAccessedInTransaction = ThreadLocal.withInitial(HashSet::new);

    // Виртуальный контент файлов в текущей транзакции (для batch refactoring)
    private final ThreadLocal<Map<Path, String>> virtualContents = ThreadLocal.withInitial(HashMap::new);

    private Path getSnapshotDir() throws IOException {
        // Используем currentOrDefault() для согласованности
        Path dir = SessionContext.currentOrDefault().getSnapshotsDir();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    public void startTransaction(String description) {
        startTransaction(description, null);
    }

    public void startTransaction(String description, String instruction) {
        if (currentTransaction.get() == null) {
            currentTransaction.set(new Transaction(description, instruction, LocalDateTime.now()));
        }
        nestingLevel.set(nestingLevel.get() + 1);
    }

    public void backup(Path path) throws IOException {
        Transaction tx = currentTransaction.get();
        if (tx != null) {
            tx.addFile(path, getSnapshotDir());
        }
    }

    public void commit() {
        Transaction tx = currentTransaction.get();
        if (tx == null) {
            return;
        }

        int level = nestingLevel.get() - 1;
        nestingLevel.set(level);

        if (level <= 0) {
            if (!tx.isEmpty()) {
                tx.updateStats();
                synchronized (undoStack) {
                    undoStack.add(tx);
                    if (undoStack.size() > MAX_HISTORY_SIZE) {
                        TransactionEntry old = undoStack.remove(0);
                        if (old instanceof Transaction t) {
                            t.deleteSnapshots();
                        }
                    }
                }
                synchronized (redoStack) {
                    redoStack.clear();
                }
                totalEdits.incrementAndGet();
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
        if (tx == null) {
            return;
        }
        try {
            tx.restore();
            tx.deleteSnapshots();
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

    // ==================== Session Tokens & InfinityRange API ====================

    /**
     * Проверяет, выполняется ли сейчас транзакция (batch/edit).
     * Используется для пропуска CRC-проверки токенов внутри транзакции.
     */
    public boolean isInTransaction() {
        return currentTransaction.get() != null;
    }

    /**
     * Регистрирует файл как созданный в текущей транзакции.
     * Для таких файлов отключается проверка границ строк (InfinityRange).
     */
    public void markFileCreatedInTransaction(Path path) {
        if (isInTransaction()) {
            filesCreatedInTransaction.get().add(path.toAbsolutePath().normalize());
        }
    }

    /**
     * Проверяет, был ли файл создан в текущей транзакции.
     * Для таких файлов не требуется проверка границ токена.
     */
    public boolean isFileCreatedInTransaction(Path path) {
        return filesCreatedInTransaction.get().contains(path.toAbsolutePath().normalize());
    }

    /**
     * Регистрирует файл как "разблокированный" в текущей транзакции.
     * Для таких файлов CRC-проверка пропускается до конца транзакции.
     */
    public void markFileAccessedInTransaction(Path path) {
        if (isInTransaction()) {
            filesAccessedInTransaction.get().add(path.toAbsolutePath().normalize());
        }
    }

    /**
     * Проверяет, был ли файл разблокирован в текущей транзакции.
     * Для таких файлов CRC-проверка пропускается.
     */
    public boolean isFileAccessedInTransaction(Path path) {
        return filesAccessedInTransaction.get().contains(path.toAbsolutePath().normalize());
    }

    // ==================== Virtual Content API (для batch refactoring) ====================

    /**
     * Устанавливает виртуальный контент файла.
     * Используется в batch для передачи изменённого контента в последующие шаги.
     */
    public void setVirtualContent(Path path, String content) {
        if (isInTransaction()) {
            virtualContents.get().put(path.toAbsolutePath().normalize(), content);
        }
    }

    /**
     * Получает виртуальный контент файла или null если не установлен.
     */
    public String getVirtualContent(Path path) {
        return virtualContents.get().get(path.toAbsolutePath().normalize());
    }

    /**
     * Проверяет, есть ли виртуальный контент для файла.
     */
    public boolean hasVirtualContent(Path path) {
        return virtualContents.get().containsKey(path.toAbsolutePath().normalize());
    }

    /**
     * Записывает перемещение файла в FileLineageTracker.
     * Вызывается при move/rename операциях.
     */
    public void recordFileMove(Path oldPath, Path newPath) {
        SessionContext ctx = SessionContext.currentOrDefault();
        ctx.lineage().recordMove(oldPath, newPath);
    }

    /**
     * Регистрирует файл в FileLineageTracker.
     * Вызывается при создании или первом доступе к файлу.
     */
    public String registerFile(Path path) {
        SessionContext ctx = SessionContext.currentOrDefault();
        return ctx.lineage().registerFile(path);
    }

    /**
     * Обновляет CRC файла в FileLineageTracker.
     * Вызывается после редактирования файла.
     */
    public void updateFileCrc(Path path) {
        SessionContext ctx = SessionContext.currentOrDefault();
        ctx.lineage().updateCrc(path);
    }

    /**
     * Возвращает набор файлов, затронутых в текущей транзакции.
     * Используется для обновления снапшотов после выполнения операций.
     *
     * @return набор путей к затронутым файлам, или пустой набор если транзакция не активна
     */
    public Set<Path> getCurrentTransactionAffectedPaths() {
        Transaction tx = currentTransaction.get();
        if (tx != null) {
            return new HashSet<>(tx.getAffectedPaths());
        }
        return Set.of();
    }

    /**
     * Записывает внешнее изменение файла в журнал.
     * Создаёт специальную транзакцию типа EXTERNAL_CHANGE, которая:
     * - Содержит снапшот предыдущего состояния файла (до внешнего изменения)
     * - Отображается в журнале с меткой [EXTERNAL]
     * - Может быть откачена через undo (возврат к состоянию до внешнего изменения)
     *
     * @param path путь к файлу
     * @param previousContent предыдущее содержимое файла (до внешнего изменения)
     * @param previousCrc CRC32C предыдущего состояния
     * @param currentCrc CRC32C текущего состояния (после внешнего изменения)
     * @param description описание изменения
     */
    public void recordExternalChange(Path path, String previousContent, long previousCrc, long currentCrc, String description) {
        Path absPath = path.toAbsolutePath().normalize();

        try {
            // Создаём снапшот предыдущего содержимого
            Path snapshotDir = getSnapshotDir();
            Path backup = snapshotDir.resolve(UUID.randomUUID().toString() + ".bak");
            Files.writeString(backup, previousContent);

            // Создаём специальную транзакцию для внешнего изменения
            ExternalChangeTransaction tx = new ExternalChangeTransaction(
                description,
                LocalDateTime.now(),
                absPath,
                backup,
                previousCrc,
                currentCrc
            );

            synchronized (undoStack) {
                undoStack.add(tx);
                if (undoStack.size() > MAX_HISTORY_SIZE) {
                    TransactionEntry old = undoStack.remove(0);
                    if (old instanceof Transaction t) {
                        t.deleteSnapshots();
                    } else if (old instanceof ExternalChangeTransaction ext) {
                        ext.deleteSnapshot();
                    }
                }
            }

            // Очищаем redo стек - внешнее изменение начинает новую ветку истории
            synchronized (redoStack) {
                redoStack.clear();
            }

        } catch (IOException e) {
            // Логируем ошибку, но не прерываем работу
            System.err.println("Failed to record external change: " + e.getMessage());
        }
    }

    public void createCheckpoint(String name) {
        synchronized (undoStack) {
            undoStack.add(new Checkpoint(name, LocalDateTime.now()));
        }
    }

    public String rollbackToCheckpoint(String name) throws IOException {
        synchronized (undoStack) {
            int idx = -1;
            for (int i = undoStack.size() - 1; i >= 0; i--) {
                if (undoStack.get(i) instanceof Checkpoint cp && name.equals(cp.name)) {
                    idx = i;
                    break;
                }
            }
            if (idx == -1) {
                throw new IllegalArgumentException("Checkpoint not found: " + name);
            }

            StringBuilder sb = new StringBuilder("Rolling back to checkpoint: " + name + "\n");
            while (undoStack.size() > idx) {
                TransactionEntry entry = undoStack.get(undoStack.size() - 1);
                if (entry instanceof Transaction tx) {
                    undoInternal();
                    sb.append("- Undone: ").append(tx.instruction != null ? tx.instruction : tx.description).append("\n");
                } else {
                    undoStack.remove(undoStack.size() - 1);
                }
            }
            return sb.toString().trim();
        }
    }

    public String undo() throws IOException {
        synchronized (undoStack) {
            return undoInternal();
        }
    }

    /**
     * Выполняет умный откат с поддержкой Path Lineage.
     * Использует SmartUndoEngine для:
     * - Отслеживания перемещённых файлов
     * - Частичного отката dirty directories
     * - Поиска "потерянных" файлов по CRC
     * - Рекомендаций Git recovery
     *
     * @return детальный результат отката
     */
    public UndoResult smartUndo() throws IOException {
        synchronized (undoStack) {
            if (undoStack.isEmpty()) {
                return UndoResult.nothingToUndo();
            }

            TransactionEntry entry = undoStack.get(undoStack.size() - 1);
            if (entry instanceof Checkpoint cp) {
                undoStack.remove(undoStack.size() - 1);
                return UndoResult.builder()
                        .status(UndoResult.Status.SUCCESS)
                        .message("Passed checkpoint: " + cp.name())
                        .build();
            }

            // Обработка внешних изменений через стандартный undo
            if (entry instanceof ExternalChangeTransaction) {
                String message = undoInternal();
                return UndoResult.builder()
                        .status(UndoResult.Status.SUCCESS)
                        .message(message)
                        .build();
            }

            Transaction tx = (Transaction) entry;
            SessionContext ctx = SessionContext.currentOrDefault();
            FileLineageTracker lineage = ctx.lineage();

            SmartUndoEngine engine = new SmartUndoEngine(lineage, PathSanitizer.getRoot());
            String description = tx.instruction != null ? tx.instruction : tx.description;

            // Создаём redo транзакцию до отката
            Transaction redoTx = new Transaction("REDO: " + tx.description, tx.instruction, LocalDateTime.now());
            for (Path path : tx.getAffectedPaths()) {
                redoTx.addFile(path, getSnapshotDir());
            }

            // Выполняем умный откат
            UndoResult result = engine.smartUndo(tx.getSnapshotsMap(), description);

            // Если откат успешен или частичен - обновляем стеки
            if (result.isSuccess() || result.isPartial()) {
                undoStack.remove(undoStack.size() - 1);
                // After restore, invalidate ExternalChangeTracker snapshots for affected files
                ExternalChangeTracker externalTracker = ctx.externalChanges();
                for (Path path : tx.getAffectedPaths()) {
                    externalTracker.removeSnapshot(path);
                }
                synchronized (redoStack) {
                    redoStack.add(redoTx);
                }
                totalUndos.incrementAndGet();
            } else if (result.isFailed()) {
                // STUCK - помечаем транзакцию
                tx.setStatus(Status.STUCK);
            }

            return result;
        }
    }

    private String undoInternal() throws IOException {
        if (undoStack.isEmpty()) {
            return "No operations to undo.";
        }
        TransactionEntry entry = undoStack.get(undoStack.size() - 1);
        if (entry instanceof Checkpoint) {
            undoStack.remove(undoStack.size() - 1);
            return "Passed checkpoint: " + ((Checkpoint) entry).name;
        }

        // Обработка внешних изменений
        if (entry instanceof ExternalChangeTransaction extTx) {
            try {
                undoStack.remove(undoStack.size() - 1);
                // Создаём redo транзакцию для текущего состояния
                Transaction redoTx = new Transaction("REDO EXTERNAL: " + extTx.getDescription(), null, LocalDateTime.now());
                redoTx.addFile(extTx.getAffectedPath(), getSnapshotDir());
                // Восстанавливаем к состоянию до внешнего изменения
                extTx.restore();
                synchronized (redoStack) {
                    redoStack.add(redoTx);
                }
                totalUndos.incrementAndGet();
                return "Undone external change: " + extTx.getDescription();
            } catch (IOException e) {
                extTx.setStatus(Status.STUCK);
                throw new IOException("Undo external change failed: " + extTx.getDescription() + ". " + e.getMessage(), e);
            }
        }

        Transaction tx = (Transaction) entry;
        try {
            tx.checkFiles();
            undoStack.remove(undoStack.size() - 1);
            Transaction redoTx = new Transaction("REDO: " + tx.description, tx.instruction, LocalDateTime.now());
            for (Path path : tx.getAffectedPaths()) {
                redoTx.addFile(path, getSnapshotDir());
            }
            tx.restore();
            // After restore, invalidate ExternalChangeTracker snapshots for affected files
            // so they don't trigger false "external change" detection
            ExternalChangeTracker externalTracker = SessionContext.currentOrDefault().externalChanges();
            for (Path path : tx.getAffectedPaths()) {
                externalTracker.removeSnapshot(path);
            }
            synchronized (redoStack) {
                redoStack.add(redoTx);
            }
            totalUndos.incrementAndGet();
            return "Undone: " + (tx.instruction != null ? tx.instruction : tx.description);
        } catch (IOException e) {
            tx.setStatus(Status.STUCK);
            throw new IOException("Undo failed: " + tx.description + ". Marked as STUCK. " + e.getMessage(), e);
        }
    }

    public String redo() throws IOException {
        synchronized (redoStack) {
            if (redoStack.isEmpty()) {
                return "No operations to redo.";
            }
            Transaction tx = (Transaction) redoStack.get(redoStack.size() - 1);
            try {
                tx.checkFiles();
                redoStack.remove(redoStack.size() - 1);
                Transaction undoTx = new Transaction("UNDO REDO: " + tx.description, tx.instruction, LocalDateTime.now());
                for (Path path : tx.getAffectedPaths()) {
                    undoTx.addFile(path, getSnapshotDir());
                }
                tx.restore();
                // After restore, invalidate ExternalChangeTracker snapshots for affected files
                ExternalChangeTracker externalTracker = SessionContext.currentOrDefault().externalChanges();
                for (Path path : tx.getAffectedPaths()) {
                    externalTracker.removeSnapshot(path);
                }
                synchronized (undoStack) {
                    undoStack.add(undoTx);
                }
                return "Redone: " + (tx.instruction != null ? tx.instruction : tx.description);
            } catch (IOException e) {
                tx.setStatus(Status.STUCK);
                throw new IOException("Redo failed: " + tx.description + ". Marked as STUCK. " + e.getMessage(), e);
            }
        }
    }

    public List<String> getFileHistory(Path path) {
        List<String> history = new ArrayList<>();
        Path absPath = path.toAbsolutePath().normalize();

        synchronized (undoStack) {
            for (TransactionEntry entry : undoStack) {
                if (entry instanceof ExternalChangeTransaction extTx && extTx.getAffectedPath().equals(absPath)) {
                    // Внешнее изменение
                    history.add(String.format("[%s] [EXTERNAL] %s (CRC: %X -> %X)",
                            extTx.timestamp.format(FORMATTER),
                            extTx.getDescription(),
                            extTx.getPreviousCrc(),
                            extTx.getCurrentCrc()));
                } else if (entry instanceof Transaction tx && tx.getAffectedPaths().contains(absPath)) {
                    String label = tx.instruction != null ? tx.instruction : tx.description;
                    FileDiffStats s = tx.stats.get(absPath);
                    String lines = s != null ? String.format(" (+%d/-%d lines)", s.added, s.deleted) : " (structural)";
                    history.add(String.format("[%s] %s%s", tx.timestamp.format(FORMATTER), label, lines));
                }
            }
        }
        return history;
    }

    public String getSessionStats() {
        SessionContext ctx = SessionContext.current();
        int unlocked = ctx != null ? ctx.tokens().getAccessedFilesCount() : 0;
        return String.format("Session: %d edits, %d undos | Unlocked: %d files",
                totalEdits.get(), totalUndos.get(), unlocked);
    }

    public List<String> getSessionInstructions() {
        List<String> instructions = new ArrayList<>();
        synchronized (undoStack) {
            for (TransactionEntry entry : undoStack) {
                if (entry instanceof Transaction tx && tx.instruction != null) {
                    instructions.add(tx.instruction);
                }
            }
        }
        return instructions;
    }

    public String getJournal() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TRANSACTION JOURNAL ===\n");

        SessionContext ctx = SessionContext.current();
        String activeTodo = ctx != null ? ctx.getActiveTodoFile() : null;
        if (activeTodo != null) {
            sb.append("Active TODO: ").append(activeTodo).append("\n");
        }

        sb.append("\nAvailable for UNDO:\n");
        synchronized (undoStack) {
            if (undoStack.isEmpty()) {
                sb.append("  (empty)\n");
            }
            for (int i = undoStack.size() - 1; i >= 0; i--) {
                appendEntryInfo(sb, undoStack.get(i));
            }
        }

        sb.append("\nAvailable for REDO:\n");
        synchronized (redoStack) {
            if (redoStack.isEmpty()) {
                sb.append("  (empty)\n");
            }
            for (int i = redoStack.size() - 1; i >= 0; i--) {
                appendEntryInfo(sb, redoStack.get(i));
            }
        }
        return sb.toString();
    }

    private void appendEntryInfo(StringBuilder sb, TransactionEntry entry) {
        if (entry instanceof Checkpoint cp) {
            sb.append(String.format("  [%s] [CHECKPOINT] >>> %s <<<\n", cp.timestamp.format(FORMATTER), cp.name));
        } else if (entry instanceof ExternalChangeTransaction extTx) {
            // Специальное форматирование для внешних изменений
            String status = extTx.getStatus() == Status.STUCK ? " [STUCK]" : "";
            sb.append(String.format("  [%s]%s [EXTERNAL] %s\n",
                    extTx.timestamp.format(FORMATTER), status, extTx.getDescription()));

            Path root = PathSanitizer.getRoot();
            Path relPath = root.relativize(extTx.getAffectedPath());
            String gitStatus = GitUtils.getFileStatus(extTx.getAffectedPath());
            String gitMark = gitStatus.isEmpty() ? "" : " [" + gitStatus + "]";
            sb.append(String.format("    - %s%s: CRC %X -> %X (external modification)\n",
                    relPath, gitMark, extTx.getPreviousCrc(), extTx.getCurrentCrc()));
        } else if (entry instanceof Transaction tx) {
            String status = tx.getStatus() == Status.STUCK ? " [STUCK]" : "";
            String label = tx.instruction != null ? tx.instruction + ": " : "";
            sb.append(String.format("  [%s]%s %s%s (%d files)\n",
                    tx.timestamp.format(FORMATTER), status, label, tx.description, tx.snapshots.size()));

            Path root = PathSanitizer.getRoot();
            for (Path path : tx.getAffectedPaths()) {
                Path relPath = root.relativize(path);
                FileDiffStats s = tx.stats.get(path);
                String gitStatus = GitUtils.getFileStatus(path);
                String gitMark = gitStatus.isEmpty() ? "" : " [" + gitStatus + "]";
                if (s != null) {
                    sb.append(String.format("    - %s%s: +%d, -%d lines", relPath, gitMark, s.added, s.deleted));
                    if (!s.affectedBlocks.isEmpty()) {
                        sb.append(" | Blocks: ").append(String.join(", ", s.affectedBlocks));
                    }
                } else {
                    sb.append(String.format("    - %s%s: (meta/structure change)", relPath, gitMark));
                }
                sb.append("\n");
            }
        }
    }

    public void reset() {
        synchronized (undoStack) {
            for (TransactionEntry entry : undoStack) {
                if (entry instanceof Transaction t) {
                    t.deleteSnapshots();
                } else if (entry instanceof ExternalChangeTransaction ext) {
                    ext.deleteSnapshot();
                }
            }
            undoStack.clear();
        }
        synchronized (redoStack) {
            for (TransactionEntry entry : redoStack) {
                if (entry instanceof Transaction t) {
                    t.deleteSnapshots();
                } else if (entry instanceof ExternalChangeTransaction ext) {
                    ext.deleteSnapshot();
                }
            }
            redoStack.clear();
        }
        totalEdits.set(0);
        totalUndos.set(0);
        Transaction tx = currentTransaction.get();
        if (tx != null) {
            tx.deleteSnapshots();
        }
        currentTransaction.remove();
        nestingLevel.set(0);
    }

    // ==================== Inner Classes ====================

    private interface TransactionEntry {
        LocalDateTime getTimestamp();
    }

    private record Checkpoint(String name, LocalDateTime timestamp) implements TransactionEntry {
        @Override
        public LocalDateTime getTimestamp() {
            return timestamp;
        }
    }

    private static class Transaction implements TransactionEntry {
        private final String description;
        private final String instruction;
        private final LocalDateTime timestamp;
        private Status status = Status.COMMITTED;
        private final Map<Path, Path> snapshots = new HashMap<>();
        private final Map<Path, FileDiffStats> stats = new HashMap<>();

        public Transaction(String description, String instruction, LocalDateTime timestamp) {
            this.description = description;
            this.instruction = instruction;
            this.timestamp = timestamp;
        }

        @Override
        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void addFile(Path path, Path snapshotDir) throws IOException {
            Path absPath = path.toAbsolutePath().normalize();
            if (snapshots.containsKey(absPath)) {
                return;
            }
            if (Files.exists(absPath)) {
                Path backup = snapshotDir.resolve(UUID.randomUUID().toString() + ".bak");
                FileUtils.safeCopy(absPath, backup);
                snapshots.put(absPath, backup);
            } else {
                snapshots.put(absPath, null);
            }
        }

        public void updateStats() {
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                Path snapshot = entry.getValue();
                                try {
                                    String oldContent = (snapshot != null) ? EncodingUtils.readTextFile(snapshot).content() : "";
                                    String newContent = Files.exists(original) ? EncodingUtils.readTextFile(original).content() : "";
                                    if (!oldContent.equals(newContent)) {
                                        stats.put(original, calculateStats(oldContent, newContent));
                                    }
                                } catch (IOException ignored) {
                                }
            }
        }

        private FileDiffStats calculateStats(String oldContent, String newContent) {
            String normOld = oldContent.replace("\r\n", "\n");
            String normNew = newContent.replace("\r\n", "\n");
            String[] oldLines = normOld.isEmpty() ? new String[0] : normOld.split("\n", -1);
            String[] newLines = normNew.isEmpty() ? new String[0] : normNew.split("\n", -1);
            int added = 0, deleted = 0;
            Set<String> oldLineSet = new HashSet<>(Arrays.asList(oldLines));
            Set<String> newLineSet = new HashSet<>(Arrays.asList(newLines));
            for (String line : newLines) {
                if (!oldLineSet.contains(line)) {
                    added++;
                }
            }
            for (String line : oldLines) {
                if (!newLineSet.contains(line)) {
                    deleted++;
                }
            }
            Set<String> blocks = new TreeSet<>();
            for (String line : newLines) {
                String trimmed = line.trim();
                if (!oldLineSet.contains(line) && (trimmed.contains("class ") || (trimmed.contains("(") && trimmed.endsWith("{")))) {
                    String name = extractName(trimmed);
                    if (name != null) {
                        blocks.add(name);
                    }
                }
            }
            return new FileDiffStats(added, deleted, new ArrayList<>(blocks));
        }

        private String extractName(String line) {
            String[] parts = line.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("(")) {
                    return parts[i].split("\\(")[0];
                }
                if ("class".equals(parts[i]) && i + 1 < parts.length) {
                    return "class " + parts[i + 1].split("\\{")[0];
                }
            }
            return null;
        }

        public void checkFiles() throws IOException {
            for (Path path : snapshots.keySet()) {
                FileUtils.checkFileAvailability(path);
            }
        }

        public void restore() throws IOException {
            Path projectRoot = PathSanitizer.getRoot();
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                Path backup = entry.getValue();
                if (backup != null) {
                    Files.createDirectories(original.getParent());
                    FileUtils.safeCopy(backup, original);
                } else {
                    FileUtils.safeDelete(original);
                    FileUtils.deleteEmptyParents(original, projectRoot);
                }
            }
        }

        public void deleteSnapshots() {
            for (Path backup : snapshots.values()) {
                if (backup != null) {
                    try {
                        FileUtils.safeDelete(backup);
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        public boolean isEmpty() {
            return snapshots.isEmpty();
        }

        public Set<Path> getAffectedPaths() {
            return snapshots.keySet();
        }

        public Map<Path, Path> getSnapshotsMap() {
            return Collections.unmodifiableMap(snapshots);
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }
    }

    /**
     * Транзакция для записи внешних изменений файла.
     * В отличие от обычной Transaction, хранит информацию о том,
     * что изменение было сделано извне (человек, линтер, IDE и т.д.).
     */
    private static class ExternalChangeTransaction implements TransactionEntry {
        private final String description;
        private final LocalDateTime timestamp;
        private final Path affectedPath;
        private final Path snapshotPath;
        private final long previousCrc;
        private final long currentCrc;
        private Status status = Status.COMMITTED;

        public ExternalChangeTransaction(String description, LocalDateTime timestamp,
                                          Path affectedPath, Path snapshotPath,
                                          long previousCrc, long currentCrc) {
            this.description = description;
            this.timestamp = timestamp;
            this.affectedPath = affectedPath;
            this.snapshotPath = snapshotPath;
            this.previousCrc = previousCrc;
            this.currentCrc = currentCrc;
        }

        @Override
        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public String getDescription() {
            return description;
        }

        public Path getAffectedPath() {
            return affectedPath;
        }

        public Path getSnapshotPath() {
            return snapshotPath;
        }

        public long getPreviousCrc() {
            return previousCrc;
        }

        public long getCurrentCrc() {
            return currentCrc;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        /**
         * Восстанавливает файл к состоянию до внешнего изменения.
         */
        public void restore() throws IOException {
            if (snapshotPath != null && Files.exists(snapshotPath)) {
                Files.createDirectories(affectedPath.getParent());
                FileUtils.safeCopy(snapshotPath, affectedPath);
            }
        }

        /**
         * Удаляет файл снапшота.
         */
        public void deleteSnapshot() {
            if (snapshotPath != null) {
                try {
                    FileUtils.safeDelete(snapshotPath);
                } catch (IOException ignored) {
                }
            }
        }

        /**
         * Возвращает множество затронутых путей (для совместимости с Transaction).
         */
        public Set<Path> getAffectedPaths() {
            return Set.of(affectedPath);
        }

        /**
         * Возвращает карту снапшотов (для совместимости с SmartUndoEngine).
         */
        public Map<Path, Path> getSnapshotsMap() {
            return Map.of(affectedPath, snapshotPath);
        }
    }

    private record FileDiffStats(int added, int deleted, List<String> affectedBlocks) {
    }
}
