// Aristo 22.12.2025
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Основной класс MCP сервера для работы с файловой системой.
 * Реализует базовый цикл обработки JSON-RPC сообщений через стандартный ввод/вывод.
 */
public class McpServer {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final boolean DEBUG = true;

    public static void main(String[] args) {
        if (DEBUG) {
            System.err.println("MCP Server starting...");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processMessage(line);
            }
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Error in server loop: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Обрабатывает входящее JSON-RPC сообщение.
     *
     * @param message Строка сообщения.
     */
    private static void processMessage(String message) {
        try {
            JsonNode request = mapper.readTree(message);
            String method = request.path("method").asText();
            JsonNode id = request.get("id");

            if (DEBUG) {
                System.err.println("Received method: " + method);
            }

            // Базовая заглушка для обработки методов
            ObjectNode response = mapper.createObjectNode();
            response.set("jsonrpc", mapper.convertValue("2.0", JsonNode.class));
            if (id != null) {
                response.set("id", id);
            }

            switch (method) {
                case "initialize" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("protocolVersion", "2024-11-05");
                    response.set("result", result);
                }
                case "tools/list" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.set("tools", mapper.createArrayNode()); // Пока пусто
                    response.set("result", result);
                }
                default -> {
                    if (id != null) {
                        ObjectNode error = mapper.createObjectNode();
                        error.put("code", -32601);
                        error.put("message", "Method not found");
                        response.set("error", error);
                    }
                }
            }

            if (id != null || response.has("result") || response.has("error")) {
                System.out.println(mapper.writeValueAsString(response));
                System.out.flush();
            }

        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Failed to process message: " + e.getMessage());
            }
        }
    }
}