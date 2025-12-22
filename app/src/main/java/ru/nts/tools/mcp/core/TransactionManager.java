// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Менеджер транзакций для обеспечения атомарности и функций UNDO/REDO.
 * Поддерживает вложенные транзакции: изменения фиксируются на диск только при завершении внешней транзакции.
 */
public class TransactionManager {
    
    private static final List<Transaction> undoStack = new ArrayList<>();
    private static final List<Transaction> redoStack = new ArrayList<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
    
    private static Transaction currentTransaction = null;
    private static int nestingLevel = 0;

    /**
     * Возвращает путь к директории снимков, создавая её при необходимости.
     */
    private static Path getSnapshotDir() throws IOException {
        Path dir = PathSanitizer.getRoot().resolve(".mcp/snapshots");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * Начинает новую транзакцию. 
     * Если транзакция уже активна, увеличивает уровень вложенности.
     * 
     * @param description Описание операции для журнала.
     */
    public static void startTransaction(String description) {
        if (currentTransaction == null) {
            currentTransaction = new Transaction(description, LocalDateTime.now());
        }
        nestingLevel++;
    }

    /**
     * Создает резервную копию файла перед его изменением в рамках текущей транзакции.
     * 
     * @param path Путь к файлу.
     */
    public static void backup(Path path) throws IOException {
        if (currentTransaction == null) return;
        currentTransaction.addFile(path);
    }

    /**
     * Завершает текущую транзакцию. 
     * Изменения фиксируются в истории UNDO только при выходе из самой внешней транзакции.
     */
    public static void commit() {
        if (currentTransaction == null) return;
        
        nestingLevel--;
        if (nestingLevel <= 0) {
            if (!currentTransaction.isEmpty()) {
                undoStack.add(currentTransaction);
                redoStack.clear(); // Новая правка делает невозможным REDO старых отмененных правок
            }
            currentTransaction = null;
            nestingLevel = 0;
        }
    }

    /**
     * Откатывает текущую незавершенную транзакцию (вызывается при ошибках).
     * Сбрасывает уровень вложенности до нуля.
     */
    public static void rollback() {
        if (currentTransaction == null) return;
        try {
            currentTransaction.restore();
        } catch (IOException e) {
            System.err.println("Rollback failed: " + e.getMessage());
        }
        currentTransaction = null;
        nestingLevel = 0;
    }

    /**
     * Отменяет последнюю совершенную транзакцию (UNDO).
     * 
     * @return Текстовый статус операции.
     */
    public static String undo() throws IOException {
        if (undoStack.isEmpty()) return "No operations to undo.";
        
        Transaction tx = undoStack.remove(undoStack.size() - 1);
        // Сохраняем состояние ПЕРЕД откатом в REDO стек
        Transaction redoTx = new Transaction("REDO: " + tx.description, LocalDateTime.now());
        for (Path path : tx.getAffectedPaths()) {
            redoTx.addFile(path);
        }
        
        tx.restore();
        redoStack.add(redoTx);
        return "Undone: " + tx.description;
    }

    /**
     * Повторяет ранее отмененную транзакцию (REDO).
     * 
     * @return Текстовый статус операции.
     */
    public static String redo() throws IOException {
        if (redoStack.isEmpty()) return "No operations to redo.";
        
        Transaction tx = redoStack.remove(redoStack.size() - 1);
        Transaction undoTx = new Transaction("UNDO REDO: " + tx.description, LocalDateTime.now());
        for (Path path : tx.getAffectedPaths()) {
            undoTx.addFile(path);
        }
        
        tx.restore();
        undoStack.add(undoTx);
        return "Redone: " + tx.description;
    }

    /**
     * Формирует текстовый журнал истории транзакций.
     */
    public static String getJournal() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TRANSACTION JOURNAL ===\n\n");
        
        sb.append("Available for UNDO:\n");
        if (undoStack.isEmpty()) sb.append("  (empty)\n");
        for (int i = undoStack.size() - 1; i >= 0; i--) {
            Transaction tx = undoStack.get(i);
            sb.append(String.format("  [%s] %s (%d files)\n", 
                    tx.timestamp.format(formatter), tx.description, tx.snapshots.size()));
        }

        sb.append("\nAvailable for REDO:\n");
        if (redoStack.isEmpty()) sb.append("  (empty)\n");
        for (int i = redoStack.size() - 1; i >= 0; i--) {
            Transaction tx = redoStack.get(i);
            sb.append(String.format("  [%s] %s (%d files)\n", 
                    tx.timestamp.format(formatter), tx.description, tx.snapshots.size()));
        }
        
        return sb.toString();
    }

    /**
     * Полный сброс истории (используется в тестах).
     */
    public static void reset() {
        undoStack.clear();
        redoStack.clear();
        currentTransaction = null;
        nestingLevel = 0;
    }

    /**
     * Внутренний класс для хранения данных транзакции и её снимков.
     */
    private static class Transaction {
        private final String description;
        private final LocalDateTime timestamp;
        private final Map<Path, Path> snapshots = new HashMap<>(); 

        public Transaction(String description, LocalDateTime timestamp) {
            this.description = description;
            this.timestamp = timestamp;
        }

        /**
         * Добавляет файл в транзакцию, создавая его физическую копию.
         */
        public void addFile(Path path) throws IOException {
            Path absPath = path.toAbsolutePath().normalize();
            if (snapshots.containsKey(absPath)) return; 

            if (Files.exists(absPath)) {
                // Если файл существует — копируем его
                Path backup = getSnapshotDir().resolve(UUID.randomUUID().toString() + ".bak");
                Files.copy(absPath, backup);
                snapshots.put(absPath, backup);
            } else {
                // Если файл новый — помечаем как null (будет удален при откате)
                snapshots.put(absPath, null);
            }
        }

        /**
         * Восстанавливает все файлы транзакции из их снимков.
         */
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