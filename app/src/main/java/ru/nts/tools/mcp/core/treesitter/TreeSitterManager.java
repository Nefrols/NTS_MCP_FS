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

import org.treesitter.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/**
 * Менеджер tree-sitter парсеров.
 * Управляет пулом парсеров для различных языков и кэшем AST деревьев.
 * Thread-safe через ThreadLocal парсеров.
 */
public final class TreeSitterManager {

    private static final TreeSitterManager INSTANCE = new TreeSitterManager();

    /**
     * Кэшированные TSLanguage объекты (потокобезопасные, можно переиспользовать).
     */
    private final Map<String, TSLanguage> languages = new ConcurrentHashMap<>();

    /**
     * ThreadLocal парсеры для каждого языка (TSParser не thread-safe).
     */
    private final Map<String, ThreadLocal<TSParser>> parsers = new ConcurrentHashMap<>();

    /**
     * Кэш AST деревьев с CRC для инвалидации.
     */
    private final Map<Path, CachedTree> treeCache = new ConcurrentHashMap<>();

    /**
     * Максимальный размер кэша деревьев.
     */
    private static final int MAX_CACHE_SIZE = 100;

    /**
     * Максимальное количество строк для полного кэширования.
     * Файлы с бОльшим количеством строк не кэшируются для экономии памяти.
     */
    private static final int MAX_LINES_FOR_CACHING = 10_000;

    /**
     * Максимальный размер файла для парсинга (5MB).
     * Файлы большего размера не парсятся для предотвращения OOM.
     */
    private static final long MAX_PARSE_SIZE_BYTES = 5 * 1024 * 1024;

    private TreeSitterManager() {}

    public static TreeSitterManager getInstance() {
        return INSTANCE;
    }

    /**
     * Получает TSLanguage объект для указанного языка.
     * Ленивая загрузка - язык загружается только при первом обращении.
     *
     * @param langId идентификатор языка (java, kotlin, javascript, etc.)
     * @return TSLanguage объект
     * @throws IllegalArgumentException если язык не поддерживается
     */
    public TSLanguage getLanguage(String langId) {
        return languages.computeIfAbsent(langId, this::loadLanguage);
    }

    /**
     * Загружает TSLanguage из tree-sitter библиотеки.
     */
    private TSLanguage loadLanguage(String langId) {
        return switch (langId) {
            case "java" -> new TreeSitterJava();
            case "kotlin" -> new TreeSitterKotlin();
            case "javascript" -> new TreeSitterJavascript();
            case "typescript" -> new TreeSitterTypescript();
            case "tsx" -> new TreeSitterTypescript();  // TSX uses TypeScript parser
            case "python" -> new TreeSitterPython();
            case "go" -> new TreeSitterGo();
            case "rust" -> new TreeSitterRust();
            case "c" -> new TreeSitterC();
            case "cpp" -> new TreeSitterCpp();
            case "csharp" -> new TreeSitterCSharp();
            case "php" -> new TreeSitterPhp();
            case "html" -> new TreeSitterHtml();
            default -> throw new IllegalArgumentException("Unsupported language: " + langId);
        };
    }

    /**
     * Получает или создает TSParser для текущего потока.
     *
     * @param langId идентификатор языка
     * @return TSParser настроенный на указанный язык
     */
    private TSParser getParser(String langId) {
        ThreadLocal<TSParser> parserHolder = parsers.computeIfAbsent(langId,
                k -> ThreadLocal.withInitial(() -> {
                    TSParser parser = new TSParser();
                    parser.setLanguage(getLanguage(k));
                    return parser;
                }));
        return parserHolder.get();
    }

    /**
     * Парсит файл и возвращает AST дерево.
     *
     * @param path путь к файлу
     * @param langId идентификатор языка (если null, определяется автоматически)
     * @return AST дерево
     * @throws IOException если файл не может быть прочитан
     * @throws IllegalArgumentException если язык не определен или не поддерживается
     */
    public TSTree parse(Path path, String langId) throws IOException {
        String effectiveLangId = langId;
        if (effectiveLangId == null) {
            effectiveLangId = LanguageDetector.detect(path)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot detect language for: " + path));
        }

        String content = Files.readString(path);
        return parse(content, effectiveLangId);
    }

    /**
     * Парсит строку содержимого и возвращает AST дерево.
     *
     * @param content исходный код
     * @param langId идентификатор языка
     * @return AST дерево
     * @throws IllegalArgumentException если язык не поддерживается
     */
    public TSTree parse(String content, String langId) {
        TSParser parser = getParser(langId);
        TSTree tree = parser.parseString(null, content);
        if (tree == null) {
            throw new IllegalStateException("Failed to parse content for language: " + langId);
        }
        return tree;
    }

