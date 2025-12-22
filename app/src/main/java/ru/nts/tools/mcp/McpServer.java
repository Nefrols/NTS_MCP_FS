// Aristo 22.12.2025
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.tools.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Основной класс MCP сервера.
 */
public class McpServer {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final McpRouter router = new McpRouter(mapper);
    private static final boolean DEBUG = true;

    static {
        router.registerTool(new FileInfoTool());
        router.registerTool(new ListDirectoryTool());
        router.registerTool(new ReadFileTool());
        router.registerTool(new EditFileTool());
        router.registerTool(new SearchFilesTool());
    }

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

    private static void processMessage(String message) {
        try {
            JsonNode request = mapper.readTree(message);
            String method = request.path("method").asText();
            JsonNode id = request.get("id");

            if (DEBUG) {
                System.err.println("Received method: " + method);
            }

            ObjectNode response = mapper.createObjectNode();
            response.set("jsonrpc", mapper.convertValue("2.0", JsonNode.class));
            if (id != null) {
                response.set("id", id);
            }

            try {
                switch (method) {
                    case "initialize" -> {
                        ObjectNode result = mapper.createObjectNode();
                        result.put("protocolVersion", "2024-11-05");
                        result.set("capabilities", mapper.createObjectNode()); // Добавить capabilities если нужно
                        ObjectNode serverInfo = result.putObject("serverInfo");
                        serverInfo.put("name", "L2NTS-FileSystem-MCP");
                        serverInfo.put("version", "1.0.0");
                        response.set("result", result);
                    }
                    case "tools/list" -> {
                        response.set("result", router.listTools());
                    }
                    case "tools/call" -> {
                        String toolName = request.path("params").path("name").asText();
                        JsonNode params = request.path("params").path("arguments");
                        response.set("result", router.callTool(toolName, params));
                    }
                    case "notifications/initialized" -> {
                        // Игнорируем или логгируем
                        return;
                    }
                    default -> {
                        if (id != null) {
                            ObjectNode error = mapper.createObjectNode();
                            error.put("code", -32601);
                            error.put("message", "Method not found: " + method);
                            response.set("error", error);
                        }
                    }
                }
            } catch (Exception e) {
                if (id != null) {
                    ObjectNode error = mapper.createObjectNode();
                    error.put("code", -32000);
                    error.put("message", "Internal error: " + e.getMessage());
                    response.set("error", error);
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
