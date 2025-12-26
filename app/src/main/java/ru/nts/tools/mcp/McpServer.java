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
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;
import ru.nts.tools.mcp.tools.navigation.*;
import ru.nts.tools.mcp.tools.refactoring.CodeRefactorTool;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Основной класс MCP сервера, оптимизированный для работы с виртуальными потоками Java 24.
 * Реализует протокол Model Context Protocol через стандартные потоки ввода-вывода (stdio).
 * Обеспечивает параллельную обработку запросов от LLM с поддержкой транзакционности.
 *
 * Session Isolation:
 * Каждый LLM-клиент может использовать независимый контекст сессии.
 * Для указания сессии клиент передает _meta.sessionId в параметрах запроса.
 * Если sessionId не указан, используется default-сессия (для обратной совместимости).
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
     * ВАЖНО: Некоторые клиенты (Antigravity) объединяют stderr и stdout,
     * что приводит к ошибкам парсинга JSON. Отключаем по умолчанию.
     * Включить можно через переменную окружения MCP_DEBUG=true
     */
    private static final boolean DEBUG = "true".equalsIgnoreCase(System.getenv("MCP_DEBUG"));

    /**
     * Путь к файлу логов. Если установлен MCP_LOG_FILE, логи пишутся в файл.
     * Это безопасный способ отладки для клиентов, объединяющих stderr/stdout.
     */
    private static final String LOG_FILE = System.getenv("MCP_LOG_FILE");
    private static PrintWriter logWriter = null;

    static {
        if (LOG_FILE != null && !LOG_FILE.isBlank()) {
            try {
                logWriter = new PrintWriter(new FileWriter(LOG_FILE, true), true);
            } catch (Exception e) {
                // Ignore - logging disabled
            }
        }
    }

    /**
     * Записывает сообщение в лог-файл (если настроен).
     */
    private static void log(String message) {
        if (logWriter != null) {
            logWriter.println("[" + java.time.LocalDateTime.now() + "] " + message);
        }
        if (DEBUG) {
            System.err.println(message);
        }
    }

    /**
     * Множество известных валидных sessionId (для проверки).
     * Сессии создаются через nts_init и добавляются сюда.
     */
    private static final Set<String> validSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Счетчик для генерации уникальных ID исходящих запросов к клиенту.
     */
    private static final AtomicLong outgoingRequestId = new AtomicLong(1000);

    /**
     * Карта ожидающих ответов на запросы, отправленные сервером клиенту.
     * Key: request id, Value: CompletableFuture для получения результата.
     */
    private static final Map<Long, CompletableFuture<JsonNode>> pendingClientResponses = new ConcurrentHashMap<>();

    /**
     * Флаг, указывающий поддерживает ли клиент roots capability.
     */
    private static volatile boolean clientSupportsRoots = false;

    /**
     * Флаг, указывающий поддерживает ли клиент уведомления об изменении roots.
     */
    private static volatile boolean clientSupportsRootsListChanged = false;

    /**
     * Имя клиента (для логирования).
     */
    private static volatile String clientName = "";

    static {
        // Регистрация всех доступных инструментов сервера
        router.registerTool(new InitTool());  // MUST be first - creates sessions

        router.registerTool(new FileReadTool());
        router.registerTool(new FileManageTool());
        router.registerTool(new FileSearchTool());
        router.registerTool(new EditFileTool());
        router.registerTool(new CompareFilesTool());

        router.registerTool(new SessionTool());

        router.registerTool(new GradleTool());
        router.registerTool(new GitCombinedTool());

        router.registerTool(new BatchToolsTool(router));
        router.registerTool(new TaskTool());

        router.registerTool(new ProjectReplaceTool());
        router.registerTool(new TodoTool());

        // Code navigation with tree-sitter
        router.registerTool(new CodeNavigateTool());

        // Code refactoring with tree-sitter
        router.registerTool(new CodeRefactorTool());
    }

    /**
     * Регистрирует новую валидную сессию.
     * Вызывается из InitTool после создания сессии.
     */
    public static void registerValidSession(String sessionId) {
        validSessions.add(sessionId);
    }

    /**
     * Проверяет, является ли sessionId валидным.
     */
    public static boolean isValidSession(String sessionId) {
        return sessionId != null && validSessions.contains(sessionId);
    }

    /**
     * Отправляет запрос клиенту и возвращает Future с результатом.
     * Используется для bidirectional communication (server → client requests).
     *
     * @param method Метод запроса (например, "roots/list")
     * @param params Параметры запроса (может быть null)
     * @return CompletableFuture с результатом от клиента
     */
    public static CompletableFuture<JsonNode> sendClientRequest(String method, JsonNode params) {
        long requestId = outgoingRequestId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingClientResponses.put(requestId, future);

        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        if (DEBUG) {
            System.err.println("Sending request to client: " + method + " (id=" + requestId + ")");
        }

        sendResponse(request);
        return future;
    }

    /**
     * Запрашивает список roots у клиента.
     * Вызывается после получения notifications/initialized если клиент объявил roots capability.
     * Если клиент объявил capability - он обязан поддерживать roots/list запрос по протоколу MCP.
     */
    private static void requestRootsFromClient() {
        if (!clientSupportsRoots) {
            log("Client does not declare roots capability, skipping roots/list request");
            return;
        }

        log("Requesting roots from client '" + clientName + "'...");

        sendClientRequest("roots/list", null)
            .thenAccept(result -> {
                if (result != null && result.has("roots")) {
                    processRootsResponse(result.get("roots"));
                } else if (result != null) {
                    // Некоторые клиенты возвращают roots напрямую
                    processRootsResponse(result);
                }
            })
            .exceptionally(ex -> {
                log("Failed to get roots from client: " + ex.getMessage());
                log("Falling back to PROJECT_ROOT environment variable");
                return null;
            });
    }

    /**
     * Обрабатывает список roots, полученный от клиента.
     * Устанавливает полученные roots в PathSanitizer.
     *
     * @param rootsArray JSON массив с объектами {uri: "file://...", name: "..."}
     */
    private static void processRootsResponse(JsonNode rootsArray) {
        log("Processing roots response: " + rootsArray);

        if (rootsArray == null || !rootsArray.isArray()) {
            log("Invalid roots response: expected array, got " + (rootsArray == null ? "null" : rootsArray.getNodeType()));
            return;
        }

        List<Path> roots = new ArrayList<>();
        for (JsonNode rootNode : rootsArray) {
            String uriStr = rootNode.path("uri").asText(null);
            String name = rootNode.path("name").asText("unnamed");

            if (uriStr != null && uriStr.startsWith("file://")) {
                try {
                    URI uri = new URI(uriStr);
                    Path path = Paths.get(uri);
                    roots.add(path.toAbsolutePath().normalize());
                    log("Added root from client: " + path + " (" + name + ")");
                } catch (Exception e) {
                    log("Failed to parse root URI: " + uriStr + " - " + e.getMessage());
                }
            } else {
                log("Skipping root with invalid URI: " + uriStr);
            }
        }

        if (!roots.isEmpty()) {
            ru.nts.tools.mcp.core.PathSanitizer.setRoots(roots);
            log("PathSanitizer updated with " + roots.size() + " root(s) from client: " + roots);
        } else {
            log("No valid roots found in response");
        }
    }

    /**
     * Точка входа в приложение. Инициализирует цикл чтения команд.
     * Использует Virtual Threads для предотвращения блокировок при тяжелых IO операциях.
     *
     * @param args Аргументы командной строки.
     */
    public static void main(String[] args) {
        // Принудительно устанавливаем UTF-8 для стандартных потоков вывода, 
        // так как на Windows они по умолчанию используют системную кодировку (cp1251/866).
        // Это критически важно для передачи кириллицы в JSON-RPC сообщениях.
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));

        if (args.length > 0 && (args[0].equalsIgnoreCase("integrate") || args[0].equalsIgnoreCase("--integrate"))) {
            McpIntegrator.run();
            return;
        }

        // Пытаемся получить корень проекта из переменной окружения PROJECT_ROOT.
        // Это важно, так как при запуске через MCP клиенты (Gemini, Claude) могут иметь произвольный CWD.
        String projectRoot = System.getenv("PROJECT_ROOT");
        if (projectRoot != null && !projectRoot.isBlank()) {
            ru.nts.tools.mcp.core.PathSanitizer.setRoot(java.nio.file.Paths.get(projectRoot));
            if (DEBUG) {
                System.err.println("Project root set from PROJECT_ROOT env: " + projectRoot);
            }
        } else {
            if (DEBUG) {
                System.err.println("No PROJECT_ROOT env found, using CWD as root: " + ru.nts.tools.mcp.core.PathSanitizer.getRoot());
            }
        }

        log("MCP Server starting...");

        // Используем ExecutorService with virtual threads for processing each request.
        // Это позволяет серверу оставаться отзывчивым даже во время выполнения длительных задач.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

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
     * Устанавливает SessionContext для изоляции состояния между сессиями.
     *
     * @param message Строка, содержащая JSON-RPC запрос.
     */
    private static void processMessage(String message) {
        // Извлекаем sessionId из параметров запроса
        // Поддерживаем два способа передачи:
        // 1. arguments.sessionId - для агентов, которые не могут передавать _meta
        // 2. _meta.sessionId - для клиентов, поддерживающих метаданные MCP
        String sessionId = null;
        try {
            JsonNode request = mapper.readTree(message);
            sessionId = extractSessionId(request);
        } catch (Exception ignored) {
            // Если не удалось извлечь sessionId - будет использована default сессия
        }

        // Устанавливаем контекст сессии для текущего потока
        SessionContext ctx = SessionContext.getOrCreate(sessionId);
        SessionContext.setCurrent(ctx);

        try {
            processMessageInternal(message);
        } finally {
            // Очищаем контекст потока (но сессия остается в реестре)
            SessionContext.clearCurrent();
        }
    }

    /**
     * Извлекает sessionId из JSON-RPC запроса.
     * Проверяет два возможных местоположения:
     * 1. params.arguments.sessionId - явный параметр в аргументах инструмента
     * 2. params._meta.sessionId - метаданные MCP протокола
     *
     * @param request JSON-RPC запрос
     * @return sessionId или null, если не найден
     */
    private static String extractSessionId(JsonNode request) {
        // Сначала проверяем arguments.sessionId (приоритет для агентов)
        String fromArgs = request.path("params").path("arguments").path("sessionId").asText(null);
        if (fromArgs != null && !fromArgs.isBlank()) {
            return fromArgs;
        }

        // Затем проверяем _meta.sessionId (для MCP-клиентов)
        String fromMeta = request.path("params").path("_meta").path("sessionId").asText(null);
        if (fromMeta != null && !fromMeta.isBlank()) {
            return fromMeta;
        }

        return null;
    }

    /**
     * Внутренняя обработка сообщения после установки контекста сессии.
     */
    private static void processMessageInternal(String message) {
        try {
            JsonNode request = mapper.readTree(message);
            String method = request.path("method").asText();
            JsonNode id = request.get("id");

            // Проверяем, является ли это ответом на наш запрос к клиенту
            // (сообщение без method, но с result или error и числовым id >= 1000)
            if ((method == null || method.isEmpty()) && id != null && id.isNumber()) {
                long responseId = id.asLong();
                if (responseId >= 1000) {
                    CompletableFuture<JsonNode> future = pendingClientResponses.remove(responseId);
                    if (future != null) {
                        if (request.has("error")) {
                            JsonNode error = request.get("error");
                            if (DEBUG) {
                                System.err.println("Received error response for request " + responseId + ": " + error);
                            }
                            future.completeExceptionally(new RuntimeException(error.path("message").asText("Unknown error")));
                        } else {
                            JsonNode result = request.get("result");
                            if (DEBUG) {
                                System.err.println("Received response for request " + responseId);
                            }
                            future.complete(result);
                        }
                        return;
                    }
                }
            }

            if (DEBUG) {
                SessionContext ctx = SessionContext.current();
                String sid = ctx != null ? ctx.getSessionId() : "none";
                System.err.println("[" + Thread.currentThread() + "] [Session: " + sid + "] Received method: " + method);
            }

            // Подготовка базового каркаса ответа
            ObjectNode response = mapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            if (id != null) {
                response.set("id", id);
            }

            try {
                // Диспетчеризация методов MCP протокола
                switch (method) {
                    case "initialize" -> {
                        var clientInfo = request.path("params").path("clientInfo");
                        clientName = clientInfo.path("name").asText("unknown").toLowerCase();
                        if (DEBUG) {
                            System.err.println("Client info: " + clientInfo.path("name").asText() + " (" + clientInfo.path("version").asText() + ")");
                            System.err.println("Client protocol version: " + request.path("params").path("protocolVersion").asText());
                        }

                        // Парсим client capabilities для определения поддержки roots
                        JsonNode clientCapabilities = request.path("params").path("capabilities");
                        JsonNode rootsCapability = clientCapabilities.path("roots");
                        if (!rootsCapability.isMissingNode() && !rootsCapability.isNull()) {
                            clientSupportsRoots = true;
                            clientSupportsRootsListChanged = rootsCapability.path("listChanged").asBoolean(false);
                            if (DEBUG) {
                                System.err.println("Client declares roots capability (listChanged=" + clientSupportsRootsListChanged + ")");
                            }
                        } else {
                            clientSupportsRoots = false;
                            clientSupportsRootsListChanged = false;
                            if (DEBUG) {
                                System.err.println("Client does not declare roots capability");
                            }
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

                        // Запрашиваем roots у клиента, если он их поддерживает
                        if (clientSupportsRoots) {
                            requestRootsFromClient();
                        }
                        return;
                    }
                    case "notifications/roots/list_changed" -> {
                        // Клиент уведомляет об изменении списка roots
                        if (DEBUG) {
                            System.err.println("Client notified about roots list change, requesting updated roots...");
                        }
                        requestRootsFromClient();
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
                        // Извлекаем sessionId из arguments или _meta
                        String sessionId = extractSessionId(request);

                        // Проверяем, требует ли инструмент сессию
                        boolean requiresSession = router.toolRequiresSession(toolName);

                        if (requiresSession) {
                            // Проверяем наличие и валидность sessionId
                            if (sessionId == null || sessionId.isBlank()) {
                                throw new IllegalStateException(
                                    "NO_SESSION: This tool requires a valid session ID. " +
                                    "Call nts_init first to create a session, then pass the returned sessionId " +
                                    "in the 'sessionId' parameter for all subsequent requests.");
                            }
                            if (!isValidSession(sessionId)) {
                                throw new IllegalStateException(
                                    "INVALID_SESSION: Session ID '" + sessionId + "' is not recognized. " +
                                    "The session may have expired or never existed. " +
                                    "Call nts_init to create a new session.");
                            }
                        }

                        // Устанавливаем контекст сессии (для nts_init будет создан новый)
                        SessionContext ctx = SessionContext.current();
                        if (ctx != null) {
                            ctx.setCurrentToolName(toolName);
                        }
                        try {
                            response.set("result", router.callTool(toolName, params));
                        } finally {
                            if (ctx != null) {
                                ctx.setCurrentToolName(null);
                            }
                        }
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
                log("IllegalArgumentException: " + e.getMessage());
                if (id != null) {
                    var error = mapper.createObjectNode();
                    error.put("code", -32602);
                    error.put("message", "Invalid params: " + e.getMessage());
                    response.set("error", error);
                }
            } catch (Exception e) {
                // Обработка внутренних ошибок инструментов
                log("Exception in tool: " + e.getClass().getName() + ": " + e.getMessage());
                if (logWriter != null) {
                    e.printStackTrace(logWriter);
                }
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
            log("Failed to process message: " + e.getMessage());
            if (logWriter != null) {
                e.printStackTrace(logWriter);
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
            String json = mapper.writeValueAsString(response);
            log(">>> SEND: " + json);
            System.out.print(json + "\n");
            System.out.flush();
        } catch (Exception e) {
            log("Failed to send response: " + e.getMessage());
        }
    }
}
