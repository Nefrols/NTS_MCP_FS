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
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.concurrent.*;

/**
 * Базовый интерфейс для реализации инструментов (Tools) в рамках Model Context Protocol.
 */
public interface McpTool {

    /**
     * Таймаут по умолчанию для выполнения инструментов (5 минут).
     */
    int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * Инструменты, исключённые из глобального таймаута.
     * nts_git и nts_gradle_task используют свои внутренние таймауты.
     */
    Set<String> TIMEOUT_EXEMPT_TOOLS = Set.of("nts_git", "nts_gradle_task");

    String getName();
    String getDescription();
    String getCategory();
    JsonNode getInputSchema();
    JsonNode execute(JsonNode params) throws Exception;

    /**
     * Указывает, требует ли инструмент валидную задачу для работы.
     * По умолчанию все инструменты требуют задачу.
     * Только nts_init переопределяет это и возвращает false.
     */
    default boolean requiresTask() {
        return true;
    }

    /**
     * Указывает, является ли инструмент внутренним для оркестратора.
     * Внутренние инструменты (finish-сигналы, вопросы архитектора, вызов скаута)
     * не отдаются внешним MCP-клиентам через tools/list,
     * но доступны агентам через оркестратор.
     */
    default boolean isInternal() {
        return false;
    }

    /**
     * Обертка над execute для обеспечения информативной обратной связи при ошибках,
     * глобального таймаута и внедрения HUD.
     */
    default JsonNode executeWithFeedback(JsonNode params) {
        // Извлекаем ntsAllowedTools из params для role-aware tip фильтрации
        // и удаляем из params перед передачей в execute()
        if (params instanceof ObjectNode paramsObj && paramsObj.has("ntsAllowedTools")) {
            JsonNode allowedNode = paramsObj.get("ntsAllowedTools");
            if (allowedNode instanceof ArrayNode allowedArr) {
                java.util.Set<String> allowed = new java.util.HashSet<>();
                for (JsonNode item : allowedArr) {
                    allowed.add(item.asText());
                }
                TipFilter.setCurrentAllowedTools(allowed);
            }
            paramsObj.remove("ntsAllowedTools");
        }

        // Валидация обязательных параметров по схеме
        validateRequiredParams(params);

        JsonNode response;
        try {
            // Проверяем, нужен ли таймаут для этого инструмента
            // Не применяем таймаут если:
            // 1. Инструмент в списке исключений (git, gradle)
            // 2. Уже находимся внутри транзакции (batch операции)
            //    - В этом случае таймаут с отдельным потоком нарушит ThreadLocal состояние транзакции
            boolean skipTimeout = TIMEOUT_EXEMPT_TOOLS.contains(getName()) ||
                    TransactionManager.isInTransaction();

            if (skipTimeout) {
                // Без таймаута
                response = execute(params);
            } else {
                // Выполняем с таймаутом
                response = executeWithTimeout(params, DEFAULT_TIMEOUT_SECONDS);
            }
        } catch (TimeoutException e) {
            response = createErrorResponse("TIMEOUT_EXCEEDED",
                    "Tool execution exceeded " + DEFAULT_TIMEOUT_SECONDS + " seconds timeout. " +
                    "Consider breaking down the operation into smaller parts.");
        } catch (IllegalArgumentException e) {
            response = createErrorResponse("INVALID_ARGUMENTS", "Invalid request parameters: " + e.getMessage());
        } catch (SecurityException e) {
            response = createErrorResponse("SECURITY_VIOLATION", "Security policy violation: " + e.getMessage());
        } catch (IllegalStateException e) {
            response = createErrorResponse("VALIDATION_FAILED", "State validation failed: " + e.getMessage());
        } catch (java.io.IOException e) {
            response = createErrorResponse("SYSTEM_ERROR", "System I/O error: " + e.getMessage());
        } catch (Exception e) {
            response = createErrorResponse("INTERNAL_BUG", "Internal server error: " + e.toString());
        } finally {
            TipFilter.clear();
        }

        // Внедрение AI-HUD как отдельного элемента контента (метаданные)
        // НЕ добавляем HUD для ошибок - это мешает batch tools читать ошибку из content[0]
        if (response instanceof ObjectNode res) {
            boolean isError = res.has("isError") && res.get("isError").asBoolean();
            if (!isError) {
                JsonNode contentNode = res.get("content");
                if (contentNode instanceof ArrayNode contentArray) {
                    // Создаем отдельный блок для HUD
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    ObjectNode hudNode = mapper.createObjectNode();
                    hudNode.put("type", "text");
                    hudNode.put("text", TodoManager.getHudInfo().toString());

                    // Вставляем HUD в самое начало массива контента
                    contentArray.insert(0, hudNode);
                }
            }
        }

        return response;
    }

    /**
     * Выполняет инструмент с ограничением по времени.
     * Сохраняет контекст задачи при переключении потоков.
     *
     * @param params параметры вызова
     * @param timeoutSeconds таймаут в секундах
     * @return результат выполнения
     * @throws TimeoutException если время выполнения превысило таймаут
     * @throws Exception другие исключения от execute()
     */
    private JsonNode executeWithTimeout(JsonNode params, int timeoutSeconds) throws Exception {
        // Захватываем контекст текущей сессии для передачи в worker thread
        TaskContext parentContext = TaskContext.currentOrDefault();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> future = executor.submit(() -> {
                // Устанавливаем контекст сессии в worker thread
                TaskContext.setCurrent(parentContext);
                try {
                    return execute(params);
                } finally {
                    // Очищаем контекст в worker thread
                    TaskContext.clearCurrent();
                }
            });
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw e;
        } catch (ExecutionException e) {
            // Разворачиваем исключение из Future
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tool execution was interrupted", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private void validateRequiredParams(JsonNode params) {
        JsonNode schema = getInputSchema();
        if (schema == null || !schema.has("required")) return;
        JsonNode required = schema.get("required");
        if (!required.isArray()) return;
        for (JsonNode req : required) {
            String fieldName = req.asText();
            if (!params.has(fieldName) || params.get(fieldName).isNull()) {
                throw new IllegalArgumentException(
                        "Missing required parameter: '" + fieldName + "' for tool " + getName());
            }
        }
    }

    private JsonNode createErrorResponse(String type, String message) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", "Error [" + type + "]: " + message);
        res.put("isError", true);
        return res;
    }
}