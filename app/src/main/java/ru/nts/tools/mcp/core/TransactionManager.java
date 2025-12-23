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
 * Обеспечивает атомарность операций над файловой системой и функции UNDO/REDO.
 * Ключевые возможности:
 * 1. Вложенные транзакции (Nesting): Позволяет объединять вызовы нескольких инструментов в один атомарный блок.
 * 2. Система снимков (Snapshots): Сохраняет состояние файлов перед изменением в директории .mcp/snapshots.
 * 3. Глобальный откат (Rollback): Гарантирует возврат всех файлов к исходному состоянию при любой ошибке.
 * 4. Управление историей: Хранит ограниченное количество транзакций для отмены с автоматической очисткой диска.
 * 5. Отказоустойчивость: Поддержка статуса STUCK для заблокированных транзакций и Retry Pattern.
 */
public class TransactionManager {

    /**
     * Статус транзакции.
     */
    public enum Status {
        /** Успешно зафиксирована. */
        COMMITTED,
        /** Ошибка IO при попытке отмены/повтора, требуется вмешательство или повтор. */
        STUCK
    }

    /**
     * Стек для хранения совершенных транзакций (доступны для UNDO).
     */
    private static final List<Transaction> undoStack = new CopyOnWriteArrayList<>();

    /**
     * Стек для хранения отмененных транзакций (доступны для REDO).
     */
    private static final List<Transaction> redoStack = new CopyOnWriteArrayList<>();

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
     * Текущая активная транзакция (изолирована для каждого потока).
     */
    private static final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();

