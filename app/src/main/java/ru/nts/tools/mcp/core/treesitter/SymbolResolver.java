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

import org.treesitter.TSNode;
import org.treesitter.TSTree;
import ru.nts.tools.mcp.core.FastSearch;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Разрешает определения и ссылки символов.
 * Реализует логику навигации: Go to Definition, Find References, Hover.
 */
public final class SymbolResolver {

    private static final SymbolResolver INSTANCE = new SymbolResolver();

    private final TreeSitterManager treeManager = TreeSitterManager.getInstance();
    private final SymbolExtractor extractor = SymbolExtractor.getInstance();

    /**
     * Максимальное количество файлов для сканирования при project-wide поиске.
     */
    private static final int MAX_FILES_TO_SCAN = 500;

    /**
     * Executor для параллельного поиска.
     */
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Глобальный таймаут на операцию поиска по проекту.
     */
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Индекс символов для быстрого поиска.
     */
    private final SymbolIndex symbolIndex = SymbolIndex.getInstance();

    static {
        // Shutdown hook для корректного завершения executor
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

    private SymbolResolver() {}

    public static SymbolResolver getInstance() {
        return INSTANCE;
    }

    /**
     * Диапазон поиска для "умного" определения колонки.
     */
    private static final int COLUMN_SEARCH_RANGE = 3;

    // ===================== ПОИСК ПО ИМЕНИ СИМВОЛА =====================

    /**
     * Go to Definition по имени символа.
     * Находит определение символа в файле без указания позиции.
     *
     * @param file путь к файлу
     * @param symbolName имя символа для поиска
     * @return информация о определении или empty
     */
    public Optional<SymbolInfo> findDefinitionByName(Path file, String symbolName) throws IOException {
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

        // 1. Ищем определение в текущем файле
        List<SymbolInfo> localDefinitions = extractor.extractDefinitions(
                parseResult.tree(), normalizedPath, parseResult.content(), parseResult.langId());

        Optional<SymbolInfo> localDef = localDefinitions.stream()
                .filter(s -> s.name().equals(symbolName))
                .filter(s -> isDefinitionKind(s.kind()))
                .findFirst();

        if (localDef.isPresent()) {
            return localDef;
        }

        // 2. Ищем в импортированных файлах (для Java)
        if (parseResult.langId().equals("java")) {
            Optional<SymbolInfo> importedDef = findInImports(normalizedPath, parseResult, symbolName);
            if (importedDef.isPresent()) {
                return importedDef;
            }
        }

        // 3. Fallback: поиск по проекту
        return findDefinitionInProject(normalizedPath, symbolName, parseResult.langId());
    }

    /**
     * Find References по имени символа.
     * Находит все использования символа в указанной области.
     *
     * @param file путь к файлу (используется для определения контекста)
     * @param symbolName имя символа для поиска
     * @param scope область поиска: "file", "directory", "project"
     * @param includeDeclaration включать ли декларацию в результат
     * @return список локаций ссылок
     */
    public List<Location> findReferencesByName(Path file, String symbolName, String scope, boolean includeDeclaration) throws IOException {
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);
        String langId = parseResult.langId();

        List<Location> allReferences = new ArrayList<>();

        // Поиск ссылок в зависимости от scope
        switch (scope) {
            case "file" -> {
                allReferences.addAll(extractor.findReferences(
                        parseResult.tree(), normalizedPath, parseResult.content(), langId, symbolName));
            }
            case "directory" -> {
                allReferences.addAll(findReferencesInDirectory(normalizedPath.getParent(), symbolName, langId));
            }
            case "project" -> {
                allReferences.addAll(findReferencesInProject(normalizedPath, symbolName, langId));
            }
            default -> throw new IllegalArgumentException("Invalid scope: " + scope);
        }

        // Удаляем декларацию если нужно
        if (!includeDeclaration) {
            Optional<SymbolInfo> def = findDefinitionByName(normalizedPath, symbolName);
            if (def.isPresent()) {
                Location defLoc = def.get().location();
                allReferences.removeIf(loc ->
                        loc.path().equals(defLoc.path()) &&
                                loc.startLine() == defLoc.startLine() &&
                                loc.startColumn() == defLoc.startColumn());
            }
        }

        return allReferences.stream()
                .sorted(Comparator
                        .comparing(Location::path)
                        .thenComparing(Location::startLine)
                        .thenComparing(Location::startColumn))
                .toList();
    }

