// Aristo 23.12.2025
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Основной класс MCP сервера, оптимизированный для работы с виртуальными потоками Java 24.
 * Реализует протокол Model Context Protocol через стандартные потоки ввода-вывода (stdio).
 * Обеспечивает параллельную обработку запросов от LLM с поддержкой транзакционности.
 */
public class McpServer {

    /**
     * Объект для работы с JSON данными.
     */
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Центральный роутер для регистрации и вызова инструментов.
     */
    private static final McpRouter router = new McpRouter(mapper);

    /**
     * Флаг включения отладочной информации в stderr.
     */
    private static final boolean DEBUG = true;

    static {
        // Регистрация всех доступных инструментов сервера
        router.registerTool(new FileInfoTool());
        router.registerTool(new FileExistsTool());
        router.registerTool(new ListDirectoryTool());
        router.registerTool(new ReadFileTool());
        router.registerTool(new ReadFileRangesTool());
        router.registerTool(new EditFileTool());
        router.registerTool(new SearchFilesTool());
        router.registerTool(new FindFileTool());
        router.registerTool(new CreateFileTool());
        router.registerTool(new DeleteFileTool());
        router.registerTool(new MoveFileTool());
        router.registerTool(new RenameFileTool());
        router.registerTool(new FileHistoryTool());
        router.registerTool(new CompareFilesTool());
        router.registerTool(new UndoTool());
        router.registerTool(new RedoTool());
        router.registerTool(new CheckpointTool());


        router.registerTool(new TransactionJournalTool());
        router.registerTool(new GradleTool());
        router.registerTool(new GitTool());
        router.registerTool(new GitDiffTool());
        router.registerTool(new GitCommitSessionTool());
        router.registerTool(new BatchToolsTool(router));
        router.registerTool(new TaskKillTool());
        router.registerTool(new TaskLogTool());
        router.registerTool(new ProjectStructureTool());
        router.registerTool(new ProjectReplaceTool());
        router.registerTool(new TodoCreateTool());
        router.registerTool(new TodoUpdateTool());
        router.registerTool(new TodoStatusTool());
    }


    /**
     * Точка входа в приложение. Инициализирует цикл чтения команд.
     * Использует Virtual Threads для предотвращения блокировок при тяжелых IO операциях.
     *
     * @param args Аргументы командной строки (не используются).
     */
    public static void main(String[] args) {
        // Принудительно устанавливаем UTF-8 для стандартных потоков вывода, 
        // так как на Windows они по умолчанию используют системную кодировку (cp1251/866).
        // Это критически важно для передачи кириллицы в JSON-RPC сообщениях.
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));

        if (DEBUG) {
            System.err.println("MCP Server starting with Virtual Threads support...");
        }

        // Используем ExecutorService с виртуальными потоками для обработки каждого запроса.
        // Это позволяет серверу оставаться отзывчивым даже во время выполнения длительных задач.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor(); BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                final String message = line;
                // Каждый входящий JSON-RPC запрос обрабатывается в отдельном виртуальном потоке
                executor.submit(() -> processMessage(message));
            }
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Error in server loop: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Обрабатывает одиночное JSON-RPC сообщение.
     * Выполняет парсинг, маршрутизацию к соответствующему методу и формирование ответа.
     *
     * @param message Строка, содержащая JSON-RPC запрос.
     */
    private static void processMessage(String message) {
        try {
            JsonNode request = mapper.readTree(message);
            String method = request.path("method").asText();
            JsonNode id = request.get("id");

            if (DEBUG) {
                System.err.println("[" + Thread.currentThread() + "] Received method: " + method);
            }

            // Подготовка базового каркаса ответа
            ObjectNode response = mapper.createObjectNode();
            response.set("jsonrpc", mapper.convertValue("2.0", JsonNode.class));
            if (id != null) {
                response.set("id", id);
            }

            try {
                // Диспетчеризация методов MCP протокола
                switch (method) {
                    case "initialize" -> {
                        if (DEBUG) {
                            var clientInfo = request.path("params").path("clientInfo");
                            System.err.println("Client info: " + clientInfo.path("name").asText() + " (" + clientInfo.path("version").asText() + ")");
                            System.err.println("Client protocol version: " + request.path("params").path("protocolVersion").asText());
                        }

                        var result = mapper.createObjectNode();
                        result.put("protocolVersion", "2024-11-05");
                        var capabilities = result.putObject("capabilities");
                        
                        // Объявляем поддержку инструментов с возможностью уведомлений об изменениях
                        var toolsCap = capabilities.putObject("tools");
                        toolsCap.put("listChanged", false);
                        
                        capabilities.putObject("resources").put("listChanged", false).put("subscribe", false);
                        capabilities.putObject("prompts").put("listChanged", false);
                        capabilities.putObject("logging");

                        var serverInfo = result.putObject("serverInfo");
                        serverInfo.put("name", "NTS-FileSystem-MCP");
                        serverInfo.put("version", "1.1.0");
                        response.set("result", result);
                    }
                    case "notifications/initialized" -> {
                        // Уведомление об успешной инициализации клиентом. 
                        // По стандарту ответ на уведомление не отправляется.
                        if (DEBUG) {
                            System.err.println("Client initialized connection.");
                        }
                        return;
                    }
                    case "ping" -> {
                        // Обязательный метод для проверки активности сервера
                        response.set("result", mapper.createObjectNode());
                    }
                    case "resources/list" -> {
                        var res = mapper.createObjectNode();
                        res.putArray("resources");
                        response.set("result", res);
                    }
                    case "prompts/list" -> {
                        var res = mapper.createObjectNode();
                        res.putArray("prompts");
                        response.set("result", res);
                    }
                    case "tools/list" -> response.set("result", router.listTools());
                    case "tools/call" -> {
                        String toolName = request.path("params").path("name").asText();
                        JsonNode params = request.path("params").path("arguments");
                        response.set("result", router.callTool(toolName, params));
                    }
                    case "logging/setLevel" -> {
                        // Заглушка для установки уровня логирования
                        response.set("result", mapper.createObjectNode());
                    }
                    default -> {
                        if (id != null) {
                            var error = mapper.createObjectNode();
                            error.put("code", -32601);
                            error.put("message", "Method not found: " + method);
                            response.set("error", error);
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                // Ошибка неверных параметров
                if (id != null) {
                    var error = mapper.createObjectNode();
                    error.put("code", -32602);
                    error.put("message", "Invalid params: " + e.getMessage());
                    response.set("error", error);
                }
            } catch (Exception e) {
                // Обработка внутренних ошибок инструментов
                if (id != null) {
                    var error = mapper.createObjectNode();
                    error.put("code", -32603); // Internal error
                    error.put("message", "Internal error: " + e.getMessage());
                    response.set("error", error);
                }
            }

            // Отправляем ответ только если это был запрос (есть id) или есть результат/ошибка
            if (id != null || response.has("result") || response.has("error")) {
                sendResponse(response);
            }

        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Failed to process message: " + e.getMessage());
            }
        }
    }

    /**
     * Синхронизированная отправка ответа в стандартный поток вывода.
     * Синхронизация необходима, так как несколько виртуальных потоков могут пытаться писать одновременно,
     * что приведет к перемешиванию байтов в JSON сообщениях.
     *
     * @param response Объект JSON ответа.
     */
    private static synchronized void sendResponse(ObjectNode response) {
        try {
            System.out.println(mapper.writeValueAsString(response));
            System.out.flush();
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Failed to send response: " + e.getMessage());
            }
        }
    }
}