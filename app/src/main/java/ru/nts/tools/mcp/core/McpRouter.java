// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Роутер для управления инструментами и маршрутизации запросов.
 */
public class McpRouter {
    private final Map<String, McpTool> tools = new HashMap<>();
    private final ObjectMapper mapper;

    public McpRouter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Регистрирует новый инструмент.
     */
    public void registerTool(McpTool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * Возвращает список всех доступных инструментов в формате MCP.
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
     * Вызывает инструмент по имени.
     */
    public JsonNode callTool(String name, JsonNode params) throws Exception {
        McpTool tool = getTool(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not found: " + name);
        }
        return tool.execute(params);
    }

    /**
     * Возвращает инструмент по его имени.
     */
    public McpTool getTool(String name) {
        return tools.get(name);
    }
}