    /**
     * Hover по имени символа.
     * Возвращает информацию о символе по имени.
     *
     * @param file путь к файлу
     * @param symbolName имя символа
     * @return информация о символе или empty
     */
    public Optional<SymbolInfo> hoverByName(Path file, String symbolName) throws IOException {
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

        // Ищем символ в текущем файле
        List<SymbolInfo> symbols = extractor.extractDefinitions(
                parseResult.tree(), normalizedPath, parseResult.content(), parseResult.langId());

        // Сначала ищем определение
        Optional<SymbolInfo> definition = symbols.stream()
                .filter(s -> s.name().equals(symbolName))
                .filter(s -> isDefinitionKind(s.kind()))
                .findFirst();

        if (definition.isPresent()) {
            return definition;
        }

        // Если не нашли определение, ищем любой символ с таким именем
        return symbols.stream()
                .filter(s -> s.name().equals(symbolName))
                .findFirst();
    }

    /**
     * Go to Definition: находит определение символа в указанной позиции.
     * Если в указанной позиции символ не найден, сканирует соседние колонки (±3).
     *
     * @param file путь к файлу
     * @param line номер строки (1-based)
     * @param column номер колонки (1-based)
     * @return информация о определении или empty
     */
    public Optional<SymbolInfo> findDefinition(Path file, int line, int column) throws IOException {
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

        // 1. Определяем символ в указанной позиции с умным поиском
        String symbolName = extractSymbolWithSmartSearch(parseResult.tree(), parseResult.content(), line, column);
        if (symbolName == null || symbolName.isEmpty()) {
            return Optional.empty();
        }

        // 2. Ищем определение в текущем файле
        List<SymbolInfo> localDefinitions = extractor.extractDefinitions(
                parseResult.tree(), normalizedPath, parseResult.content(), parseResult.langId());

        Optional<SymbolInfo> localDef = localDefinitions.stream()
                .filter(s -> s.name().equals(symbolName))
                .filter(s -> isDefinitionKind(s.kind()))
                .findFirst();

        if (localDef.isPresent()) {
            return localDef;
        }

        // 3. Ищем в импортированных файлах (для Java)
        if (parseResult.langId().equals("java")) {
            Optional<SymbolInfo> importedDef = findInImports(normalizedPath, parseResult, symbolName);
            if (importedDef.isPresent()) {
                return importedDef;
            }
        }

        // 4. Fallback: поиск по проекту
        return findDefinitionInProject(normalizedPath, symbolName, parseResult.langId());
    }

    /**
     * Find References: находит все использования символа.
     *
     * @param file путь к файлу
     * @param line номер строки (1-based)
     * @param column номер колонки (1-based)
     * @param scope область поиска: "file", "directory", "project"
     * @param includeDeclaration включать ли декларацию в результат
     * @return список локаций ссылок
     */
    public List<Location> findReferences(Path file, int line, int column,
                                          String scope, boolean includeDeclaration) throws IOException {
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

        // 1. Определяем символ с умным поиском
        String symbolName = extractSymbolWithSmartSearch(parseResult.tree(), parseResult.content(), line, column);
        if (symbolName == null || symbolName.isEmpty()) {
            return Collections.emptyList();
        }

        List<Location> allReferences = new ArrayList<>();

        // 2. Ищем ссылки в зависимости от scope
        switch (scope) {
            case "file" -> {
                List<Location> refs = extractor.findReferences(
                        parseResult.tree(), normalizedPath, parseResult.content(),
                        parseResult.langId(), symbolName);
                allReferences.addAll(refs);
            }
            case "directory" -> {
                allReferences.addAll(findReferencesInDirectory(
                        normalizedPath.getParent(), symbolName, parseResult.langId()));
            }
            case "project" -> {
                allReferences.addAll(findReferencesInProject(
                        normalizedPath, symbolName, parseResult.langId()));
            }
        }

        // 3. Фильтруем декларацию если нужно
        if (!includeDeclaration) {
            Optional<SymbolInfo> definition = findDefinition(file, line, column);
            if (definition.isPresent()) {
                Location defLoc = definition.get().location();
                allReferences.removeIf(ref ->
                        ref.path().equals(defLoc.path()) &&
                                ref.startLine() == defLoc.startLine() &&
                                ref.startColumn() == defLoc.startColumn());
            }
        }

        // 4. Убираем дубликаты и сортируем
        return allReferences.stream()
                .distinct()
                .sorted(Comparator
                        .comparing((Location l) -> l.path().toString())
                        .thenComparing(Location::startLine)
                        .thenComparing(Location::startColumn))
                .collect(Collectors.toList());
    }

