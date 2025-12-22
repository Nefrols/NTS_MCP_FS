// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Менеджер транзакций (Transaction Manager).
 * Обеспечивает атомарность операций над файловой системой и функции UNDO/REDO.
 * Ключевые возможности:
 * 1. Вложенные транзакции (Nesting): Позволяет объединять вызовы нескольких инструментов в один атомарный блок.
 * 2. Система снимков (Snapshots): Сохраняет состояние файлов перед изменением в директории .mcp/snapshots.
 * 3. Глобальный откат (Rollback): Гарантирует возврат всех файлов к исходному состоянию при любой ошибке.
 * 4. Управление историей: Хранит ограниченное количество транзакций для отмены с автоматической очисткой диска.
 */
public class TransactionManager {

    /**
     * Стек для хранения совершенных транзакций (доступны для UNDO).
     */
    private static final List<Transaction> undoStack = new ArrayList<>();

    /**
     * Стек для хранения отмененных транзакций (доступны для REDO).
     */
    private static final List<Transaction> redoStack = new ArrayList<>();

    /**
     * Форматтер времени для вывода в журнал транзакций.
     */
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Максимальное количество хранимых транзакций в истории UNDO.
     * Ограничивает потребление дискового пространства снимками.
     */
    private static final int MAX_HISTORY_SIZE = 50;

    /**
     * Текущая активная транзакция.
     */
    private static Transaction currentTransaction = null;

    /**
     * Текущий уровень вложенности транзакций.
     * Фиксация (commit) на диск происходит только при достижении уровня 0.
     */
    private static int nestingLevel = 0;

