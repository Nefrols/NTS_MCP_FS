// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Базовый интерфейс для реализации инструментов (Tools) в рамках Model Context Protocol.
 */
public interface McpTool {

    String getName();
    String getDescription();
    String getCategory();
    JsonNode getInputSchema();
    JsonNode execute(JsonNode params) throws Exception;

    /**
     * Обертка над execute для обеспечения информативной обратной связи при ошибках и внедрения HUD.
     */
    default JsonNode executeWithFeedback(JsonNode params) {
        JsonNode response;
        try {
            response = execute(params);
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
        }

        // Внедрение AI-HUD как отдельного элемента контента (метаданные)
        if (response instanceof ObjectNode res) {
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

        return response;
    }

    private JsonNode createErrorResponse(String type, String message) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ObjectNode res = mapper.createObjectNode();
        res.putArray("content").addObject().put("type", "text").put("text", "Error [" + type + "]: " + message);
        res.put("isError", true);
        return res;
    }
}