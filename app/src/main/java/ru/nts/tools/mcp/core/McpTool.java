// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Базовый интерфейс для реализации инструментов (Tools) в рамках Model Context Protocol.
 * Каждая реализация представляет собой атомарную функцию, которую LLM может вызывать
 * для взаимодействия с файловой системой, системой контроля версий или инструментами сборки.
 */
public interface McpTool {

    /**
     * Возвращает уникальное имя инструмента.
     * Это имя используется LLM в поле 'name' при выполнении запроса 'tools/call'.
     *
     * @return Техническое имя инструмента (например, "read_file").
     */
    String getName();

    /**
     * Возвращает краткое описание назначения инструмента.
     * Это описание помогает LLM понять, в каких ситуациях следует вызывать данный инструмент.
     * Описание должно быть лаконичным для экономии контекстного окна модели.
     *
     * @return Текстовое описание функционала.
     */
    String getDescription();

    /**
     * Возвращает схему входных параметров инструмента в формате JSON Schema.
     * Схема используется MCP клиентом для валидации запросов и моделью для понимания аргументов.
     *
     * @return JsonNode, содержащий спецификацию параметров (типы, описания, обязательность).
     */
    JsonNode getInputSchema();

    /**
     * Выполняет основную бизнес-логику инструмента.
     * Реализация должна обеспечивать безопасность операций и возвращать результат в формате,
     * совместимом с MCP (обычно объект с массивом 'content').
     *
     * @param params JSON-узел с аргументами, переданными LLM.
     *
     * @return JSON-узел с результатом выполнения (текст, данные или отчет об ошибке).
     *
     * @throws Exception Если выполнение невозможно из-за ошибок валидации, прав доступа или системных сбоев.
     */
    JsonNode execute(JsonNode params) throws Exception;

    /**
     * Обертка над execute для обеспечения информативной обратной связи при ошибках.
     * Перехватывает исключения и преобразует их в понятные для LLM сообщения.
     * 
     * @param params Аргументы вызова.
     * @return JSON-узел с результатом или описанием ошибки для LLM.
     */
    default JsonNode executeWithFeedback(JsonNode params) {
        try {
            return execute(params);
        } catch (IllegalArgumentException e) {
            return createErrorResponse("INVALID_ARGUMENTS", 
                "Invalid request parameters: " + e.getMessage() + 
                "\nPlease check the tool schema and correct your arguments.");
        } catch (SecurityException e) {
            return createErrorResponse("SECURITY_VIOLATION", 
                "Security policy violation: " + e.getMessage() + 
                "\nYou might be trying to access a protected file or modify a file without reading it first.");
        } catch (IllegalStateException e) {
            return createErrorResponse("VALIDATION_FAILED", 
                "State validation failed: " + e.getMessage() + 
                "\nThe file might have changed on disk, or you used incorrect expectations (expectedContent/checksum).");
        } catch (java.io.IOException e) {
            return createErrorResponse("SYSTEM_ERROR", 
                "System I/O error: " + e.getMessage() + 
                "\nThis could be a server-side issue (file locked by OS) or an invalid path.");
        } catch (Exception e) {
            return createErrorResponse("INTERNAL_BUG", 
                "Internal server error: " + e.toString() + 
                "\nPlease report this bug to the developer.");
        }
    }

    /**
     * Вспомогательный метод для формирования JSON-ответа с ошибкой.
     */
    private JsonNode createErrorResponse(String type, String message) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode res = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode content = res.putArray("content");
        com.fasterxml.jackson.databind.node.ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", "Error [" + type + "]: " + message);
        res.put("isError", true);
        return res;
    }
}