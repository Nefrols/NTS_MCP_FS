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
package ru.nts.tools.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;

/**
 * MCP Tool для семантического рефакторинга кода.
 * Предоставляет высокоуровневые операции рефакторинга для LLM.
 *
 * Операции:
 * - rename: переименование символа с обновлением всех ссылок
 * - generate: генерация кода (getters, setters, constructors, etc.)
 * - delete: удаление символа с обработкой ссылок
 * - wrap: обёртка кода (try-catch, if, loop)
 * - extract_method: извлечение кода в метод
 * - inline: встраивание метода/переменной
 * - change_signature: изменение сигнатуры метода
 * - move: перемещение символа в другой файл
 * - batch: пакетное выполнение операций
 */
public class CodeRefactorTool implements McpTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RefactoringEngine engine = RefactoringEngine.getInstance();

    @Override
    public String getName() {
        return "nts_code_refactor";
    }

    @Override
    public String getCategory() {
        return "refactoring";
    }

    @Override
    public String getDescription() {
        return """
                Semantic refactoring with automatic reference updates.
                USE THIS instead of nts_edit_file when renaming symbols - it finds ALL usages.

                WORKFLOW:
                1. preview=true -> review changes
                2. preview=false -> apply changes

                RENAME (most common):
                  path + symbol + newName [+ kind] [+ scope]
                  scope: file|directory|project (default: project)

                GENERATE:
                  path + symbol (class name) + what (accessors|constructor|builder|toString)

                LANGUAGES: Java, Kotlin, JS/TS/TSX, Python, Go, Rust, C/C++, C#, PHP, HTML

                OTHER: delete, wrap, extract_method, inline, change_signature, move, batch
                """;
    }

    @Override
    public JsonNode getInputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        // action
        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        ArrayNode actionEnum = action.putArray("enum");
        actionEnum.add("rename");
        actionEnum.add("generate");
        actionEnum.add("delete");
        actionEnum.add("wrap");
        actionEnum.add("extract_method");
        actionEnum.add("inline");
        actionEnum.add("change_signature");
        actionEnum.add("move");
        actionEnum.add("batch");
        action.put("description", "Operation: rename (+ newName), generate (+ what), delete, wrap, extract_method, inline, change_signature, move, batch");

        // path
        ObjectNode path = properties.putObject("path");
        path.put("type", "string");
        path.put("description", "File path (required)");

        // symbol
        ObjectNode symbol = properties.putObject("symbol");
        symbol.put("type", "string");
        symbol.put("description", "Symbol name (alternative to line/column). Use 'kind' to disambiguate if multiple matches.");

        // newName (for rename)
        ObjectNode newName = properties.putObject("newName");
        newName.put("type", "string");
        newName.put("description", "[rename] New name for the symbol");

        // scope
        ObjectNode scope = properties.putObject("scope");
        scope.put("type", "string");
        ArrayNode scopeEnum = scope.putArray("enum");
        scopeEnum.add("file");
        scopeEnum.add("directory");
        scopeEnum.add("project");
        scope.put("default", "project");
        scope.put("description", "Scope for reference search (default: project)");

        // kind (optional symbol kind filter)
        ObjectNode kind = properties.putObject("kind");
        kind.put("type", "string");
        ArrayNode kindEnum = kind.putArray("enum");
        kindEnum.add("class");
        kindEnum.add("interface");
        kindEnum.add("method");
        kindEnum.add("function");
        kindEnum.add("field");
        kindEnum.add("variable");
        kindEnum.add("parameter");
        kind.put("description", "Disambiguate symbol (e.g., class 'User' vs method 'User')");

        // line/column for positional operations
        ObjectNode line = properties.putObject("line");
        line.put("type", "integer");
        line.put("description", "Line number (1-based) for positional operations");

        ObjectNode column = properties.putObject("column");
        column.put("type", "integer");
        column.put("description", "Column number (1-based) for positional operations");

        // startLine/endLine for range operations
        ObjectNode startLine = properties.putObject("startLine");
        startLine.put("type", "integer");
        startLine.put("description", "Start line for range operations");

        ObjectNode endLine = properties.putObject("endLine");
        endLine.put("type", "integer");
        endLine.put("description", "End line for range operations");

        // what (for generate)
        ObjectNode what = properties.putObject("what");
        what.put("type", "string");
        ArrayNode whatEnum = what.putArray("enum");
        whatEnum.add("getter");
        whatEnum.add("getters");
        whatEnum.add("setter");
        whatEnum.add("setters");
        whatEnum.add("accessors");
        whatEnum.add("constructor");
        whatEnum.add("builder");
        whatEnum.add("equals_hashcode");
        whatEnum.add("toString");
        what.put("description", "What to generate (generate operation)");

        // fields (for generate)
        ObjectNode fields = properties.putObject("fields");
        fields.put("type", "array");
        ObjectNode fieldsItems = fields.putObject("items");
        fieldsItems.put("type", "string");
        fields.put("description", "Field names for code generation");

        // wrapper (for wrap)
        ObjectNode wrapper = properties.putObject("wrapper");
        wrapper.put("type", "string");
        ArrayNode wrapperEnum = wrapper.putArray("enum");
        wrapperEnum.add("try_catch");
        wrapperEnum.add("try_finally");
        wrapperEnum.add("try_with_resources");
        wrapperEnum.add("if");
        wrapperEnum.add("if_else");
        wrapperEnum.add("for");
        wrapperEnum.add("foreach");
        wrapperEnum.add("while");
        wrapperEnum.add("synchronized");
        wrapperEnum.add("custom");
        wrapper.put("description", "Wrapper type for wrap operation");

        // methodName (for extract_method)
        ObjectNode methodName = properties.putObject("methodName");
        methodName.put("type", "string");
        methodName.put("description", "Name for the extracted method");

        // targetPath (for move)
        ObjectNode targetPath = properties.putObject("targetPath");
        targetPath.put("type", "string");
        targetPath.put("description", "Target file path for move operation");

        // handleReferences (for delete)
        ObjectNode handleReferences = properties.putObject("handleReferences");
        handleReferences.put("type", "string");
        ArrayNode handleEnum = handleReferences.putArray("enum");
        handleEnum.add("comment");
        handleEnum.add("remove");
        handleEnum.add("error");
        handleReferences.put("default", "error");
        handleReferences.put("description", "How to handle references when deleting");

        // options (generic options object)
        ObjectNode options = properties.putObject("options");
        options.put("type", "object");
        options.put("description", "Additional options specific to operation");

        // operations (for batch)
        ObjectNode operations = properties.putObject("operations");
        operations.put("type", "array");
        ObjectNode opsItems = operations.putObject("items");
        opsItems.put("type", "object");
        operations.put("description", "Array of operations for batch execution");

        // preview
        ObjectNode preview = properties.putObject("preview");
        preview.put("type", "boolean");
        preview.put("default", false);
        preview.put("description", "RECOMMENDED: true first, review diff, then false to apply. Shows all affected files.");

        // hybridMode (for rename)
        ObjectNode hybridMode = properties.putObject("hybridMode");
        hybridMode.put("type", "boolean");
        hybridMode.put("default", false);
        hybridMode.put("description", "[rename] Enable hybrid refactoring: combines semantic analysis (tree-sitter) " +
                "with text-based search to find additional references that may be missed by AST parsing. " +
                "Shows confidence level for each match (SEMANTIC vs TEXT_ONLY).");

        // includeTextMatches (for rename with hybridMode)
        ObjectNode includeTextMatches = properties.putObject("includeTextMatches");
        includeTextMatches.put("type", "boolean");
        includeTextMatches.put("default", false);
        includeTextMatches.put("description", "[rename + hybridMode] Include uncertain (text-only) matches in rename. " +
                "By default only semantic matches are renamed. Set true to also rename text-only matches.");

        // instruction
        ObjectNode instruction = properties.putObject("instruction");
        instruction.put("type", "string");
        instruction.put("description", "Description for transaction journal");

        // Required fields
        ArrayNode required = schema.putArray("required");
        required.add("action");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = getRequiredString(params, "action");
        boolean preview = params.has("preview") && params.get("preview").asBoolean(false);

        try {
            RefactoringResult result = engine.execute(action, params, preview);
            return resultToJson(result);
        } catch (RefactoringException e) {
            return errorToJson(action, e);
        }
    }

    private String getRequiredString(JsonNode params, String field) {
        if (!params.has(field) || params.get(field).isNull()) {
            throw new IllegalArgumentException("Required parameter '" + field + "' is missing");
        }
        return params.get(field).asText();
    }

    private JsonNode resultToJson(RefactoringResult result) {
        ObjectNode json = MAPPER.createObjectNode();

        json.put("status", result.status().name().toLowerCase());
        json.put("action", result.action());

        if (result.summary() != null) {
            json.put("summary", result.summary());
        }

        if (result.error() != null) {
            json.put("error", result.error());
        }

        if (!result.suggestions().isEmpty()) {
            ArrayNode suggestions = json.putArray("suggestions");
            result.suggestions().forEach(suggestions::add);
        }

        if (!result.changes().isEmpty()) {
            ArrayNode changes = json.putArray("changes");
            for (RefactoringResult.FileChange change : result.changes()) {
                ObjectNode changeNode = changes.addObject();
                changeNode.put("path", change.path().toString());
                changeNode.put("occurrences", change.occurrences());

                if (change.newToken() != null) {
                    changeNode.put("newToken", change.newToken());
                }

                if (change.diff() != null) {
                    changeNode.put("diff", change.diff());
                }

                if (!change.details().isEmpty()) {
                    ArrayNode details = changeNode.putArray("details");
                    for (RefactoringResult.ChangeDetail detail : change.details()) {
                        ObjectNode detailNode = details.addObject();
                        detailNode.put("line", detail.line());
                        if (detail.column() > 0) {
                            detailNode.put("column", detail.column());
                        }
                        detailNode.put("before", detail.before());
                        detailNode.put("after", detail.after());
                    }
                }
            }
        }

        json.put("affectedFiles", result.affectedFiles());
        json.put("totalChanges", result.totalChanges());

        if (result.transactionId() != null) {
            json.put("transactionId", result.transactionId());
        }

        // Wrap in MCP content format
        ObjectNode response = MAPPER.createObjectNode();
        ArrayNode content = response.putArray("content");
        ObjectNode textContent = content.addObject();
        textContent.put("type", "text");

        // Format as readable text for LLM
        StringBuilder text = new StringBuilder();
        text.append("Refactoring: ").append(result.action()).append("\n");
        text.append("Status: ").append(result.status()).append("\n");

        if (result.summary() != null) {
            text.append("\n").append(result.summary()).append("\n");
        }

        if (result.error() != null) {
            text.append("\nError: ").append(result.error()).append("\n");
        }

        if (!result.suggestions().isEmpty()) {
            text.append("\nSuggestions:\n");
            result.suggestions().forEach(s -> text.append("  - ").append(s).append("\n"));
        }

        if (!result.changes().isEmpty()) {
            text.append("\nChanges:\n");
            for (RefactoringResult.FileChange change : result.changes()) {
                text.append("  ").append(change.path()).append(": ")
                        .append(change.occurrences()).append(" occurrence(s)\n");

                if (change.diff() != null && result.status() == RefactoringResult.Status.PREVIEW) {
                    text.append(change.diff()).append("\n");
                }
            }
        }

        if (result.transactionId() != null) {
            text.append("\nTransaction: ").append(result.transactionId()).append("\n");
        }

        textContent.put("text", text.toString());

        return response;
    }

    private JsonNode errorToJson(String action, RefactoringException e) {
        RefactoringResult errorResult = RefactoringResult.error(
                action,
                e.getMessage(),
                e.getSuggestions()
        );
        return resultToJson(errorResult);
    }
}