    /**
     * Текущий уровень вложенности транзакций (изолирован для каждого потока).
     */
    private static final ThreadLocal<Integer> nestingLevel = ThreadLocal.withInitial(() -> 0);

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
        startTransaction(description, null);
    }

    /**
     * Инициализирует новую транзакцию с семантической меткой.
     *
     * @param description Описание технического действия.
     * @param instruction Намерение (intent) или краткая инструкция.
     */
    public static void startTransaction(String description, String instruction) {
        if (currentTransaction.get() == null) {
            currentTransaction.set(new Transaction(description, instruction, LocalDateTime.now()));
        }
        nestingLevel.set(nestingLevel.get() + 1);
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
        Transaction tx = currentTransaction.get();
        if (tx == null) {
            return;
        }
        tx.addFile(path);
    }

    /**
     * Подтверждает завершение текущего уровня транзакции.
     * Если это была самая внешняя транзакция (уровень 0), она сохраняется в историю UNDO,
     * а стек REDO очищается.
     */
    public static void commit() {
        Transaction tx = currentTransaction.get();
        if (tx == null) {
            return;
        }

        int level = nestingLevel.get() - 1;
        nestingLevel.set(level);

        if (level <= 0) {
            if (!tx.isEmpty()) {
                tx.updateStats();
                undoStack.add(tx);
                // Новая зафиксированная правка делает невозможным REDO старых отмененных правок (стандарт истории)
                redoStack.clear();

                // Проверка лимитов истории и удаление старых снимков с диска
                if (undoStack.size() > MAX_HISTORY_SIZE) {
                    Transaction old = undoStack.remove(0);
                    old.deleteSnapshots();
                }
            }
            currentTransaction.remove();
            nestingLevel.set(0);
        }
    }

    /**
     * Прерывает текущую транзакцию и мгновенно восстанавливает состояние всех файлов из снимков.
     * Уровень вложенности сбрасывается в 0, транзакция считается проваленной.
     *
     * @throws RuntimeException Если критический откат не удался (риск повреждения данных).
     */
    public static void rollback() {
        Transaction tx = currentTransaction.get();
        if (tx == null) {
            return;
        }
        try {
            tx.restore();
            // Снимки проваленной транзакции удаляются, так как состояние возвращено в исходное
            tx.deleteSnapshots();
        } catch (IOException e) {
            // Критическая системная ошибка: ФС может находиться в частично измененном состоянии
            throw new RuntimeException("CRITICAL: Transaction rollback failed! File system might be corrupted. " + e.getMessage(), e);
        } finally {
            currentTransaction.remove();
            nestingLevel.set(0);
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

        Transaction tx = undoStack.get(undoStack.size() - 1);
        
        try {
            // Pre-flight check
            tx.checkFiles();
            
            // Если проверка прошла, удаляем из стека
            undoStack.remove(undoStack.size() - 1);

            // Перед тем как восстановить старое состояние, бэкапим текущее (новое) для возможности REDO
            Transaction redoTx = new Transaction("REDO: " + tx.description, tx.instruction, LocalDateTime.now());
            for (Path path : tx.getAffectedPaths()) {
                redoTx.addFile(path);
            }

            tx.restore();
            redoStack.add(redoTx);
            return "Undone: " + (tx.instruction != null ? tx.instruction : tx.description);
        } catch (IOException e) {
            tx.setStatus(Status.STUCK);
            throw new IOException("Undo failed: " + tx.description + ". Transaction marked as STUCK. Error: " + e.getMessage(), e);
        }
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

        Transaction tx = redoStack.get(redoStack.size() - 1);
        
        try {
            // Pre-flight check
            tx.checkFiles();

            // Если проверка прошла, удаляем из стека
            redoStack.remove(redoStack.size() - 1);

            // Возможность повторного UNDO после REDO
            Transaction undoTx = new Transaction("UNDO REDO: " + tx.description, tx.instruction, LocalDateTime.now());
            for (Path path : tx.getAffectedPaths()) {
                undoTx.addFile(path);
            }

            tx.restore();
            undoStack.add(undoTx);
            return "Redone: " + (tx.instruction != null ? tx.instruction : tx.description);
        } catch (IOException e) {
            tx.setStatus(Status.STUCK);
            throw new IOException("Redo failed: " + tx.description + ". Transaction marked as STUCK. Error: " + e.getMessage(), e);
        }
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
            appendTransactionInfo(sb, undoStack.get(i));
        }

        sb.append("\nAvailable for REDO:\n");
        if (redoStack.isEmpty()) {
            sb.append("  (empty)\n");
        }
        for (int i = redoStack.size() - 1; i >= 0; i--) {
            appendTransactionInfo(sb, redoStack.get(i));
        }

        return sb.toString();
    }

    private static void appendTransactionInfo(StringBuilder sb, Transaction tx) {
        String status = tx.getStatus() == Status.STUCK ? " [STUCK]" : "";
        String label = tx.instruction != null ? tx.instruction + ": " : "";
        sb.append(String.format("  [%s]%s %s%s (%d files)\n", tx.timestamp.format(formatter), status, label, tx.description, tx.snapshots.size()));
        
        Path root = PathSanitizer.getRoot();
        for (Map.Entry<Path, FileDiffStats> entry : tx.stats.entrySet()) {
            Path relPath = root.relativize(entry.getKey());
            FileDiffStats s = entry.getValue();
            sb.append(String.format("    - %s: +%d, -%d lines", relPath, s.added, s.deleted));
            if (!s.affectedBlocks.isEmpty()) {
                sb.append(" | Blocks: ").append(String.join(", ", s.affectedBlocks));
            }
            sb.append("\n");
        }
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
        Transaction tx = currentTransaction.get();
        if (tx != null) {
            tx.deleteSnapshots();
        }
        currentTransaction.remove();
        nestingLevel.set(0);
    }

    /**
     * Внутренний объект, представляющий логическую транзакцию и связанные с ней физические снимки файлов.
     */
    private static class Transaction {
        private final String description;
        private final String instruction;
        private final LocalDateTime timestamp;
        private Status status = Status.COMMITTED;
        /**
         * Карта: Абсолютный путь файла -> Путь к его временному снимку.
         */
        private final Map<Path, Path> snapshots = new HashMap<>();

        /**
         * Карта: Абсолютный путь файла -> Статистика изменений (diff).
         */
        private final Map<Path, FileDiffStats> stats = new HashMap<>();

        public Transaction(String description, LocalDateTime timestamp) {
            this(description, null, timestamp);
        }

        public Transaction(String description, String instruction, LocalDateTime timestamp) {
            this.description = description;
            this.instruction = instruction;
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
                FileUtils.safeCopy(absPath, backup);
                snapshots.put(absPath, backup);
            } else {
                // Файл еще не существует (будет создан) — запоминаем это как null
                snapshots.put(absPath, null);
            }
        }

        /**
         * Обновляет статистику изменений для файла в конце транзакции.
         */
        public void updateStats() {
            for (Map.Entry<Path, Path> entry : snapshots.entrySet()) {
                Path original = entry.getKey();
                Path snapshot = entry.getValue();
                
                try {
                    String oldContent = (snapshot != null) ? Files.readString(snapshot) : "";
                    String newContent = Files.exists(original) ? Files.readString(original) : "";
                    
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
            
            // Упрощенный подсчет изменений (разница в количестве строк как база)
            int linesAdded = 0;
            int linesDeleted = 0;
            
            Set<String> oldLineSet = new HashSet<>(Arrays.asList(oldLines));
            Set<String> newLineSet = new HashSet<>(Arrays.asList(newLines));
            
            for (String line : newLines) {
                if (!oldLineSet.contains(line)) linesAdded++;
            }
            for (String line : oldLines) {
                if (!newLineSet.contains(line)) linesDeleted++;
            }
            
            // Поиск затронутых блоков (методы/классы)
            Set<String> affectedBlocks = new TreeSet<>();
            for (String line : newLines) {
                String trimmed = line.trim();
                if (!oldLineSet.contains(line) && (trimmed.contains("class ") || trimmed.contains("void ") || trimmed.contains("String ") || (trimmed.contains("(") && trimmed.endsWith("{")))) {
                    // Пытаемся вычленить имя
                    String name = extractName(trimmed);
                    if (name != null) affectedBlocks.add(name);
                }
            }
            
            return new FileDiffStats(linesAdded, linesDeleted, new ArrayList<>(affectedBlocks));
        }

        private String extractName(String line) {
            // Очень простая эвристика для Java
            String[] parts = line.split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].contains("(")) {
                    return parts[i].split("\\(")[0];
                }
                if ("class".equals(parts[i]) && i + 1 < parts.length) {
                    return "class " + parts[i+1].split("\\{")[0];
                }
            }
            return null;
        }

        /**
         * Проверяет доступность всех файлов транзакции для записи.
         *
         * @throws IOException Если хотя бы один файл заблокирован.
         */
        public void checkFiles() throws IOException {
            for (Path path : snapshots.keySet()) {
                FileUtils.checkFileAvailability(path);
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
                    FileUtils.safeCopy(backup, original);
                } else {
                    // Файл был создан — удаляем его при откате
                    FileUtils.safeDelete(original);
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

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }
    }

    private record FileDiffStats(int added, int deleted, List<String> affectedBlocks) {}
}