    /**
     * Возвращает путь к директории снимков, создавая её при необходимости.
     * Директория находится внутри служебной папки .mcp в корне проекта.
     *
     * @return Объект {@link Path} к папке снимков.
     *
     * @throws IOException Если не удалось создать директорию.
     */
    private static Path getSnapshotDir() throws IOException {
        Path dir = PathSanitizer.getRoot().resolve(".mcp/snapshots");
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * Инициализирует новую транзакцию или увеличивает уровень вложенности.
     * Если транзакция уже открыта, новая не создается, но счетчик вложенности инкрементируется.
     *
     * @param description Описание транзакции для отображения в журнале.
     */
    public static void startTransaction(String description) {
        if (currentTransaction == null) {
            currentTransaction = new Transaction(description, LocalDateTime.now());
        }
        nestingLevel++;
    }

    /**
     * Регистрирует файл в текущей транзакции и создает его резервную копию.
     * Бэкап создается только один раз за транзакцию (первое состояние "ДО").
     *
     * @param path Путь к файлу для резервного копирования.
     *
     * @throws IOException Если не удалось создать снимок файла.
     */
    public static void backup(Path path) throws IOException {
        if (currentTransaction == null) {
            return;
        }
        currentTransaction.addFile(path);
    }

    /**
     * Подтверждает завершение текущего уровня транзакции.
     * Если это была самая внешняя транзакция (уровень 0), она сохраняется в историю UNDO,
     * а стек REDO очищается.
     */
    public static void commit() {
        if (currentTransaction == null) {
            return;
        }

        nestingLevel--;
        if (nestingLevel <= 0) {
            if (!currentTransaction.isEmpty()) {
                undoStack.add(currentTransaction);
                // Новая зафиксированная правка делает невозможным REDO старых отмененных правок (стандарт истории)
                redoStack.clear();

                // Проверка лимитов истории и удаление старых снимков с диска
                if (undoStack.size() > MAX_HISTORY_SIZE) {
                    Transaction old = undoStack.remove(0);
                    old.deleteSnapshots();
                }
            }
            currentTransaction = null;
            nestingLevel = 0;
        }
    }

    /**
     * Прерывает текущую транзакцию и мгновенно восстанавливает состояние всех файлов из снимков.
     * Уровень вложенности сбрасывается в 0, транзакция считается проваленной.
     *
     * @throws RuntimeException Если критический откат не удался (риск повреждения данных).
     */
    public static void rollback() {
        if (currentTransaction == null) {
            return;
        }
        try {
            currentTransaction.restore();
            // Снимки проваленной транзакции удаляются, так как состояние возвращено в исходное
            currentTransaction.deleteSnapshots();
        } catch (IOException e) {
            // Критическая системная ошибка: ФС может находиться в частично измененном состоянии
            throw new RuntimeException("CRITICAL: Transaction rollback failed! File system might be corrupted. " + e.getMessage(), e);
        } finally {
            currentTransaction = null;
            nestingLevel = 0;
        }
    }

    /**
     * Выполняет отмену последней зафиксированной транзакции (UNDO).
     * Текущее состояние файлов перед откатом сохраняется в стек REDO.
     *
     * @return Сообщение о результате отмены.
     *
     * @throws IOException Если возникла ошибка при манипуляции файлами снимков.
     */
    public static String undo() throws IOException {
        if (undoStack.isEmpty()) {
            return "No operations to undo.";
        }

        Transaction tx = undoStack.remove(undoStack.size() - 1);
        // Перед тем как восстановить старое состояние, бэкапим текущее (новое) для возможности REDO
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
     * @return Сообщение о результате повтора.
     *
     * @throws IOException Если возникла ошибка при восстановлении.
     */
    public static String redo() throws IOException {
        if (redoStack.isEmpty()) {
            return "No operations to redo.";
        }

        Transaction tx = redoStack.remove(redoStack.size() - 1);
        // Возможность повторного UNDO после REDO
        Transaction undoTx = new Transaction("UNDO REDO: " + tx.description, LocalDateTime.now());
        for (Path path : tx.getAffectedPaths()) {
            undoTx.addFile(path);
        }

        tx.restore();
        undoStack.add(undoTx);
        return "Redone: " + tx.description;
    }

    /**
     * Генерирует текстовый отчет о текущем состоянии истории правок.
     * Используется для визуализации журналов в инструментах MCP.
     *
     * @return Многострочный текст с описанием транзакций в обоих стеках.
     */
    public static String getJournal() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== TRANSACTION JOURNAL ===\n\n");

        sb.append("Available for UNDO:\n");
        if (undoStack.isEmpty()) {
            sb.append("  (empty)\n");
        }
        for (int i = undoStack.size() - 1; i >= 0; i--) {
            Transaction tx = undoStack.get(i);
            sb.append(String.format("  [%s] %s (%d files)\n", tx.timestamp.format(formatter), tx.description, tx.snapshots.size()));
        }

        sb.append("\nAvailable for REDO:\n");
        if (redoStack.isEmpty()) {
            sb.append("  (empty)\n");
        }
        for (int i = redoStack.size() - 1; i >= 0; i--) {
            Transaction tx = redoStack.get(i);
            sb.append(String.format("  [%s] %s (%d files)\n", tx.timestamp.format(formatter), tx.description, tx.snapshots.size()));
        }

        return sb.toString();
    }

    /**
     * Полная очистка всех транзакционных данных и удаление всех временных снимков с диска.
     * Вызывается при закрытии сервера или в тестах.
     */
    public static void reset() {
        for (Transaction tx : undoStack) {
            tx.deleteSnapshots();
        }
        for (Transaction tx : redoStack) {
            tx.deleteSnapshots();
        }
        undoStack.clear();
        redoStack.clear();
        if (currentTransaction != null) {
            currentTransaction.deleteSnapshots();
        }
        currentTransaction = null;
        nestingLevel = 0;
    }

    /**
     * Внутренний объект, представляющий логическую транзакцию и связанные с ней физические снимки файлов.
     */
    private static class Transaction {
        private final String description;
        private final LocalDateTime timestamp;
        /**
         * Карта: Абсолютный путь файла -> Путь к его временному снимку.
         */
        private final Map<Path, Path> snapshots = new HashMap<>();

        public Transaction(String description, LocalDateTime timestamp) {
            this.description = description;
            this.timestamp = timestamp;
        }

        /**
         * Сохраняет состояние файла. Если файл новый, помечает его на удаление при откате.
         *
         * @param path Путь к файлу.
         *
         * @throws IOException При ошибке копирования.
         */
        public void addFile(Path path) throws IOException {
            Path absPath = path.toAbsolutePath().normalize();
            if (snapshots.containsKey(absPath)) {
                return;
            }

            if (Files.exists(absPath)) {
                // Если файл существует — создаем уникальный временный бэкап
                Path backup = getSnapshotDir().resolve(UUID.randomUUID().toString() + ".bak");
                Files.copy(absPath, backup);
                snapshots.put(absPath, backup);
            } else {
                // Файл еще не существует (будет создан) — запоминаем это как null
                snapshots.put(absPath, null);
            }
        }

        /**
         * Восстанавливает файлы из снимков.
         *
         * @throws IOException При ошибке восстановления.
         */
        public void restore() throws IOException {
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                Path backup = entry.getValue();

                if (backup != null) {
                    // Файл был изменен — восстанавливаем из копии
                    Files.createDirectories(original.getParent());
                    Files.copy(backup, original, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // Файл был создан — удаляем его при откате
                    Files.deleteIfExists(original);
                }
            }
        }

        /**
         * Физически удаляет временные файлы снимков этой транзакции с диска.
         */
        public void deleteSnapshots() {
            for (Path backup : snapshots.values()) {
                if (backup != null) {
                    try {
                        Files.deleteIfExists(backup);
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
    }
}