    /**
     * Hover: возвращает информацию о символе в указанной позиции.
     *
     * @param file путь к файлу
     * @param line номер строки (1-based)
     * @param column номер колонки (1-based)
     * @return информация о символе или empty
     */
    public Optional<SymbolInfo> hover(Path file, int line, int column) throws IOException {
        // Сначала пробуем найти определение
        Optional<SymbolInfo> definition = findDefinition(file, line, column);
        if (definition.isPresent()) {
            return definition;
        }

        // Если определение не найдено, возвращаем информацию о символе в позиции
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

        return extractor.symbolAtPosition(parseResult.tree(), normalizedPath,
                parseResult.content(), parseResult.langId(), line, column);
    }

    /**
     * List Symbols: возвращает все символы в файле.
     *
     * @param file путь к файлу
     * @return список символов
     */
    public List<SymbolInfo> listSymbols(Path file) throws IOException {
        Path normalizedPath = file.toAbsolutePath().normalize();
        TreeSitterManager.ParseResult parseResult = treeManager.getCachedOrParseWithContent(normalizedPath);

        return extractor.extractDefinitions(
                parseResult.tree(), normalizedPath, parseResult.content(), parseResult.langId());
    }

    // ===================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====================

    /**
     * Извлекает имя символа с "умным" поиском.
     * Сначала пробует точную позицию, затем сканирует ±COLUMN_SEARCH_RANGE.
     */
    private String extractSymbolWithSmartSearch(TSTree tree, String content, int line, int column) {
        // Сначала пробуем точную позицию
        String symbol = extractSymbolAtPosition(tree, content, line, column);
        if (symbol != null && !symbol.isEmpty()) {
            return symbol;
        }

        // Умный поиск: сканируем соседние колонки
        // Порядок: ближайшие сначала (1, -1, 2, -2, 3, -3)
        for (int offset = 1; offset <= COLUMN_SEARCH_RANGE; offset++) {
            // Пробуем справа
            symbol = extractSymbolAtPosition(tree, content, line, column + offset);
            if (symbol != null && !symbol.isEmpty()) {
                return symbol;
            }

            // Пробуем слева
            if (column - offset > 0) {
                symbol = extractSymbolAtPosition(tree, content, line, column - offset);
                if (symbol != null && !symbol.isEmpty()) {
                    return symbol;
                }
            }
        }

        return null;
    }

    /**
     * Извлекает имя символа в указанной позиции (без умного поиска).
     */
    private String extractSymbolAtPosition(TSTree tree, String content, int line, int column) {
        TSNode root = tree.getRootNode();
        TSNode node = findNodeAtPosition(root, line - 1, column - 1); // tree-sitter 0-based

        if (node == null) {
            return null;
        }

        // Если это identifier, возвращаем его текст
        String nodeType = node.getType();
        if (isIdentifierType(nodeType)) {
            return getNodeText(node, content);
        }

        // Ищем ближайший identifier среди детей
        TSNode identNode = findIdentifierChild(node, content);
        if (identNode != null) {
            return getNodeText(identNode, content);
        }

        return null;
    }

