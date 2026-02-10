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

import com.fasterxml.jackson.databind.JsonNode;
import ru.nts.tools.mcp.tools.refactoring.operations.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Движок рефакторинга.
 * Управляет регистрацией и выполнением операций.
 */
public final class RefactoringEngine {

    private static final RefactoringEngine INSTANCE = new RefactoringEngine();

    private final Map<String, RefactoringOperation> operations = new HashMap<>();

    private RefactoringEngine() {
        // Регистрация встроенных операций
        registerOperation(new RenameOperation());
        registerOperation(new GenerateOperation());
        registerOperation(new DeleteOperation());
        registerOperation(new WrapOperation());
        registerOperation(new ExtractMethodOperation());
        registerOperation(new InlineOperation());
        registerOperation(new ExtractVariableOperation());
        registerOperation(new ChangeSignatureOperation());
        registerOperation(new MoveOperation());
        registerOperation(new BatchOperation());
    }

    public static RefactoringEngine getInstance() {
        return INSTANCE;
    }

    /**
     * Регистрирует операцию рефакторинга.
     */
    public void registerOperation(RefactoringOperation operation) {
        operations.put(operation.getName(), operation);
    }

    /**
     * Получает операцию по имени.
     */
    public RefactoringOperation getOperation(String name) {
        return operations.get(name);
    }

    /**
     * Проверяет, зарегистрирована ли операция.
     */
    public boolean hasOperation(String name) {
        return operations.containsKey(name);
    }

    /**
     * Выполняет операцию рефакторинга.
     */
    public RefactoringResult execute(String action, JsonNode params, boolean preview)
            throws RefactoringException {

        RefactoringOperation operation = operations.get(action);
        if (operation == null) {
            throw new RefactoringException(
                    "Unknown refactoring action: " + action,
                    operations.keySet().stream()
                            .map(op -> "Available: " + op)
                            .toList()
            );
        }

        // Валидация параметров
        operation.validateParams(params);

        // Создание контекста
        RefactoringContext context = new RefactoringContext();

        try {
            RefactoringResult result;
            if (preview) {
                result = operation.preview(params, context);
            } else {
                result = operation.execute(params, context);
                // Обновляем снапшоты для всех записанных файлов
                context.updateSnapshots();
            }
            return result;
        } catch (RefactoringException e) {
            // Откат транзакции если была начата
            try {
                context.rollbackTransaction();
            } catch (Exception ignored) {}
            throw e;
        } catch (Exception e) {
            // Откат транзакции если была начата
            try {
                context.rollbackTransaction();
            } catch (Exception ignored) {}
            throw new RefactoringException("Refactoring failed: " + e.getMessage(), e);
        }
    }

    /**
     * Возвращает список доступных операций.
     */
    public Iterable<String> getAvailableOperations() {
        return operations.keySet();
    }
}
