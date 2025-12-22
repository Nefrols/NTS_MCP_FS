// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Интерфейс для реализации инструментов (tools) MCP сервера.
 */
public interface McpTool {
    /**
     * Возвращает имя инструмента.
     */
    String getName();

    /**
     * Возвращает описание инструмента для LLM.
     */
    String getDescription();

    /**
     * Возвращает схему входных параметров (JSON Schema).
     */
    JsonNode getInputSchema();

    /**
     * Выполняет логику инструмента.
     *
     * @param params Параметры запроса.
     * @return Результат выполнения.
     * @throws Exception Если произошла ошибка.
     */
    JsonNode execute(JsonNode params) throws Exception;
}
