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
package ru.nts.tools.mcp.tools.refactoring;

import ru.nts.tools.mcp.core.ExternalChangeTracker;
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.SessionTransactionManager;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractor;
import ru.nts.tools.mcp.core.treesitter.SymbolResolver;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Контекст выполнения операции рефакторинга.
 * Предоставляет доступ к необходимым сервисам и хранит состояние.
 */
public class RefactoringContext {

    private final SessionContext sessionContext;
    private final TreeSitterManager treeManager;
    private final SymbolExtractor symbolExtractor;
    private final SymbolResolver symbolResolver;
    private final SessionTransactionManager transactionManager;
    private final Path projectRoot;

    // Кэш для промежуточных результатов
    private final Map<String, Object> cache = new HashMap<>();

    // Накопленные токены доступа для файлов
    private final Map<Path, String> accessTokens = new HashMap<>();

    // Файлы, записанные в рамках этой операции (для обновления снапшотов)
    private final Set<Path> writtenFiles = new HashSet<>();

    public RefactoringContext() {
        this.sessionContext = SessionContext.current();
        this.treeManager = TreeSitterManager.getInstance();
        this.symbolExtractor = SymbolExtractor.getInstance();
        this.symbolResolver = SymbolResolver.getInstance();
        this.transactionManager = sessionContext.transactions();
        this.projectRoot = PathSanitizer.getRoot();
    }

    public SessionContext getSessionContext() {
        return sessionContext;
    }

    public TreeSitterManager getTreeManager() {
        return treeManager;
    }

    public SymbolExtractor getSymbolExtractor() {
        return symbolExtractor;
    }

    public SymbolResolver getSymbolResolver() {
        return symbolResolver;
    }

    public SessionTransactionManager getTransactionManager() {
        return transactionManager;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    // Cache management
    @SuppressWarnings("unchecked")
    public <T> T getCached(String key) {
        return (T) cache.get(key);
    }

    public void putCached(String key, Object value) {
        cache.put(key, value);
    }

    public boolean hasCached(String key) {
        return cache.containsKey(key);
    }

    // Access token management
    public void registerAccessToken(Path path, String token) {
        accessTokens.put(path.toAbsolutePath().normalize(), token);
    }

    public String getAccessToken(Path path) {
        return accessTokens.get(path.toAbsolutePath().normalize());
    }

    public Map<Path, String> getAccessTokens() {
        return new HashMap<>(accessTokens);
    }

    /**
     * Начинает транзакцию рефакторинга.
     */
    public void beginTransaction(String description) {
        transactionManager.startTransaction(description, description);
    }

    /**
     * Фиксирует транзакцию.
     */
    public String commitTransaction() {
        transactionManager.commit();
        return "tx_" + System.currentTimeMillis();
    }

    /**
     * Откатывает транзакцию.
     */
    public void rollbackTransaction() {
        transactionManager.rollback();
    }

    /**
     * Создаёт backup файла перед изменением.
     */
    public void backupFile(Path path) throws RefactoringException {
        try {
            transactionManager.backup(path);
        } catch (java.io.IOException e) {
            throw new RefactoringException("Failed to backup file: " + path, e);
        }
    }

    /**
     * Записывает файл и регистрирует его для обновления снапшота.
     * Этот метод должен использоваться всеми операциями рефакторинга.
     *
     * @param path путь к файлу
     * @param content новое содержимое
     * @param charset кодировка (по умолчанию UTF-8)
     */
    public void writeFile(Path path, String content, Charset charset) throws RefactoringException {
        try {
            FileUtils.safeWrite(path, content, charset);
            writtenFiles.add(path.toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new RefactoringException("Failed to write file: " + path, e);
        }
    }

    /**
     * Записывает файл в UTF-8 и регистрирует его для обновления снапшота.
     */
    public void writeFile(Path path, String content) throws RefactoringException {
        writeFile(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Обновляет снапшоты для всех записанных файлов.
     * Вызывается после коммита транзакции.
     * Учитывает как файлы, зарегистрированные через registerWrittenFile(),
     * так и файлы, затронутые в текущей транзакции (через backup).
     */
    public void updateSnapshots() {
        ExternalChangeTracker tracker = sessionContext.externalChanges();

        // Объединяем явно записанные файлы и файлы из транзакции
        Set<Path> allAffectedFiles = new HashSet<>(writtenFiles);
        allAffectedFiles.addAll(transactionManager.getCurrentTransactionAffectedPaths());

        for (Path path : allAffectedFiles) {
            try {
                if (java.nio.file.Files.exists(path)) {
                    String content = java.nio.file.Files.readString(path);
                    long crc = calculateCRC32(content.getBytes(StandardCharsets.UTF_8));
                    int lineCount = content.split("\n", -1).length;
                    tracker.updateSnapshot(path, content, crc, StandardCharsets.UTF_8, lineCount);
                }
            } catch (IOException ignored) {
                // Если не удалось обновить снапшот - пропускаем
            }
        }
    }

    /**
     * Возвращает набор записанных файлов.
     */
    public Set<Path> getWrittenFiles() {
        return new HashSet<>(writtenFiles);
    }

    /**
     * Регистрирует файл как записанный для последующего обновления снапшота.
     * Используется операциями, которые пишут файлы напрямую через FileUtils.safeWrite.
     */
    public void registerWrittenFile(Path path) {
        writtenFiles.add(path.toAbsolutePath().normalize());
    }

    private long calculateCRC32(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        return crc.getValue();
    }
}
