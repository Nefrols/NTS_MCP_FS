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
package ru.nts.tools.mcp.core.treesitter;

import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.CRC32C;

/**
 * Высокопроизводительный индекс символов проекта.
 * <p>
 * Обеспечивает O(1) поиск символов по имени вместо сканирования файлов.
 * Индексация происходит асинхронно при инициализации проекта.
 * <p>
 * Особенности:
 * - Асинхронная индексация с прогрессом
 * - Инкрементальное обновление при изменении файлов
 * - CRC-based инвалидация
 * - Thread-safe операции
 */
public final class SymbolIndex {

    private static final SymbolIndex INSTANCE = new SymbolIndex();

    /**
     * Максимальное количество файлов для индексации.
     */
    private static final int MAX_FILES_TO_INDEX = 5000;

    /**
     * Максимальный размер файла для индексации (2MB).
     */
    private static final long MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024;

    /**
     * Таймаут на индексацию всего проекта.
     */
    private static final Duration INDEXING_TIMEOUT = Duration.ofMinutes(2);

    /**
     * Директории, которые пропускаем при индексации.
     */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode",
            "node_modules", "build", "target", "dist", "out",
            "__pycache__", ".gradle", "bin", "obj"
    );

    // ==================== ИНДЕКСЫ ====================

    /**
     * Главный индекс: имя символа -> список записей.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<IndexedSymbol>> symbolsByName = new ConcurrentHashMap<>();

    /**
     * Обратный индекс: путь файла -> множество имён символов (для инвалидации).
     */
    private final ConcurrentHashMap<Path, Set<String>> symbolNamesByFile = new ConcurrentHashMap<>();

    /**
     * CRC файлов для проверки актуальности.
     */
    private final ConcurrentHashMap<Path, Long> fileCrcs = new ConcurrentHashMap<>();

    /**
     * Корень проиндексированного проекта.
     */
    private volatile Path indexedRoot = null;

    /**
     * Статус индексации.
     */
    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private final AtomicBoolean indexed = new AtomicBoolean(false);
    private final AtomicInteger indexedFilesCount = new AtomicInteger(0);
    private final AtomicInteger totalFilesToIndex = new AtomicInteger(0);
    private volatile Instant indexingStartTime = null;

    /**
     * Executor для параллельной индексации.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final TreeSitterManager treeManager = TreeSitterManager.getInstance();
    private final SymbolExtractor extractor = SymbolExtractor.getInstance();

    private SymbolIndex() {
        // Shutdown hook для очистки executor
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }));
    }

    public static SymbolIndex getInstance() {
        return INSTANCE;
    }

    // ==================== ИНДЕКСАЦИЯ ====================

    /**
     * Проверяет, проиндексирован ли проект.
     */
    public boolean isIndexed() {
        return indexed.get();
    }

    /**
     * Проверяет, идёт ли индексация.
     */
    public boolean isIndexing() {
        return indexing.get();
    }

    /**
     * Возвращает прогресс индексации (0.0 - 1.0).
     */
    public double getIndexingProgress() {
        int total = totalFilesToIndex.get();
        if (total == 0) return 0.0;
        return (double) indexedFilesCount.get() / total;
    }

    /**
     * Возвращает количество проиндексированных символов.
     */
    public int getSymbolCount() {
        return symbolsByName.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    /**
     * Возвращает количество проиндексированных файлов.
     */
    public int getFileCount() {
        return fileCrcs.size();
    }

    /**
     * Запускает асинхронную индексацию проекта.
     * Возвращается немедленно, индексация идёт в фоне.
     *
     * @param projectRoot корень проекта
     * @return CompletableFuture с результатом индексации
     */
    public CompletableFuture<IndexingResult> indexProjectAsync(Path projectRoot) {
        if (!indexing.compareAndSet(false, true)) {
            // Уже идёт индексация
            return CompletableFuture.completedFuture(
                    new IndexingResult(false, 0, 0, Duration.ZERO, "Indexing already in progress"));
        }

        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();

        // Если уже проиндексирован тот же корень - просто обновляем
        if (indexed.get() && normalizedRoot.equals(indexedRoot)) {
            indexing.set(false);
            return CompletableFuture.completedFuture(
                    new IndexingResult(true, getFileCount(), getSymbolCount(), Duration.ZERO, "Already indexed"));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return indexProjectSync(normalizedRoot);
            } finally {
                indexing.set(false);
            }
        }, executor);
    }

    /**
     * Синхронная индексация проекта.
     */
    private IndexingResult indexProjectSync(Path projectRoot) {
        indexingStartTime = Instant.now();
        indexedFilesCount.set(0);

        // Очищаем старый индекс
        symbolsByName.clear();
        symbolNamesByFile.clear();
        fileCrcs.clear();
        indexed.set(false);
        indexedRoot = projectRoot;

        try {
            // 1. Собираем файлы для индексации
            List<Path> filesToIndex = collectFilesToIndex(projectRoot);
            totalFilesToIndex.set(filesToIndex.size());

            if (filesToIndex.isEmpty()) {
                indexed.set(true);
                return new IndexingResult(true, 0, 0,
                        Duration.between(indexingStartTime, Instant.now()),
                        "No files to index");
            }

            // 2. Параллельная индексация с таймаутом
            List<CompletableFuture<Void>> futures = filesToIndex.stream()
                    .map(file -> CompletableFuture.runAsync(() -> indexFile(file), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(INDEXING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();

            indexed.set(true);

            Duration elapsed = Duration.between(indexingStartTime, Instant.now());
            return new IndexingResult(true, getFileCount(), getSymbolCount(), elapsed, "Success");

        } catch (CompletionException e) {
            // Таймаут или другая ошибка - частично проиндексировано
            indexed.set(true);
            Duration elapsed = Duration.between(indexingStartTime, Instant.now());
            String message = e.getCause() instanceof java.util.concurrent.TimeoutException
                    ? "Timeout (partial indexing)"
                    : "Partial indexing: " + e.getMessage();
            return new IndexingResult(true, getFileCount(), getSymbolCount(), elapsed, message);
        } catch (Exception e) {
            Duration elapsed = Duration.between(indexingStartTime, Instant.now());
            return new IndexingResult(false, getFileCount(), getSymbolCount(), elapsed,
                    "Error: " + e.getMessage());
        }
    }

    /**
     * Собирает файлы для индексации.
     */
    private List<Path> collectFilesToIndex(Path root) {
        List<Path> files = new ArrayList<>();

        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 20,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (files.size() >= MAX_FILES_TO_INDEX) {
                                return FileVisitResult.TERMINATE;
                            }

                            // Проверяем размер
                            if (attrs.size() > MAX_FILE_SIZE_BYTES) {
                                return FileVisitResult.CONTINUE;
                            }

                            // Проверяем, поддерживается ли язык
                            if (LanguageDetector.detect(file).isPresent()) {
                                files.add(file);
                            }

                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String dirName = dir.getFileName().toString();
                            if (SKIP_DIRECTORIES.contains(dirName) || dirName.startsWith(".")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            // Игнорируем ошибки обхода
        }

        return files;
    }

    /**
     * Индексирует один файл.
     */
    private void indexFile(Path file) {
        try {
            Path normalizedPath = file.toAbsolutePath().normalize();

            // Парсим файл
            TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

            // Сохраняем CRC
            long crc = calculateCrc(parseResult.content());
            fileCrcs.put(normalizedPath, crc);

            // Извлекаем символы
            List<SymbolInfo> symbols = extractor.extractDefinitions(
                    parseResult.tree(), normalizedPath, parseResult.content(), parseResult.langId());

            // Добавляем в индекс
            Set<String> symbolNames = ConcurrentHashMap.newKeySet();

            for (SymbolInfo symbol : symbols) {
                IndexedSymbol indexed = new IndexedSymbol(
                        symbol.name(),
                        symbol.kind(),
                        normalizedPath,
                        symbol.location().startLine(),
                        symbol.location().endLine(),
                        symbol.parentName(),
                        crc
                );

                symbolsByName
                        .computeIfAbsent(symbol.name(), k -> new CopyOnWriteArrayList<>())
                        .add(indexed);

                symbolNames.add(symbol.name());
            }

            symbolNamesByFile.put(normalizedPath, symbolNames);

        } catch (Exception e) {
            // Игнорируем ошибки индексации отдельных файлов
        } finally {
            indexedFilesCount.incrementAndGet();
        }
    }

    // ==================== ПОИСК ====================

    /**
     * Ищет определения символа по имени.
     *
     * @param symbolName имя символа
     * @return список локаций определений
     */
    public List<Location> findDefinitions(String symbolName) {
        List<IndexedSymbol> symbols = symbolsByName.get(symbolName);
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyList();
        }

        return symbols.stream()
                .filter(s -> isDefinitionKind(s.kind()))
                .filter(this::isSymbolValid)
                .map(s -> new Location(s.file(), s.startLine(), 1, s.endLine(), 1))
                .collect(Collectors.toList());
    }

    /**
     * Ищет первое определение символа.
     *
     * @param symbolName имя символа
     * @return Optional с локацией определения
     */
    public Optional<Location> findFirstDefinition(String symbolName) {
        List<IndexedSymbol> symbols = symbolsByName.get(symbolName);
        if (symbols == null || symbols.isEmpty()) {
            return Optional.empty();
        }

        return symbols.stream()
                .filter(s -> isDefinitionKind(s.kind()))
                .filter(this::isSymbolValid)
                .findFirst()
                .map(s -> new Location(s.file(), s.startLine(), 1, s.endLine(), 1));
    }

    /**
     * Ищет все файлы, содержащие символ с указанным именем.
     *
     * @param symbolName имя символа
     * @return множество путей к файлам
     */
    public Set<Path> findFilesContainingSymbol(String symbolName) {
        List<IndexedSymbol> symbols = symbolsByName.get(symbolName);
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptySet();
        }

        return symbols.stream()
                .filter(this::isSymbolValid)
                .map(IndexedSymbol::file)
                .collect(Collectors.toSet());
    }

    /**
     * Проверяет, актуален ли символ (файл не изменился).
     */
    private boolean isSymbolValid(IndexedSymbol symbol) {
        Long currentCrc = fileCrcs.get(symbol.file());
        return currentCrc != null && currentCrc.equals(symbol.fileCrc());
    }

    /**
     * Проверяет, является ли вид символа определением.
     */
    private boolean isDefinitionKind(SymbolKind kind) {
        return kind == SymbolKind.CLASS ||
                kind == SymbolKind.INTERFACE ||
                kind == SymbolKind.ENUM ||
                kind == SymbolKind.STRUCT ||
                kind == SymbolKind.TRAIT ||
                kind == SymbolKind.OBJECT ||
                kind == SymbolKind.METHOD ||
                kind == SymbolKind.FUNCTION ||
                kind == SymbolKind.CONSTRUCTOR ||
                kind == SymbolKind.FIELD ||
                kind == SymbolKind.PROPERTY ||
                kind == SymbolKind.VARIABLE ||
                kind == SymbolKind.CONSTANT;
    }

    // ==================== ИНВАЛИДАЦИЯ ====================

    /**
     * Инвалидирует и переиндексирует файл.
     * Вызывается при изменении файла.
     *
     * @param file путь к изменённому файлу
     */
    public void invalidateFile(Path file) {
        Path normalizedPath = file.toAbsolutePath().normalize();

        // Удаляем старые записи
        Set<String> oldSymbolNames = symbolNamesByFile.remove(normalizedPath);
        if (oldSymbolNames != null) {
            for (String symbolName : oldSymbolNames) {
                CopyOnWriteArrayList<IndexedSymbol> symbols = symbolsByName.get(symbolName);
                if (symbols != null) {
                    symbols.removeIf(s -> s.file().equals(normalizedPath));
                    if (symbols.isEmpty()) {
                        symbolsByName.remove(symbolName);
                    }
                }
            }
        }

        fileCrcs.remove(normalizedPath);

        // Переиндексируем
        if (Files.exists(normalizedPath)) {
            indexFile(normalizedPath);
        }
    }

    /**
     * Очищает весь индекс.
     */
    public void clear() {
        symbolsByName.clear();
        symbolNamesByFile.clear();
        fileCrcs.clear();
        indexed.set(false);
        indexedRoot = null;
        indexedFilesCount.set(0);
        totalFilesToIndex.set(0);
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private long calculateCrc(String content) {
        CRC32C crc = new CRC32C();
        crc.update(content.getBytes());
        return crc.getValue();
    }

    // ==================== ЗАПИСИ ====================

    /**
     * Запись проиндексированного символа.
     */
    public record IndexedSymbol(
            String name,
            SymbolKind kind,
            Path file,
            int startLine,
            int endLine,
            String parentName,
            long fileCrc
    ) {}

    /**
     * Результат индексации.
     */
    public record IndexingResult(
            boolean success,
            int filesIndexed,
            int symbolsIndexed,
            Duration duration,
            String message
    ) {}
}
