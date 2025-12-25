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

            KEY FEATURES:
            • All-or-nothing: If ANY action fails → ALL rolled back
            • Session Tokens: CRC check skipped within batch (no re-read needed)
            • InfinityRange: Files created in batch have no line boundary checks

            VARIABLE INTERPOLATION (token & path passing):
            • {{step1.token}}  - First LAT token from step 1
            • {{step1.tokens}} - All tokens comma-separated
            • {{myId.token}}   - Reference by action 'id'
            • {{myId.path}}    - Current file path (auto-updates after rename/move!)

            SESSION REFERENCES (path tracking after rename/move):
            Use {{id.path}} to reference a file that was renamed/moved:
            actions: [
              {id: 'f', tool: 'nts_file_manage', params: {action: 'create', path: 'Old.java', ...}},
              {tool: 'nts_file_manage', params: {action: 'rename', path: '{{f.path}}', newName: 'New.java'}},
              {tool: 'nts_edit_file', params: {path: '{{f.path}}', ...}}  // ← auto-resolves to 'New.java'!
            ]

            SMART LINE ADDRESSING (Virtual FS Context):
            For startLine/endLine, use special values that auto-calculate:
            • $LAST           - Last line of the file
            • $PREV_END       - End line of previous edit on this file
            • $PREV_END+N     - N lines after previous edit (e.g., $PREV_END+1)

            EXAMPLE (create + rename + edit):
            actions: [
              {id: 'svc', tool: 'nts_file_manage', params: {action: 'create', path: 'Service.java', content: 'class Service {}'}},
              {tool: 'nts_file_manage', params: {action: 'rename', path: '{{svc.path}}', newName: 'UserService.java'}},
              {tool: 'nts_edit_file', params: {path: '{{svc.path}}', startLine: 1, content: 'class UserService {}', accessToken: '{{svc.token}}'}}
            ]

            LIMITATION: Only NTS MCP tools tracked in rollback.
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

        // Virtual FS Context: отслеживание состояния файлов для умной адресации
        Map<String, FileState> fileStates = new HashMap<>();

        try {
            ArrayNode results = mapper.createArrayNode();
            int index = 0;
            for (JsonNode action : actions) {
                index++;
                String stepKey = "step" + index;
                String actionId = action.path("id").asText(null);
                String toolName = action.path("tool").asText();
                JsonNode toolParams = action.path("params");

                // Интерполяция переменных и умная адресация
                JsonNode interpolatedParams = interpolateParams(toolParams, stepResults, fileStates);

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
                    StepResult stepResult = parseStepResult(result, interpolatedParams);
                    stepResults.put(stepKey, stepResult);
                    if (actionId != null && !actionId.isEmpty()) {
                        stepResults.put(actionId, stepResult);
                    }

                    // Session References: обновляем path при rename/move
                    updatePathAfterRenameMove(toolName, interpolatedParams, stepResults);

                    // Virtual FS Context: обновляем состояние файла после операции
                    updateFileState(toolName, interpolatedParams, stepResult, fileStates);

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

    /** Паттерн для извлечения количества строк из ответа */
    private static final Pattern LINES_PATTERN = Pattern.compile("Lines?:\\s*(\\d+)|LINES:\\s*\\d+-\\d+\\s+of\\s+(\\d+)");

    /** Паттерн для умной адресации: $LAST, $PREV_END, $PREV_END+N */
    private static final Pattern SMART_LINE_PATTERN = Pattern.compile("\\$(LAST|PREV_END)(?:\\+(\\d+))?");

    /**
     * Рекурсивно обрабатывает JSON-параметры, заменяя переменные и умную адресацию.
     */
    private JsonNode interpolateParams(JsonNode params, Map<String, StepResult> stepResults, Map<String, FileState> fileStates) {
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

            // Извлекаем path для умной адресации строк
            String filePath = params.path("path").asText(null);
            FileState state = filePath != null ? fileStates.get(normalizePathKey(filePath)) : null;

            params.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();

                // Умная адресация для startLine и endLine
                if (("startLine".equals(key) || "endLine".equals(key) || "line".equals(key)) && value.isTextual()) {
                    int resolved = resolveSmartLine(value.asText(), state);
                    result.put(key, resolved);
                } else {
                    result.set(key, interpolateParams(value, stepResults, fileStates));
                }
            });
            return result;
        }

        if (params.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : params) {
                result.add(interpolateParams(item, stepResults, fileStates));
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
                case "path" -> stepResult.path != null ? stepResult.path : "";
                default -> throw new IllegalArgumentException(
                        "Unknown property '" + prop + "' in {{" + ref + "." + prop + "}}. " +
                        "Supported: token, tokens, text, path");
            };

            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Парсит результат выполнения инструмента, извлекая текст, токены и путь.
     * Обрабатывает все элементы content[] (HUD в [0], результат в [1+]).
     *
     * @param result ответ инструмента
     * @param params интерполированные параметры вызова (для извлечения path)
     */
    private StepResult parseStepResult(JsonNode result, JsonNode params) {
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

        // Извлекаем путь из параметров
        String path = params.path("path").asText(null);

        return new StepResult(allText.toString(), tokens, path);
    }

    /**
     * Обновляет path во всех StepResult, которые ссылались на переименованный/перемещённый файл.
     * Вызывается после успешного выполнения rename/move.
     *
     * @param toolName имя инструмента
     * @param params параметры вызова (уже интерполированные)
     * @param stepResults хранилище результатов шагов
     */
    private void updatePathAfterRenameMove(String toolName, JsonNode params, Map<String, StepResult> stepResults) {
        if (!"nts_file_manage".equals(toolName)) {
            return;
        }

        String action = params.path("action").asText("");
        String oldPath = params.path("path").asText(null);
        if (oldPath == null) return;

        String newPath = null;

        if ("rename".equals(action)) {
            String newName = params.path("newName").asText(null);
            if (newName != null) {
                // Вычисляем новый путь: заменяем имя файла в oldPath
                int lastSep = Math.max(oldPath.lastIndexOf('/'), oldPath.lastIndexOf('\\'));
                if (lastSep >= 0) {
                    newPath = oldPath.substring(0, lastSep + 1) + newName;
                } else {
                    newPath = newName;
                }
            }
        } else if ("move".equals(action)) {
            newPath = params.path("targetPath").asText(null);
        }

        if (newPath == null) return;

        // Обновляем path во всех StepResult, которые ссылались на oldPath
        String normalizedOld = normalizePathKey(oldPath);
        for (StepResult sr : stepResults.values()) {
            if (sr.path != null && normalizePathKey(sr.path).equals(normalizedOld)) {
                sr.path = newPath;
            }
        }
    }

    /**
     * Результат выполнения шага для интерполяции.
     * @param text полный текст ответа
     * @param tokens список LAT токенов
     * @param path текущий путь к файлу (обновляется при rename/move)
     */
    private static class StepResult {
        final String text;
        final List<String> tokens;
        String path; // Мутабельный: обновляется при rename/move

        StepResult(String text, List<String> tokens, String path) {
            this.text = text;
            this.tokens = tokens;
            this.path = path;
        }
    }

    // ============ Virtual FS Context: умная адресация строк ============

    /**
     * Состояние файла для отслеживания изменений в рамках батча.
     * @param lineCount текущее количество строк в файле
     * @param lastEditEndLine номер последней затронутой строки после последней правки
     */
    private record FileState(int lineCount, int lastEditEndLine) {}

    /**
     * Нормализует путь для использования как ключ в Map.
     * Приводит к нижнему регистру и заменяет обратные слеши на прямые.
     */
    private String normalizePathKey(String path) {
        if (path == null) return "";
        return path.toLowerCase().replace('\\', '/');
    }

    /**
     * Разрешает умную адресацию строк: $LAST, $PREV_END, $PREV_END+N.
     *
     * @param expr выражение (например, "$LAST", "$PREV_END+1")
     * @param state текущее состояние файла (может быть null)
     * @return вычисленный номер строки
     */
    private int resolveSmartLine(String expr, FileState state) {
        if (expr == null || expr.isEmpty()) {
            throw new IllegalArgumentException("Empty line expression");
        }

        Matcher matcher = SMART_LINE_PATTERN.matcher(expr);
        if (!matcher.matches()) {
            // Если не совпадает с паттерном, пробуем как число
            try {
                return Integer.parseInt(expr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid line expression: '" + expr + "'. " +
                        "Expected: number, $LAST, $PREV_END, or $PREV_END+N");
            }
        }

        String keyword = matcher.group(1);  // LAST или PREV_END
        String offsetStr = matcher.group(2); // число после + (может быть null)
        int offset = offsetStr != null ? Integer.parseInt(offsetStr) : 0;

        if (state == null) {
            throw new IllegalArgumentException(
                    "Cannot use '" + expr + "' - no previous operation on this file in batch. " +
                    "Use numeric line number for the first operation.");
        }

        return switch (keyword) {
            case "LAST" -> state.lineCount + offset;
            case "PREV_END" -> state.lastEditEndLine + offset;
            default -> throw new IllegalArgumentException("Unknown keyword: $" + keyword);
        };
    }

    /**
     * Обновляет состояние файла после выполнения операции.
     * Отслеживает количество строк и позицию последней правки.
     */
    private void updateFileState(String toolName, JsonNode params, StepResult result, Map<String, FileState> fileStates) {
        String path = params.path("path").asText(null);
        if (path == null) return;

        String key = normalizePathKey(path);
        String text = result.text;

        // Извлекаем информацию о файле из ответа
        int lineCount = extractLineCount(text);
        int lastEditEnd = extractLastEditEnd(params, text, fileStates.get(key));

        if (lineCount > 0 || lastEditEnd > 0) {
            // Если не удалось извлечь lineCount, используем предыдущее значение или lastEditEnd
            FileState oldState = fileStates.get(key);
            int finalLineCount = lineCount > 0 ? lineCount :
                    (oldState != null ? oldState.lineCount : lastEditEnd);
            int finalLastEnd = lastEditEnd > 0 ? lastEditEnd :
                    (oldState != null ? oldState.lastEditEndLine : 1);

            fileStates.put(key, new FileState(finalLineCount, finalLastEnd));
        }
    }

    /**
     * Извлекает количество строк из ответа инструмента.
     */
    private int extractLineCount(String text) {
        if (text == null) return 0;

        // Паттерн: "Lines: 42" или "LINES: 1-10 of 42"
        Matcher matcher = LINES_PATTERN.matcher(text);
        if (matcher.find()) {
            String g1 = matcher.group(1);
            String g2 = matcher.group(2);
            if (g1 != null) return Integer.parseInt(g1);
            if (g2 != null) return Integer.parseInt(g2);
        }

        // Альтернативный паттерн для create: "Lines: 5 |"
        Pattern altPattern = Pattern.compile("Lines:\\s*(\\d+)\\s*\\|");
        Matcher altMatcher = altPattern.matcher(text);
        if (altMatcher.find()) {
            return Integer.parseInt(altMatcher.group(1));
        }

        return 0;
    }

    /**
     * Вычисляет позицию последней затронутой строки после операции.
     */
    private int extractLastEditEnd(JsonNode params, String text, FileState prevState) {
        // Для edit операций берем endLine из параметров
        if (params.has("endLine")) {
            int endLine = params.get("endLine").asInt();
            // Учитываем дельту от вставки/удаления строк
            if (params.has("content")) {
                String content = params.get("content").asText("");
                int newLines = content.split("\n", -1).length;
                String operation = params.path("operation").asText("replace");

                if ("insert_after".equals(operation) || "insert_before".equals(operation)) {
                    // При вставке endLine смещается на количество вставленных строк
                    return endLine + newLines;
                } else {
                    // При замене endLine = startLine + newLines - 1
                    int startLine = params.path("startLine").asInt(endLine);
                    return startLine + newLines - 1;
                }
            }
            return endLine;
        }

        // Для create операций возвращаем количество строк
        if (params.has("content")) {
            String content = params.get("content").asText("");
            return content.isEmpty() ? 1 : content.split("\n", -1).length;
        }

        // По умолчанию используем предыдущее значение
        return prevState != null ? prevState.lastEditEndLine : 1;
    }
}
