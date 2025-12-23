// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Менеджер транзакций (Transaction Manager).
 * Обеспечивает атомарность операций над файловой системой, функции UNDO/REDO и поддержку чекпоинтов.
 */
public class TransactionManager {

    public enum Status { COMMITTED, STUCK }

    private static final List<TransactionEntry> undoStack = new CopyOnWriteArrayList<>();
    private static final List<TransactionEntry> redoStack = new CopyOnWriteArrayList<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_HISTORY_SIZE = 50;
    private static final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();
    private static final ThreadLocal<Integer> nestingLevel = ThreadLocal.withInitial(() -> 0);
    
    // Ссылка на активный TODO файл
    private static String activeTodoPath = null;

    public static void setActiveTodo(String path) { activeTodoPath = path; }

    private static Path getSnapshotDir() throws IOException {
        Path dir = PathSanitizer.getRoot().resolve(".mcp/snapshots");
        if (!Files.exists(dir)) Files.createDirectories(dir);
        return dir;
    }

    public static void startTransaction(String description) { startTransaction(description, null); }

    public static void startTransaction(String description, String instruction) {
        if (currentTransaction.get() == null) {
            currentTransaction.set(new Transaction(description, instruction, LocalDateTime.now()));
        }
        nestingLevel.set(nestingLevel.get() + 1);
    }

    public static void backup(Path path) throws IOException {
        Transaction tx = currentTransaction.get();
        if (tx != null) tx.addFile(path);
    }

    public static void commit() {
        Transaction tx = currentTransaction.get();
        if (tx == null) return;

        int level = nestingLevel.get() - 1;
        nestingLevel.set(level);

        if (level <= 0) {
            if (!tx.isEmpty()) {
                tx.updateStats();
                undoStack.add(tx);
                redoStack.clear();
                if (undoStack.size() > MAX_HISTORY_SIZE) {
                    TransactionEntry old = undoStack.remove(0);
                    if (old instanceof Transaction t) t.deleteSnapshots();
                }
            }
            currentTransaction.remove();
            nestingLevel.set(0);
        }
    }

    public static void rollback() {
        Transaction tx = currentTransaction.get();
        if (tx == null) return;
        try {
            tx.restore();
            tx.deleteSnapshots();
        } catch (IOException e) {
            throw new RuntimeException("CRITICAL: Transaction rollback failed! " + e.getMessage(), e);
        } finally {
            currentTransaction.remove();
            nestingLevel.set(0);
        }
    }

    /**
     * Создает именованную контрольную точку.
     */
    public static void createCheckpoint(String name) {
        undoStack.add(new Checkpoint(name, LocalDateTime.now()));
    }

    /**
     * Откатывает состояние до указанного чекпоинта.
     */
    public static String rollbackToCheckpoint(String name) throws IOException {
        int idx = -1;
        for (int i = undoStack.size() - 1; i >= 0; i--) {
            if (undoStack.get(i) instanceof Checkpoint cp && name.equals(cp.name)) {
                idx = i;
                break;
            }
        }
        if (idx == -1) throw new IllegalArgumentException("Checkpoint not found: " + name);

        StringBuilder sb = new StringBuilder("Rolling back to checkpoint: " + name + "\n");
        while (undoStack.size() > idx) {
            TransactionEntry entry = undoStack.get(undoStack.size() - 1);
            if (entry instanceof Transaction tx) {
                undo();
                sb.append("- Undone: ").append(tx.instruction != null ? tx.instruction : tx.description).append("\n");
            } else {
                undoStack.remove(undoStack.size() - 1);
            }
        }
        return sb.toString().trim();
    }

