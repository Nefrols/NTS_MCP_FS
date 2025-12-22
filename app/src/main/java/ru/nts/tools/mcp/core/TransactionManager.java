// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Менеджер транзакций для обеспечения атомарности и функций UNDO/REDO.
 */
public class TransactionManager {
    
    private static final List<Transaction> undoStack = new ArrayList<>();
    private static final List<Transaction> redoStack = new ArrayList<>();
    
    private static Transaction currentTransaction = null;

    private static Path getSnapshotDir() throws IOException {
        Path dir = PathSanitizer.getRoot().resolve(".mcp/snapshots");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * Начинает новую транзакцию.
     */
    public static void startTransaction(String description) {
        if (currentTransaction != null) return;
        currentTransaction = new Transaction(description);
    }

    /**
     * Создает резервную копию файла перед изменением.
     */
    public static void backup(Path path) throws IOException {
        if (currentTransaction == null) return;
        currentTransaction.addFile(path);
    }

    /**
     * Завершает транзакцию и сохраняет её в историю.
     */
    public static void commit() {
        if (currentTransaction == null) return;
        if (!currentTransaction.isEmpty()) {
            undoStack.add(currentTransaction);
            redoStack.clear(); 
        }
        currentTransaction = null;
    }

    /**
     * Откатывает текущую незавершенную транзакцию.
     */
    public static void rollback() {
        if (currentTransaction == null) return;
        try {
            currentTransaction.restore();
        } catch (IOException e) {
            System.err.println("Rollback failed: " + e.getMessage());
        }
        currentTransaction = null;
    }

    /**
     * Отменяет последнюю совершенную транзакцию.
     */
    public static String undo() throws IOException {
        if (undoStack.isEmpty()) return "Нет операций для отмены.";
        
        Transaction tx = undoStack.remove(undoStack.size() - 1);
        Transaction redoTx = new Transaction("REDO: " + tx.description);
        for (Path path : tx.getAffectedPaths()) {
            redoTx.addFile(path);
        }
        
        tx.restore();
        redoStack.add(redoTx);
        return "Отменено: " + tx.description;
    }

    /**
     * Повторяет ранее отмененную транзакцию.
     */
    public static String redo() throws IOException {
        if (redoStack.isEmpty()) return "Нет операций для повтора.";
        
        Transaction tx = redoStack.remove(redoStack.size() - 1);
        Transaction undoTx = new Transaction("UNDO REDO: " + tx.description);
        for (Path path : tx.getAffectedPaths()) {
            undoTx.addFile(path);
        }
        
        tx.restore();
        undoStack.add(undoTx);
        return "Повторено: " + tx.description;
    }

    /**
     * Очищает историю (используется в тестах).
     */
    public static void reset() {
        undoStack.clear();
        redoStack.clear();
        currentTransaction = null;
    }

    private static class Transaction {
        private final String description;
        private final Map<Path, Path> snapshots = new HashMap<>(); 

        public Transaction(String description) {
            this.description = description;
        }

        public void addFile(Path path) throws IOException {
            Path absPath = path.toAbsolutePath().normalize();
            if (snapshots.containsKey(absPath)) return; 

            if (Files.exists(absPath)) {
                Path backup = getSnapshotDir().resolve(UUID.randomUUID().toString() + ".bak");
                Files.copy(absPath, backup);
                snapshots.put(absPath, backup);
            } else {
                snapshots.put(absPath, null);
            }
        }

        public void restore() throws IOException {
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                Path backup = entry.getValue();

                if (backup != null) {
                    Files.createDirectories(original.getParent());
                    Files.copy(backup, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(original);
                }
            }
        }

        public boolean isEmpty() { return snapshots.isEmpty(); }
        public Set<Path> getAffectedPaths() { return snapshots.keySet(); }
    }
}
