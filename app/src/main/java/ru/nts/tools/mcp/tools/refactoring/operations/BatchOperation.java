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
package ru.nts.tools.mcp.tools.refactoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Операция пакетного рефакторинга.
 * Позволяет выполнить несколько операций рефакторинга атомарно в одной транзакции.
 * Если любая операция падает, все изменения откатываются.
 */
public class BatchOperation implements RefactoringOperation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String getName() {
        return "batch";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("operations") || !params.get("operations").isArray()) {
            throw new IllegalArgumentException("Parameter 'operations' array is required");
        }

        JsonNode operations = params.get("operations");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("At least one operation is required");
        }

        // Валидируем каждую операцию
        for (int i = 0; i < operations.size(); i++) {
            JsonNode op = operations.get(i);
            if (!op.has("action")) {
                throw new IllegalArgumentException(
                        "Operation " + i + ": 'action' is required");
            }
            // params может быть вложенным объектом или параметры могут быть на том же уровне
            // Поддерживаем оба формата:
            // 1. {"action": "rename", "params": {"path": "...", "symbol": "..."}}
            // 2. {"action": "rename", "path": "...", "symbol": "..."}
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        JsonNode operations = params.get("operations");
        boolean stopOnError = !params.has("continueOnError") ||
                !params.get("continueOnError").asBoolean();

        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Batch refactoring (" + operations.size() + " operations)";

        // Начинаем единую транзакцию для всех операций
        context.beginTransaction(instruction);

        List<RefactoringResult.FileChange> allChanges = new ArrayList<>();
        List<OperationResult> operationResults = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        try {
            for (int i = 0; i < operations.size(); i++) {
                JsonNode op = operations.get(i);
                String action = op.get("action").asText();
                // Поддержка двух форматов: вложенный params или плоский формат
                JsonNode opParams = op.has("params") ? op.get("params") : op;

                // Получаем операцию (исключаем batch чтобы избежать рекурсии)
                if (action.equals("batch")) {
                    throw new RefactoringException("Nested batch operations are not allowed");
                }

                RefactoringOperation operation = RefactoringEngine.getInstance()
                        .getOperation(action);

                if (operation == null) {
                    if (stopOnError) {
                        throw new RefactoringException("Unknown operation: " + action);
                    } else {
                        operationResults.add(new OperationResult(
                                i, action, false, "Unknown operation"));
                        failCount++;
                        continue;
                    }
                }

                try {
                    // Валидируем параметры
                    operation.validateParams(opParams);

                    // Выполняем операцию (без собственной транзакции - используем общую)
                    RefactoringResult result = executeWithoutTransaction(
                            operation, opParams, context);

                    if (result.status() == RefactoringResult.Status.SUCCESS) {
                        allChanges.addAll(result.changes());
                        operationResults.add(new OperationResult(
                                i, action, true, result.summary()));
                        successCount++;
                    } else {
                        if (stopOnError) {
                            throw new RefactoringException(
                                    "Operation " + i + " (" + action + ") failed: " + result.summary());
                        }
                        operationResults.add(new OperationResult(
                                i, action, false, result.summary()));
                        failCount++;
                    }

                } catch (IllegalArgumentException e) {
                    if (stopOnError) {
                        throw new RefactoringException(
                                "Operation " + i + " validation failed: " + e.getMessage(), e);
                    }
                    operationResults.add(new OperationResult(
                            i, action, false, "Validation: " + e.getMessage()));
                    failCount++;

                } catch (RefactoringException e) {
                    if (stopOnError) {
                        throw e;
                    }
                    operationResults.add(new OperationResult(
                            i, action, false, e.getMessage()));
                    failCount++;
                }
            }

            // Если хотя бы одна операция успешна, коммитим
            if (successCount > 0) {
                String txId = context.commitTransaction();

                // Собираем уникальные файлы
                long affectedFiles = allChanges.stream()
                        .map(RefactoringResult.FileChange::path)
                        .distinct()
                        .count();

                int totalChanges = allChanges.stream()
                        .mapToInt(RefactoringResult.FileChange::occurrences)
                        .sum();

                StringBuilder summary = new StringBuilder();
                summary.append(String.format("Batch completed: %d/%d operations succeeded",
                        successCount, operations.size()));

                if (failCount > 0) {
                    summary.append(String.format(", %d failed", failCount));
                }

                // Добавляем детали по операциям
                ObjectNode details = MAPPER.createObjectNode();
                for (OperationResult or : operationResults) {
                    ObjectNode opResult = MAPPER.createObjectNode();
                    opResult.put("action", or.action);
                    opResult.put("success", or.success);
                    opResult.put("message", or.message);
                    details.set("op_" + or.index, opResult);
                }

                return RefactoringResult.builder()
                        .status(failCount == 0
                                ? RefactoringResult.Status.SUCCESS
                                : RefactoringResult.Status.PARTIAL)
                        .action("batch")
                        .summary(summary.toString())
                        .changes(allChanges)
                        .affectedFiles((int) affectedFiles)
                        .totalChanges(totalChanges)
                        .transactionId(txId)
                        .details(details)
                        .build();

            } else {
                context.rollbackTransaction();
                return RefactoringResult.error("batch",
                        "All operations failed", null);
            }

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Batch failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        JsonNode operations = params.get("operations");
        List<RefactoringResult.FileChange> allChanges = new ArrayList<>();
        StringBuilder previewText = new StringBuilder();

        previewText.append("=== Batch Refactoring Preview ===\n");
        previewText.append("Operations: ").append(operations.size()).append("\n\n");

        for (int i = 0; i < operations.size(); i++) {
            JsonNode op = operations.get(i);
            String action = op.get("action").asText();
            JsonNode opParams = op.get("params");

            previewText.append("--- Operation ").append(i + 1)
                    .append(": ").append(action).append(" ---\n");

            if (action.equals("batch")) {
                previewText.append("  [ERROR] Nested batch operations not allowed\n");
                continue;
            }

            RefactoringOperation operation = RefactoringEngine.getInstance()
                    .getOperation(action);

            if (operation == null) {
                previewText.append("  [ERROR] Unknown operation\n");
                continue;
            }

            try {
                operation.validateParams(opParams);
                RefactoringResult result = operation.preview(opParams, context);

                if (result.status() == RefactoringResult.Status.PREVIEW) {
                    previewText.append("  ").append(result.summary()).append("\n");
                    allChanges.addAll(result.changes());

                    // Добавляем diff если есть
                    for (RefactoringResult.FileChange change : result.changes()) {
                        if (change.diff() != null) {
                            previewText.append(change.diff()).append("\n");
                        }
                    }
                } else {
                    previewText.append("  [").append(result.status()).append("] ")
                            .append(result.summary()).append("\n");
                }

            } catch (Exception e) {
                previewText.append("  [ERROR] ").append(e.getMessage()).append("\n");
            }

            previewText.append("\n");
        }

        return RefactoringResult.builder()
                .status(RefactoringResult.Status.PREVIEW)
                .action("batch")
                .summary("Batch preview: " + operations.size() + " operations")
                .changes(allChanges)
                .affectedFiles(allChanges.size())
                .totalChanges(allChanges.size())
                .diff(previewText.toString())
                .build();
    }

    /**
     * Выполняет операцию без создания собственной транзакции.
     * Использует транзакцию batch-операции.
     */
    private RefactoringResult executeWithoutTransaction(
            RefactoringOperation operation, JsonNode params, RefactoringContext context)
            throws RefactoringException {

        // Для большинства операций просто вызываем execute
        // Операции используют context.backupFile() для отдельных файлов
        // Транзакция уже начата на уровне batch
        return operation.execute(params, context);
    }

    private record OperationResult(
            int index,
            String action,
            boolean success,
            String message
    ) {}
}