    public static String undo() throws IOException {
        if (undoStack.isEmpty()) return "No operations to undo.";
        TransactionEntry entry = undoStack.get(undoStack.size() - 1);
        if (entry instanceof Checkpoint) {
            undoStack.remove(undoStack.size() - 1);
            return "Passed checkpoint: " + ((Checkpoint) entry).name;
        }
        Transaction tx = (Transaction) entry;
        try {
            tx.checkFiles();
            undoStack.remove(undoStack.size() - 1);
            Transaction redoTx = new Transaction("REDO: " + tx.description, tx.instruction, LocalDateTime.now());
            for (Path path : tx.getAffectedPaths()) redoTx.addFile(path);
            tx.restore();
            redoStack.add(redoTx);
            return "Undone: " + (tx.instruction != null ? tx.instruction : tx.description);
        } catch (IOException e) {
            tx.setStatus(Status.STUCK);
            throw new IOException("Undo failed: " + tx.description + ". Marked as STUCK. " + e.getMessage(), e);
        }
    }

    public static String redo() throws IOException {
        if (redoStack.isEmpty()) return "No operations to redo.";
        TransactionEntry entry = redoStack.get(redoStack.size() - 1);
        Transaction tx = (Transaction) entry;
        try {
            tx.checkFiles();
            redoStack.remove(redoStack.size() - 1);
            Transaction undoTx = new Transaction("UNDO REDO: " + tx.description, tx.instruction, LocalDateTime.now());
            for (Path path : tx.getAffectedPaths()) undoTx.addFile(path);
            tx.restore();
            undoStack.add(undoTx);
            return "Redone: " + (tx.instruction != null ? tx.instruction : tx.description);
        } catch (IOException e) {
            tx.setStatus(Status.STUCK);
            throw new IOException("Redo failed: " + tx.description + ". Marked as STUCK. " + e.getMessage(), e);
        }
    }

    /**
     * Собирает все инструкции с момента последнего коммита для генерации сообщения.
     */
    public static List<String> getSessionInstructions() {
        List<String> instructions = new ArrayList<>();
        for (TransactionEntry entry : undoStack) {
            if (entry instanceof Transaction tx && tx.instruction != null) {
                instructions.add(tx.instruction);
            }
        }
        return instructions;
    }