    /**
     * Находит узел в указанной позиции.
     */
    private TSNode findNodeAtPosition(TSNode node, int row, int col) {
        // Проверяем, находится ли позиция внутри узла
        int startRow = node.getStartPoint().getRow();
        int startCol = node.getStartPoint().getColumn();
        int endRow = node.getEndPoint().getRow();
        int endCol = node.getEndPoint().getColumn();

        if (row < startRow || row > endRow) {
            return null;
        }
        if (row == startRow && col < startCol) {
            return null;
        }
        if (row == endRow && col > endCol) {
            return null;
        }

        // Ищем наиболее специфичный узел
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                TSNode found = findNodeAtPosition(child, row, col);
                if (found != null) {
                    return found;
                }
            }
        }

        return node;
    }

    /**
     * Проверяет, является ли тип узла идентификатором.
     */
    private boolean isIdentifierType(String nodeType) {
        return nodeType.equals("identifier") ||
                nodeType.equals("simple_identifier") ||
                nodeType.equals("type_identifier") ||
                nodeType.equals("property_identifier") ||
                nodeType.equals("field_identifier");
    }

    /**
     * Ищет identifier среди дочерних узлов.
     */
    private TSNode findIdentifierChild(TSNode node, String content) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && isIdentifierType(child.getType())) {
                return child;
            }
        }
        return null;
    }

    /**
     * Извлекает текст узла (корректно для UTF-8).
     * КРИТИЧНО: tree-sitter возвращает байтовые смещения, а не символьные!
     */
    private String getNodeText(TSNode node, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start >= 0 && end <= contentBytes.length && start < end) {
            return new String(contentBytes, start, end - start, StandardCharsets.UTF_8);
        }
        return "";
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

    /**
     * Ищет определение в импортированных файлах (Java).
     */
    private Optional<SymbolInfo> findInImports(Path currentFile, TreeSitterManager.ParseResult parseResult,
                                                String symbolName) throws IOException {
        // Извлекаем импорты из файла
        List<String> imports = extractJavaImports(parseResult.tree(), parseResult.content());

        for (String imp : imports) {
            // Проверяем, совпадает ли имя класса
            String className = imp.substring(imp.lastIndexOf('.') + 1);
            if (className.equals(symbolName) || className.equals("*")) {
                // Конвертируем import в путь к файлу
                Path importedFile = resolveImportPath(currentFile, imp);
                if (importedFile != null && Files.exists(importedFile)) {
                    TreeSitterManager.ParseResult importedParse =
                            treeManager.getCachedOrParseWithContent(importedFile);

                    List<SymbolInfo> definitions = extractor.extractDefinitions(
                            importedParse.tree(), importedFile,
                            importedParse.content(), importedParse.langId());

                    Optional<SymbolInfo> found = definitions.stream()
                            .filter(s -> s.name().equals(symbolName))
                            .filter(s -> isDefinitionKind(s.kind()))
                            .findFirst();

                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Извлекает Java импорты из AST.
     */
    private List<String> extractJavaImports(TSTree tree, String content) {
        List<String> imports = new ArrayList<>();
        extractImportsRecursive(tree.getRootNode(), content, imports);
        return imports;
    }

    private void extractImportsRecursive(TSNode node, String content, List<String> imports) {
        if (node.getType().equals("import_declaration")) {
            // Находим scoped_identifier
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                TSNode child = node.getChild(i);
                if (child != null && (child.getType().equals("scoped_identifier") ||
                        child.getType().equals("identifier"))) {
                    imports.add(getNodeText(child, content));
                    break;
                }
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null) {
                extractImportsRecursive(child, content, imports);
            }
        }
    }

    /**
     * Конвертирует Java import в путь к файлу.
     */
    private Path resolveImportPath(Path currentFile, String importStatement) {
        // Находим корень проекта (где есть src/main/java или просто src)
        Path projectRoot = findProjectRoot(currentFile);
        if (projectRoot == null) {
            return null;
        }

        // Конвертируем com.example.MyClass -> com/example/MyClass.java
        String relativePath = importStatement.replace('.', '/') + ".java";

        // Проверяем стандартные директории
        Path[] possiblePaths = {
                projectRoot.resolve("src/main/java").resolve(relativePath),
                projectRoot.resolve("src").resolve(relativePath),
                projectRoot.resolve(relativePath)
        };

        for (Path p : possiblePaths) {
            if (Files.exists(p)) {
                return p;
            }
        }

        return null;
    }

    /**
     * Находит корень проекта.
     */
    private Path findProjectRoot(Path file) {
        Path current = file.getParent();
        while (current != null) {
            if (Files.exists(current.resolve("build.gradle")) ||
                    Files.exists(current.resolve("build.gradle.kts")) ||
                    Files.exists(current.resolve("pom.xml")) ||
                    Files.exists(current.resolve("package.json")) ||
                    Files.exists(current.resolve("go.mod")) ||
                    Files.exists(current.resolve("Cargo.toml")) ||
                    Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return file.getParent();
    }

    /**
     * Ищет определение в проекте.
     * Использует SymbolIndex если доступен, иначе fallback на сканирование.
     */
    private Optional<SymbolInfo> findDefinitionInProject(Path currentFile, String symbolName,
                                                          String langId) throws IOException {
        // 1. Сначала пробуем индекс (O(1) lookup)
        if (symbolIndex.isIndexed()) {
            Optional<Location> indexedLocation = symbolIndex.findFirstDefinition(symbolName);
            if (indexedLocation.isPresent()) {
                Location loc = indexedLocation.get();
                // Загружаем полную информацию о символе
                try {
                    TreeSitterManager.ParseResult pr = treeManager.getCachedOrParseWithContent(loc.path());
                    List<SymbolInfo> defs = extractor.extractDefinitions(
                            pr.tree(), loc.path(), pr.content(), pr.langId());
                    return defs.stream()
                            .filter(s -> s.name().equals(symbolName))
                            .filter(s -> isDefinitionKind(s.kind()))
                            .findFirst();
                } catch (Exception e) {
                    // Fallback к сканированию
                }
            }
        }

        // 2. Fallback: сканирование файлов
        Path projectRoot = findProjectRoot(currentFile);
        if (projectRoot == null) {
            return Optional.empty();
        }

        // Получаем glob паттерн для языка
        String globPattern = LanguageDetector.getGlobPattern(langId);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

        // Быстрый поиск с использованием FastSearch.containsText()
        List<Path> candidateFiles = new ArrayList<>();

        try {
            Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), 15,
                    new SimpleFileVisitor<>() {
                        int count = 0;

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (count >= MAX_FILES_TO_SCAN) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (matcher.matches(file) && !file.equals(currentFile)) {
                                // Быстрая проверка без полного чтения файла в память
                                try {
                                    if (FastSearch.containsText(file, symbolName)) {
                                        candidateFiles.add(file);
                                        count++;
                                    }
                                } catch (IOException e) {
                                    // Игнорируем нечитаемые файлы
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String dirName = dir.getFileName().toString();
                            // Пропускаем скрытые директории и build директории
                            if (dirName.startsWith(".") ||
                                    dirName.equals("node_modules") ||
                                    dirName.equals("build") ||
                                    dirName.equals("target") ||
                                    dirName.equals("dist") ||
                                    dirName.equals("out")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            // Игнорируем ошибки обхода
        }

        // Параллельный поиск с глобальным таймаутом
        List<CompletableFuture<Optional<SymbolInfo>>> futures = candidateFiles.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> {
                    try {
                        TreeSitterManager.ParseResult pr = treeManager.getCachedOrParseWithContent(file);
                        List<SymbolInfo> defs = extractor.extractDefinitions(
                                pr.tree(), file, pr.content(), pr.langId());
                        return defs.stream()
                                .filter(s -> s.name().equals(symbolName))
                                .filter(s -> isDefinitionKind(s.kind()))
                                .findFirst();
                    } catch (Exception e) {
                        return Optional.<SymbolInfo>empty();
                    }
                }, executor))
                .toList();

        try {
            // Глобальный таймаут на всю операцию
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            // Таймаут или другая ошибка - возвращаем что успели найти
        }

        // Возвращаем первый найденный результат
        for (CompletableFuture<Optional<SymbolInfo>> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    Optional<SymbolInfo> result = future.getNow(Optional.empty());
                    if (result.isPresent()) {
                        return result;
                    }
                } catch (Exception e) {
                    // Игнорируем
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Ищет ссылки в директории.
     */
    private List<Location> findReferencesInDirectory(Path directory, String symbolName,
                                                      String langId) throws IOException {
        String globPattern = LanguageDetector.getGlobPattern(langId);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

        List<Path> candidateFiles;
        try (var stream = Files.list(directory)) {
            candidateFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(matcher::matches)
                    .filter(file -> {
                        try {
                            // Быстрая проверка без полного чтения файла
                            return FastSearch.containsText(file, symbolName);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }

        // Параллельный поиск ссылок с глобальным таймаутом
        List<CompletableFuture<List<Location>>> futures = candidateFiles.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> {
                    try {
                        TreeSitterManager.ParseResult pr = treeManager.getCachedOrParseWithContent(file);
                        return extractor.findReferences(
                                pr.tree(), file, pr.content(), pr.langId(), symbolName);
                    } catch (Exception e) {
                        return Collections.<Location>emptyList();
                    }
                }, executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            // Таймаут - возвращаем что успели найти
        }

        List<Location> references = new ArrayList<>();
        for (CompletableFuture<List<Location>> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    references.addAll(future.getNow(Collections.emptyList()));
                } catch (Exception e) {
                    // Игнорируем
                }
            }
        }

        return references;
    }

    /**
     * Ищет ссылки в проекте.
     * ВАЖНО: Для поиска ИСПОЛЬЗОВАНИЙ символа нужно сканировать файлы по тексту (FastSearch),
     * а не использовать SymbolIndex, который хранит только ОПРЕДЕЛЕНИЯ символов.
     */
    private List<Location> findReferencesInProject(Path currentFile, String symbolName,
                                                    String langId) throws IOException {
        Path projectRoot = findProjectRoot(currentFile);
        if (projectRoot == null) {
            return findReferencesInDirectory(currentFile.getParent(), symbolName, langId);
        }

        // Для поиска ссылок (использований) нужно сканировать файлы по тексту,
        // а не использовать индекс определений (он хранит только места где символ ОПРЕДЕЛЁН)
        List<Path> candidateFiles = scanFilesForSymbol(projectRoot, symbolName, langId);

        // Параллельный поиск ссылок с глобальным таймаутом
        List<CompletableFuture<List<Location>>> futures = candidateFiles.stream()
                .map(file -> CompletableFuture.supplyAsync(() -> {
                    try {
                        TreeSitterManager.ParseResult pr = treeManager.getCachedOrParseWithContent(file);
                        return extractor.findReferences(
                                pr.tree(), file, pr.content(), pr.langId(), symbolName);
                    } catch (Exception e) {
                        return Collections.<Location>emptyList();
                    }
                }, executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(OPERATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
        } catch (CompletionException e) {
            // Таймаут - возвращаем что успели найти
        }

        List<Location> allReferences = new ArrayList<>();
        for (CompletableFuture<List<Location>> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    allReferences.addAll(future.getNow(Collections.emptyList()));
                } catch (Exception e) {
                    // Игнорируем
                }
            }
        }

        return allReferences;
    }

    /**
     * Сканирует файлы проекта для поиска символа (fallback если индекс не доступен).
     */
    private List<Path> scanFilesForSymbol(Path projectRoot, String symbolName, String langId) {
        String globPattern = LanguageDetector.getGlobPattern(langId);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + globPattern);

        List<Path> candidateFiles = new ArrayList<>();

        try {
            Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), 15,
                    new SimpleFileVisitor<>() {
                        int count = 0;

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (count >= MAX_FILES_TO_SCAN) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (matcher.matches(file)) {
                                try {
                                    // Быстрая проверка без полного чтения файла
                                    if (FastSearch.containsText(file, symbolName)) {
                                        candidateFiles.add(file);
                                        count++;
                                    }
                                } catch (IOException e) {
                                    // Игнорируем
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String dirName = dir.getFileName().toString();
                            if (dirName.startsWith(".") ||
                                    dirName.equals("node_modules") ||
                                    dirName.equals("build") ||
                                    dirName.equals("target") ||
                                    dirName.equals("dist") ||
                                    dirName.equals("out")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            // Игнорируем
        }

        return candidateFiles;
    }
}
