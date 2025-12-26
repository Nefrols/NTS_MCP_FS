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

import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.SessionTransactionManager;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractor;
import ru.nts.tools.mcp.core.treesitter.SymbolResolver;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
}
