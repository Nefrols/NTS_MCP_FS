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
package ru.nts.tools.mcp.tools.refactoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.MethodSignatureAST;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.ParameterInfoAST;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Операция изменения сигнатуры метода.
 * Позволяет добавлять, удалять, переименовывать и переупорядочивать параметры,
 * менять тип возврата и модификаторы доступа.
 */
public class ChangeSignatureOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "change_signature";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required");
        }
        if (!params.has("methodName") && !params.has("symbol") && !params.has("line")) {
            throw new IllegalArgumentException("Either 'methodName', 'symbol', or 'line' is required");
        }
        // Должен быть хотя бы один параметр изменения
        // Проверяем как в params, так и в params.options (LLM может передать параметры в options)
        JsonNode options = params.has("options") ? params.get("options") : null;
        boolean hasChange = params.has("newName") || params.has("parameters") ||
                params.has("returnType") || params.has("accessModifier");
        if (!hasChange && options != null) {
            hasChange = options.has("newName") || options.has("parameters") ||
                    options.has("returnType") || options.has("accessModifier");
        }
        if (!hasChange) {
            throw new IllegalArgumentException(
                    "At least one change is required: newName, parameters, returnType, or accessModifier");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        // Находим метод
        SymbolInfo method = findMethod(params, path, context);
        if (method == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("methodName") ? params.get("methodName").asText() : "method",
                    path.toString());
        }

        // Парсим текущую сигнатуру используя AST
        SignatureInfo currentSignature = parseSignature(method, path, langId, context);

        // Строим новую сигнатуру
        SignatureInfo newSignature = buildNewSignature(currentSignature, params);

        // REPORT4 Fix 1.1: Находим все ссылки с разделением на реализации и вызовы
        SeparatedReferences allRefs = findAllReferences(method, path, scope, context);

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Change signature of '" + method.name() + "'";
        context.beginTransaction(instruction);

        try {
            List<RefactoringResult.FileChange> changes = new ArrayList<>();

            // 1. Обновляем оригинальное объявление метода
            RefactoringResult.FileChange declChange = updateDeclaration(
                    method, currentSignature, newSignature, path, langId, context);
            changes.add(declChange);

            // 2. REPORT4 Fix 1.1: Обновляем все реализации (@Override) как declarations
            for (SymbolInfo impl : allRefs.implementations()) {
                try {
                    SignatureInfo implSignature = parseSignature(impl, impl.location().path(), langId, context);
                    RefactoringResult.FileChange implChange = updateDeclaration(
                            impl, implSignature, newSignature, impl.location().path(), langId, context);
                    changes.add(implChange);
                } catch (Exception e) {
                    // Продолжаем с другими реализациями
                    System.err.println("[CHANGE_SIGNATURE] Failed to update implementation at " +
                            impl.location().path() + ":" + impl.location().startLine() + " - " + e.getMessage());
                }
            }

            // 3. Обновляем все вызовы
            Map<Path, List<Location>> callsByFile = allRefs.callSites().stream()
                    .collect(Collectors.groupingBy(Location::path));

            for (Map.Entry<Path, List<Location>> entry : callsByFile.entrySet()) {
                Path filePath = entry.getKey();
                List<Location> fileCalls = entry.getValue();

                RefactoringResult.FileChange callChange = updateCallSites(
                        fileCalls, currentSignature, newSignature, filePath, langId, context);
                if (callChange != null) {
                    changes.add(callChange);
                }
            }

            String txId = context.commitTransaction();

            int totalChanges = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            int implCount = allRefs.implementations().size();
            int callCount = allRefs.callSites().size();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("change_signature")
                    .summary(String.format("Changed signature of '%s': 1 declaration, %d implementation(s), %d call site(s)",
                            newSignature.name, implCount, callCount))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalChanges)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Change signature failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        SymbolInfo method = findMethod(params, path, context);
        if (method == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("methodName") ? params.get("methodName").asText() : "method",
                    path.toString());
        }

        SignatureInfo currentSignature = parseSignature(method, path, langId, context);
        SignatureInfo newSignature = buildNewSignature(currentSignature, params);

        // REPORT4 Fix 1.1: Используем разделённые ссылки
        SeparatedReferences allRefs = findAllReferences(method, path, scope, context);

        // Генерируем preview
        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        StringBuilder diff = new StringBuilder();
        diff.append("=== Signature Change ===\n");
        diff.append("-").append(formatSignature(currentSignature, langId)).append("\n");
        diff.append("+").append(formatSignature(newSignature, langId)).append("\n");

        // Показываем реализации (@Override)
        if (!allRefs.implementations().isEmpty()) {
            diff.append("\n=== Implementations (").append(allRefs.implementations().size()).append(") ===\n");
            for (SymbolInfo impl : allRefs.implementations()) {
                diff.append("  @Override ").append(impl.location().path().getFileName())
                        .append(":").append(impl.location().startLine()).append("\n");
            }
        }

        // Показываем вызовы
        if (!allRefs.callSites().isEmpty()) {
            diff.append("\n=== Call Sites (").append(allRefs.callSites().size()).append(") ===\n");
            for (Location call : allRefs.callSites()) {
                diff.append("  ").append(call.path().getFileName())
                        .append(":").append(call.startLine()).append("\n");
            }
        }

        int totalOccurrences = 1 + allRefs.implementations().size() + allRefs.callSites().size();
        changes.add(new RefactoringResult.FileChange(
                path, totalOccurrences,
                List.of(new RefactoringResult.ChangeDetail(
                        method.location().startLine(), 0,
                        formatSignature(currentSignature, langId),
                        formatSignature(newSignature, langId))),
                null, diff.toString()));

        return RefactoringResult.preview("change_signature", changes);
    }

    private SymbolInfo findMethod(JsonNode params, Path path, RefactoringContext context)
            throws RefactoringException {
        try {
            // Поддержка как methodName, так и symbol (унификация с CodeRefactorTool schema)
            String methodName = null;
            if (params.has("methodName")) {
                methodName = params.get("methodName").asText();
            } else if (params.has("symbol")) {
                methodName = params.get("symbol").asText();
            }

            if (methodName != null) {
                final String searchName = methodName;
                List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
                return symbols.stream()
                        .filter(s -> s.name().equals(searchName))
                        .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.FUNCTION)
                        .findFirst()
                        .orElse(null);
            } else if (params.has("line")) {
                int line = params.get("line").asInt();
                int column = params.has("column") ? params.get("column").asInt() : 1;
                return context.getSymbolResolver().hover(path, line, column)
                        .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.FUNCTION)
                        .orElse(null);
            }
            return null;
        } catch (IOException e) {
            throw new RefactoringException("Failed to find method: " + e.getMessage(), e);
        }
    }

    /**
     * Парсит текущую сигнатуру метода используя AST.
     */
    private SignatureInfo parseSignature(SymbolInfo method, Path path, String langId,
                                          RefactoringContext context) throws RefactoringException {
        try {
            String content = Files.readString(path);

            // Получаем AST узел метода
            TSNode methodNode = findMethodNode(method, path, content, context, langId);

            if (methodNode != null && !methodNode.isNull()) {
                // Используем AST-извлечение
                MethodSignatureAST astSig = SymbolExtractorUtils.extractMethodSignatureAST(
                        methodNode, content, langId);

                if (astSig != null && astSig.name() != null) {
                    // Конвертируем AST-сигнатуру в наш формат
                    List<ParameterInfo> params = astSig.parameters().stream()
                            .map(p -> new ParameterInfo(p.name(), p.type(), p.defaultValue(), p.isVarargs()))
                            .toList();

                    String originalLine = extractOriginalLine(content, method.location().startLine());

                    return new SignatureInfo(
                            astSig.name(),
                            astSig.accessModifier(),
                            astSig.returnType(),
                            new ArrayList<>(params),
                            originalLine
                    );
                }
            }

            // Fallback на regex парсинг если AST не сработал
            String[] lines = content.split("\n", -1);
            int lineIndex = method.location().startLine() - 1;
            if (lineIndex < 0 || lineIndex >= lines.length) {
                throw new RefactoringException("Method line out of range");
            }
            return parseSignatureLineRegex(lines[lineIndex], method.name(), langId);

        } catch (IOException e) {
            throw new RefactoringException("Failed to read file: " + e.getMessage(), e);
        }
    }

    /**
     * Находит AST узел метода по позиции.
     */
    private TSNode findMethodNode(SymbolInfo method, Path path, String content,
                                   RefactoringContext context, String langId) {
        try {
            TSTree tree = context.getTreeManager().parse(content, langId);
            if (tree == null) return null;

            TSNode root = tree.getRootNode();
            int targetLine = method.location().startLine() - 1; // 0-based для tree-sitter
            int targetColumn = method.location().startColumn() - 1;

            return findMethodNodeAtPosition(root, targetLine, targetColumn);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Рекурсивно ищет узел метода в указанной позиции.
     */
    private TSNode findMethodNodeAtPosition(TSNode node, int line, int column) {
        if (node == null || node.isNull()) return null;

        String nodeType = node.getType();
        int startLine = node.getStartPoint().getRow();
        int startCol = node.getStartPoint().getColumn();

        // Проверяем, является ли этот узел методом в нужной позиции
        if (isMethodNode(nodeType) && startLine == line) {
            return node;
        }

        // Рекурсивно проверяем дочерние узлы
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                int childStartLine = child.getStartPoint().getRow();
                int childEndLine = child.getEndPoint().getRow();

                if (line >= childStartLine && line <= childEndLine) {
                    TSNode result = findMethodNodeAtPosition(child, line, column);
                    if (result != null) return result;
                }
            }
        }

        return null;
    }

    /**
     * Проверяет, является ли тип узла методом или функцией.
     */
    private boolean isMethodNode(String nodeType) {
        return nodeType.equals("method_declaration") ||
               nodeType.equals("function_declaration") ||
               nodeType.equals("function_definition") ||
               nodeType.equals("function_item") ||
               nodeType.equals("method_definition") ||
               nodeType.equals("arrow_function") ||
               nodeType.equals("function_expression");
    }

    /**
     * Извлекает оригинальную строку из контента.
     */
    private String extractOriginalLine(String content, int lineNumber) {
        String[] lines = content.split("\n", -1);
        int idx = lineNumber - 1;
        return (idx >= 0 && idx < lines.length) ? lines[idx] : "";
    }

    /**
     * Парсит строку с сигнатурой метода (fallback regex версия).
     */
    private SignatureInfo parseSignatureLineRegex(String line, String methodName, String langId) {
        String accessModifier = "public";
        String returnType = "void";
        List<ParameterInfo> parameters = new ArrayList<>();

        if (langId.equals("java") || langId.equals("kotlin")) {
            // Паттерн: [пробелы] [модификатор] [тип] имя(параметры)
            Pattern sigPattern = Pattern.compile(
                    "^\\s*(public|private|protected|internal)?\\s*" +
                            "(static\\s+)?" +
                            "(?:fun\\s+)?" + // Kotlin
                            "([\\w<>\\[\\],]+)\\s+" +
                            Pattern.quote(methodName) +
                            "\\s*\\(([^)]*)\\)", Pattern.MULTILINE);
            Matcher matcher = sigPattern.matcher(line);

            if (matcher.find()) {
                if (matcher.group(1) != null) {
                    accessModifier = matcher.group(1);
                }
                returnType = matcher.group(3).trim();
                String paramsStr = matcher.group(4).trim();
                parameters = parseParameters(paramsStr, langId);
            }
        } else if (langId.equals("python")) {
            // Python: def name(self, param1, param2):
            Pattern sigPattern = Pattern.compile(
                    "def\\s+" + Pattern.quote(methodName) + "\\s*\\(([^)]*)\\)");
            Matcher matcher = sigPattern.matcher(line);
            if (matcher.find()) {
                String paramsStr = matcher.group(1).trim();
                parameters = parseParameters(paramsStr, langId);
            }
            accessModifier = methodName.startsWith("_") ? "private" : "public";
            returnType = ""; // Python doesn't have explicit return type in def
        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            // JS/TS: [async] name(params) или function name(params)
            Pattern sigPattern = Pattern.compile(
                    "(?:async\\s+)?(?:function\\s+)?" +
                            Pattern.quote(methodName) +
                            "\\s*\\(([^)]*)\\)(?:\\s*:\\s*([\\w<>\\[\\]]+))?");
            Matcher matcher = sigPattern.matcher(line);
            if (matcher.find()) {
                String paramsStr = matcher.group(1).trim();
                parameters = parseParameters(paramsStr, langId);
                if (matcher.group(2) != null) {
                    returnType = matcher.group(2);
                }
            }
        }

        return new SignatureInfo(methodName, accessModifier, returnType, parameters, line);
    }

    /**
     * Парсит список параметров с учётом вложенных скобок в generic типах.
     */
    private List<ParameterInfo> parseParameters(String paramsStr, String langId) {
        List<ParameterInfo> params = new ArrayList<>();
        if (paramsStr.isEmpty()) {
            return params;
        }

        // Разбиваем по запятым с учётом вложенных скобок
        List<String> parts = splitByCommaBalanced(paramsStr);
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.equals("self") || trimmed.equals("this")) {
                continue;
            }

            ParameterInfo param = parseParameter(trimmed, langId);
            if (param != null) {
                params.add(param);
            }
        }

        return params;
    }

    /**
     * Разбивает строку по запятым с учётом вложенных скобок.
     */
    private List<String> splitByCommaBalanced(String str) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '<' || c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == '>' || c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Парсит один параметр.
     */
    private ParameterInfo parseParameter(String param, String langId) {
        if (langId.equals("java") || langId.equals("kotlin")) {
            // Java: Type name или Type... name
            // Поддержка сложных типов: Map<String, List<Integer>> name
            Pattern pattern = Pattern.compile("([\\w<>\\[\\].,?\\s]+?)\\s+(\\w+)\\s*$");
            Matcher matcher = pattern.matcher(param.trim());
            if (matcher.find()) {
                String type = matcher.group(1).trim();
                String name = matcher.group(2).trim();
                return new ParameterInfo(name, type, null);
            }
        } else if (langId.equals("python")) {
            // Python: name или name: type или name=default
            Pattern pattern = Pattern.compile("(\\w+)(?:\\s*:\\s*([\\w\\[\\]]+))?(?:\\s*=\\s*(.+))?");
            Matcher matcher = pattern.matcher(param);
            if (matcher.find()) {
                String name = matcher.group(1);
                String type = matcher.group(2) != null ? matcher.group(2) : "Any";
                String defaultVal = matcher.group(3);
                return new ParameterInfo(name, type, defaultVal);
            }
        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            // TS: name: type или name: type = default
            Pattern pattern = Pattern.compile("(\\w+)(?:\\s*:\\s*([\\w<>\\[\\]]+))?(?:\\s*=\\s*(.+))?");
            Matcher matcher = pattern.matcher(param);
            if (matcher.find()) {
                String name = matcher.group(1);
                String type = matcher.group(2) != null ? matcher.group(2) : "any";
                String defaultVal = matcher.group(3);
                return new ParameterInfo(name, type, defaultVal);
            }
        }

        // Fallback: просто имя
        return new ParameterInfo(param.trim(), "Object", null);
    }

    /**
     * Строит новую сигнатуру на основе изменений.
     * Параметры ищутся как напрямую в params, так и в params.options для совместимости с разными форматами.
     */
    private SignatureInfo buildNewSignature(SignatureInfo current, JsonNode params) {
        // Получаем options если есть (для совместимости с CodeRefactorTool schema)
        JsonNode options = params.has("options") ? params.get("options") : null;

        // Ищем newName: сначала в params, затем в options
        String newName = getParamOrOption(params, options, "newName", current.name);

        // Ищем accessModifier: сначала в params, затем в options
        String newAccessModifier = getParamOrOption(params, options, "accessModifier", current.accessModifier);

        // Ищем returnType: сначала в params, затем в options
        String newReturnType = getParamOrOption(params, options, "returnType", current.returnType);

        // Ищем parameters: сначала в params, затем в options
        List<ParameterInfo> newParams;
        JsonNode parametersNode = getNodeFromParamsOrOptions(params, options, "parameters");
        if (parametersNode != null) {
            newParams = buildNewParameters(current.parameters, parametersNode);
        } else {
            newParams = new ArrayList<>(current.parameters);
        }

        return new SignatureInfo(newName, newAccessModifier, newReturnType, newParams, "");
    }

    /**
     * Получает строковое значение параметра из params или options.
     */
    private String getParamOrOption(JsonNode params, JsonNode options, String field, String defaultValue) {
        if (params.has(field) && !params.get(field).isNull()) {
            return params.get(field).asText();
        }
        if (options != null && options.has(field) && !options.get(field).isNull()) {
            return options.get(field).asText();
        }
        return defaultValue;
    }

    /**
     * Получает узел параметра из params или options.
     */
    private JsonNode getNodeFromParamsOrOptions(JsonNode params, JsonNode options, String field) {
        if (params.has(field) && !params.get(field).isNull()) {
            return params.get(field);
        }
        if (options != null && options.has(field) && !options.get(field).isNull()) {
            return options.get(field);
        }
        return null;
    }

    /**
     * Строит новый список параметров на основе изменений.
     */
    private List<ParameterInfo> buildNewParameters(List<ParameterInfo> current, JsonNode paramsNode) {
        List<ParameterInfo> result = new ArrayList<>();

        // paramsNode может быть:
        // 1. Массивом объектов {name, type, defaultValue, action}
        // 2. Объектом с add/remove/reorder полями

        if (paramsNode.isArray()) {
            for (JsonNode paramNode : paramsNode) {
                String action = paramNode.has("action") ? paramNode.get("action").asText() : null;
                String name = paramNode.get("name").asText();
                String type = paramNode.has("type") ? paramNode.get("type").asText() : "Object";
                String defaultVal = paramNode.has("defaultValue")
                        ? paramNode.get("defaultValue").asText() : null;

                // Find existing parameter with this name
                ParameterInfo existing = current.stream()
                        .filter(p -> p.name.equals(name))
                        .findFirst()
                        .orElse(null);

                if ("remove".equals(action)) {
                    // Explicitly remove - don't add to result
                    continue;
                }

                if ("add".equals(action)) {
                    // Explicitly add new parameter
                    result.add(new ParameterInfo(name, type, defaultVal));

                } else if ("rename".equals(action) || "retype".equals(action)) {
                    // Rename or retype existing parameter
                    if (existing != null) {
                        String newName = paramNode.has("newName")
                                ? paramNode.get("newName").asText() : existing.name;
                        String newType = paramNode.has("type")
                                ? paramNode.get("type").asText() : existing.type;
                        String defVal = paramNode.has("defaultValue")
                                ? paramNode.get("defaultValue").asText() : existing.defaultValue;
                        result.add(new ParameterInfo(newName, newType, defVal, existing.isVarargs()));
                    }

                } else {
                    // action == "keep" OR action not specified (null)
                    // If param exists - keep it (with possible type override)
                    // If param doesn't exist - treat as ADD (this is the key fix!)
                    if (existing != null) {
                        // Keep existing, but allow type override
                        String newType = paramNode.has("type") ? type : existing.type;
                        String defVal = paramNode.has("defaultValue") ? defaultVal : existing.defaultValue;
                        result.add(new ParameterInfo(name, newType, defVal, existing.isVarargs()));
                    } else {
                        // Parameter doesn't exist in current - ADD it
                        result.add(new ParameterInfo(name, type, defaultVal));
                    }
                }
            }
        } else if (paramsNode.isObject()) {
            // Обрабатываем add/remove/reorder
            Set<String> toRemove = new HashSet<>();
            if (paramsNode.has("remove")) {
                for (JsonNode r : paramsNode.get("remove")) {
                    toRemove.add(r.asText());
                }
            }

            // Сначала добавляем существующие (кроме удалённых)
            for (ParameterInfo p : current) {
                if (!toRemove.contains(p.name)) {
                    result.add(p);
                }
            }

            // Добавляем новые
            if (paramsNode.has("add")) {
                for (JsonNode addNode : paramsNode.get("add")) {
                    String name = addNode.get("name").asText();
                    String type = addNode.has("type") ? addNode.get("type").asText() : "Object";
                    String defaultVal = addNode.has("defaultValue")
                            ? addNode.get("defaultValue").asText() : null;
                    int position = addNode.has("position")
                            ? addNode.get("position").asInt() : result.size();
                    result.add(Math.min(position, result.size()),
                            new ParameterInfo(name, type, defaultVal));
                }
            }

            // Переупорядочиваем
            if (paramsNode.has("reorder")) {
                List<String> order = new ArrayList<>();
                for (JsonNode r : paramsNode.get("reorder")) {
                    order.add(r.asText());
                }
                result.sort(Comparator.comparingInt(p -> {
                    int idx = order.indexOf(p.name);
                    return idx >= 0 ? idx : Integer.MAX_VALUE;
                }));
            }
        }

        return result;
    }

    /**
     * Результат разделения ссылок на реализации и вызовы.
     */
    private record SeparatedReferences(
            List<SymbolInfo> implementations,  // @Override методы - нужно обновлять сигнатуру
            List<Location> callSites           // Вызовы - нужно обновлять аргументы
    ) {}

    /**
     * Находит все места вызова и реализации метода.
     * REPORT4 Fix 1.1: Разделяет ссылки на реализации (@Override) и вызовы.
     */
    private SeparatedReferences findAllReferences(SymbolInfo method, Path path, String scope,
                                                   RefactoringContext context) throws RefactoringException {
        try {
            // Используем findReferencesByName вместо findReferences по позиции,
            // т.к. method.location().startColumn() может указывать на модификатор,
            // а не на имя метода
            List<Location> refs = context.getSymbolResolver().findReferencesByName(
                    path, method.name(), scope, true);

            // Убираем само объявление (по строке, т.к. колонки могут не совпадать)
            Location defLoc = method.location();
            refs = refs.stream()
                    .filter(r -> !(r.path().equals(defLoc.path()) &&
                            r.startLine() == defLoc.startLine()))
                    .toList();

            List<SymbolInfo> implementations = new ArrayList<>();
            List<Location> callSites = new ArrayList<>();

            // Группируем по файлам для эффективности
            Map<Path, List<Location>> refsByFile = refs.stream()
                    .collect(Collectors.groupingBy(Location::path));

            for (Map.Entry<Path, List<Location>> entry : refsByFile.entrySet()) {
                Path filePath = entry.getKey();
                List<Location> fileRefs = entry.getValue();

                try {
                    // Получаем все символы в файле
                    List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(filePath);

                    for (Location ref : fileRefs) {
                        // Проверяем, является ли эта ссылка определением метода (@Override)
                        // Важно: метод может начинаться с @Override на предыдущей строке,
                        // поэтому проверяем попадание ссылки в диапазон строк метода
                        SymbolInfo matchingMethod = symbols.stream()
                                .filter(s -> s.kind() == SymbolInfo.SymbolKind.METHOD ||
                                             s.kind() == SymbolInfo.SymbolKind.FUNCTION)
                                .filter(s -> s.name().equals(method.name()))
                                .filter(s -> {
                                    int methodStart = s.location().startLine();
                                    int methodEnd = s.location().endLine();
                                    int refLine = ref.startLine();
                                    // Ссылка должна быть в пределах метода (включая аннотации)
                                    return refLine >= methodStart && refLine <= methodEnd;
                                })
                                .findFirst()
                                .orElse(null);

                        if (matchingMethod != null) {
                            // Это реализация метода (@Override)
                            implementations.add(matchingMethod);
                        } else {
                            // Это вызов метода
                            callSites.add(ref);
                        }
                    }
                } catch (IOException e) {
                    // Если не удалось проанализировать файл, считаем все ссылки вызовами
                    callSites.addAll(fileRefs);
                }
            }

            return new SeparatedReferences(implementations, callSites);

        } catch (IOException e) {
            throw new RefactoringException("Failed to find references: " + e.getMessage(), e);
        }
    }
    /**
     * Находит строку с сигнатурой метода.
     * Сканирует от startLine вниз, пока не найдёт строку с именем метода и '('.
     */
    private int findMethodSignatureLine(List<String> lines, SymbolInfo method, SignatureInfo sig) {
        int startLine = method.location().startLine() - 1; // 0-based
        int endLine = Math.min(method.location().endLine(), lines.size());

        // Ищем строку, содержащую имя метода и открывающую скобку
        String methodName = sig.name;
        for (int i = startLine; i < endLine; i++) {
            String line = lines.get(i);
            // Проверяем, есть ли на строке имя метода, за которым следует '('
            int nameIdx = line.indexOf(methodName);
            if (nameIdx >= 0) {
                // Проверяем, что после имени есть '(' (возможно через пробелы)
                String afterName = line.substring(nameIdx + methodName.length()).trim();
                if (afterName.startsWith("(")) {
                    return i;
                }
            }
        }

        // Fallback на startLine, если не нашли
        return startLine;
    }

    /**
     * Обновляет объявление метода.
     */
    private RefactoringResult.FileChange updateDeclaration(
            SymbolInfo method, SignatureInfo oldSig, SignatureInfo newSig,
            Path path, String langId, RefactoringContext context) throws RefactoringException {
        try {
            context.backupFile(path);

            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            // Ищем строку с сигнатурой метода (может начинаться не на startLine из-за аннотаций)
            int lineIndex = findMethodSignatureLine(lines, method, oldSig);
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                throw new RefactoringException("Could not find method signature line for " + method.name());
            }

            String oldLine = lines.get(lineIndex);

            // Генерируем новую строку с сигнатурой
            String newLine = replaceSignature(oldLine, oldSig, newSig, langId);
            lines.set(lineIndex, newLine);

            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
            context.getTreeManager().invalidateCache(path);

            // Вычисляем метаданные и регистрируем токен
            int lineCount = lines.size();
            long crc32c = LineAccessToken.computeRangeCrc(newContent);

            // Обновляем снапшот сессии для синхронизации с batch tools
            TaskContext.currentOrDefault().externalChanges()
                .updateSnapshot(path, newContent, crc32c, StandardCharsets.UTF_8, lineCount);

            LineAccessToken token = LineAccessTracker.registerAccess(path, 1, lineCount, newContent, lineCount, crc32c);

            return new RefactoringResult.FileChange(
                    path, 1,
                    List.of(new RefactoringResult.ChangeDetail(
                            lineIndex + 1, 0, // lineIndex is 0-based, ChangeDetail expects 1-based
                            oldLine.trim(), newLine.trim())),
                    token.encode(), null, crc32c, lineCount);

        } catch (IOException e) {
            throw new RefactoringException("Failed to update declaration: " + e.getMessage(), e);
        }
    }

    /**
     * Заменяет сигнатуру в строке с корректной заменой модификаторов.
     */
    private String replaceSignature(String line, SignatureInfo oldSig, SignatureInfo newSig,
                                     String langId) {
        String result = line;

        // Заменяем только первое вхождение модификатора в начале сигнатуры
        if (!oldSig.accessModifier.equals(newSig.accessModifier)) {
            // Паттерн: модификатор как целое слово в начале строки (с возможным отступом)
            String accessPattern = "(^\\s*)" + Pattern.quote(oldSig.accessModifier) + "\\b";
            result = result.replaceFirst(accessPattern, "$1" + Matcher.quoteReplacement(newSig.accessModifier));
        }

        // Заменяем только тип перед именем метода, не затрагивая другие вхождения типа
        if (!oldSig.returnType.equals(newSig.returnType) && !oldSig.returnType.isEmpty()) {
            // Паттерн: тип + пробелы + имя метода (но не заменяем имя)
            String returnTypePattern = "\\b" + Pattern.quote(oldSig.returnType) + "(\\s+)" + Pattern.quote(oldSig.name) + "\\s*\\(";
            Matcher rtMatcher = Pattern.compile(returnTypePattern).matcher(result);
            if (rtMatcher.find()) {
                String replacement = newSig.returnType + rtMatcher.group(1) + newSig.name + "(";
                result = result.substring(0, rtMatcher.start()) + replacement + result.substring(rtMatcher.end());
            }
        }

        // Заменяем имя и параметры с balanced matching для скобок
        int methodStart = result.indexOf(oldSig.name + "(");
        if (methodStart == -1) {
            // Попробуем с пробелами
            Pattern namePattern = Pattern.compile(Pattern.quote(oldSig.name) + "\\s*\\(");
            Matcher nameMatcher = namePattern.matcher(result);
            if (nameMatcher.find()) {
                methodStart = nameMatcher.start();
            }
        }

        if (methodStart >= 0) {
            int parenStart = result.indexOf('(', methodStart);
            if (parenStart >= 0) {
                int parenEnd = findMatchingParen(result, parenStart);
                if (parenEnd > parenStart) {
                    String newSignatureStr = newSig.name + "(" + formatParameters(newSig.parameters, langId) + ")";
                    result = result.substring(0, methodStart) + newSignatureStr + result.substring(parenEnd + 1);
                }
            }
        }

        return result;
    }

    /**
     * Находит индекс закрывающей скобки с учётом вложенности.
     */
    private int findMatchingParen(String str, int openIndex) {
        if (openIndex < 0 || openIndex >= str.length() || str.charAt(openIndex) != '(') {
            return -1;
        }
        int depth = 1;
        for (int i = openIndex + 1; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Обновляет вызовы метода.
     */
    private RefactoringResult.FileChange updateCallSites(
            List<Location> calls, SignatureInfo oldSig, SignatureInfo newSig,
            Path path, String langId, RefactoringContext context) throws RefactoringException {
        try {
            context.backupFile(path);

            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

            // Обрабатываем снизу вверх
            List<Location> sorted = calls.stream()
                    .sorted(Comparator.comparingInt(Location::startLine).reversed())
                    .toList();

            for (Location call : sorted) {
                int lineIndex = call.startLine() - 1;
                if (lineIndex >= 0 && lineIndex < lines.size()) {
                    String oldLine = lines.get(lineIndex);
                    String newLine = updateCallInLine(oldLine, oldSig, newSig, langId);

                    if (!oldLine.equals(newLine)) {
                        lines.set(lineIndex, newLine);
                        details.add(new RefactoringResult.ChangeDetail(
                                call.startLine(), call.startColumn(),
                                oldLine.trim(), newLine.trim()));
                    }
                }
            }

            if (details.isEmpty()) {
                return null;
            }

            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
            context.getTreeManager().invalidateCache(path);

            // Вычисляем метаданные и регистрируем токен
            int lineCount = lines.size();
            long crc32c = LineAccessToken.computeRangeCrc(newContent);

            // Обновляем снапшот сессии для синхронизации с batch tools
            TaskContext.currentOrDefault().externalChanges()
                .updateSnapshot(path, newContent, crc32c, StandardCharsets.UTF_8, lineCount);

            LineAccessToken token = LineAccessTracker.registerAccess(path, 1, lineCount, newContent, lineCount, crc32c);

            return new RefactoringResult.FileChange(path, details.size(), details, token.encode(), null, crc32c, lineCount);

        } catch (IOException e) {
            throw new RefactoringException("Failed to update call sites: " + e.getMessage(), e);
        }
    }

    /**
     * Обновляет вызов метода в строке с balanced matching для скобок.
     */
    private String updateCallInLine(String line, SignatureInfo oldSig, SignatureInfo newSig,
                                     String langId) {
        // Ищем начало вызова: name(
        Pattern namePattern = Pattern.compile("\\b" + Pattern.quote(oldSig.name) + "\\s*\\(");
        Matcher nameMatcher = namePattern.matcher(line);

        if (nameMatcher.find()) {
            int callStart = nameMatcher.start();
            int parenStart = line.indexOf('(', callStart);
            if (parenStart >= 0) {
                int parenEnd = findMatchingParen(line, parenStart);
                if (parenEnd > parenStart) {
                    String oldArgs = line.substring(parenStart + 1, parenEnd);
                    String newArgs = transformArguments(oldArgs, oldSig.parameters, newSig.parameters);
                    String replacement = newSig.name + "(" + newArgs + ")";
                    return line.substring(0, callStart) + replacement + line.substring(parenEnd + 1);
                }
            }
        }

        return line;
    }

    /**
     * Трансформирует аргументы вызова.
     */
    private String transformArguments(String oldArgs, List<ParameterInfo> oldParams,
                                        List<ParameterInfo> newParams) {
        if (oldArgs.trim().isEmpty() && newParams.isEmpty()) {
            return "";
        }

        List<String> args = splitByCommaBalanced(oldArgs);
        Map<String, String> argByParamName = new LinkedHashMap<>();

        // Сопоставляем аргументы со старыми параметрами
        for (int i = 0; i < args.size() && i < oldParams.size(); i++) {
            argByParamName.put(oldParams.get(i).name, args.get(i).trim());
        }

        // Строим новые аргументы
        List<String> newArgs = new ArrayList<>();
        for (ParameterInfo newParam : newParams) {
            if (argByParamName.containsKey(newParam.name)) {
                // Существующий аргумент
                newArgs.add(argByParamName.get(newParam.name));
            } else if (newParam.defaultValue != null) {
                // Новый параметр со значением по умолчанию
                newArgs.add(newParam.defaultValue);
            } else {
                // Новый параметр без значения - вставляем placeholder
                newArgs.add("/* " + newParam.name + " */");
            }
        }

        return String.join(", ", newArgs);
    }

    /**
     * Форматирует параметры для вывода.
     */
    private String formatParameters(List<ParameterInfo> params, String langId) {
        if (langId.equals("java") || langId.equals("kotlin")) {
            return params.stream()
                    .map(p -> {
                        String type = p.type;
                        // Handle varargs: convert Type[] back to Type... for last parameter
                        if (p.isVarargs && type.endsWith("[]")) {
                            type = type.substring(0, type.length() - 2) + "...";
                        } else if (p.isVarargs && !type.contains("...")) {
                            type = type + "...";
                        }
                        return type + " " + p.name;
                    })
                    .collect(Collectors.joining(", "));
        } else if (langId.equals("python")) {
            return params.stream()
                    .map(p -> p.name + (p.defaultValue != null ? "=" + p.defaultValue : ""))
                    .collect(Collectors.joining(", "));
        } else {
            return params.stream()
                    .map(p -> p.name + ": " + p.type +
                            (p.defaultValue != null ? " = " + p.defaultValue : ""))
                    .collect(Collectors.joining(", "));
        }
    }

    /**
     * Форматирует сигнатуру для вывода.
     */
    private String formatSignature(SignatureInfo sig, String langId) {
        StringBuilder sb = new StringBuilder();

        if (langId.equals("java") || langId.equals("kotlin")) {
            sb.append(sig.accessModifier).append(" ");
            sb.append(sig.returnType).append(" ");
            sb.append(sig.name).append("(");
            sb.append(formatParameters(sig.parameters, langId));
            sb.append(")");
        } else if (langId.equals("python")) {
            sb.append("def ").append(sig.name).append("(");
            sb.append(formatParameters(sig.parameters, langId));
            sb.append(")");
        } else {
            sb.append(sig.name).append("(");
            sb.append(formatParameters(sig.parameters, langId));
            sb.append(")");
            if (!sig.returnType.isEmpty()) {
                sb.append(": ").append(sig.returnType);
            }
        }

        return sb.toString();
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private record ParameterInfo(String name, String type, String defaultValue, boolean isVarargs) {
        // Convenience constructor without varargs flag
        ParameterInfo(String name, String type, String defaultValue) {
            this(name, type, defaultValue, false);
        }
    }

    private record SignatureInfo(
            String name,
            String accessModifier,
            String returnType,
            List<ParameterInfo> parameters,
            String originalLine
    ) {}
}