    public static String getJournal() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TRANSACTION JOURNAL ===\n");
        if (activeTodoPath != null) sb.append("Active TODO: ").append(activeTodoPath).append("\n");
        sb.append("\nAvailable for UNDO:\n");
        if (undoStack.isEmpty()) sb.append("  (empty)\n");
        for (int i = undoStack.size() - 1; i >= 0; i--) appendEntryInfo(sb, undoStack.get(i));
        sb.append("\nAvailable for REDO:\n");
        if (redoStack.isEmpty()) sb.append("  (empty)\n");
        for (int i = redoStack.size() - 1; i >= 0; i--) appendEntryInfo(sb, redoStack.get(i));
        return sb.toString();
    }

    private static void appendEntryInfo(StringBuilder sb, TransactionEntry entry) {
        if (entry instanceof Checkpoint cp) {
            sb.append(String.format("  [%s] [CHECKPOINT] >>> %s <<<\n", cp.timestamp.format(formatter), cp.name));
        } else if (entry instanceof Transaction tx) {
            String status = tx.getStatus() == Status.STUCK ? " [STUCK]" : "";
            String label = tx.instruction != null ? tx.instruction + ": " : "";
            sb.append(String.format("  [%s]%s %s%s (%d files)\n", tx.timestamp.format(formatter), status, label, tx.description, tx.snapshots.size()));
            Path root = PathSanitizer.getRoot();
            for (Path path : tx.getAffectedPaths()) {
                Path relPath = root.relativize(path);
                FileDiffStats s = tx.stats.get(path);
                String gitStatus = GitUtils.getFileStatus(path);
                String gitMark = gitStatus.isEmpty() ? "" : " [" + gitStatus + "]";
                if (s != null) {
                    sb.append(String.format("    - %s%s: +%d, -%d lines", relPath, gitMark, s.added, s.deleted));
                    if (!s.affectedBlocks.isEmpty()) sb.append(" | Blocks: ").append(String.join(", ", s.affectedBlocks));
                } else {
                    sb.append(String.format("    - %s%s: (meta/structure change)", relPath, gitMark));
                }
                sb.append("\n");
            }
        }
    }

    public static void reset() {
        for (TransactionEntry entry : undoStack) if (entry instanceof Transaction t) t.deleteSnapshots();
        for (TransactionEntry entry : redoStack) if (entry instanceof Transaction t) t.deleteSnapshots();
        undoStack.clear(); redoStack.clear();
        Transaction tx = currentTransaction.get();
        if (tx != null) tx.deleteSnapshots();
        currentTransaction.remove();
        nestingLevel.set(0);
    }

    private interface TransactionEntry { LocalDateTime getTimestamp(); }

    private record Checkpoint(String name, LocalDateTime timestamp) implements TransactionEntry {
        @Override public LocalDateTime getTimestamp() { return timestamp; }
    }

    private static class Transaction implements TransactionEntry {
        private final String description;
        private final String instruction;
        private final LocalDateTime timestamp;
        private Status status = Status.COMMITTED;
        private final Map<Path, Path> snapshots = new HashMap<>();
        private final Map<Path, FileDiffStats> stats = new HashMap<>();

        public Transaction(String description, String instruction, LocalDateTime timestamp) {
            this.description = description; this.instruction = instruction; this.timestamp = timestamp;
        }

        @Override public LocalDateTime getTimestamp() { return timestamp; }

        public void addFile(Path path) throws IOException {
            Path absPath = path.toAbsolutePath().normalize();
            if (snapshots.containsKey(absPath)) return;
            if (Files.exists(absPath)) {
                Path backup = getSnapshotDir().resolve(UUID.randomUUID().toString() + ".bak");
                FileUtils.safeCopy(absPath, backup);
                snapshots.put(absPath, backup);
            } else {
                snapshots.put(absPath, null);
            }
        }

        public void updateStats() {
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey(); Path snapshot = entry.getValue();
                try {
                    String oldContent = (snapshot != null) ? Files.readString(snapshot) : "";
                    String newContent = Files.exists(original) ? Files.readString(original) : "";
                    if (!oldContent.equals(newContent)) stats.put(original, calculateStats(oldContent, newContent));
                } catch (IOException ignored) {}
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
            for (String line : newLines) if (!oldLineSet.contains(line)) added++;
            for (String line : oldLines) if (!newLineSet.contains(line)) deleted++;
            Set<String> blocks = new TreeSet<>();
            for (String line : newLines) {
                String trimmed = line.trim();
                if (!oldLineSet.contains(line) && (trimmed.contains("class ") || (trimmed.contains("(") && trimmed.endsWith("{")))) {
                    String name = extractName(trimmed);
                    if (name != null) blocks.add(name);
                }
            }
            return new FileDiffStats(added, deleted, new ArrayList<>(blocks));
        }

        private String extractName(String line) {
            String[] parts = line.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("(")) return parts[i].split("\\(")[0];
                if ("class".equals(parts[i]) && i + 1 < parts.length) return "class " + parts[i+1].split("\\{")[0];
            }
            return null;
        }

        public void checkFiles() throws IOException { for (Path path : snapshots.keySet()) FileUtils.checkFileAvailability(path); }

        public void restore() throws IOException {
            Path projectRoot = PathSanitizer.getRoot();
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey(); Path backup = entry.getValue();
                if (backup != null) { Files.createDirectories(original.getParent()); FileUtils.safeCopy(backup, original); }
                else { FileUtils.safeDelete(original); FileUtils.deleteEmptyParents(original, projectRoot); }
            }
        }

        public void deleteSnapshots() {
            for (Path backup : snapshots.values()) if (backup != null) try { FileUtils.safeDelete(backup); } catch (IOException ignored) {}
        }

        public boolean isEmpty() { return snapshots.isEmpty(); }
        public Set<Path> getAffectedPaths() { return snapshots.keySet(); }
        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }
    }

    private record FileDiffStats(int added, int deleted, List<String> affectedBlocks) {}
}