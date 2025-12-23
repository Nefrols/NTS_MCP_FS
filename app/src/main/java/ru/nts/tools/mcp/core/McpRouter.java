// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Роутер для управления инструментами (Tools) и маршрутизации запросов от MCP клиента.
 * Реализует реестр инструментов и предоставляет интерфейс для их динамического вызова.
 */
public class McpRouter {

    /**
     * Карта зарегистрированных инструментов, где ключ — имя инструмента.
     */
    private final Map<String, McpTool> tools = new HashMap<>();

    /**
     * Объект для манипуляции JSON узлами при формировании списков инструментов.
     */
    private final ObjectMapper mapper;

    /**
     * Создает новый роутер с привязкой к ObjectMapper.
     *
     * @param mapper Объект ObjectMapper, используемый для работы с JSON.
     */
    public McpRouter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Регистрирует новый инструмент в реестре сервера.
     * После регистрации инструмент становится доступен для вызова через 'tools/call'.
     *
     * @param tool Объект реализации инструмента.
     */
    public void registerTool(McpTool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * Формирует список всех доступных инструментов в формате, соответствующем протоколу MCP.
     * Используется для ответа на запрос 'tools/list'.
     *
     * @return JsonNode, содержащий массив объектов с описанием имен, описаний и схем параметров инструментов.
     */
    public JsonNode listTools() {
        ArrayNode toolsArray = mapper.createArrayNode();
        for (McpTool tool : tools.values()) {
            ObjectNode toolNode = mapper.createObjectNode();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            toolNode.set("inputSchema", tool.getInputSchema());
            toolsArray.add(toolNode);
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        return result;
    }

    /**
     * Выполняет вызов инструмента по его уникальному имени.
     *
     * @param name   Имя инструмента.
     * @param params JSON-узел с аргументами вызова.
     *
     * @return JSON-узел с результатом выполнения инструмента.
     *
     * @throws Exception Если инструмент не найден или произошла ошибка во время его выполнения.
     */
    public JsonNode callTool(String name, JsonNode params) throws Exception {
        McpTool tool = getTool(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return tool.executeWithFeedback(params);
    }

    /**
     * Возвращает объект реализации инструмента по его имени.
     *
     * @param name Имя инструмента.
     *
     * @return Реализация {@link McpTool} или null, если инструмент не зарегистрирован.
     */
    public McpTool getTool(String name) {
        return tools.get(name);
    }
}