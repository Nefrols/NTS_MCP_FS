// Aristo 25.12.2025
package ru.nts.tools.mcp.tools.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import ru.nts.tools.mcp.core.McpRouter;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.TransactionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Инструмент-оркестратор для пакетного выполнения различных инструментов MCP в рамках одной транзакции.
 * Позволяет объединять логически связанные действия (например, переименование класса и последующая правка его контента)
 * в единый атомарный блок. Если любой инструмент в цепочке вернет ошибку, все предыдущие действия будут откатаны.
 */
public class BatchToolsTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ссылка на роутер сервера для выполнения вложенных вызовов инструментов.
     */
    private final McpRouter router;

    /**
     * Создает новый инструмент пакетного выполнения.
     *
     * @param router Роутер, содержащий реестр всех доступных инструментов.
     */
    public BatchToolsTool(McpRouter router) {
        this.router = router;
    }

    @Override
    public String getName() {
        return "nts_batch_tools";
    }

    @Override
    public String getDescription() {
        return """
            Atomic transaction orchestrator - execute multiple tools as single operation.

            KEY FEATURE: All-or-nothing execution
            • If ANY action fails → ALL previous actions rolled back
            • Perfect for complex workflows requiring consistency

            VARIABLE INTERPOLATION (token passing between steps):
            Use {{stepN.property}} to reference results from previous steps:
            • {{step1.token}}  - First LAT token from step 1 response
            • {{step1.tokens}} - All tokens as comma-separated list
            • {{step1.text}}   - Full text content of response
            • {{myId.token}}   - Reference by action 'id' field

            WORKFLOW EXAMPLE (read → edit):
            actions: [
              {id: 'read', tool: 'nts_file_read', params: {path: 'App.java', startLine: 1, endLine: 50}},
              {tool: 'nts_edit_file', params: {path: 'App.java', startLine: 10, content: 'new code', accessToken: '{{read.token}}'}}
            ]

            MULTI-FILE EXAMPLE:
            actions: [
              {id: 'cfg', tool: 'nts_file_read', params: {path: 'config.json', startLine: 1, endLine: 20}},
              {id: 'main', tool: 'nts_file_read', params: {path: 'Main.java', startLine: 1, endLine: 100}},
              {tool: 'nts_edit_file', params: {path: 'config.json', startLine: 5, content: '...', accessToken: '{{cfg.token}}'}},
              {tool: 'nts_edit_file', params: {path: 'Main.java', startLine: 20, content: '...', accessToken: '{{main.token}}'}}
            ]

            LIMITATION: Only NTS MCP tools tracked. External tools not included in rollback.
            """;
    }

    @Override
    public String getCategory() {
        return "system";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        var actions = props.putObject("actions");
        actions.put("type", "array");
        actions.put("description",
                "Ordered list of tool calls. Executed sequentially. " +
                "On any failure, all previous actions are rolled back. Required.");

        var item = actions.putObject("items");
        item.put("type", "object");
        var itemProps = item.putObject("properties");
        itemProps.putObject("id").put("type", "string").put("description",
                "Optional identifier for referencing this step's result. Use in {{id.token}} syntax. " +
                "If omitted, use {{stepN.token}} where N is 1-based index.");
        itemProps.putObject("tool").put("type", "string").put("description",
                "NTS MCP tool name: 'nts_file_read', 'nts_edit_file', 'nts_file_manage', etc.");
        itemProps.putObject("params").put("type", "object").put("description",
                "Tool parameters. Supports {{ref.token}} interpolation for values from previous steps.");

        props.putObject("instruction").put("type", "string").put("description",
                "Descriptive label for session journal. Example: 'Rename User class to Account'. " +
                "Helps identify batch in undo history.");

        schema.putArray("required").add("actions");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        JsonNode actions = params.get("actions");
        if (actions == null || !actions.isArray()) {
            throw new IllegalArgumentException("Parameter 'actions' must be an array.");
        }

        // Запуск глобальной транзакции для всей цепочки действий
        String instruction = params.has("instruction") ? params.get("instruction").asText() : null;
        TransactionManager.startTransaction("Batch Tools (" + actions.size() + " actions)", instruction);

        // Хранилище результатов для интерполяции переменных
        Map<String, StepResult> stepResults = new HashMap<>();

        try {
            ArrayNode results = mapper.createArrayNode();
            int index = 0;
            for (JsonNode action : actions) {
                index++;
                String stepKey = "step" + index;
                String actionId = action.path("id").asText(null);
                String toolName = action.path("tool").asText();
                JsonNode toolParams = action.path("params");

                // Интерполяция переменных в параметрах
                JsonNode interpolatedParams = interpolateParams(toolParams, stepResults);

                // Вызываем целевой инструмент через роутер.
                // Благодаря поддержке вложенности в TransactionManager, вызовы commit()
                // внутри этих инструментов не приведут к фиксации на диск до завершения батча.
                try {
                    JsonNode result = router.callTool(toolName, interpolatedParams);
                    // Проверяем, не вернул ли инструмент ошибку через executeWithFeedback
                    if (result.has("isError") && result.get("isError").asBoolean()) {
                        String errorMsg = result.path("content").get(0).path("text").asText("Unknown error");
                        throw new IllegalStateException(errorMsg);
                    }
                    results.add(result);

                    // Сохраняем результат для интерполяции в следующих шагах
                    StepResult stepResult = parseStepResult(result);
                    stepResults.put(stepKey, stepResult);
                    if (actionId != null && !actionId.isEmpty()) {
                        stepResults.put(actionId, stepResult);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(String.format(
                            "Batch execution failed at action #%d ('%s'). Error: %s. " +
                            "All previous actions in this batch have been rolled back.",
                            index, toolName, e.getMessage()), e);
                }
            }
            // Успешное завершение всей цепочки — фиксируем изменения
            TransactionManager.commit();

            ObjectNode response = mapper.createObjectNode();
            var content = response.putArray("content").addObject();
            content.put("type", "text");
            content.put("text", "Batch execution successful. All " + actions.size() + " actions applied atomically.");

            return response;
        } catch (Exception e) {
            // Любая ошибка (включая ошибки валидации или безопасности в любом инструменте)
            // приводит к полному откату всех изменений, сделанных в рамках этого батча.
            TransactionManager.rollback();
            throw e;
        }
    }

    // ============ Система интерполяции переменных ============

    /** Паттерн для поиска переменных вида {{stepN.property}} или {{id.property}} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)\\.([a-zA-Z0-9_]+)}}");

    /** Паттерн для извлечения LAT токенов из текста */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("LAT:[A-Za-z0-9+/=:]+");

    /**
     * Рекурсивно обрабатывает JSON-параметры, заменяя {{ref.prop}} на значения из предыдущих шагов.
     */
    private JsonNode interpolateParams(JsonNode params, Map<String, StepResult> stepResults) {
        if (params == null || params.isNull()) {
            return params;
        }

        if (params.isTextual()) {
            String text = params.asText();
            String interpolated = interpolateString(text, stepResults);
            return new TextNode(interpolated);
        }

        if (params.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            params.fields().forEachRemaining(entry -> {
                result.set(entry.getKey(), interpolateParams(entry.getValue(), stepResults));
            });
            return result;
        }

        if (params.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : params) {
                result.add(interpolateParams(item, stepResults));
            }
            return result;
        }

        // Примитивы (числа, булевы) возвращаем как есть
        return params;
    }

    /**
     * Интерполирует строку, заменяя все {{ref.prop}} на соответствующие значения.
     */
    private String interpolateString(String text, Map<String, StepResult> stepResults) {
        if (text == null || !text.contains("{{")) {
            return text;
        }

        Matcher matcher = VAR_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String ref = matcher.group(1);      // stepN или id
            String prop = matcher.group(2);     // token, tokens, text

            StepResult stepResult = stepResults.get(ref);
            if (stepResult == null) {
                throw new IllegalArgumentException(
                        "Variable reference '{{" + ref + "." + prop + "}}' not found. " +
                        "Available refs: " + stepResults.keySet());
            }

            String value = switch (prop) {
                case "token" -> stepResult.tokens.isEmpty() ? "" : stepResult.tokens.get(0);
                case "tokens" -> String.join(",", stepResult.tokens);
                case "text" -> stepResult.text;
                default -> throw new IllegalArgumentException(
                        "Unknown property '" + prop + "' in {{" + ref + "." + prop + "}}. " +
                        "Supported: token, tokens, text");
            };

            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Парсит результат выполнения инструмента, извлекая текст и токены.
     * Обрабатывает все элементы content[] (HUD в [0], результат в [1+]).
     */
    private StepResult parseStepResult(JsonNode result) {
        StringBuilder allText = new StringBuilder();
        List<String> tokens = new ArrayList<>();

        // Извлекаем текст из ВСЕХ элементов content (HUD в [0], ответ в [1+])
        JsonNode content = result.path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if (item.has("text")) {
                    String itemText = item.get("text").asText();
                    if (allText.length() > 0) {
                        allText.append("\n");
                    }
                    allText.append(itemText);

                    // Извлекаем LAT токены из каждого текстового блока
                    Matcher tokenMatcher = TOKEN_PATTERN.matcher(itemText);
                    while (tokenMatcher.find()) {
                        tokens.add(tokenMatcher.group());
                    }
                }
            }
        }

        return new StepResult(allText.toString(), tokens);
    }

    /**
     * Результат выполнения шага для интерполяции.
     */
    private record StepResult(String text, List<String> tokens) {}
}