    /**
     * Получает AST дерево из кэша или парсит файл.
     * Кэш инвалидируется если CRC файла изменился.
     *
     * @param path путь к файлу
     * @return AST дерево
     * @throws IOException если файл не может быть прочитан
     */
    public TSTree getCachedOrParse(Path path) throws IOException {
        Path normalizedPath = path.toAbsolutePath().normalize();
        String content = Files.readString(normalizedPath);
        long currentCrc = calculateCrc(content);

        CachedTree cached = treeCache.get(normalizedPath);
        if (cached != null && cached.crc32c == currentCrc) {
            return cached.tree;
        }

        String langId = LanguageDetector.detect(normalizedPath)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot detect language for: " + normalizedPath));

        TSTree tree = parse(content, langId);

        // Ограничиваем размер кэша
        if (treeCache.size() >= MAX_CACHE_SIZE) {
            // Удаляем самые старые записи
            treeCache.entrySet().stream()
                    .sorted((a, b) -> a.getValue().parsedAt.compareTo(b.getValue().parsedAt))
                    .limit(MAX_CACHE_SIZE / 4)
                    .map(Map.Entry::getKey)
                    .forEach(treeCache::remove);
        }

        treeCache.put(normalizedPath, new CachedTree(tree, currentCrc, Instant.now(), langId));
        return tree;
    }

    /**
     * Получает AST дерево из кэша или парсит файл, вместе с контентом.
     * Для файлов > 10000 строк AST не кэшируется для экономии памяти.
     *
     * @param path путь к файлу
     * @return пара (дерево, контент)
     * @throws IOException если файл не может быть прочитан
     * @throws IllegalArgumentException если файл слишком большой для парсинга
     */
    public ParseResult getCachedOrParseWithContent(Path path) throws IOException {
        Path normalizedPath = path.toAbsolutePath().normalize();

        // Проверяем размер файла перед чтением
        long fileSize = Files.size(normalizedPath);
        if (fileSize > MAX_PARSE_SIZE_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "File too large for AST parsing: %d bytes (max: %d bytes). Path: %s",
                    fileSize, MAX_PARSE_SIZE_BYTES, normalizedPath));
        }

        String content = Files.readString(normalizedPath);
        long currentCrc = calculateCrc(content);

        CachedTree cached = treeCache.get(normalizedPath);
        if (cached != null && cached.crc32c == currentCrc) {
            return new ParseResult(cached.tree, content, cached.langId, currentCrc);
        }

        String langId = LanguageDetector.detect(normalizedPath)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot detect language for: " + normalizedPath));

        TSTree tree = parse(content, langId);

        // Подсчитываем строки для решения о кэшировании
        int lineCount = countLines(content);

        // Не кэшируем очень большие файлы для экономии памяти
        if (lineCount <= MAX_LINES_FOR_CACHING) {
            if (treeCache.size() >= MAX_CACHE_SIZE) {
                treeCache.entrySet().stream()
                        .sorted((a, b) -> a.getValue().parsedAt.compareTo(b.getValue().parsedAt))
                        .limit(MAX_CACHE_SIZE / 4)
                        .map(Map.Entry::getKey)
                        .forEach(treeCache::remove);
            }
            treeCache.put(normalizedPath, new CachedTree(tree, currentCrc, Instant.now(), langId));
        }

        return new ParseResult(tree, content, langId, currentCrc);
    }

    /**
     * Подсчитывает количество строк в содержимом.
     */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    /**
     * Инвалидирует кэш для указанного файла.
     *
     * @param path путь к файлу
     */
    public void invalidateCache(Path path) {
        treeCache.remove(path.toAbsolutePath().normalize());
    }

    /**
     * Очищает весь кэш деревьев.
     */
    public void clearCache() {
        treeCache.clear();
    }

    /**
     * Возвращает текущий размер кэша.
     */
    public int getCacheSize() {
        return treeCache.size();
    }

    /**
     * Проверяет, есть ли файл в кэше с актуальной CRC.
     */
    public boolean isCached(Path path) {
        CachedTree cached = treeCache.get(path.toAbsolutePath().normalize());
        if (cached == null) {
            return false;
        }
        try {
            String content = Files.readString(path);
            return cached.crc32c == calculateCrc(content);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Вычисляет CRC32C хеш содержимого.
     */
    private long calculateCrc(String content) {
        CRC32C crc = new CRC32C();
        crc.update(content.getBytes());
        return crc.getValue();
    }

    /**
     * Кэшированное AST дерево с метаданными.
     */
    private record CachedTree(TSTree tree, long crc32c, Instant parsedAt, String langId) {}

    /**
     * Результат парсинга с контентом.
     */
    public record ParseResult(TSTree tree, String content, String langId, long crc32c) {}
}
